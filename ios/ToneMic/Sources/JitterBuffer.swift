import Foundation

/// A jitter buffer: packets arrive bunched, playout must be smooth.
///
/// Measured on this link (SPEC.md §11.4): mean inter-arrival is a clean 10 ms but p99 is 26 ms and
/// ~5% of packets arrive back-to-back, so playing straight from the socket underruns constantly.
///
/// The target is read live, so moving the control changes the delay of a stream already playing.
/// Raising it refills; lowering it is absorbed by the trimmer below, a pitch period at a time.
/// MEASURED: dropping 543 ms of delay this way left a worst sample-to-sample step of 0.0105 on a
/// 300 Hz tone — which is exactly what that tone does anyway, so the cuts are inaudible.
final class JitterBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private var ring: [Int16]
    private var head = 0, count = 0
    private let capacity: Int
    private var filling = true

    let rate: Int

    /// Milliseconds of audio to hold. 0 means automatic: follow the worst gaps on the link.
    private var requestedMs: Int

    /* Worst gap seen in each of the last 30 seconds, so a single bad second ages out.
     *
     * This window is the memory the target floor is built on, and it was 10 s. MEASURED: a 95 ms
     * gap arrived at t=120 s when the window had seen nothing worse than 28 ms, so the floor was
     * 68 ms, the depth was ~100 ms, and the buffer ran dry. Gaps that big were turning up roughly
     * a minute apart on this link — i.e. reliably OUTSIDE a 10 s memory — so the floor kept
     * forgetting them just in time to be surprised again.
     *
     * 30 s costs a little latency when the link has one bad moment and then behaves, and that is
     * the cheaper mistake: the latency is inaudible and the underrun is not. */
    private var gapSlots = [Int](repeating: 0, count: 30)
    private var slot = 0
    private var slotStarted = Date()
    private var lastArrival: Date?
    private var worstEver = 0
    private var autoHeld = 0.0
    private var autoUpdated = Date()
    /// The target as of the last pull, so a RISE can be acted on. Auto changes the target
    /// internally, through neither an underrun nor setTarget, so without this the number moved
    /// and the depth never followed it — measured: target 273 ms, buffer stuck at 46 ms.
    private var lastTarget = 0
    /* How much has to be buffered before playout resumes. Not the same as the target.
     *
     * `filling` used to wait for the FULL target, which is fine on a fresh stream and ruinous
     * after an underrun: MEASURED with a 2 s target, two underruns in 47 s produced 2396 ms of
     * concealment — the buffer dutifully synthesising a second of audio each time while it
     * refilled to 2 s. That is why raising the buffer setting made things worse rather than
     * better, and it is the opposite of what a deep buffer is for.
     *
     * After an underrun, resume as soon as there is enough to play safely and let the rate
     * matcher grow the depth back toward the target inaudibly, which is its job. */
    private var resumeFrames = 0
    private static let resumeFloorMs = 60
    /// Stream start, and whether anything has been played yet — the pre-roll applies only to a
    /// fresh stream, never to an underrun refill.
    private var firstPushAt: Date?
    private var everPlayed = false
    private static let prerollMs = 400.0

    /* Absolute frame accounting, which is what makes a late packet placeable.
     *
     * `written` is the position just past the newest frame; `consumed` is the position of the
     * oldest frame still in the ring. Both only ever increase, so they give every frame a stable
     * address that survives the ring wrapping — and comparing a packet's address against
     * `consumed` answers the only question that matters about a straggler: has its audio already
     * gone to the speaker, or is there still time to put it where it belongs? */
    private var written = 0
    private var consumed = 0

    /* Slots reserved for packets that have not arrived: sequence -> where its audio belongs.
     *
     * This is the heart of the design. The receive path used to decide at ARRIVAL whether a
     * packet was lost, with a zero-width window, and discard anything out of order — MEASURED
     * over a VPN: 113 packets counted as lost, of which 81 had actually arrived and been thrown
     * away, never more than 3 positions out of order. The decision belongs at PLAYOUT instead:
     * when a slot's audio is due, either the packet is here or it is not.
     *
     * Reserving the slot moves the decision there. The reordering tolerance becomes the buffer
     * depth — hundreds of milliseconds, with no window constant to choose — and nothing is ever
     * held back waiting, because by the time a slot is due its packet has long since arrived. */
    private var holes: [UInt16: (pos: Int, len: Int)] = [:]
    private var expectedSeq = -1

    /// Late packets put back in their slot. Each one would previously have been discarded AND
    /// counted as a loss.
    private(set) var recoveredLate = 0
    /// Packets that arrived after their slot had already been played: the only unusable ones.
    private(set) var tooLate = 0
    /// Slots that were played without their packet ever arriving: real loss.
    private(set) var realLoss = 0
    private(set) var duplicates = 0

    private(set) var underruns = 0
    private(set) var concealedFrames = 0
    /// Frames handed to the audio engine. Dividing by elapsed time gives the true playout rate,
    /// which is the only way to tell a device that sends too fast from a phone that plays too slow.
    private(set) var pulledFrames = 0
    /// Frames actually taken from the ring. `pulledFrames` counts everything handed to the engine,
    /// concealment included — and concealed frames are produced without consuming any buffer, so
    /// using that total as the playout rate overstates it. Reconciling the 116 s run needed this
    /// split: 44480 frames/s pulled, of which 618/s were synthesised, leaving 43862/s truly drained.
    private(set) var ringFrames = 0
    private(set) var trimmedFrames = 0

    /* Concealment and delay trimming, ported from the Android player.
     *
     * A gap is filled by continuing the waveform (Concealer) rather than going silent, and the
     * seam back into real audio is crossfaded. Excess delay — left behind by a burst, a filled
     * gap, or clock drift — is removed one PITCH PERIOD at a time at a point where the waveform
     * repeats, which is close to inaudible; that is what makes lowering the buffer, or recovering
     * from a stall, free of clicks. */
    private let conceal: Concealer
    private let blockFrames: Int        // 5 ms
    private let minLag: Int             // 400 Hz
    private let maxLag: Int             // 70 Hz
    private let window: Int             // 10 ms compared at each lag
    private let trimDeadband: Int       // only reclaim delay above this much
    /* And no faster than this fraction of real time, ACCRUED CONTINUOUSLY.
     *
     * The first rate limit handed out a whole second's allowance at once, so the trimmer spent it
     * in a burst and then idled — turning a steady 2% excision into one audible snip per second,
     * which is exactly the periodic glitch it was supposed to prevent. Accruing a little on every
     * pull spreads the same total smoothly. */
    /* 0.5% of real time. Clock drift between the microphone and the phone is well under 0.1%,
     * so this tracks it easily, and reclaiming a 600 ms overshoot takes about two minutes —
     * which nobody minds. Earlier values were chosen to reclaim quickly: 3% produced a sawtooth
     * (dump the excess, wait, dump again) and no limit at all produced 17% bursts. Every version
     * that reclaimed fast enough to notice was audible, so the answer is to reclaim slowly. */
    private let trimFraction = 0.005
    private var trimBudget = 0.0
    private var excess = 0              // frames of pure delay still to remove
    private var cutTick = 0
    private var lowest = Int.max
    private var sinceCheck = 0
    private var concealing = false
    private var scratchA = [Float]()
    private var scratchB = [Float]()
    private var corr = [Float]()

    var bufferedMs: Int { lock.withLock { count * 1000 / max(1, rate) } }
    var targetMs: Int { lock.withLock { effectiveTargetMs() } }
    var isAuto: Bool { lock.withLock { requestedMs == 0 } }
    /// The worst arrival gap still inside auto's window, and the worst ever seen this stream.
    /// Auto can only react to what it still remembers, so both numbers matter.
    var worstGapMs: Int { lock.withLock { gapSlots.max() ?? 0 } }
    var worstGapEverMs: Int { lock.withLock { worstEver } }

    /// What the rate loop needs to know, read under one lock.
    ///
    /// `errorMs` is positive when the buffer is deeper than the target. It reads 0 while the
    /// buffer is refilling or concealing: there is no meaningful depth then, and letting the
    /// integrator wind up during a stall would leave the rate wrong for a minute afterwards.
    ///
    /// `giveBack` is the fraction of real time by which playout must RUN FAST to keep up with a
    /// target that auto is currently walking downward. It is feedforward, and it is here because
    /// the give-back rate is something this class already knows — making the loop rediscover it
    /// through feedback costs a standing error for as long as the target is moving. MEASURED: the
    /// target descended 3 ms/s while the depth followed at only 2.27 ms/s, leaving a permanent
    /// +31 ms error that drove the integrator onto its clamp, where it was no longer estimating
    /// clock drift at all. Reclaiming latency and playing slightly fast are the same act; this
    /// says so once instead of having two mechanisms negotiate it.
    func loopInput() -> (errorMs: Double, giveBack: Double) {
        lock.withLock {
            guard !filling && !concealing else { return (0, 0) }
            let target = frames(for: effectiveTargetMs())
            let err = Double(count - target) * 1000.0 / Double(max(1, rate))
            // Only while auto is actually decaying: pinned at the floor, or rising, it is not.
            let decaying = requestedMs == 0 && autoHeld > Double(autoFloorMs()) + 1
            return (err, decaying ? Self.giveBackMsPerSecond / 1000.0 : 0)
        }
    }

    /// The largest target anything may ask for.    /// The largest target anything may ask for. The ring is sized from it, so raising this is
    /// the only change needed to support a worse link.
    static let maxTargetMs = 5000

    init(rate: Int, targetMs: Int) {
        self.rate = rate
        self.requestedMs = targetMs
        /* Twice the maximum plus a second. The target has to be reachable with room to spare:
         * bursts push the depth above it, and the ring drops the OLDEST audio when it fills, so a
         * ring merely equal to the target would quietly discard what it had just buffered. At
         * 48 kHz mono that is about 1 MB for a 5 s ceiling. */
        self.capacity = max(1, rate * (Self.maxTargetMs * 2 + 1000) / 1000)
        self.ring = [Int16](repeating: 0, count: capacity)
        self.conceal = Concealer(rate: rate)
        self.blockFrames = max(1, rate / 200)
        self.minLag = max(1, rate / 400)
        self.maxLag = max(2, rate / 70)
        self.window = max(2, rate / 100)
        /* Reclaim only overshoots the rate matcher cannot be left to clear on its own.
         *
         * This was 300 ms, and that is audible for a reason worth recording: the rate matcher
         * drains an overshoot at up to 0.5% of real time with nothing but a few hundredths of a
         * percent of pitch shift, while every cut this trimmer makes is a splice. MEASURED on a
         * 59 s run whose depth sat 307-550 ms above target: the trimmer removed 301 ms — its full
         * budget, continuously — and that was exactly the "small glitches" being reported, with
         * no underruns and no concealment anywhere in the same run.
         *
         * So the threshold is now 1 s, which the matcher would need over three minutes to clear
         * by itself. Below that, carrying the latency is strictly better than cutting the audio;
         * above it, waiting minutes is worse. Latency is free and splices are not.
         */
        self.trimDeadband = max(1, rate * 1000 / 1000)
    }

    /// Applies while playing.
    func setTarget(ms: Int) {
        lock.withLock {
            guard ms != requestedMs else { return }
            requestedMs = ms
            let want = frames(for: effectiveTargetMs())
            if count > want {
                // Lowering it: drop the oldest, which is the delay the user asked to remove.
                let drop = count - want
                head = (head + drop) % capacity
                count -= drop
            } else if count < want {
                filling = true          // raising it: let it refill before playing on
                resumeFrames = want
            }
        }
    }

    /* Auto: hold the low-water mark at a safety margin.
     *
     * The first version drove the target from the worst ARRIVAL GAP plus 15 ms. That is only a
     * proxy for what matters: a 706 ms gap does not need 706 ms of buffer if the buffer was deep
     * at the time and the packets arrived in a burst afterwards, and it can equally underestimate.
     * What the buffer actually has to survive is running dry, so measure that directly — the
     * lowest occupancy reached since the last check.
     *
     *   never dipped below the margin  ->  there is headroom to give back, decay slowly
     *   dipped below the margin        ->  deepen by the shortfall at once
     *   ran dry (underrun)             ->  deepen hard; we already made a noise
     *
     * Rises are immediate and falls are slow on purpose. A link that stalled once will stall
     * again, often further apart than any short observation window; the cost of carrying the
     * depth is latency, the cost of giving it up too early is an audible glitch.
     */
    /* Give headroom back at a FIXED 3 ms per second, not as a fraction of the depth.
     *
     * This was 0.97 per second — 3% of the depth, which at a 350 ms depth is 10 ms/s. Nothing can
     * follow that inaudibly: the rate matcher's whole budget is 0.5% of real time (5 ms/s) and the
     * trimmer's is the same, so the target descended about six times faster than the depth could
     * be reduced. The consequences were visible in two separate measurements — the old trimmer
     * nibbling forever (1916 ms removed in 98 s) and then the rate loop's integrator winding up
     * against an error it could never null. A setpoint that moves faster than any mechanism can
     * track it is not a setpoint, it is a standing error.
     *
     * 3 ms/s stays inside the budget, and reclaiming 100 ms of stale headroom takes about half a
     * minute, which nobody minds. */
    private static let giveBackMsPerSecond = 3.0
    /* Faster give-back once the link has behaved for a while.
     *
     * A single underrun adds 150 ms, and at 3 ms/s that is fifty seconds of carrying latency for
     * one momentary fault — MEASURED on a LAN: one underrun at t=13 s held the target above
     * 180 ms for the rest of the run, with the depth dutifully following it up. 4.5 ms/s stays
     * inside the rate matcher's 0.5% (5 ms/s) budget, so the depth can still track the target
     * down without the trimmer having to splice anything. */
    private static let giveBackCleanMsPerSecond = 4.5
    private static let cleanAfterSeconds = 15.0
    private var lastUnderrunAt: Date?
    private static let marginMs = 40               // keep this much slack at the low-water mark
    private static let autoStartMs = 150.0         // first guess, before any gap is measured
    private static let underrunJumpMs = 150        // a dry buffer is evidence of a big stall

    /// Called on the periodic check with the lowest occupancy seen since the previous one.
    private func autoObserve(lowestFrames: Int) {
        /* Only from a stream that is actually running, and never faster than it can be given back.
         *
         * This is called four times a second and used to add the whole shortfall each time, up to
         * 160 ms/s, while the give-back is 3 ms/s. During the startup transient the buffer is
         * legitimately shallow, so it ratcheted hard on a condition that was not a fault at all
         * and then took minutes to release — MEASURED: the target reached 2000 ms on a link whose
         * worst gap was 380 ms. Capped, and gated on the stream having settled. */
        guard requestedMs == 0, everPlayed, !filling else { return }
        guard Date().timeIntervalSince(firstPushAt ?? Date()) > 2 else { return }
        /* And only once the depth has actually REACHED the target.
         *
         * A buffer still climbing toward its target is not evidence the target is too low; it is
         * evidence the rate matcher has not finished filling it, and the matcher is limited to
         * 0.5% of real time by design. Without this the two form a positive feedback loop — the
         * depth is shallow, so the low-water mark dips below the margin, so the target rises, so
         * the depth is further from target, so it keeps dipping. MEASURED on the Android client,
         * which shares this policy, over a LAN with a healthy 71 ms worst gap: the target walked
         * 174 -> 238 ms while the depth sat at 50 ms and the error never closed. */
        guard count >= frames(for: effectiveTargetMs()) else { return }
        let lowMs = lowestFrames * 1000 / rate
        if lowMs < Self.marginMs {
            autoHeld += min(Double(Self.marginMs - lowMs), Self.maxRisePerObservation)
        }
    }

    /// At four observations a second this caps the climb at 40 ms/s, against 3 ms/s of give-back.
    private static let maxRisePerObservation = 10.0

    /* A dry buffer is direct evidence that the depth was too shallow, so respond to the MEASURED
     * cause rather than creeping toward it.
     *
     * This added a flat 150 ms. On a link whose stalls grow, creeping costs one audible glitch
     * per step: MEASURED over a VPN, arrival gaps climbed 55 -> 84 -> 140 -> 182 -> 767 ->
     * 1072 ms, and the target crept up in 150 ms increments — EIGHT underruns across 20 s before
     * it settled at 1.3 s and went quiet. The gap that caused each underrun was already recorded,
     * so using it converges in one step instead of eight.
     *
     * The WORST gap in the window is used here, not the second worst that autoFloorMs uses. The
     * floor is deliberately conservative because it would otherwise inflate latency on a single
     * outlier with no evidence of harm; here there is evidence of harm — the buffer ran dry — so
     * the outlier is precisely what has to be covered. */
    private func autoNoteUnderrun() {
        lastUnderrunAt = Date()
        guard requestedMs == 0 else { return }
        let worstGap = gapSlots.max() ?? 0
        autoHeld = max(autoHeld + Double(Self.underrunJumpMs) / 3,
                       Double(min(Self.maxTargetMs, worstGap + Self.marginMs)))
    }

    /* The floor: survive the worst arrival gap the link has recently shown, plus the margin.
     *
     * A gap of G ms delivers nothing for G ms, so the depth drains by G — a buffer shallower than
     * the worst gap WILL run dry, and no amount of reacting afterwards prevents the glitch that
     * already happened. MEASURED: auto let the target decay to its 40 ms floor on a link whose
     * worst gap in the same window was 164 ms, ran dry at t=63 s, and only then jumped to 324 ms.
     * The information needed to avoid it was already in hand before the underrun.
     *
     * An earlier version of auto drove the target from the worst gap and I removed it, on the
     * grounds that a 706 ms gap does not need 706 ms of buffer. That reasoning was wrong: it
     * confused a gap with a burst. Packets arriving in a clump afterwards do refill the buffer,
     * but the silence still has to be ridden out first. What was right about removing it is that
     * the gap alone is not SUFFICIENT — hence this is a floor under the low-water-mark learning
     * below, not a replacement for it.
     *
     * It reads the 10-second window rather than the worst ever seen, so that a link which
     * genuinely improves gives the latency back; the memory of rarer stalls lives in autoHeld,
     * which only decays at 3 ms/s. */
    private func autoFloorMs() -> Int {
        /* The SECOND worst second in the window, not the worst.
         *
         * Taking the maximum lets a single outlier hold the delay up for the whole window —
         * MEASURED over a VPN: one 380 ms gap pinned the target near 420 ms for 30 s, and that
         * one number was most of the buffer's contribution to a 980 ms end-to-end delay. A gap
         * that happens once is cheaper to CONCEAL than to carry 300 ms of latency for; a gap that
         * recurs appears in several slots and still sets the floor, which is the point. */
        let sorted = gapSlots.sorted()
        let secondWorst = sorted.count >= 2 ? sorted[sorted.count - 2] : (sorted.last ?? 0)
        return max(Self.marginMs, secondWorst + Self.marginMs)
    }

    private func autoTargetMs() -> Int {
        let now = Date()
        let dt = max(0, now.timeIntervalSince(autoUpdated))
        autoUpdated = now
        let floorMs = autoFloorMs()
        /* The target before any gap has been measured.
         *
         * This was 80 ms, and the startup clamp sets the depth to exactly the target — so playout
         * began with ZERO margin against a link whose gaps were not yet known. MEASURED on the
         * Android client, which shares this policy, over a LAN: a clean start at depth 78 /
         * target 78, then a 61 ms arrival gap, then SEVENTEEN underruns and 335 ms of
         * concealment inside three seconds, with packet loss of exactly zero. The same signature
         * appears in this client's own startup concealment figures.
         *
         * A first guess has to be conservative; the alternative is a burst of glitches before the
         * measurement that would have prevented them exists. The clean give-back takes it back
         * down to the measured gap floor at 4.5 ms/s without a sound. */
        if autoHeld == 0 { autoHeld = Self.autoStartMs }
        let clean = Date().timeIntervalSince(lastUnderrunAt ?? .distantPast) > Self.cleanAfterSeconds
        autoHeld = max(Double(floorMs),
                       autoHeld - (clean ? Self.giveBackCleanMsPerSecond
                                         : Self.giveBackMsPerSecond) * dt)
        return min(Self.maxTargetMs, max(floorMs, Int(autoHeld)))
    }

    private func effectiveTargetMs() -> Int {
        requestedMs == 0 ? autoTargetMs() : requestedMs
    }

    private func frames(for ms: Int) -> Int {
        max(1, min(capacity - 1, rate * ms / 1000))
    }

    /// Takes one packet, identified by its sequence number so that order can be restored.
    func push(seq: UInt16, _ samples: [Int16]) {
        lock.withLock {
            noteArrival()
            let sq = Int(seq)
            if expectedSeq < 0 { expectedSeq = sq }
            let ahead = (sq - expectedSeq) & 0xFFFF

            if ahead >= 0x8000 {
                /* Behind the write head: a straggler. Under the old strict gate this was thrown
                 * away. Now it goes into the slot reserved for it, provided that slot has not
                 * already been played. */
                if let h = holes.removeValue(forKey: seq) {
                    if patch(h, with: samples) { recoveredLate += 1 } else { tooLate += 1 }
                } else {
                    duplicates += 1          // already filled, or long past
                }
                return
            }

            if ahead > Self.resyncAhead {
                // A jump this large is a restart or a long outage, not reordering. Reserving it
                // would mean filling seconds of ring with guesses, so resynchronise instead.
                retireHoles(all: true)
                expectedSeq = sq
            } else if ahead > 0 {
                // Reserve the skipped slots, provisionally filled, so stragglers have a home.
                for k in 0..<ahead {
                    let mseq = UInt16((expectedSeq + k) & 0xFFFF)
                    holes[mseq] = (pos: written, len: samples.count)
                    appendProvisional(len: samples.count)
                }
            }

            append(samples)
            expectedSeq = (sq + 1) & 0xFFFF
            retireHoles(all: false)
            enforceCeiling()
            /* Absorb the connect burst while still filling, which is the only time dropping
             * audio is free: none of it has been played, so there is no seam to hear.
             *
             * The previous attempt clamped for 2 s AFTER playout began, and that was wrong in a
             * way worth recording: discarding the oldest samples mid-playout is a splice with no
             * crossfade, so it glitched audibly at every stream start. Clamping is free before
             * the first sample is played and expensive afterwards.
             *
             * The pre-roll buys time for the burst to land — it keeps arriving for about a second
             * after enough audio exists to start — and costs nothing audible, because nothing is
             * playing during it. It applies only to a fresh stream: after an underrun, waiting
             * would just extend the gap. */
            if filling {
                clampToTarget()
                if firstPushAt == nil { firstPushAt = Date() }
            }
            let prerollDone = everPlayed
                || Date().timeIntervalSince(firstPushAt ?? Date()) * 1000 >= Self.prerollMs
            /* A fresh stream waits for the LIVE target; only an underrun recovery uses the
             * shallow frozen value set when the buffer ran dry.
             *
             * Freezing it for both deadlocked the start of every stream: clampToTarget() runs on
             * each push while filling and holds the depth at the CURRENT target, which autoHeld
             * is walking down at 3 ms/s, so the depth could never reach a threshold captured from
             * the higher initial target. MEASURED: depth 70 ms against a frozen 80 ms threshold,
             * ring_frames 0 — not one sample of real audio ever read — and 100% concealment,
             * which with no history is silence. */
            if !everPlayed { resumeFrames = frames(for: effectiveTargetMs()) }

            /* Last resort against exactly that class of fault. Audio arriving but never playing
             * is the worst failure this buffer has, because it is silent in both senses, so if
             * filling somehow persists while packets keep coming, start anyway. */
            if filling, let since = firstPushAt, Date().timeIntervalSince(since) > 3,
               count >= frames(for: Self.resumeFloorMs) {
                resumeFrames = min(resumeFrames, count)
            }

            if filling && prerollDone && count >= resumeFrames {
                filling = false
                everPlayed = true
                /* Start at the target rather than wherever the fill happened to land.
                 *
                 * MEASURED: 627 ms was buffered within the first second of a stream whose target
                 * was 77 ms — a burst queued up at connect, arriving far faster than real time.
                 * Draining that through the rate matcher takes over two minutes of needless
                 * latency, and it was what pushed the depth into the trimmer's range above.
                 *
                 * Dropping it here is free: nothing has been played yet, so the discarded audio
                 * was never going to be heard. The same applies after an underrun refills. */
                clampToTarget()
            }
            retireHoles(all: false)
        }
    }


    /* A HARD CEILING on depth, which is the piece that was missing.
     *
     * Every other mechanism here assumes playout can be nudged to match arrivals: the rate
     * matcher may bend the clock 0.5% and the trimmer may excise 0.5% of real time. Both are
     * deliberately inaudible, and both are useless if playout genuinely cannot keep up.
     *
     * MEASURED over a WAN on a Bluetooth route: the engine rendered 5.5% fewer frames per second
     * than the rate it declared, so audio arrived 5% faster than it left. Nothing bounded the
     * depth, and it grew from 77 ms to 3620 ms in 83 s — 4.2 s end to end — while the trimmer,
     * rate-limited as designed, would have needed eleven minutes to recover it.
     *
     * A jitter buffer that cannot bound its own depth is not finished. When the arrival and
     * playout rates differ by more than the inaudible mechanisms can absorb, the only choices are
     * to discard audio or to let delay grow without limit, and one splice costs far less than
     * four seconds of latency. So: discard, promptly, and count it, because a ceiling that fires
     * is evidence of a real fault elsewhere and should never be silent. */
    private static let ceilingMs = 500
    private(set) var overflows = 0
    private(set) var overflowMs = 0

    private func enforceCeiling() {
        let want = frames(for: effectiveTargetMs())
        let limit = want + frames(for: Self.ceilingMs)
        guard count > limit else { return }
        let drop = count - want
        head = (head + drop) % capacity
        count = want
        consumed += drop
        overflows += 1
        overflowMs += drop * 1000 / rate
        retireHoles(all: false)
    }

    /// A sequence jump beyond this is treated as a restart rather than as reordering.
    /// 40 packets is 200 ms at the 5 ms frame size, far beyond any plausible displacement.
    private static let resyncAhead = 40

    /// Caller holds the lock.
    private func append(_ samples: [Int16]) {
        for v in samples {
            if count == capacity {          // overfull: drop the oldest, never the newest
                head = (head + 1) % capacity
                count -= 1
                consumed += 1
            }
            ring[(head + count) % capacity] = v
            count += 1
        }
        written += samples.count
    }

    /* Provisional fill for a reserved slot: the preceding waveform continued, in phase.
     *
     * Something has to occupy the slot so later packets land at the right offset, and a straggler
     * usually overwrites it within milliseconds. For the minority that never arrive, this is the
     * audio actually heard, so it has to be concealment rather than a raw block copy.
     *
     * The first version repeated the previous `len` frames verbatim. That leaves a phase
     * discontinuity at BOTH ends of the slot — two clicks per lost packet — and at 0.5 losses a
     * second it was audible as a steady tick. Repeating one PITCH PERIOD cyclically instead keeps
     * the waveform in phase across the join and into the packet that follows, which is what makes
     * the splice inaudible; it is the same principle the delay trimmer already uses to cut a
     * period out without a click. */
    private func appendProvisional(len: Int) {
        guard len > 0 else { return }
        var fill = [Int16](repeating: 0, count: len)
        let history = window + maxLag
        if count >= history {
            // Pitch period of the audio immediately before the slot.
            if corr.count < history { corr = [Float](repeating: 0, count: history) }
            let base = head + count - history
            for f in 0..<history { corr[f] = Float(ring[(base + f) % capacity]) }
            let (lag, score) = bestLagAheadFast(corr, window: window, minLag: minLag, maxLag: maxLag)
            let period = score < 0.3 ? maxLag : lag        // unvoiced: any period will do
            for i in 0..<len {
                fill[i] = ring[(head + count - period + (i % period)) % capacity]
            }
        } else if count >= len {
            for i in 0..<len { fill[i] = ring[(head + count - len + i) % capacity] }
        }
        append(fill)
    }

    /// Writes a straggler into its reserved slot. Returns false if the slot has already played.
    private func patch(_ hole: (pos: Int, len: Int), with samples: [Int16]) -> Bool {
        let from = max(hole.pos, consumed)              // the part still unplayed
        // Past its end, or the whole ring has moved on: too late either way. The second test
        // also keeps the index below safe, since count == written - consumed.
        guard from < hole.pos + hole.len, from < written else { return false }
        var idx = (head + (from - consumed)) % capacity
        let offset = from - hole.pos
        for i in offset..<min(samples.count, hole.len) {
            ring[idx] = samples[i]
            idx = (idx + 1) % capacity
        }
        return true
    }

    /// Drops slots whose audio has been played. Those packets are now genuinely lost — which is
    /// the one place loss is declared, and it is declared at playout, not at arrival.
    private func retireHoles(all: Bool) {
        guard !holes.isEmpty else { return }
        for (k, h) in holes where all || h.pos + h.len <= consumed {
            holes.removeValue(forKey: k)
            realLoss += 1
        }
    }

    /// Caller holds the lock.
    private func noteArrival() {
        let now = Date()
        if now.timeIntervalSince(slotStarted) >= 1 {
            let steps = min(gapSlots.count, Int(now.timeIntervalSince(slotStarted)))
            for _ in 0..<steps {
                slot = (slot + 1) % gapSlots.count
                gapSlots[slot] = 0
            }
            slotStarted = now
        }
        if let last = lastArrival {
            let gap = Int(now.timeIntervalSince(last) * 1000)
            if gap > gapSlots[slot] { gapSlots[slot] = gap }
            if gap > worstEver { worstEver = gap }
        }
        lastArrival = now
    }

    /* The level and tone chain, applied to whatever leaves this buffer.
     *
     * It belongs on this side of the buffer because it is sequential and the ordered stream only
     * exists here; see AudioChain.swift for the fault that proved it. Applied outside the lock,
     * after every path through pullRaw, so concealed and trimmed audio is processed too. */
    var postProcess: ((UnsafeMutablePointer<Float>, Int) -> Void)?

    /// Fills `out` with `n` frames: real audio where there is some, a continuation of the
    /// waveform where there is not.
    func pull(into out: UnsafeMutablePointer<Float>, frames n: Int) {
        pullRaw(into: out, frames: n)
        postProcess?(out, n)
    }

    private func pullRaw(into out: UnsafeMutablePointer<Float>, frames n: Int) {
        lock.withLock {
            pulledFrames += n
            let target = frames(for: effectiveTargetMs())

            /* Deliberately NOT refilling when the target rises.
             *
             * There is no free way to deepen a buffer: you either pause (and conceal) or stretch
             * the audio. The old rule paused, which meant every upward move of the target cost a
             * glitch — measured at 431 ms of concealment in a 20 s run that had no network
             * trouble at all. So the target is now only a FLOOR for trimming: depth is whatever
             * the link delivers, it grows naturally after a genuine underrun refills it, and it
             * only ever shrinks on purpose. */
            lastTarget = target

            // Still filling, or run dry: hand out a continuation rather than a hole.
            if filling || count < n {
                if !filling {
                    underruns += 1
                    autoNoteUnderrun()
                    filling = true
                    // Resume shallow; the rate matcher restores the depth without a sound.
                    resumeFrames = min(frames(for: effectiveTargetMs()),
                                       frames(for: max(Self.resumeFloorMs, autoFloorMs())))
                    conceal.start()                       // the buffer just emptied
                }
                if !concealing {
                    concealing = true
                    if !conceal.audible { conceal.start() }
                }
                // The concealer works in full-scale units, because its predictor is fitted to
                // Int16 history; the output is normalised. Convert at the boundary, once.
                ensureScratch(n)
                scratchA.withUnsafeMutableBufferPointer { conceal.next($0.baseAddress!, count: n) }
                for i in 0..<n { out[i] = scratchA[i] / 32768 }
                concealedFrames += n
                excess = 0
                lowest = Int.max
                return
            }

            // Enough audio again. Crossfade out of the continuation into it, so the seam does not
            // click, then carry on normally.
            if concealing {
                concealing = false
                ensureScratch(n)
                scratchA.withUnsafeMutableBufferPointer { conceal.next($0.baseAddress!, count: n) }
                for i in 0..<n { scratchA[i] /= 32768 }   // same units as `out` before mixing
                take(out, n)
                crossfade(from: scratchA, into: out, frames: min(n, blockFrames))
                conceal.remember(bufferOf(out, n), count: n)
                return
            }

            // Remove accumulated delay a pitch period at a time, on alternate pulls so a long
            // excess is spread out rather than yanked away.
            /* Accrue the allowance smoothly: 3% of the audio just played. With no limit at all
             * the trimmer removed up to 169 ms in one second — a 17% speed-up, audible however
             * clean each splice is. */
            trimBudget = min(trimBudget + Double(n) * trimFraction, Double(maxLag))

            cutTick += 1
            if excess >= minLag && Int(trimBudget) >= minLag
                && count >= window + maxLag + n && cutTick % 2 == 0 {
                let lag = repeatLag(wanted: min(excess, Int(trimBudget)))
                ensureScratch(n)
                peek(&scratchA, n)
                skip(lag)
                take(out, n)
                crossfade(from: scratchA, into: out, frames: min(n, blockFrames))
                excess -= lag
                trimBudget -= Double(lag)
                trimmedFrames += lag
            } else {
                take(out, n)
            }

            // Whatever the buffer never dipped below, beyond the target, is pure delay. Judged on
            // the low-water mark so a burst does not look like something to trim.
            lowest = min(lowest, count)
            sinceCheck += n
            if sinceCheck >= rate / 4 {                   // four times a second
                /* Only remove delay that is worth removing.
                 *
                 * The threshold used to be one block (5 ms), which was fine when the target was a
                 * step function: it trimmed once after a stall and then stopped. With auto now
                 * decaying continuously the target is ALWAYS drifting below the depth, so a 5 ms
                 * threshold had the trimmer cutting forever — MEASURED on the device: 1916 ms
                 * removed in 98 s, about 2% of the audio, in 2.5-14 ms snips. Each cut splices
                 * cleanly, but deleting one frame in fifty is audible as a stutter even so, and
                 * the telemetry showed it was the only thing touching the audio (no underruns, no
                 * concealment, two late packets).
                 *
                 * Carrying some extra latency costs nothing audible; carving the audio to reclaim
                 * it costs plenty. */
                autoObserve(lowestFrames: lowest)
                let slack = lowest - target
                excess = slack > trimDeadband ? slack : 0
                lowest = Int.max
                sinceCheck = 0
            }

            conceal.remember(bufferOf(out, n), count: n)
        }
    }

    /// Drops the oldest audio down to the target. Caller holds the lock.
    private func clampToTarget() {
        let want = frames(for: effectiveTargetMs())
        guard count > want else { return }
        let drop = count - want
        head = (head + drop) % capacity
        count = want
        consumed += drop
    }

    // MARK: - ring helpers (caller holds the lock)

    private func take(_ out: UnsafeMutablePointer<Float>, _ n: Int) {
        ringFrames += n
        consumed += n
        for i in 0..<n {
            out[i] = Float(ring[head]) / 32768.0
            head = (head + 1) % capacity
            count -= 1
        }
    }

    private func peek(_ dst: inout [Float], _ n: Int) {
        for i in 0..<n { dst[i] = Float(ring[(head + i) % capacity]) / 32768.0 }
    }

    private func skip(_ n: Int) {
        let d = min(n, count)
        head = (head + d) % capacity
        count -= d
        consumed += d
    }

    private func ensureScratch(_ n: Int) {
        if scratchA.count < n { scratchA = [Float](repeating: 0, count: n) }
        if scratchB.count < n { scratchB = [Float](repeating: 0, count: n) }
    }

    private func bufferOf(_ p: UnsafeMutablePointer<Float>, _ n: Int) -> [Float] {
        ensureScratch(n)
        for i in 0..<n { scratchB[i] = p[i] * 32768 }
        return scratchB
    }

    private func crossfade(from a: [Float], into out: UnsafeMutablePointer<Float>, frames f: Int) {
        guard f > 0 else { return }
        for i in 0..<f {
            let w = Float(i + 1) / Float(f + 1)
            out[i] = a[i] * (1 - w) + out[i] * w
        }
    }

    /// The lag at which the waveform best repeats, so a period can be removed unnoticed. Falls
    /// back to the shortest useful cut when nothing correlates (noise, silence).
    private func repeatLag(wanted: Int) -> Int {
        let need = window + maxLag
        if corr.count < need { corr = [Float](repeating: 0, count: need) }
        for f in 0..<need { corr[f] = Float(ring[(head + f) % capacity]) }
        let (lag, score) = bestLagAheadFast(corr, window: window, minLag: minLag, maxLag: maxLag)
        return score < 0.3 ? min(wanted, maxLag) : lag
    }
}

extension NSLock {
    @discardableResult
    func withLock<T>(_ body: () -> T) -> T {
        lock(); defer { unlock() }
        return body()
    }
}
