import Foundation
import Network

/// Talks to the microphone: CONFIGURE to start, heartbeat to hold the stream, decode, play.
@MainActor
final class MicClient: ObservableObject {

    enum State: Equatable {
        case idle, connecting, playing, failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var status = "Not connected"
    @Published private(set) var info: Eassp.Info?
    private var connectedId: String?
    private var startedAt = Date()

    /* Telemetry to a file in the app container, pulled off the device with
     * `devicectl device copy from`. The console drops output around a force-quit and asking a
     * human to read numbers off a screen once a minute is no way to measure a fault that happens
     * every few seconds. One line per second, truncated at the start of each stream. */
    private lazy var statsURL: URL? = {
        try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                     appropriateFor: nil, create: true)
            .appendingPathComponent("stats.log")
    }()
    private var statsHandle: FileHandle?

    private func openStats() {
        guard let u = statsURL else { return }
        statsHandle?.closeFile()
        /* APPEND across runs rather than truncating.
         *
         * This used to call createFile, which wipes the log every time a stream starts — so a
         * session recorded away from the desk was destroyed by the next session at it. That is
         * exactly backwards for the runs that matter most: the ones that cannot be measured live.
         * Capped so it cannot grow without bound; 4 MB is several hours of once-a-second lines. */
        let fm = FileManager.default
        if let size = try? fm.attributesOfItem(atPath: u.path)[.size] as? Int, size > 4_000_000 {
            try? fm.removeItem(at: u)
        }
        if !fm.fileExists(atPath: u.path) { fm.createFile(atPath: u.path, contents: nil) }
        statsHandle = try? FileHandle(forWritingTo: u)
        _ = try? statsHandle?.seekToEnd()
        appendStats("# t,buffered_ms,target_ms,auto,pkt,late,underruns,concealed_ms,trimmed_ms,"
                    + "gap_worst_ms,gap_window_ms,codec,pulled_frames,rate_trim_pct,ring_frames,"
                    + "e2e_ms,cap_ms,net_ms,out_ms,out_frames,node_hz,engine_hz,route,"
                    + "pkt_per_tick,loss_events,worst_loss_burst,reordered,worst_reorder,"
                    + "duplicates,too_late,render_calls,render_us_mean,render_us_max,"
                    + "overflows,overflow_ms,measured_out_hz,skipped_frames")
    }

    private func appendStats(_ line: String) {
        guard let h = statsHandle, let d = (line + "\n").data(using: .utf8) else { return }
        h.write(d)
        try? h.synchronize()
    }
    @Published var host = "10.8.1.168"
    /// A remembered device plus whether the last scan could reach it.
    struct Row: Identifiable, Equatable {
        var device: StoredDevice
        var available: Bool
        var id: String { device.id }
    }
    @Published private(set) var rows: [Row] = []
    @Published private(set) var scanning = false
    /// Visible in the playback sheet: what was read at launch and how often it has been written.
    /// On screen rather than in the console, because the console drops output around a force-quit.
    @Published private(set) var prefsDebug = ""
    private var saveCount = 0
    @Published var gainDb = 30 { didSet { chain?.gainDb = gainDb; savePrefs() } }
    /// Playback-side low cut / high cut in Hz; 0 is off. See Filters.
    @Published var lowCutHz = 0 {
        didSet { chain?.configure(lowCut: lowCutHz, highCut: highCutHz); savePrefs() }
    }
    @Published var highCutHz = 0 {
        didSet { chain?.configure(lowCut: lowCutHz, highCut: highCutHz); savePrefs() }
    }
    /// When on, Agc replaces the plain gain: same loudness, but it ducks before a peak overflows.
    @Published var agcOn = false { didSet { chain?.agcOn = agcOn; chain?.resetAgc(); savePrefs() } }
    /// 0 = automatic. Applies live, to a stream already playing.
    @Published var bufferMs = 120 { didSet { jitter?.setTarget(ms: bufferMs); savePrefs() } }

    /* One UDP socket for everything.
     *
     * The first attempt used an NWListener on an ephemeral port and let it hand out a connection
     * per peer. The device's audio did arrive - the log showed "inbound connection from
     * 10.8.1.168:3950" over and over - but a NEW connection was spawned for every datagram and
     * none of them ever delivered a message, because nothing retained them long enough for
     * receiveMessage to complete.
     *
     * A single connection bound to a known local port is both simpler and a better fit for the
     * protocol: CONFIGURE nominates the port the device should send to, so sending it from the
     * very socket that will receive the audio removes a whole class of mismatch. Control replies
     * and audio are told apart by the EASSP magic, which only control packets carry - an audio
     * packet begins with its sequence number. */
    private static let localPort: UInt16 = 39501

    private var link: NWConnection?
    private let output = AudioOutput()
    private var jitter: JitterBuffer?
    private var chain: AudioChain?
    private var seq: UInt16 = 1
    private var received: UInt32 = 0
    private var lastSeq: Int = -1
    private var lost = 0
    private var lossEvents = 0
    /// Packets that arrived BEHIND the write head and were thrown away, and how far behind the
    /// worst one was. If this tracks `lost`, the loss is ours, not the network's.
    private var reordered = 0
    private var worstReorderBehind = 0
    private var duplicates = 0
    private var tooLate = 0
    private var worstLossBurst = 0
    /// Packets seen at the previous status tick, so the log shows the arrival RATE — a rate that
    /// falls to zero means the stream stopped, which is a different fault from losing packets.
    private var lastTickPackets: UInt32 = 0
    private var format = ""
    private var currentCodec: UInt8 = Eassp.codecPCM
    /// End-to-end delay, for the UI. See Latency.swift for what each term is and where it comes
    /// from. `nil` until a packet and a probe reply have both arrived.
    @Published private(set) var latency: LatencyBreakdown?
    private var rtt = RttWindow()
    private var framesPerPacket = 0

    private var heartbeatTimer: Timer?
    private var statsTimer: Timer?

    // MARK: - preferences

    /* Playback settings persist.
     *
     * MEASURED, and the opposite of what the Swift book's "observers are not called during
     * initialization" implies: that holds for a PLAIN stored property, but a @Published one fires
     * its didSet even inside init, because the assignment goes through the wrapper's setter.
     *
     * That made the loader destroy its own data. Loading gainDb fired didSet -> savePrefs(), which
     * writes every property — and the rest were still at their defaults, so the stored buffer,
     * cutoffs and limiter were overwritten with defaults before the next line could read them.
     * Gain survived; nothing after it did. Hence `loaded` below: no saving until loading is done. */
    private enum Pref {
        static let gain = "playback.gainDb"
        static let buffer = "playback.bufferMs"
        static let lowCut = "playback.lowCutHz"
        static let highCut = "playback.highCutHz"
        static let agc = "playback.agcOn"
    }

    private var loaded = false

    init() {
        let d = UserDefaults.standard
        let had = d.object(forKey: Pref.gain) != nil
        defer {
            prefsDebug = "loaded \(had ? "stored" : "DEFAULTS") — gain \(gainDb), buf \(bufferMs), "
                       + "lo \(lowCutHz), hi \(highCutHz), agc \(agcOn)"
        }
        if d.object(forKey: Pref.gain) != nil { gainDb = d.integer(forKey: Pref.gain) }
        if d.object(forKey: Pref.buffer) != nil { bufferMs = d.integer(forKey: Pref.buffer) }
        if d.object(forKey: Pref.lowCut) != nil { lowCutHz = d.integer(forKey: Pref.lowCut) }
        if d.object(forKey: Pref.highCut) != nil { highCutHz = d.integer(forKey: Pref.highCut) }
        if d.object(forKey: Pref.agc) != nil { agcOn = d.bool(forKey: Pref.agc) }
        loaded = true
    }

    private func savePrefs() {
        guard loaded else { return }        // see above: never save while still loading
        let d = UserDefaults.standard
        d.set(gainDb, forKey: Pref.gain)
        d.set(bufferMs, forKey: Pref.buffer)
        d.set(lowCutHz, forKey: Pref.lowCut)
        d.set(highCutHz, forKey: Pref.highCut)
        d.set(agcOn, forKey: Pref.agc)
        // Belt and braces for a force-quit: UserDefaults batches writes and flushes when it
        // chooses. This was NOT what broke persistence — that was the init-time clobber above —
        // but it costs nothing and a swiped-away app is exactly the case Apple's "unnecessary"
        // deprecation note does not cover.
        d.synchronize()
        saveCount += 1
        prefsDebug = "saved ×\(saveCount) — gain \(gainDb), buf \(bufferMs), "
                   + "lo \(lowCutHz), hi \(highCutHz), agc \(agcOn)"
    }

    // MARK: - discovery

    /// Sweeps the subnet and merges what it finds into the remembered list. A device the sweep
    /// misses is marked unreachable rather than forgotten — it may simply be switched off, and
    /// losing its address would mean typing it again.
    func scan() {
        guard !scanning else { return }
        scanning = true
        status = "Scanning the network…"
        Task.detached(priority: .userInitiated) {
            let found = Discovery.scan()
            await MainActor.run {
                for f in found {
                    DeviceStore.save(StoredDevice(id: f.id, name: f.name, host: f.host))
                    DeviceStore.updateAddress(id: f.id, host: f.host)
                }
                let seen = Set(found.map(\.id))
                self.reload(available: seen)
                self.scanning = false
                self.status = found.isEmpty
                    ? "No transmitters found. Add one by address if it is on another network."
                    : "\(found.count) transmitter(s) found"
                if self.selected == nil, let first = self.rows.first(where: { $0.available }) {
                    self.select(first.device)
                }
            }
        }
    }

    /// Adds a transmitter by address. The sweep cannot cross a router, so this is the only way
    /// to reach one over a VPN or on another subnet — it asks that address directly, and the INFO
    /// reply supplies the identity that makes it a remembered device rather than just a string.
    func addByAddress(_ text: String) {
        let h = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !h.isEmpty else { return }
        status = "Checking \(h)…"
        Task.detached(priority: .userInitiated) {
            let info = Discovery.probe(host: h, timeout: 2.5)
            await MainActor.run {
                guard let i = info else {
                    self.status = "No transmitter answered at \(h)"
                    return
                }
                DeviceStore.save(StoredDevice(id: i.id, name: i.name, host: h))
                DeviceStore.updateAddress(id: i.id, host: h)
                let reachable = Set(self.rows.filter(\.available).map(\.id)).union([i.id])
                self.reload(available: reachable)
                self.host = h
                self.status = "Added \(i.name) at \(h)"
            }
        }
    }

    /// Rebuilds the visible list, reachable ones first.
    private func reload(available: Set<String>) {
        rows = DeviceStore.all()
            .map { Row(device: $0, available: available.contains($0.id) || $0.id == connectedId) }
            .sorted { ($0.available ? 0 : 1, $0.device.name) < ($1.available ? 0 : 1, $1.device.name) }
    }

    var selected: StoredDevice? { rows.first { $0.device.host == host }?.device }

    func select(_ d: StoredDevice) { host = d.host }

    func forget(_ d: StoredDevice) {
        if connectedId == d.id { stop() }
        DeviceStore.remove(id: d.id)
        reload(available: Set(rows.filter(\.available).map(\.id)))
        status = "Forgot \(d.name)"
    }

    /// Resumes whatever was playing when the app was last used. Probes the remembered address
    /// directly rather than waiting for a sweep: it is one packet, and the common case is that
    /// the device has not moved.
    func resumeIfWasPlaying() {
        reload(available: [])
        guard let target = DeviceStore.resumeTarget() else {
            scan()
            return
        }
        host = target.host
        status = "Resuming \(target.name)…"
        Task.detached(priority: .userInitiated) {
            let reachable = Discovery.probe(host: target.host)?.id == target.id
            await MainActor.run {
                if reachable {
                    self.reload(available: [target.id])
                    self.start()
                } else {
                    self.status = "\(target.name) did not answer; scanning…"
                    self.scan()
                }
            }
        }
    }

    // MARK: - connect

    func start() {
        guard state == .idle || isFailed else { return }
        state = .connecting
        status = "Connecting to \(host)…"
        received = 0; lastSeq = -1; lost = 0; format = ""; startedAt = Date()
        lossEvents = 0; worstLossBurst = 0; lastTickPackets = 0
        reordered = 0; worstReorderBehind = 0; duplicates = 0; tooLate = 0
        openStats()

        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true
        params.requiredLocalEndpoint = NWEndpoint.hostPort(
            host: .ipv4(.any), port: NWEndpoint.Port(rawValue: Self.localPort)!)

        let conn = NWConnection(host: NWEndpoint.Host(host),
                                port: NWEndpoint.Port(rawValue: Eassp.port)!,
                                using: params)
        link = conn
        conn.stateUpdateHandler = { [weak self] st in
            print("[ToneMic] link state: \(st)")
            guard let self else { return }
            switch st {
            case .ready:
                self.receiveLoop(conn)
                Task { @MainActor in self.began(on: conn) }
            case .failed(let e):
                Task { @MainActor in self.fail("link failed: \(e.localizedDescription)") }
            default: break
            }
        }
        conn.start(queue: .global(qos: .userInitiated))
    }

    private var isFailed: Bool { if case .failed = state { return true }; return false }

    private func began(on conn: NWConnection) {
        // Playing now, so this is the one to resume next launch.
        if let d = selected {
            connectedId = d.id
            DeviceStore.setResume(d.id)
        }
        print("[ToneMic] bound to UDP \(Self.localPort), CONFIGURE -> \(host)")
        send(Eassp.configure(seq: nextSeq(), streamPort: Self.localPort), on: conn)

        heartbeatTimer?.invalidate()
        // The device drops a listener that goes quiet after 15 s (SPEC.md §10).
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let c = self.link else { return }
                // The heartbeat IS a DISCOVER and the device echoes the seq in its INFO, so
                // timing the pair measures the link without adding a single packet.
                let seq = self.nextSeq()
                self.rtt.sent(seq: seq)
                self.send(Eassp.heartbeat(seq: seq, received: self.received), on: c)
            }
        }
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshStatus() }
        }
    }

    func stop() {
        heartbeatTimer?.invalidate(); heartbeatTimer = nil
        statsTimer?.invalidate(); statsTimer = nil
        if let c = link { send(Eassp.packet(Eassp.cmdStop, seq: nextSeq()), on: c) }
        output.stop()
        link?.cancel(); link = nil
        jitter = nil
        // A deliberate stop: nothing to resume next launch.
        DeviceStore.setResume(nil)
        connectedId = nil
        state = .idle
        status = "Stopped"
    }

    // MARK: - receive

    private nonisolated func receiveLoop(_ conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, err in
            if let e = err { print("[ToneMic] receive error: \(e)") }
            if let d = data, !d.isEmpty {
                // Only control packets carry the EASSP magic; audio starts with its sequence number.
                if d.count >= 2, d[d.startIndex] == 0xEA, d[d.startIndex + 1] == 0x55 {
                    let seq = Eassp.infoSeq(d)
                    if let i = Eassp.parseInfo(d) {
                        Task { @MainActor in
                            self?.learn(i)
                            if let q = seq { self?.rtt.received(seq: q) }
                        }
                    } else if let q = seq {
                        Task { @MainActor in self?.rtt.received(seq: q) }
                    }
                } else {
                    self?.handleAudio(d)
                }
            }
            if err == nil { self?.receiveLoop(conn) }
        }
    }

    private nonisolated func handleAudio(_ d: Data) {
        guard let h = Eassp.parseAudio(d) else {
            print("[ToneMic] unparseable audio packet, \(d.count) bytes")
            return
        }
        let body = d.subdata(in: (d.startIndex + Eassp.AudioHeader.size)..<d.endIndex)

        let samples: [Int16]
        switch h.codec {
        case Eassp.codecADPCM:
            samples = Adpcm.decode(body)
        case Eassp.codecPCM:
            samples = body.withUnsafeBytes { raw in
                let n = raw.count / 2
                var out = [Int16](repeating: 0, count: n)
                for i in 0..<n {
                    out[i] = Int16(bitPattern: UInt16(raw[i*2]) | (UInt16(raw[i*2 + 1]) << 8))
                }
                return out
            }
        default:
            return
        }
        guard !samples.isEmpty else { return }

        let n = samples.count
        Task { @MainActor in
            self.framesPerPacket = n
            self.accept(header: h, samples: samples, packetSeq: Int(h.seq))
        }
    }

    private func accept(header h: Eassp.AudioHeader, samples: [Int16], packetSeq: Int) {
        // A new format (first packet, or the device was reconfigured) needs a new output.
        let key = "\(h.codec)/\(h.rate)/\(h.channels)/\(h.bits)"
        if key != format {
            format = key
            currentCodec = h.codec
            // ADPCM transmits 4 bits but the capture is 16-bit, which is what sets the clock.
            let real = Eassp.actualRate(nominal: h.rate, bits: 16)
            let jb = JitterBuffer(rate: real, targetMs: bufferMs)
            jitter = jb
            let ch = AudioChain(rate: real)
            ch.configure(lowCut: lowCutHz, highCut: highCutHz)
            ch.gainDb = gainDb
            ch.agcOn = agcOn
            chain = ch
            jb.postProcess = ch.process
            do {
                try output.start(buffer: jb)
            } catch {
                fail("audio engine: \(error.localizedDescription)")
                return
            }
            state = .playing
            status = "Playing — \(codecName(h.codec)) \(real) Hz"
        }

        /* Straight into the buffer, carrying the sequence number.
         *
         * No ordering gate here any more: the buffer reserves a slot for every sequence number
         * and a straggler is written into its own slot if that slot has not been played yet.
         * Loss is therefore declared at playout, by the buffer, not guessed at on arrival. */
        received &+= 1
        jitter?.push(seq: UInt16(truncatingIfNeeded: packetSeq), samples)
        if let jb = jitter {
            lost = jb.realLoss
            lossEvents = jb.realLoss
            reordered = jb.recoveredLate
            duplicates = jb.duplicates
            tooLate = jb.tooLate
        }
    }

    /* Filters, then level — the order matters. A low cut placed after the limiter would have the
     * limiter ducking for rumble that is about to be removed, so the whole programme dips for a
     * sound nobody ever hears. Done here rather than in the render callback so that callback stays
     * a plain copy; the cost is that a gain change only reaches audio pushed after it. */
    /// INFO carries the device's stable id, which is how a hand-typed address becomes a
    /// remembered device rather than just a string.
    private func learn(_ i: Eassp.Info) {
        info = i
        DeviceStore.save(StoredDevice(id: i.id, name: i.name, host: host))
        DeviceStore.updateAddress(id: i.id, host: host)
        if state == .playing || state == .connecting {
            connectedId = i.id
            DeviceStore.setResume(i.id)
        }
        reload(available: Set(rows.filter(\.available).map(\.id)).union([i.id]))
    }

    private func refreshStatus() {
        guard state == .playing, let jb = jitter else { return }

        output.sampleOutputLatency()
        var lb = LatencyBreakdown()
        lb.captureMs = LatencyBreakdown.capture(rate: jb.rate, framesPerPacket: framesPerPacket)
        lb.networkMs = rtt.oneWayMs
        lb.bufferMs = Double(jb.bufferedMs)
        lb.outputMs = output.outputLatencyMs
        latency = framesPerPacket > 0 ? lb : nil
        let pct = received > 0 ? Double(lost) * 100 / Double(Int(received) + lost) : 0
        /* Loss and underruns are different faults with different fixes, and they sound the same.
         * Loss is packets that never arrived — buffering cannot help it. An underrun is the buffer
         * running dry — buffering is exactly what helps. Concealed ms is how much audio was
         * synthesised rather than received, which is what you actually hear as a glitch. */
        appendStats(String(format: "%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%@,%d,%.4f,%d,%.0f,%.1f,%.1f,%.1f,%d,%.0f,%.0f,%@,%d,%d,%d,%d,%d,%d,%d,%d,%.0f,%.0f,%d,%d,%.1f,%d",
                           Date().timeIntervalSince(startedAt),
                           jb.bufferedMs, jb.targetMs, jb.isAuto ? 1 : 0,
                           Int(received), lost, jb.underruns,
                           jb.concealedFrames * 1000 / jb.rate,
                           jb.trimmedFrames * 1000 / jb.rate,
                           jb.worstGapEverMs, jb.worstGapMs, codecName(currentCodec),
                           jb.pulledFrames, output.rateTrim * 100, jb.ringFrames,
                           lb.totalMs, lb.captureMs, lb.networkMs, lb.outputMs,
                           output.outFrames, output.nodeRate, output.engineRate,
                           output.routeName,
                           Int(received) - Int(lastTickPackets), lossEvents, worstLossBurst,
                           reordered, worstReorderBehind, duplicates, tooLate,
                           output.renderCalls,
                           output.renderCalls > 0
                               ? Double(output.renderNanos) / Double(output.renderCalls) / 1000 : 0,
                           Double(output.renderMaxNanos) / 1000,
                           jb.overflows, jb.overflowMs,
                           output.measuredOutRate, output.skippedFrames))
        lastTickPackets = received

        let target = jb.isAuto ? "auto \(jb.targetMs)" : "\(jb.targetMs)"
        let secs = max(1, Int(Date().timeIntervalSince(startedAt)))
        status = String(format:
            "%@ %@  ·  buf %d/%@ ms\n%d pkt, %.2f%% late/lost (%d)  ·  %d underruns\ngap worst %d ms (window %d)  ·  concealed %d ms (%d/min)  ·  trim %d ms",
            "Playing", codecName(currentCodec), jb.bufferedMs, target,
            Int(received), pct, lost, jb.underruns,
            jb.worstGapEverMs, jb.worstGapMs,
            jb.concealedFrames * 1000 / jb.rate,
            jb.concealedFrames * 1000 / jb.rate * 60 / secs,
            jb.trimmedFrames * 1000 / jb.rate)
    }

    private func codecName(_ c: UInt8) -> String {
        c == Eassp.codecADPCM ? "ADPCM" : "PCM"
    }

    private func fail(_ why: String) {
        state = .failed(why)
        status = why
        output.stop()
    }

    private func send(_ d: Data, on conn: NWConnection) {
        conn.send(content: d, completion: .contentProcessed { _ in })
    }

    private func nextSeq() -> UInt16 { seq &+= 1; return seq }
}
