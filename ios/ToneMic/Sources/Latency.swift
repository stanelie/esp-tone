import Foundation

/// End-to-end delay: sound reaching the microphone to sound leaving this phone.
///
/// Four terms, and it is worth being precise about which are measured and which are not, because
/// a latency figure that quietly includes a guess is worse than no figure at all.
///
///   capture  derived  one I2S DMA descriptor + one packet's worth of audio
///   network  MEASURED half the smallest recent round trip on the control channel
///   buffer   MEASURED the jitter buffer's live depth — normally the dominant term
///   output   MEASURED what iOS reports for its own output path
///
/// Why a whole packet counts as capture delay: the samples in a frame are captured over its
/// duration and sent together, so the first sample waits the full frame and the last waits none.
/// They are then played back in order at the sample rate, so every sample in the frame ends up
/// delayed by the same amount — the frame duration — rather than by an average of it.
struct LatencyBreakdown {
    var captureMs: Double = 0
    var networkMs: Double = 0
    var bufferMs: Double = 0
    var outputMs: Double = 0

    var totalMs: Double { captureMs + networkMs + bufferMs + outputMs }

    /* The firmware's I2S DMA descriptor: dma_buf_len = 256 words, and at 16 bits the peripheral
     * writes CAPTURE_OVERSAMPLE = 2 words per real sample, so a descriptor holds 128 real samples
     * (firmware-v2/main/main.c). A descriptor has to complete before i2s_read can see it, which
     * is where the term comes from.
     *
     * This is the one term not measured on the device, and it is cross-referenced from the
     * firmware in this same repository rather than assumed. It is also the smallest term by a
     * wide margin — about 3 ms against a buffer of a few hundred — so being a little wrong about
     * it does not matter much. If it ever does, the device would have to report it. */
    static let dmaFramesPerDescriptor = 128

    /// `framesPerPacket` comes from the decoded packet, so it covers PCM and ADPCM alike and
    /// follows a frame-size change without being told.
    static func capture(rate: Int, framesPerPacket: Int) -> Double {
        guard rate > 0 else { return 0 }
        return Double(dmaFramesPerDescriptor + framesPerPacket) * 1000 / Double(rate)
    }
}

/// Round-trip times on the control channel, kept as a small window so the MINIMUM can be taken.
///
/// The minimum is the useful statistic, not the mean: a probe that queued behind other traffic
/// measures the queueing, and the audio path's steady-state delay already accounts for queueing
/// in the jitter buffer's depth. What is wanted here is the propagation floor, which is what the
/// smallest sample of a short window estimates.
///
/// The probes are free: the 1.5 s heartbeat is already a DISCOVER, and the firmware answers it
/// with an INFO carrying the same sequence number back (main.c, eassp_send_info), so matching the
/// reply to the request needs no new protocol and no extra packets.
struct RttWindow {
    private var samples: [(seq: UInt16, sentAt: Date)] = []
    private var recent: [Double] = []
    private let keep = 10

    mutating func sent(seq: UInt16, at t: Date = Date()) {
        samples.append((seq, t))
        // Unanswered probes would otherwise accumulate; anything this old is lost, not late.
        samples.removeAll { t.timeIntervalSince($0.sentAt) > 10 }
    }

    /// Returns the round trip in ms if this reply matches an outstanding probe.
    @discardableResult
    mutating func received(seq: UInt16, at t: Date = Date()) -> Double? {
        guard let i = samples.lastIndex(where: { $0.seq == seq }) else { return nil }
        let ms = t.timeIntervalSince(samples[i].sentAt) * 1000
        samples.removeSubrange(0...i)          // this reply retires the older probes too
        recent.append(ms)
        if recent.count > keep { recent.removeFirst(recent.count - keep) }
        return ms
    }

    /// Half the smallest recent round trip: the one-way propagation estimate.
    var oneWayMs: Double { (recent.min() ?? 0) / 2 }
    var minRttMs: Double? { recent.min() }
}
