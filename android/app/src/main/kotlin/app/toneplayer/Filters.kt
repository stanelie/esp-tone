package app.toneplayer

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Optional playback-side low cut and high cut: two second-order Butterworth biquads (RBJ cookbook,
 * Q = 1/sqrt(2), so 12 dB/octave and maximally flat in the passband).
 *
 * Playback-side only, and off by default. SPEC §2.3a rules out filtering the *captured* signal —
 * what goes on the wire stays exactly what the microphone heard — but a low cut is the practical
 * way to get rid of handling rumble and wind, and a high cut tames hiss, without touching the
 * recording.
 *
 * **These run before the gain and the limiter, not after.** Filtering afterwards would let a big
 * low-frequency rumble drive [Agc] into ducking for something that is then removed, so the whole
 * programme would dip for a sound nobody ever hears. Filter first, then decide how loud it is.
 *
 * State is per channel and is kept when a cutoff changes, so moving the control settles in a few
 * samples instead of clicking.
 */
class Filters(private val rate: Int, private val channels: Int) {

    private val hp = Array(channels) { Biquad() }
    private val lp = Array(channels) { Biquad() }

    private var hpHz = 0
    private var lpHz = 0

    /** 0 disables that filter. [lowCut] and [highCut] are in Hz. */
    fun configure(lowCut: Int, highCut: Int) {
        if (lowCut != hpHz) {
            hpHz = lowCut
            if (lowCut > 0) hp.forEach { it.highPass(lowCut.toDouble(), rate) }
        }
        // Above Nyquist a "high cut" would do nothing but misbehave, so treat it as off.
        val top = if (highCut > 0 && highCut < rate / 2 - 500) highCut else 0
        if (top != lpHz) {
            lpHz = top
            if (top > 0) lp.forEach { it.lowPass(top.toDouble(), rate) }
        }
    }

    val active: Boolean get() = hpHz > 0 || lpHz > 0

    fun reset() {
        hp.forEach { it.reset() }
        lp.forEach { it.reset() }
    }

    /** In place over [n] interleaved samples. */
    fun process(pcm: ShortArray, n: Int) {
        if (!active) return
        for (c in 0 until channels) {
            val h = hp[c]
            val l = lp[c]
            var i = c
            while (i < n) {
                var v = pcm[i].toDouble()
                if (hpHz > 0) v = h.step(v)
                if (lpHz > 0) v = l.step(v)
                pcm[i] = when {
                    v > 32767.0 -> 32767
                    v < -32768.0 -> -32768
                    else -> Math.round(v).toInt().toShort()
                }
                i += channels
            }
        }
    }

    /** Transposed direct form II, double state: cheap, and stable at the low cutoffs we offer. */
    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun reset() { z1 = 0.0; z2 = 0.0 }

        fun step(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun lowPass(f: Double, rate: Int) {
            val w = 2.0 * PI * f / rate
            val cw = cos(w)
            val alpha = sin(w) / (2.0 * Q)
            val a0 = 1.0 + alpha
            set((1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0, -2.0 * cw, 1.0 - alpha, a0)
        }

        fun highPass(f: Double, rate: Int) {
            val w = 2.0 * PI * f / rate
            val cw = cos(w)
            val alpha = sin(w) / (2.0 * Q)
            val a0 = 1.0 + alpha
            set((1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0, -2.0 * cw, 1.0 - alpha, a0)
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double, a0: Double) {
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0
            a1 = na1 / a0; a2 = na2 / a0
        }

        companion object {
            /** Butterworth: maximally flat, no resonant bump at the corner. */
            private const val Q = 0.70710678
        }
    }
}
