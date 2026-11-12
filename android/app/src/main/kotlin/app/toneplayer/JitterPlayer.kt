package app.toneplayer

import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * Plays 16-bit PCM that arrives in irregular bursts over Wi-Fi, holding the delay near a target.
 *
 * The receiving thread calls [push]; a playout thread feeds [track] in 5 ms blocks, so the track's
 * own buffer can stay at its minimum. When the buffer runs dry the playout synthesises a
 * continuation ([Concealer]) instead of a click or a hole. When audio piles up (a burst after a
 * Wi-Fi stall, or clock drift), the excess is removed one pitch period at a time, at points where
 * the waveform repeats, so catching up is close to inaudible. Every seam (into or out of
 * concealment, across a removed period, from silence) is crossfaded over one block.
 *
 * [targetMs] is read every block, so changing the setting applies while playing. 0 means automatic:
 * the target follows the longest gaps between arrivals over the last 10 s.
 */
class JitterPlayer(
    private val track: AudioTrack,
    private val rate: Int,
    private val channels: Int,
    private val targetMs: () -> Int,
    private val onFirstAudio: () -> Unit,
    /** The device's own output block (PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 0 if unknown. */
    private val outputBlockFrames: Int = 0,
) {
    class Stats(
        val bufferedMs: Int, val targetMs: Int, val outputMs: Int,
        val concealedMs: Long, val trimmedMs: Long, val dropouts: Int,
        /** True frames-in-flight below AudioTrack, from getTimestamp(): mixer + HAL included.
         *  -1 until the first timestamp is available. */
        val realOutMs: Int,
        /** Times Android's output ran dry (the playout thread was late): heard as a cutout. */
        val outputUnderruns: Int,
    )

    private val block = rate / 200                          // frames per 5 ms block
    private val minLag = rate / 400                         // pitch search range: 2.5 ms (400 Hz) ...
    private val maxLag = rate / 70                          // ... to 14 ms (70 Hz)
    private val window = rate / 100                         // 10 ms compared at each lag
    private val trimDeadband = rate                         // 1 s: see the cut site in playout()
    /* Sized from the largest selectable target, not from a flat 2 s. The target has to be
     * reachable with room to spare: bursts push the depth well above it, and the ring drops the
     * OLDEST audio when it fills, so a ring merely equal to the target would quietly throw away
     * what it had just buffered. Twice the maximum plus a second. At 48 kHz mono that is ~480 KB
     * for a 2 s ceiling, which is nothing on a phone and is allocated once per stream. */
    private val ring = ShortArray(rate * channels * (MAX_BUFFER_MS * 2 + 1000) / 1000)
    private var head = 0                                    // read position, in samples
    private var size = 0                                    // samples buffered
    private val lock = Object()

    private var largestPush = 0                             // frames; the target can't be below one packet
    private val pushConcealer = Concealer(rate, channels)   // fills sequence gaps as they are received
    private val playConcealer = Concealer(rate, channels)   // fills underruns at playout time
    private var outputUnderruns = 0

    @Volatile private var running = true
    private var concealedFrames = 0L
    private var trimmedFrames = 0L
    private var dropouts = 0

    /**
     * Debugging aid: while a file named capture.raw.request exists in the directory given, the first
     * 20 s played are also written to capture.raw (16-bit little-endian, as played), to check
     * concealment offline. Fetch with `adb exec-out run-as app.toneplayer cat files/capture.raw`.
     */
    var captureDir: java.io.File? = null
        set(dir) {
            field = dir
            if (dir != null && java.io.File(dir, "capture.raw.request").exists()) {
                capture = java.io.FileOutputStream(java.io.File(dir, "capture.raw"))
                Log.i("TonePlayer", "capturing output to ${dir}/capture.raw")
            }
        }
    @Volatile private var capture: java.io.OutputStream? = null
    private var captured = 0

    private val thread = Thread(::playout, "jitter-playout").apply { start() }

    /** Stops playout and releases [track]. */
    fun release() {
        running = false
        thread.join(500)
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private var firstPushNanos = 0L
    private var lastUnderrunNanos = 0L
    /** Times the depth ceiling had to discard audio; a ceiling that fires means a real fault. */
    var overflows = 0; private set
    private var everPlayed = false

    /** Drops the oldest audio down to [target] frames. Caller holds the lock. */
    private fun clampToTarget(target: Int) {
        if (target <= 0) return
        val excessFrames = size / channels - target
        if (excessFrames > 0) skip(excessFrames * channels)
    }

    private var meanFrames = 0                              // average buffer depth over the last 0.5 s
    private var targetFrames = 0                            // as last used by the playout
    private var lastPushNanos = 0L

    /* Automatic target: the longest gap between arrivals in each of the last 30 seconds.
     *
     * This window was 10 s. MEASURED on the iOS client, against the same microphone and access
     * points: gaps around 95 ms were arriving roughly a minute apart — reliably OUTSIDE a 10 s
     * memory — so the target kept forgetting them just in time to be caught out by the next one,
     * and the buffer ran dry. 30 s costs a little latency after one bad moment, which is the
     * cheaper mistake of the two. */
    private val gapSlots = IntArray(30)
    private var slotSecond = -1L

    private fun noteArrival(now: Long) {
        if (lastPushNanos != 0L) {
            val gapMs = ((now - lastPushNanos) / 1_000_000).toInt()
            val sec = now / 1_000_000_000
            if (sec != slotSecond) {
                if (slotSecond < 0 || sec - slotSecond >= gapSlots.size) gapSlots.fill(0)
                else for (s in slotSecond + 1..sec) gapSlots[(s % gapSlots.size).toInt()] = 0
                slotSecond = sec
            }
            val slot = (sec % gapSlots.size).toInt()
            gapSlots[slot] = maxOf(gapSlots[slot], gapMs)
        }
        lastPushNanos = now
    }

    /* Automatic target, ported from the iOS client.
     *
     * The rule used to be a bare `worstGap + margin` read straight off the window, which steps up
     * and down as gaps enter and leave it. MEASURED on this phone: the target walked 67 -> 70 ->
     * 71 -> 76 -> 84 ms over about three minutes as worse gaps arrived, with the depth following
     * within ~10 ms, and that climb is what reads as the delay growing.
     *
     * So the target is now a HELD value instead of a direct readout:
     *
     *   a gap worse than it holds   -> rise to cover it at once
     *   the depth dipped near dry   -> deepen by the shortfall at once
     *   a dropout                   -> deepen hard; a noise was already made
     *   otherwise                   -> give it back at GIVE_BACK_MS_PER_S, slowly
     *
     * Rises are immediate and falls are gradual on purpose. A link that stalled once will stall
     * again, often further apart than any short window remembers; the cost of holding depth is
     * latency, and the cost of dropping it too early is an audible glitch. Giving it back at
     * 3 ms/s also keeps it inside what the rate matcher can follow — 0.5% of real time, i.e.
     * 5 ms/s — which the old step changes were not, and a setpoint that moves faster than the
     * loop can track is not a setpoint but a standing error.
     */
    private var autoHeld = 0.0
    private var autoUpdatedNanos = 0L

    /** The shortest target that still covers the worst gap the link has recently shown. */
    private fun autoFloorMs(): Int {
        val worst = gapSlots.max()
        // Capped at AUTO_MAX_MS: a long stall can put a gap well past the ceiling, and an
        // unclamped floor would then exceed it and make the coerceIn below throw.
        return if (worst == 0) AUTO_MIN_MS
        else (worst + AUTO_MARGIN_MS).coerceIn(AUTO_MIN_MS, AUTO_MAX_MS)
    }

    private fun autoTargetMs(): Int {
        val now = System.nanoTime()
        val dt = if (autoUpdatedNanos == 0L) 0.0 else (now - autoUpdatedNanos) / 1e9
        autoUpdatedNanos = now
        val floorMs = autoFloorMs()
        if (autoHeld == 0.0) autoHeld = AUTO_START_MS.toDouble()
        /* Faster give-back once the link has behaved. One underrun's jump at 3 ms/s is fifty
         * seconds of carried latency for a momentary fault; 4.5 ms/s stays inside the rate
         * matcher's 0.5% (5 ms/s) budget, so the depth still follows without any splicing. */
        val clean = lastUnderrunNanos != 0L &&
            System.nanoTime() - lastUnderrunNanos > CLEAN_AFTER_S * 1_000_000_000L
        autoHeld = maxOf(floorMs.toDouble(),
                         autoHeld - (if (clean) GIVE_BACK_CLEAN_MS_PER_S else GIVE_BACK_MS_PER_S) * dt)
        return autoHeld.toInt().coerceIn(floorMs, AUTO_MAX_MS)
    }

    /* Called on the periodic check with the lowest occupancy seen since the previous one.
     *
     * Only learns once the depth has actually REACHED the target. A buffer still climbing toward
     * its target is not evidence that the target is too low — it is evidence that the rate matcher
     * has not finished filling it, and the matcher is limited to 0.5% of real time (5 ms/s) by
     * design. Without this gate the two form a positive feedback loop: the depth is shallow, so
     * the low-water mark dips below the margin, so the target rises, so the depth is further from
     * target, so it keeps dipping. MEASURED on this phone over a LAN with a healthy 71 ms worst
     * gap: the target walked 174 -> 238 ms while the depth sat at 50 ms and the error never
     * closed, the loop winding to -0.32% trying to catch a target moving away from it. */
    private fun autoObserve(lowestFrames: Int, targetFrames: Int) {
        if (targetMs() > 0 || lowestFrames == Int.MAX_VALUE) return      // manual, or nothing seen
        if (!everPlayed || size / channels < targetFrames) return        // still catching up
        if (System.nanoTime() - firstPushNanos < 2_000_000_000L) return  // startup transient
        val lowMs = lowestFrames * 1000 / rate
        if (lowMs < AUTO_MARGIN_MS) {
            autoHeld += minOf((AUTO_MARGIN_MS - lowMs).toDouble(), MAX_RISE_PER_OBSERVATION)
        }
    }

    /** At four observations a second this caps the climb at 40 ms/s, against 3-4.5 ms/s given back. */
    private val MAX_RISE_PER_OBSERVATION = 10.0

    /* A dry buffer is evidence the depth was too shallow, so respond to the MEASURED cause.
     *
     * A flat jump costs one audible glitch per step on a link whose stalls grow. MEASURED on iOS
     * over a VPN: arrival gaps climbed 55 -> 84 -> 140 -> 182 -> 767 -> 1072 ms while the target
     * crept up in 150 ms increments — eight underruns across twenty seconds before it settled and
     * went quiet. The gap that caused each one was already in the window, so using it converges
     * in a single step.
     *
     * The WORST gap is used here, not the second worst [autoFloorMs] uses. The floor is
     * conservative because it would otherwise inflate latency on an outlier with no evidence of
     * harm; here the buffer ran dry, so the outlier is exactly what has to be covered. */
    private fun autoNoteDropout() {
        lastUnderrunNanos = System.nanoTime()
        if (targetMs() > 0) return
        val worstGap = gapSlots.max()
        autoHeld = maxOf(autoHeld + DROPOUT_JUMP_MS / 3,
                         minOf(AUTO_MAX_MS, worstGap + AUTO_MARGIN_MS).toDouble())
    }

    /* A hard ceiling on depth: the piece that was missing.
     *
     * Every other mechanism assumes playout can be nudged to match arrivals — the rate matcher
     * bends the clock 0.5%, the trimmer excises 0.5% — and both are useless when playout cannot
     * keep up at all. MEASURED on iOS: a 5.5% shortfall grew the buffer from 77 ms to 3620 ms in
     * 83 s, while the trimmer, rate-limited by design, would have needed eleven minutes. When the
     * rates differ by more than the inaudible mechanisms can absorb, the only choices are to
     * discard or to let delay grow without bound, and one splice costs far less than four seconds
     * of latency. */
    private fun enforceCeiling(target: Int) {
        if (target <= 0) return
        val limit = target + rate * CEILING_MS / 1000
        val avail = size / channels
        if (avail <= limit) return
        skip((avail - target) * channels)
        overflows++
    }

    /** Depth minus target in ms; 0 unless playing steadily, so the rate loop does not wind up
     *  while the buffer is refilling or concealing. Drives [Resampler.correct]. */
    fun depthErrorMs(): Int = synchronized(lock) {
        if (modeNow != PLAYING || targetFrames <= 0) 0
        else (size / channels - targetFrames) * 1000 / rate
    }

    @Volatile private var modeNow = BUFFERING

    /** The rate loop's learned drift, as a percentage, published by [Player] so the periodic log
     *  can show whether the loop is actually tracking or sitting idle. */
    @Volatile var driftPercent = 0.0

    /** Resequencer counters, published by [Player] so the periodic log can show whether the
     *  "loss" the old gate reported was ever loss at all. */
    @Volatile var reqLost = 0
    @Volatile var reqReordered = 0
    @Volatile var reqTooLate = 0

    val stats: Stats
        get() = synchronized(lock) {
            Stats(
                bufferedMs = meanFrames * 1000 / rate,
                targetMs = targetFrames * 1000 / rate,
                outputMs = track.bufferSizeInFrames * 1000 / rate,
                realOutMs = realOutFrames.let { if (it < 0) -1 else it * 1000 / rate },
                concealedMs = concealedFrames * 1000 / rate,
                trimmedMs = trimmedFrames * 1000 / rate,
                dropouts = dropouts,
                outputUnderruns = outputUnderruns,
            )
        }

    /* What AudioTrack's own bufferSizeInFrames reports is only the app-side buffer; the mixer and
     * the HAL add their own, and on a track whose rate is not the device's native rate Android has
     * to resample, which takes it off the fast-mixer path entirely. getTimestamp() is the only way
     * to see the true figure: it says which frame is leaving the speaker and when. */
    @Volatile private var realOutFrames = -1
    private var framesWritten = 0L

    // A sequence gap waiting for enough audio after it to be filled from both sides.
    private var gapFrames = 0
    private val after = ShortArray(rate / 2 * channels)     // audio received after the gap
    private var afterSize = 0                               // samples
    private val afterNeeded = rate / 80 * channels          // 12.5 ms: enough to model it

    /**
     * Queues [n] interleaved samples. [missingFrames] > 0 says that much audio was lost just before
     * them (a sequence gap). The gap is filled from both sides once ~25 ms after it has arrived, as
     * long as the buffer has that much in hand; otherwise from the audio before it alone.
     */
    fun push(pcm: ShortArray, n: Int, missingFrames: Int = 0) {
        if (n <= 0) return
        synchronized(lock) {
            noteArrival(System.nanoTime())
            largestPush = maxOf(largestPush, n / channels)
            if (missingFrames > 0) {
                if (gapFrames > 0) resolveGap()   // a second gap: settle the first with what there is
                gapFrames = minOf(missingFrames, rate / 10)   // at most 100 ms is filled
                dropouts++
                autoNoteDropout()
            }
            if (gapFrames > 0) {
                if (afterSize + n > after.size) resolveGap()
                if (gapFrames > 0) {
                    System.arraycopy(pcm, 0, after, afterSize, n)
                    afterSize += n
                    if (afterSize >= afterNeeded) resolveGap()
                    return
                }
            }
            pushConcealer.remember(pcm, 0, n)
            append(pcm, n)
            /* Absorb the connect burst while still BUFFERING — the only time dropping audio is
             * free, because none of it has been played and there is no seam to hear.
             *
             * The previous attempt clamped for 2 s after playout had begun, which splices the
             * ring with no crossfade and glitched audibly at every stream start. Clamping is free
             * before the first sample is played and expensive afterwards. */
            if (modeNow == BUFFERING) {
                if (firstPushNanos == 0L) firstPushNanos = System.nanoTime()
                clampToTarget(targetFrames)
            }
        }
    }

    /**
     * Writes the fill for the pending gap, then the audio held after it. Two-sided (the idea of
     * their Windows player's BWSOLA, done with linear prediction): the audio before the gap is
     * extrapolated forward, the audio after it backward (by predicting its time reversal), and the
     * two are crossfaded across the gap, so the fill leaves the old audio and lands on the new one
     * sample-continuously. With too little audio after the gap, it falls back to the one-sided
     * continuation, crossfaded into the new audio.
     */
    private fun resolveGap() {
        val g = gapFrames
        gapFrames = 0
        val fill = ShortArray(g * channels)
        val twoSided = afterSize >= afterNeeded
        if (twoSided) {
            val fwd = ShortArray(g * channels)
            pushConcealer.start(fade = false)
            pushConcealer.next(fwd, 0, g)
            val m = afterSize / channels
            val reversed = ShortArray(afterSize)
            for (f in 0 until m) for (c in 0 until channels) reversed[f * channels + c] = after[(m - 1 - f) * channels + c]
            val back = Concealer(rate, channels)
            back.remember(reversed, 0, reversed.size)
            back.start(fade = false)
            val bwd = ShortArray(g * channels)   // bwd[j] is j + 1 frames before the new audio
            back.next(bwd, 0, g)
            for (f in 0 until g) {
                val w = 0.5 - 0.5 * Math.cos(Math.PI * (f + 1) / (g + 1))
                for (c in 0 until channels) {
                    val a = fwd[f * channels + c]
                    val b = bwd[(g - 1 - f) * channels + c]
                    fill[f * channels + c] = when {
                        !back.audible -> a
                        !pushConcealer.audible -> b
                        else -> (a * (1 - w) + b * w).toInt().toShort()
                    }
                }
            }
        } else {
            pushConcealer.start()
            pushConcealer.next(fill, 0, g)
            val xf = minOf(block, afterSize / channels)
            val synth = ShortArray(xf * channels)
            pushConcealer.next(synth, 0, xf)
            crossfade(synth, after, after, xf)
        }
        concealedFrames += g
        Log.d("TonePlayer", "lost: ${g * 1000 / rate} ms, filled ${if (twoSided) "two-sided" else "one-sided"}")
        pushConcealer.remember(fill, 0, fill.size)
        append(fill, fill.size)
        if (afterSize > 0) {
            pushConcealer.remember(after, 0, afterSize)
            append(after, afterSize)
            afterSize = 0
        }
    }


    // ---- ring buffer (callers hold the lock) ----

    private fun append(src: ShortArray, n: Int) {
        var overflow = size + n - ring.size
        if (overflow > 0) {   // keep the newest audio
            overflow += overflow % channels
            head = (head + overflow) % ring.size
            size -= overflow
        }
        var tail = (head + size) % ring.size
        var i = 0
        while (i < n) {
            val run = minOf(n - i, ring.size - tail)
            System.arraycopy(src, i, ring, tail, run)
            i += run
            tail = (tail + run) % ring.size
        }
        size += n
    }

    private fun take(dst: ShortArray, n: Int) {
        var i = 0
        while (i < n) {
            val run = minOf(n - i, ring.size - head)
            System.arraycopy(ring, head, dst, i, run)
            i += run
            head = (head + run) % ring.size
        }
        size -= n
    }

    private fun skip(n: Int) {
        head = (head + n) % ring.size
        size -= n
    }

    /** Copies [n] samples starting [offset] samples into the buffer, without consuming them. */
    private fun peek(dst: ShortArray, offset: Int, n: Int) {
        for (i in 0 until n) dst[i] = ring[(head + offset + i) % ring.size]
    }

    /**
     * How far ahead the buffered audio repeats itself: the lag at which the next 10 ms best match
     * what follows. Skipping exactly that much joins two near-identical waveforms. For noise-like
     * audio nothing repeats, and the joint is inaudible anyway: skip as much as allowed.
     */
    private fun repeatLag(wanted: Int): Int {
        val frames = window + maxLag
        val x = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += ring[(head + f * channels + c) % ring.size]
            x[f] = s
        }
        val (lag, score) = bestLag(x, 0, window, minLag, maxLag, ahead = true)
        return if (score < 0.3f) minOf(wanted, maxLag) else lag
    }

    /** out = a fading out into b, over [frames] frames. [out] may be [a] or [b]. */
    private fun crossfade(a: ShortArray, b: ShortArray, out: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            val w = (f + 1).toFloat() / (frames + 1)
            for (c in 0 until channels) {
                val k = f * channels + c
                out[k] = (a[k] * (1 - w) + b[k] * w).toInt().toShort()
            }
        }
    }

    // ---- playout ----

    private fun playout() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val len = block * channels
        val out = ShortArray(len)
        val other = ShortArray(len)
        var mode = BUFFERING
        var announced = false
        var lowest = Int.MAX_VALUE                          // fewest frames buffered since the last check
        var blocksSinceCheck = 0
        var excess = 0                                      // frames of pure delay still to remove
        var cutTick = 0
        var depthSum = 0L
        var depthBlocks = 0
        var tsTick = 0
        var lastTarget = 0
        var settleBlocks = 0
        var settled = false
        /* The track's own buffer. On the low-latency fast mixer this wants to be a small multiple
         * of the device's own output block, not a round number of milliseconds: four blocks is
         * 16 ms on a 192-frame phone, against the 40 ms this used to ask for. It is still grown a
         * step at a time whenever the output actually underruns, so the floor finds itself. The old
         * fixed 40 ms was measured on a track that was being resampled by AudioFlinger - the deep
         * buffer path - where small buffers underran within a minute. */
        val outputStep = block * 2
        track.bufferSizeInFrames =
            if (outputBlockFrames > 0) maxOf(outputBlockFrames * 4, block * 2)
            else outputStep * 4
        var underruns = track.underrunCount
        track.play()
        try {
            while (running) {
                synchronized(lock) {
                    // A gap waiting for audio after it must be filled before the buffer runs out.
                    if (gapFrames > 0 && size / channels < 2 * block) resolveGap()
                    // Never aim below one packet plus a block, or every packet would underrun.
                    val ms = targetMs().let { if (it <= 0) autoTargetMs() else it }
                    val target = maxOf(rate * ms / 1000, largestPush + block)
                    targetFrames = target
                    enforceCeiling(target)
                    val avail = size / channels
                    when (mode) {
                        BUFFERING -> {
                            val prerollDone = everPlayed || firstPushNanos != 0L &&
                                System.nanoTime() - firstPushNanos >= PREROLL_MS * 1_000_000L
                            if (avail >= target && prerollDone) {
                                mode = RESUMING
                                everPlayed = true
                            }
                        }
                        CONCEALING ->
                            if (!playConcealer.audible) mode = BUFFERING        // faded out: refill properly
                            else if (avail >= largestPush + block) mode = RESUMING
                    }
                    if ((mode == PLAYING || mode == RESUMING) && avail < block) {
                        if (mode == PLAYING) {
                            mode = CONCEALING
                            playConcealer.start()
                            dropouts++
                            Log.d("TonePlayer", "underrun: ${(System.nanoTime() - lastPushNanos) / 1_000_000} ms since the last packet")
                        } else {
                            mode = BUFFERING
                        }
                    }
                    when (mode) {
                        BUFFERING, CONCEALING -> {
                            playConcealer.next(out, 0, block)
                            if (mode == CONCEALING) concealedFrames += block
                        }
                        RESUMING -> {
                            // Whatever the concealer was producing (silence, if it had faded) into real audio.
                            playConcealer.next(other, 0, block)
                            take(out, len)
                            crossfade(other, out, out, block)
                            mode = PLAYING
                            /* Start at the target rather than wherever the fill landed. A connect
                             * burst arrives faster than real time, so it is stale on arrival and
                             * keeping it only buys latency — MEASURED on iOS: 373 ms against an
                             * 81 ms target, which then took the whole run to drain. Dropping it
                             * costs nothing audible here, because none of it has been played. */
                            clampToTarget(target)
                            lowest = Int.MAX_VALUE
                            blocksSinceCheck = 0
                            excess = 0
                            if (!announced) { announced = true; onFirstAudio() }
                            settleBlocks = 0
                            settled = false
                        }
                        PLAYING -> {
                            // Remove excess delay on every other block, one repeat of the waveform at a
                            // time: out fades from the natural continuation into the audio a period later.
                            if (excess >= minLag && avail >= window + maxLag + block && (++cutTick and 1) == 0) {
                                val lag = repeatLag(excess)
                                peek(other, 0, len)
                                skip(lag * channels)
                                take(out, len)
                                crossfade(other, out, out, block)
                                excess -= lag
                                trimmedFrames += lag
                            } else {
                                take(out, len)
                            }
                            if (!settled && ++settleBlocks >= 200) settled = true   // 1 s of playout
                            lowest = minOf(lowest, size / channels)
                            // Every second: whatever the buffer never dipped below, beyond the target,
                            // is pure delay (left by a burst, a concealed gap, or clock drift).
                            /* Every 250 ms, not every second: the check is what drives trimming,
                             * and the arrival bursts (measured: 4.8% of packets back-to-back,
                             * p99 gap 26 ms) push depth to 110 ms between checks, so a slow check
                             * left the MEAN depth far above the target even though the low-water
                             * mark was fine. Still judged on the low-water mark, so this does not
                             * make dropouts more likely - it only removes delay sooner. */
                            if (++blocksSinceCheck >= 50) {
                                autoObserve(lowest, target)
                                val slack = lowest - target
                                /* Only overshoots the rate matcher cannot be left to clear.
                                 *
                                 * This threshold was one block — 5 ms — which has the trimmer
                                 * splicing continuously, because arrival bursts keep the depth
                                 * above the target all the time. MEASURED on iOS with the same
                                 * 5 ms rule: 1916 ms of audio removed in 98 s, and at a 300 ms
                                 * rule still 301 ms in 59 s, which was audible as small glitches
                                 * in a run that had no underruns and no concealment at all.
                                 *
                                 * Resampler.correct now absorbs drift inaudibly, so a cut is only
                                 * worth making for an overshoot it would need minutes to clear. */
                                excess = if (slack > trimDeadband) slack else 0
                                /* Deliberately NOT refilling when the target rises.
                                 *
                                 * There is no free way to deepen a buffer: you either pause —
                                 * which conceals, i.e. glitches — or you stretch the audio. This
                                 * used to pause, so every upward step of the target cost a
                                 * glitch. MEASURED on iOS where the same rule was in force: 431
                                 * ms of concealment in a 20 s run with no network trouble at all;
                                 * and MEASURED here on this phone, 3 refills in a single short
                                 * session, each one a small self-inflicted dropout.
                                 *
                                 * Removing it is only safe because Resampler.correct now exists:
                                 * when the depth is below target the ratio goes slightly slow and
                                 * the buffer fills itself at up to 0.5% of real time, inaudibly.
                                 * The target is therefore only a FLOOR for trimming — depth is
                                 * whatever the link delivers, it grows on its own after a genuine
                                 * underrun, and it only ever shrinks on purpose. */
                                lastTarget = target
                                lowest = Int.MAX_VALUE
                                blocksSinceCheck = 0
                            }
                        }
                    }
                    modeNow = mode
                    playConcealer.remember(out, 0, len)

                    depthSum += size / channels
                    if (++depthBlocks >= 100) {
                        meanFrames = (depthSum / depthBlocks).toInt()
                        depthSum = 0
                        depthBlocks = 0
                        val u = track.underrunCount
                        if (u != underruns) {
                            outputUnderruns += u - underruns
                            underruns = u
                            /* Growing on the first underruns is wrong: the track always underruns
                             * once or twice while the pipeline fills, and that transient was
                             * permanently inflating the buffer - measured, it went straight from
                             * 16 ms to 36 ms within a second or two of starting and stayed there.
                             * Ignore the settling period and only grow for underruns that happen
                             * once playout is genuinely steady. */
                            if (settled) {
                                val grown = track.setBufferSizeInFrames(track.bufferSizeInFrames + outputStep)
                                Log.i("TonePlayer", "output underrun: track buffer now ${grown * 1000 / rate} ms")
                            }
                        }
                    }
                }
                capture?.let { c ->
                    val bytes = ByteArray(len * 2)
                    for (i in 0 until len) { bytes[2 * i] = out[i].toByte(); bytes[2 * i + 1] = (out[i].toInt() shr 8).toByte() }
                    c.write(bytes)
                    captured += block
                    if (captured >= rate * CAPTURE_SECONDS) { c.close(); capture = null; Log.i("TonePlayer", "capture done") }
                }
                track.write(out, 0, len, AudioTrack.WRITE_BLOCKING)
                framesWritten += block
                if (++tsTick >= 100) {                       // twice a second
                    tsTick = 0
                    val ts = android.media.AudioTimestamp()
                    if (track.getTimestamp(ts)) {
                        val played = ts.framePosition +
                            (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L
                        realOutFrames = (framesWritten - played).coerceAtLeast(0L).toInt()
                        Log.i("TonePlayer", "latency: buffer ${size / channels * 1000 / rate} ms" +
                            " + output ${realOutFrames * 1000 / rate} ms measured" +
                            " (track buffer ${track.bufferSizeInFrames * 1000 / rate} ms," +
                            " target ${targetFrames * 1000 / rate} ms)" +
                            " drift ${"%+.4f".format(driftPercent)}%" +
                            " err ${size / channels * 1000 / rate - targetFrames * 1000 / rate} ms" +
                            " gapW ${gapSlots.max()} ms" +
                            " trimmed ${trimmedFrames * 1000 / rate} ms" +
                            " concealed ${concealedFrames * 1000 / rate} ms" +
                            " dropouts $dropouts" +
                            " loss $reqLost reord $reqReordered tooLate $reqTooLate" +
                            " overflows $overflows")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TonePlayer", "playout ended", e)
        }
    }

    companion object {
        private const val BUFFERING = 0    // silence until the buffer reaches the target
        private const val RESUMING = 1     // next block crossfades into real audio
        private const val PLAYING = 2
        private const val CONCEALING = 3   // the buffer ran dry: synthesised continuation

        private const val CAPTURE_SECONDS = 20

        /** The largest buffer the UI may offer; the ring is sized from it. */
        const val MAX_BUFFER_MS = 2000

    /* The target before any gap has been measured.
     *
     * This was 80 ms, and the startup clamp sets the depth to exactly the target — so playout
     * began with ZERO margin against a link whose gaps were not yet known. MEASURED on a LAN:
     * a clean start at depth 78 / target 78, then a 61 ms arrival gap, then SEVENTEEN underruns
     * and 335 ms of concealment inside the first three seconds, with packet loss of exactly zero.
     *
     * A first guess has to be conservative, because the alternative is a burst of glitches before
     * the measurement that would have prevented them exists. 150 ms costs a little latency for
     * about fifteen seconds; the clean give-back then takes it down to the measured gap floor at
     * 4.5 ms/s without a sound. */
        private const val AUTO_START_MS = 150
        private const val GIVE_BACK_MS_PER_S = 3.0   // inside the rate matcher's 5 ms/s budget
        private const val DROPOUT_JUMP_MS = 150.0
        private const val GIVE_BACK_CLEAN_MS_PER_S = 4.5
        private const val CLEAN_AFTER_S = 15L
        private const val CEILING_MS = 500
        private const val PREROLL_MS = 400L   // silent settling time before playout starts
        private const val AUTO_MARGIN_MS = 40   // slack left after the worst gap drains the buffer
        private const val AUTO_MIN_MS = 40

        /* Auto has to be able to ride out a WAN link, not just a LAN one: a VPN or mobile path
         * stalls for hundreds of ms where Wi-Fi stalls for tens. Capped well below MAX_BUFFER_MS
         * so automatic never costs the full ceiling, and so the ring keeps its headroom. */
        private const val AUTO_MAX_MS = 1500
    }
}

/**
 * The lag in [minLag]..[maxLag] at which [window] samples of [x] best match those at [ref]
 * (normalised cross-correlation), looking back from [ref], or forward when [ahead]. Returns the
 * lag and its score (1 = identical shape).
 */
internal fun bestLag(x: FloatArray, ref: Int, window: Int, minLag: Int, maxLag: Int, ahead: Boolean): Pair<Int, Float> {
    var e0 = 0f
    for (i in 0 until window) e0 += x[ref + i] * x[ref + i]
    if (e0 < 1f) return Pair(maxLag, 0f)   // digital silence
    var best = maxLag
    var bestScore = -1f
    for (lag in minLag..maxLag) {
        val other = if (ahead) ref + lag else ref - lag
        var xy = 0f
        var e = 0f
        for (i in 0 until window) {
            val p = x[other + i]
            xy += x[ref + i] * p
            e += p * p
        }
        val score = if (e > 0f) xy / sqrt(e0 * e) else 0f
        if (score > bestScore) { bestScore = score; best = lag }
    }
    return Pair(best, bestScore)
}

/**
 * Packet-loss concealment by linear-prediction (autoregressive) extrapolation, as used in audio
 * restoration: an order-40 predictor is fitted to the last 40 ms of each channel and run forward
 * from where the audio stopped, so the continuation starts sample-continuous and keeps the
 * spectrum (chords and noise included, which a pitch-period repeat cannot do). One-sided use
 * holds full level for 10 ms and fades to silence by 60 ms; [start] with fade off holds full
 * level, for a crossfade done by the caller.
 */
class Concealer(rate: Int, private val channels: Int) {
    private val frames = rate / 25                     // 40 ms of history analysed
    private val history = ShortArray(frames * channels)
    private var filled = 0                             // frames of real history

    private val hold = rate / 100                      // full level for 10 ms
    private val fadeEnd = rate * 60 / 1000             // silent from 60 ms
    private val coef = Array(channels) { DoubleArray(ORDER) }
    private val state = Array(channels) { DoubleArray(ORDER) }   // last ORDER samples, a ring
    private var statePos = 0                           // oldest entry of each ring
    private var active = false
    private var produced = Int.MAX_VALUE               // frames since start(); MAX = not concealing

    private var fade = true

    /** True while [next] still produces sound. */
    val audible get() = active && (!fade || produced < fadeEnd)

    /** Records [n] interleaved samples just played or queued, as the basis for the next gap. */
    fun remember(src: ShortArray, off: Int, n: Int) {
        if (n >= history.size) {
            System.arraycopy(src, off + n - history.size, history, 0, history.size)
        } else {
            System.arraycopy(history, n, history, 0, history.size - n)
            System.arraycopy(src, off, history, history.size - n, n)
        }
        filled = minOf(frames, filled + n / channels)
    }

    /** Begins a continuation; with [fade] false it holds full level (for a crossfade the caller does). */
    fun start(fade: Boolean = true) {
        this.fade = fade
        produced = 0
        active = false
        if (filled < ORDER * 4) return   // not enough history yet: silence
        val first = frames - filled
        for (c in 0 until channels) {
            val x = DoubleArray(filled) { history[(first + it) * channels + c].toDouble() }
            val a = lpc(x)
            if (a == null) coef[c].fill(0.0) else { a.copyInto(coef[c]); active = true }
            for (k in 0 until ORDER) state[c][k] = x[filled - ORDER + k]   // oldest first
        }
        statePos = 0
    }

    /** Writes [n] frames of continuation (silence once faded, or when not concealing). */
    fun next(out: ShortArray, off: Int, n: Int) {
        for (f in 0 until n) {
            val g = when {
                !active -> 0f
                !fade || produced < hold -> 1f
                produced >= fadeEnd -> 0f
                else -> (fadeEnd - produced).toFloat() / (fadeEnd - hold)
            }
            for (c in 0 until channels) {
                var y = 0.0
                if (active) {
                    val a = coef[c]
                    val s = state[c]
                    for (k in 1..ORDER) y += a[k - 1] * s[(statePos + ORDER - k) % ORDER]
                    y = y.coerceIn(-32767.0, 32767.0)
                    s[statePos] = y   // replaces the oldest: it is now the newest
                }
                out[off + f * channels + c] = (y * g).toInt().toShort()
            }
            if (active) statePos = (statePos + 1) % ORDER
            if (produced < Int.MAX_VALUE) produced++
        }
    }

    companion object {
        private const val ORDER = 40

        /**
         * Predictor coefficients (x[n] ≈ Σ c[k-1]·x[n-k]) by autocorrelation and Levinson-Durbin,
         * with a Hann window, -40 dB white-noise correction and slight bandwidth expansion so the
         * filter stays stable and rings down rather than up. Null for silence.
         */
        private fun lpc(x: DoubleArray): DoubleArray? {
            val n = x.size
            val w = DoubleArray(n) { x[it] * (0.5 - 0.5 * Math.cos(2 * Math.PI * (it + 0.5) / n)) }
            val r = DoubleArray(ORDER + 1)
            for (lag in 0..ORDER) {
                var s = 0.0
                for (i in lag until n) s += w[i] * w[i - lag]
                r[lag] = s
            }
            if (r[0] < n * 1.0) return null   // under ~1 LSB rms: digital silence
            r[0] *= 1.0001
            val a = DoubleArray(ORDER + 1)
            val tmp = DoubleArray(ORDER + 1)
            a[0] = 1.0
            var e = r[0]
            for (i in 1..ORDER) {
                var acc = r[i]
                for (j in 1 until i) acc += a[j] * r[i - j]
                val k = -acc / e
                for (j in 1 until i) tmp[j] = a[j] + k * a[i - j]
                for (j in 1 until i) a[j] = tmp[j]
                a[i] = k
                e *= 1 - k * k
                if (e <= 0) return null
            }
            var gamma = 1.0
            return DoubleArray(ORDER) { gamma *= 0.998; -a[it + 1] * gamma }
        }
    }
}
