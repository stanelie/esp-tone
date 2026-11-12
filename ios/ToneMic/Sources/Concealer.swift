import Foundation

/// Fills a gap by continuing the waveform rather than going silent.
///
/// A 40th-order linear predictor is fitted to the last 40 ms of audio and then run open-loop: it
/// carries on the pitch and formants that were there, which for a short dropout is far less
/// noticeable than a hole. It holds full level for 10 ms and fades to silence by 60 ms, because a
/// predictor extrapolating indefinitely turns into a buzz.
///
/// Ported from the Android player (`Concealer` in JitterPlayer.kt). Mono only: this microphone is
/// mono, and carrying the channel loop across would be untested code.
final class Concealer {
    private static let order = 40

    private let frames: Int                 // 40 ms of history
    private var history: [Int16]
    private var filled = 0

    private let hold: Int                   // full level for 10 ms
    private let fadeEnd: Int                // silent from 60 ms

    private var coef = [Double](repeating: 0, count: order)
    private var state = [Double](repeating: 0, count: order)   // ring, oldest first
    private var statePos = 0
    private var active = false
    private var produced = Int.max
    private var fade = true

    init(rate: Int) {
        frames = max(Self.order * 4, rate / 25)
        history = [Int16](repeating: 0, count: frames)
        hold = rate / 100
        fadeEnd = rate * 60 / 1000
    }

    /// True while `next` still produces sound.
    var audible: Bool { active && (!fade || produced < fadeEnd) }

    /// Records samples just played, as the basis for the next gap.
    func remember(_ src: [Float], count n: Int) {
        if n >= frames {
            for i in 0..<frames { history[i] = clamp(src[n - frames + i]) }
            filled = frames
        } else {
            let keep = frames - n
            for i in 0..<keep { history[i] = history[i + n] }
            for i in 0..<n { history[keep + i] = clamp(src[i]) }
            filled = min(frames, filled + n)
        }
    }

    private func clamp(_ v: Float) -> Int16 {
        Int16(max(-32768, min(32767, v)))
    }

    /// Begins a continuation. With `fade` false it holds full level, for a crossfade the caller does.
    func start(fade: Bool = true) {
        self.fade = fade
        produced = 0
        active = false
        guard filled >= Self.order * 4 else { return }      // too little history: stay silent
        let first = frames - filled
        var x = [Double](repeating: 0, count: filled)
        for i in 0..<filled { x[i] = Double(history[first + i]) }
        guard let a = Self.lpc(x) else { return }
        coef = a
        for k in 0..<Self.order { state[k] = x[filled - Self.order + k] }
        statePos = 0
        active = true
    }

    /// Writes `n` frames of continuation (silence once faded, or when not concealing).
    func next(_ out: UnsafeMutablePointer<Float>, count n: Int) {
        for f in 0..<n {
            var g: Double = 0
            if active {
                if !fade || produced < hold { g = 1 }
                else if produced >= fadeEnd { g = 0 }
                else { g = Double(fadeEnd - produced) / Double(fadeEnd - hold) }
            }
            var y = 0.0
            if active {
                for k in 1...Self.order {
                    y += coef[k - 1] * state[(statePos + Self.order - k) % Self.order]
                }
                y = max(-32767, min(32767, y))
                state[statePos] = y                        // replaces the oldest: now the newest
                statePos = (statePos + 1) % Self.order
            }
            out[f] = Float(y * g)
            if produced < Int.max { produced += 1 }
        }
    }

    /// Levinson-Durbin on a Hann-windowed autocorrelation, with a mild bandwidth expansion so the
    /// extrapolation decays rather than ringing. Nil when the history is digital silence or the
    /// recursion goes unstable.
    private static func lpc(_ x: [Double]) -> [Double]? {
        let n = x.count
        var w = [Double](repeating: 0, count: n)
        for i in 0..<n { w[i] = x[i] * (0.5 - 0.5 * cos(2 * Double.pi * (Double(i) + 0.5) / Double(n))) }

        var r = [Double](repeating: 0, count: order + 1)
        for lag in 0...order {
            var s = 0.0
            for i in lag..<n { s += w[i] * w[i - lag] }
            r[lag] = s
        }
        guard r[0] >= Double(n) else { return nil }        // under ~1 LSB rms: digital silence
        r[0] *= 1.0001

        var a = [Double](repeating: 0, count: order + 1)
        var tmp = [Double](repeating: 0, count: order + 1)
        a[0] = 1
        var e = r[0]
        for i in 1...order {
            var acc = r[i]
            if i > 1 { for j in 1..<i { acc += a[j] * r[i - j] } }
            let k = -acc / e
            if i > 1 {
                for j in 1..<i { tmp[j] = a[j] + k * a[i - j] }
                for j in 1..<i { a[j] = tmp[j] }
            }
            a[i] = k
            e *= 1 - k * k
            if e <= 0 { return nil }
        }
        var gamma = 1.0
        return (0..<order).map { gamma *= 0.998; return -a[$0 + 1] * gamma }
    }
}

/// The lag in `minLag...maxLag` at which `window` samples of `x` best match those `lag` ahead
/// (normalised cross-correlation). Returns the lag and its score, 1 meaning identical shape.
func bestLagAhead(_ x: [Float], window: Int, minLag: Int, maxLag: Int) -> (lag: Int, score: Float) {
    var e0: Float = 0
    for i in 0..<window { e0 += x[i] * x[i] }
    guard e0 >= 1 else { return (maxLag, 0) }              // digital silence
    var best = maxLag
    var bestScore: Float = -1
    for lag in minLag...maxLag {
        var xy: Float = 0, e: Float = 0
        for i in 0..<window {
            let p = x[lag + i]
            xy += x[i] * p
            e += p * p
        }
        let score = e > 0 ? xy / (e0 * e).squareRoot() : 0
        if score > bestScore { bestScore = score; best = lag }
    }
    return (best, bestScore)
}

/// The same search as `bestLagAhead`, coarse-to-fine: correlate on 4:1 decimated audio, then
/// refine at full resolution around the winner.
///
/// WHY. The direct search is window x lags multiply-adds — 226 000 at 43860 Hz — and it runs in
/// the audio render callback whenever the delay trimmer fires. MEASURED on the device: a render
/// block that normally costs 69 us peaked at 4397 us against a 5000 us deadline, and in a Debug
/// build the same path peaked at 151 ms and dropped ~4% of all renders. Optimisation bought cover
/// for it; it did not make unbounded work in a realtime callback correct.
///
/// Decimating by four cuts both the window and the lag range, so the coarse pass costs a
/// sixteenth, and a +/-4 refinement at full resolution recovers the exact period: about 18 000
/// multiply-adds in total, 13x less. Pitch lives between 70 and 400 Hz, far below the 5.5 kHz
/// Nyquist that 4:1 decimation leaves, so nothing that matters to the search is lost.
func bestLagAheadFast(_ x: [Float], window: Int, minLag: Int, maxLag: Int) -> (lag: Int, score: Float) {
    let d = 4
    let w = window / d, lo = max(1, minLag / d), hi = maxLag / d
    guard w >= 8, hi > lo, x.count >= window + maxLag else {
        return bestLagAhead(x, window: window, minLag: minLag, maxLag: maxLag)
    }

    var small = [Float](repeating: 0, count: hi + w + 2)
    for i in 0..<small.count {
        let s = i * d
        guard s + d <= x.count else { break }
        var a: Float = 0
        for k in 0..<d { a += x[s + k] }
        small[i] = a / Float(d)
    }

    var e0: Float = 0
    for i in 0..<w { e0 += small[i] * small[i] }
    guard e0 >= 1 else { return (maxLag, 0) }              // digital silence

    var coarse = lo, coarseScore: Float = -1
    for lag in lo...hi {
        var xy: Float = 0, e: Float = 0
        for i in 0..<w {
            let p = small[lag + i]
            xy += small[i] * p
            e += p * p
        }
        let score = e > 0 ? xy / (e0 * e).squareRoot() : 0
        if score > coarseScore { coarseScore = score; coarse = lag }
    }

    var ef: Float = 0
    for i in 0..<window { ef += x[i] * x[i] }
    guard ef >= 1 else { return (maxLag, 0) }
    var best = min(maxLag, max(minLag, coarse * d))
    var bestScore: Float = -1
    for lag in max(minLag, coarse * d - d)...min(maxLag, coarse * d + d) {
        var xy: Float = 0, e: Float = 0
        for i in 0..<window {
            let p = x[lag + i]
            xy += x[i] * p
            e += p * p
        }
        let score = e > 0 ? xy / (ef * e).squareRoot() : 0
        if score > bestScore { bestScore = score; best = lag }
    }
    return (best, bestScore)
}
