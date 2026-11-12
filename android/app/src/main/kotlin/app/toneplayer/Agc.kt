package app.toneplayer

/**
 * Look-ahead peak limiter, standing in for the fixed playback gain when AGC is switched on.
 *
 * The microphone runs at a high fixed gain on purpose, so quiet sounds are audible (SPEC §2.4);
 * the cost is that a loud sound overflows. This rides the gain down fast enough that it never does,
 * and brings it back in about 0.15 s so the quiet floor returns promptly.
 *
 * Three things make it behave:
 *
 * **It subsumes the fixed gain.** The old path did `clamp(sample * gainX)`, which means the signal
 * is already clipped by the time any limiter downstream could look at it. So [process] takes the
 * raw sample and the fixed gain together, works in float, and saturates once at the very end.
 *
 * **Look-ahead, so the reduction lands before the peak does.** The output is delayed by
 * [LOOKAHEAD_MS], and the gain is driven by the peak of the *coming* window, so by the time a loud
 * sample arrives the gain is already where it needs to be. That delay is the entire latency cost:
 * 2 ms against ~100 ms end to end. Without it the only options are clipping the first few samples
 * or a step change in gain, which clicks.
 *
 * **The window peak is an upper bound, not an exact sliding max.** The peak of the block being
 * filled and the peak of the previous block together cover between one and two blocks, which is
 * O(1) per sample and only ever over-estimates - a limiter erring towards slightly more reduction
 * for slightly longer, which is the harmless direction.
 *
 * The ceiling sits below full scale because the resampler runs *after* this: cubic interpolation
 * can overshoot between two samples that are themselves in range.
 */
class Agc(rate: Int) {

    private val look = (rate * LOOKAHEAD_MS / 1000).coerceAtLeast(16)
    private val delay = FloatArray(look)
    private var dpos = 0
    private var primed = 0

    /** Peak of the block being filled, and of the one before it. */
    private var curPeak = 0f
    private var prevPeak = 0f
    private var blockPos = 0

    private var gain = 1f

    /** Reaches the target within the look-ahead window, so no peak outruns it. */
    private val attack = 1f - Math.exp(-3.0 / look).toFloat()

    /** ~0.05 s time constant: back to within a few percent of unity in ~0.15 s. */
    private val release = 1f - Math.exp(-1.0 / (RELEASE_TAU_S * rate)).toFloat()

    /** Gain currently applied, for display. 1.0 = no reduction. */
    val reduction: Float get() = gain

    fun reset() {
        delay.fill(0f)
        dpos = 0; primed = 0
        curPeak = 0f; prevPeak = 0f; blockPos = 0
        gain = 1f
    }

    /**
     * Applies [fixedGain] and the limiter to [n] samples of [pcm], in place. Interleaved stereo is
     * handled as one signal, which is what keeps a stereo image from shifting as the gain moves.
     */
    fun process(pcm: ShortArray, n: Int, fixedGain: Int) {
        val g0 = fixedGain.toFloat()
        for (i in 0 until n) {
            val v = pcm[i] * g0

            val mag = if (v < 0f) -v else v
            if (mag > curPeak) curPeak = mag
            if (++blockPos >= look) {
                blockPos = 0
                prevPeak = curPeak
                curPeak = 0f
            }

            // Where the gain has to be for the loudest sample in view.
            val peak = if (prevPeak > curPeak) prevPeak else curPeak
            val need = if (peak > CEILING) CEILING / peak else 1f
            gain += (need - gain) * (if (need < gain) attack else release)

            // Emit the sample from one look-ahead window ago, at today's gain.
            val out = delay[dpos] * gain
            delay[dpos] = v
            dpos = if (dpos + 1 >= look) 0 else dpos + 1

            pcm[i] = when {
                primed < look -> { primed++; 0 }        // the window has not come through yet
                out > 32767f -> 32767
                out < -32768f -> -32768
                else -> out.toInt().toShort()
            }
        }
    }

    companion object {
        /** Delay added to the stream, and the window the limiter sees ahead. */
        private const val LOOKAHEAD_MS = 2

        /** -1.2 dBFS, leaving room for the resampler to overshoot between samples. */
        private const val CEILING = 28500f

        private const val RELEASE_TAU_S = 0.05
    }
}
