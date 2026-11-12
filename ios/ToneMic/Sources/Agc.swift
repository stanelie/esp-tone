import Foundation

/// Look-ahead peak limiter, standing in for the plain gain when AGC is switched on.
///
/// The microphone runs at high fixed gain on purpose, so quiet sounds are audible (SPEC.md §2.4);
/// the cost is that a loud sound overflows. This rides the gain down fast enough that it never
/// does, and brings it back in about 0.15 s so the quiet floor returns promptly.
///
/// **It subsumes the gain.** Applying gain first and limiting afterwards would mean the signal is
/// already clipped by the time the limiter sees it, so `process` takes the raw sample and the gain
/// together and saturates once at the end.
///
/// **Look-ahead is what lets it duck before the peak, not after.** Output is delayed by
/// LOOKAHEAD_MS and the gain is driven by the peak of the *coming* window. Without it the only
/// options are clipping the first few samples or a step in gain, which clicks.
///
/// Measured on the Android port with a burst that would reach 2× full scale: peak output stayed
/// below full scale with zero samples over, gain reached its target within 3 ms, and recovery to
/// 0.95 took 122 ms, to 0.99 took 203 ms.
final class Agc {
    private let look: Int
    private var delay: [Float]
    private var dpos = 0
    private var primed = 0

    /// Peak of the block being filled, and of the one before it: an O(1) upper bound on the window
    /// maximum, which only ever over-estimates — more reduction for slightly longer, the harmless
    /// direction.
    private var curPeak: Float = 0
    private var prevPeak: Float = 0
    private var blockPos = 0

    private var gain: Float = 1
    private let attack: Float
    private let release: Float

    init(rate: Int) {
        look = max(16, rate * Self.lookaheadMs / 1000)
        delay = [Float](repeating: 0, count: look)
        attack = 1 - expf(-3 / Float(look))              // converges within the look-ahead window
        release = 1 - expf(-1 / Float(Self.releaseTau * Double(rate)))
    }

    func reset() {
        for i in 0..<delay.count { delay[i] = 0 }
        dpos = 0; primed = 0; curPeak = 0; prevPeak = 0; blockPos = 0; gain = 1
    }

    /// Applies `fixedGain` and the limiter to `x` in place, in full-scale units (±32768).
    func process(_ x: inout [Float], count n: Int, fixedGain: Float) {
        for i in 0..<n {
            let v = x[i] * fixedGain

            let mag = abs(v)
            if mag > curPeak { curPeak = mag }
            blockPos += 1
            if blockPos >= look {
                blockPos = 0
                prevPeak = curPeak
                curPeak = 0
            }

            let peak = max(prevPeak, curPeak)
            let need: Float = peak > Self.ceiling ? Self.ceiling / peak : 1
            gain += (need - gain) * (need < gain ? attack : release)

            let out = delay[dpos] * gain
            delay[dpos] = v
            dpos = dpos + 1 >= look ? 0 : dpos + 1

            if primed < look {
                primed += 1
                x[i] = 0                                  // the window has not come through yet
            } else {
                x[i] = max(-32768, min(32767, out))
            }
        }
    }

    private static let lookaheadMs = 2
    /// −1.2 dBFS, leaving room for the output stage to overshoot between samples.
    private static let ceiling: Float = 28500
    private static let releaseTau = 0.05
}
