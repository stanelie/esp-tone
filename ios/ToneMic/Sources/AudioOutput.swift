import AVFoundation

/// Playback via AVAudioSourceNode: a pull-based render callback, which is the natural fit for a
/// jitter buffer — the engine asks for exactly the frames it needs, when it needs them.
///
/// The node runs at the HARDWARE rate, not the microphone's, and RateMatcher does the conversion.
/// That is the opposite of what this file used to do, and the reason is in RateMatcher.swift: when
/// the engine owned the conversion, the rate at which the jitter buffer drained was invisible and
/// uncontrolled, and it was 1.33% slow — so latency grew without bound. Pulling at the hardware
/// rate makes the drain rate exact, and leaves the ratio somewhere it can be corrected.
final class AudioOutput {
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
    private var matcher: RateMatcher?
    private var buffer: JitterBuffer?
    private var routeObserver: NSObjectProtocol?
    private(set) var running = false

    /* iOS's own output delay: the hardware path plus the IO buffer we asked for. Sampled while
     * the session is active and held, because stop() deactivates the session and the reported
     * value goes stale. */
    private(set) var outputLatencyMs = 0.0

    /// Refreshes the reported output latency; valid only while running.
    func sampleOutputLatency() {
        guard running else { return }
        let s = AVAudioSession.sharedInstance()
        outputLatencyMs = (s.outputLatency + s.ioBufferDuration) * 1000
        engineRate = engine.mainMixerNode.outputFormat(forBus: 0).sampleRate
        routeName = s.currentRoute.outputs.first?.portType.rawValue ?? "none"
    }

    /// Output frames the engine has pulled, for measuring its true rate.
    var outFrames: Int { matcher?.outFrames ?? 0 }
    var renderCalls: Int { matcher?.renderCalls ?? 0 }
    var renderNanos: UInt64 { matcher?.renderNanos ?? 0 }
    var renderMaxNanos: UInt64 { matcher?.renderMaxNanos ?? 0 }
    /// The playout rate CoreAudio reports, and frames the engine skipped outright.
    var measuredOutRate: Double { matcher?.measuredOutRate ?? 0 }
    var skippedFrames: Int { matcher?.skippedFrames ?? 0 }
    /// What the node was built at, and what the engine's output actually is. A route change can
    /// move the hardware rate out from under a node already running at the old one, which puts a
    /// converter back in the path and takes the playout rate out of this app's hands again.
    private(set) var nodeRate = 0.0
    private(set) var engineRate = 0.0
    private(set) var routeName = "?"

    /// The correction the rate loop has learned, for telemetry. This is the measured mismatch
    /// between the microphone's clock and this phone's.
    var rateTrim: Double { matcher?.trim ?? 0 }

    func start(buffer: JitterBuffer) throws {
        stop()

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default)
        // Ask for a short IO buffer: this is the floor on output latency, and the jitter buffer
        // is already carrying as much delay as the link needs.
        try? session.setPreferredIOBufferDuration(0.005)
        try session.setActive(true)

        let hw = session.sampleRate
        guard hw > 0, let format = AVAudioFormat(standardFormatWithSampleRate: hw, channels: 1)
        else {
            throw NSError(domain: "ToneMic", code: 1,
                          userInfo: [NSLocalizedDescriptionKey:
                                        "cannot make a format at \(hw) Hz"])
        }

        let rm = RateMatcher(inRate: buffer.rate, outRate: hw)
        let node = AVAudioSourceNode(format: format) { _, timestamp, frameCount, audioBufferList in
            rm.noteTimestamp(timestamp.pointee, frames: Int(frameCount))
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let ptr = abl[0].mData?.assumingMemoryBound(to: Float.self) else {
                return noErr
            }
            // Gain, filtering and limiting all happen before the jitter buffer, so this is just
            // the buffer plus interpolation.
            rm.render(into: ptr, frames: Int(frameCount), from: buffer)
            return noErr
        }

        print("[ToneMic] audio: source \(buffer.rate) Hz -> node \(hw) Hz, "
              + "io \(String(format: "%.1f", session.ioBufferDuration * 1000)) ms")
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        try engine.start()
        source = node
        matcher = rm
        self.buffer = buffer
        running = true
        nodeRate = hw
        /* Rebuild if the route moves the hardware rate. A node keeps the format it was created
         * with, so when the hardware rate changes underneath it AVAudioEngine silently inserts a
         * converter — and that converter is precisely what this whole design exists to remove
         * (see RateMatcher.swift). The playout rate stops being this app's to set, and the buffer
         * drifts again with nothing in the telemetry to say why. Switching to a Bluetooth earpiece
         * mid-stream does exactly this: 48000 -> 44100. */
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main) { [weak self] _ in self?.routeChanged() }
        sampleOutputLatency()
        print("[ToneMic] route \(routeName), node \(hw) Hz, engine \(engineRate) Hz, "
              + "out latency \(String(format: "%.1f", outputLatencyMs)) ms")
    }

    private func routeChanged() {
        guard running, let b = buffer else { return }
        let hw = AVAudioSession.sharedInstance().sampleRate
        guard hw > 0, abs(hw - nodeRate) > 1 else {
            sampleOutputLatency()           // same rate: just refresh the reported latency
            return
        }
        print("[ToneMic] route change: \(nodeRate) -> \(hw) Hz, rebuilding the output")
        try? start(buffer: b)               // start() stops the old node first
    }

    func stop() {
        guard running else { return }
        if let o = routeObserver { NotificationCenter.default.removeObserver(o) }
        routeObserver = nil
        engine.stop()
        if let s = source { engine.detach(s) }
        source = nil
        matcher = nil
        running = false
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
