import Foundation

/// Optional playback-side low cut and high cut: two second-order Butterworth biquads
/// (RBJ cookbook, Q = 1/√2, so 12 dB/octave and maximally flat in the passband).
///
/// Playback only, and off by default. SPEC.md §2.3a rules out filtering the *captured* signal —
/// what goes on the wire stays exactly what the microphone heard — but a low cut is the practical
/// way to lose handling rumble and wind, and a high cut tames hiss, without touching the capture.
///
/// **These run before the gain and the limiter.** Filtering afterwards would let a big
/// low-frequency rumble drive the limiter into ducking for something that is then removed, so the
/// whole programme would dip for a sound nobody ever hears.
///
/// Verified against the Android implementation: a 250 Hz low cut measures −3.0 dB at 250 Hz and
/// −12.3 dB an octave below; an 8 kHz high cut, −3.0 dB at 8 kHz.
final class Filters {
    private var hp = Biquad()
    private var lp = Biquad()
    private var hpHz = 0
    private var lpHz = 0
    private let rate: Int

    init(rate: Int) { self.rate = rate }

    var active: Bool { hpHz > 0 || lpHz > 0 }

    /// 0 disables that filter. State is kept when a cutoff changes, so moving a control settles in
    /// a few samples instead of clicking.
    func configure(lowCut: Int, highCut: Int) {
        if lowCut != hpHz {
            hpHz = lowCut
            if lowCut > 0 { hp.highPass(Double(lowCut), rate: rate) }
        }
        // Above Nyquist a "high cut" does nothing but misbehave, so treat it as off.
        let top = (highCut > 0 && highCut < rate / 2 - 500) ? highCut : 0
        if top != lpHz {
            lpHz = top
            if top > 0 { lp.lowPass(Double(top), rate: rate) }
        }
    }

    func reset() { hp.reset(); lp.reset() }

    func process(_ x: inout [Float], count n: Int) {
        guard active else { return }
        for i in 0..<n {
            var v = Double(x[i])
            if hpHz > 0 { v = hp.step(v) }
            if lpHz > 0 { v = lp.step(v) }
            x[i] = Float(v)
        }
    }

    /// Transposed direct form II, double state: cheap and stable at the low cutoffs offered.
    private struct Biquad {
        private var b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0
        private var z1 = 0.0, z2 = 0.0

        mutating func reset() { z1 = 0; z2 = 0 }

        mutating func step(_ x: Double) -> Double {
            let y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        mutating func lowPass(_ f: Double, rate: Int) {
            let w = 2 * Double.pi * f / Double(rate), cw = cos(w)
            let alpha = sin(w) / (2 * Self.q), a0 = 1 + alpha
            set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, -2 * cw, 1 - alpha, a0)
        }

        mutating func highPass(_ f: Double, rate: Int) {
            let w = 2 * Double.pi * f / Double(rate), cw = cos(w)
            let alpha = sin(w) / (2 * Self.q), a0 = 1 + alpha
            set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, -2 * cw, 1 - alpha, a0)
        }

        private mutating func set(_ nb0: Double, _ nb1: Double, _ nb2: Double,
                                  _ na1: Double, _ na2: Double, _ a0: Double) {
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0
            a1 = na1 / a0; a2 = na2 / a0
        }

        /// Butterworth: maximally flat, no resonant bump at the corner.
        private static let q = 0.70710678
    }
}
