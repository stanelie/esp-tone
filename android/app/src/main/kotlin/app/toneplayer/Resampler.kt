package app.toneplayer

/**
 * Fractional sample-rate conversion, cubic Hermite, continuous across packets.
 *
 * The microphone's rates (32051 and 43860 Hz) are set by the ESP8266's two integer clock dividers
 * and cannot be made to land on 48000 Hz — the nearest reachable is 48077, still not exact. So
 * *something* has to resample. Letting Android do it is the expensive option: a track whose rate
 * is not the device's native rate never reaches the low-latency fast mixer, however politely
 * PERFORMANCE_MODE_LOW_LATENCY is requested. MEASURED on the test phone, via
 * AudioTrack.getTimestamp(): **110 ms** of output latency at 43860 Hz, where the native path's
 * 192-frame block implies well under 20 ms.
 *
 * So do it here, at the phone's own rate, and keep the fast path.
 *
 * On quality: this is interpolation, not a brick-wall polyphase filter, so it is not transparent —
 * it rolls off slightly and leaves small images near Nyquist. Two things make that the right call
 * anyway. The resampling was happening regardless, in AudioFlinger, with no control over it; and
 * the microphone's own noise floor is about -61 dBFS (SPEC §2.4), some 35 dB above where cubic
 * interpolation error sits for this 1.09x ratio. It is playback-side only: nothing touches the
 * data on the wire, which stays exactly what the microphone captured.
 */
class Resampler(inRate: Int, outRate: Int, private val channels: Int) {

    /** Input frames consumed per output frame at the nominal rates; < 1 when upsampling. */
    private val nominalStep = inRate.toDouble() / outRate.toDouble()

    /** The live ratio, nudged by [correct] to hold the jitter buffer at its target. */
    private var step = nominalStep

    /* Closed-loop rate matching, ported from the iOS client (RateMatcher.swift), where it was
     * developed against measurements on this same microphone.
     *
     * The microphone's clock and the phone's are not the same clock, so a FIXED ratio here lets
     * the jitter buffer drift until something removes the surplus — and the only thing that could
     * was the delay trimmer, which works by splicing out pitch periods and is audible when it has
     * to work continuously. Nudging this ratio instead costs a pitch shift of a few hundredths of
     * a percent, which is not.
     *
     * MEASURED on iOS: with the conversion done here rather than by the platform, the standing
     * mismatch is -0.027% — plain crystal drift. Hence the ceilings below: the integral gets 0.1%,
     * four times the drift it must learn and nowhere near enough to absorb a transient, and the
     * total correction gets 0.5% (about 8.6 cents, inaudible as a steady offset). Both limits were
     * arrived at the hard way: a 2% integral wound up, played 1.44% fast and emptied the buffer,
     * and an integral free to reach the full 0.5% absorbed a startup drain it then took over a
     * minute to shed. The proportional term does transient work; the integral estimates drift and
     * nothing else. */
    private var trim = 0.0

    /**
     * Adjusts the ratio from the jitter buffer's depth error in ms, positive when it is deeper
     * than its target. [dt] is the seconds of audio this call covers.
     */
    fun correct(errorMs: Int, dt: Double) {
        val e = errorMs.toDouble()
        val mag = kotlin.math.abs(e)

        /* The integral runs only NEAR equilibrium, and this is the opposite of what it did first.
         *
         * Both terms used to be gated on |error| > DEADBAND, which has the integral accumulating
         * precisely when the error is large — i.e. during a transient, which is the one thing it
         * must not absorb. MEASURED on the phone: when the target stepped from 76 to 84 ms the
         * error went to -27 ms and the integral wound straight to its -0.1% clamp, so it was no
         * longer an estimate of anything. Large errors are the proportional term's business;
         * the integral exists to estimate a slowly-varying clock offset, so it should only move
         * when the loop is already close to where it belongs. */
        if (mag < INTEGRAL_BAND_MS) {
            trim = (trim + KI * e * dt).coerceIn(-MAX_INTEGRAL, MAX_INTEGRAL)
        }

        // Lean harder when the buffer is shallow: extra latency is inaudible, a dry buffer is not.
        val p = if (mag > DEADBAND_MS) (if (e < 0) KP * KP_SHALLOW else KP) * e else 0.0
        step = nominalStep * (1 + (p + trim).coerceIn(-MAX_TRIM, MAX_TRIM))
    }

    /** The learned drift, as a percentage, for the stats display. */
    val trimPercent: Double get() = trim * 100

    private companion object {
        const val KP = 4.0e-5
        const val KP_SHALLOW = 1.5
        const val KI = 5.0e-6
        /* The integral gets the FULL correction budget, not a fraction of it.
         *
         * This was 0.1%, reasoning that the integral only holds clock drift and drift is 0.027%.
         * MEASURED on iOS, which shares this loop: the real standing imbalance was +0.360%, the
         * integral was pinned at -0.033%, and the proportional term had to supply the rest — which
         * at KP = 4e-5 needs about 90 ms of PERMANENT depth error. The buffer duly slid from 75 ms
         * to 47 ms and ran dry. Capping the integral below the offset it exists to absorb
         * guarantees a standing error. A steady 0.5% is 8.6 cents of pitch, inaudible, so there is
         * nothing to protect against; the anti-windup rule and the band are the real protection. */
        const val MAX_INTEGRAL = 0.005
        const val MAX_TRIM = 0.005
        const val DEADBAND_MS = 20.0
        /* Wide enough to converge: 15 ms was a deadlock, because the integral could only move
         * once the error was small and the error could only shrink once the integral had learned
         * the offset. */
        const val INTEGRAL_BAND_MS = 150.0
    }

    /** The four input frames the cubic needs, per channel, oldest first. */
    private val p0 = FloatArray(channels)
    private val p1 = FloatArray(channels)
    private val p2 = FloatArray(channels)
    private val p3 = FloatArray(channels)

    /** Where the next output sample sits between p1 and p2, in input frames. */
    private var phase = 0.0
    private var primed = false

    /** Enough room for [inFrames] input frames, with slack for the phase. */
    fun maxOutSamples(inFrames: Int) =
        ((inFrames / (nominalStep * (1 - MAX_TRIM))).toInt() + 4) * channels

    /**
     * Converts [inSamples] interleaved samples from [input] into [out], returning the number of
     * samples written. State carries over, so consecutive packets join without a seam.
     */
    fun process(input: ShortArray, inSamples: Int, out: ShortArray): Int {
        var o = 0
        var i = 0
        while (i < inSamples) {
            for (c in 0 until channels) {
                p0[c] = p1[c]; p1[c] = p2[c]; p2[c] = p3[c]
                p3[c] = input[i + c].toFloat()
            }
            i += channels
            // The first three frames only fill the history; there is nothing to interpolate yet.
            if (!primed) {
                if (i >= 3 * channels) primed = true
                continue
            }
            while (phase < 1.0) {
                if (o + channels > out.size) return o        // caller under-sized the buffer
                val mu = phase.toFloat()
                for (c in 0 until channels) {
                    // Catmull-Rom: passes through p1 and p2, C1 continuous.
                    val a = -0.5f * p0[c] + 1.5f * p1[c] - 1.5f * p2[c] + 0.5f * p3[c]
                    val b = p0[c] - 2.5f * p1[c] + 2.0f * p2[c] - 0.5f * p3[c]
                    val d = -0.5f * p0[c] + 0.5f * p2[c]
                    val v = ((a * mu + b) * mu + d) * mu + p1[c]
                    out[o + c] = when {
                        v > 32767f -> 32767
                        v < -32768f -> -32768
                        else -> v.toInt().toShort()
                    }
                }
                o += channels
                phase += step
            }
            phase -= 1.0
        }
        return o
    }
}
