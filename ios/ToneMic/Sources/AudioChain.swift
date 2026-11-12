import Foundation

/// The level and tone chain — filters, then gain or AGC — applied to the stream on its way OUT of
/// the jitter buffer.
///
/// WHY IT MOVED. This used to run on each packet as it arrived, before buffering. That is wrong
/// once the buffer restores packet order, and it was the cause of a "clicks and pops like vinyl"
/// fault: every element here is sequential, and Agc carries a 2 ms LOOK-AHEAD DELAY LINE, so a
/// call does not return the audio it was handed — it returns audio from a couple of milliseconds
/// earlier. In arrival order that is a harmless constant delay. With packets arriving out of
/// order it is not: processing N+1 before N emits N's delayed tail labelled as N+1, which then
/// gets stored in N+1's slot, and vice versa. MEASURED over a VPN: 113 reorderings in 47 s, each
/// scrambling two 5 ms blocks — about five discontinuities a second.
///
/// A sequential chain has to see a continuous, in-order stream, and after the rewrite that stream
/// only exists on the far side of the buffer. So it runs there, on whatever the buffer hands out,
/// concealment included — which is also more correct, since concealed audio should be filtered
/// and levelled like everything else.
///
/// It runs on the audio thread, so it allocates nothing and just walks the block.
final class AudioChain {
    private let filters: Filters
    private let agc: Agc
    private var scratch = [Float]()

    /// Read on the audio thread, written from the UI. Both are plain values, as before the move.
    var gainDb = 30
    var agcOn = false

    init(rate: Int) {
        filters = Filters(rate: rate)
        agc = Agc(rate: rate)
        scratch.reserveCapacity(4096)
    }

    func configure(lowCut: Int, highCut: Int) { filters.configure(lowCut: lowCut, highCut: highCut) }
    func resetAgc() { agc.reset() }

    /// In place, on the ±1.0 float block the buffer produced. The filters and the AGC are fitted
    /// to full-scale units, so the conversion happens here, once, at the boundary.
    func process(_ p: UnsafeMutablePointer<Float>, _ n: Int) {
        guard n > 0 else { return }
        if scratch.count < n { scratch = [Float](repeating: 0, count: n) }
        for i in 0..<n { scratch[i] = p[i] * 32768 }

        filters.process(&scratch, count: n)
        let g = powf(10, Float(gainDb) / 20)
        if agcOn {
            agc.process(&scratch, count: n, fixedGain: g)
        } else {
            for i in 0..<n { scratch[i] = max(-32768, min(32767, scratch[i] * g)) }
        }

        for i in 0..<n { p[i] = scratch[i] / 32768 }
    }
}
