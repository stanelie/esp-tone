package app.toneplayer

/**
 * Puts arriving packets back into sequence order before anything stateful touches them.
 *
 * WHY THIS EXISTS. The receive loop used to apply a strict in-order gate:
 *
 *     if (ahead == 0 || ahead >= 0x8000) continue   // duplicate, or late
 *
 * A packet one position behind the write head was discarded, and the hole it left had already
 * been counted as loss when its successor arrived — so one swapped pair destroyed a good packet
 * AND reported a lost one. There was no window and no time limit, just "not next, therefore
 * gone". MEASURED on the iOS client over a VPN, which had the identical gate: 113 packets counted
 * as lost, of which 81 — 72% — had actually arrived and been thrown away, never more than three
 * positions out of order. On a LAN the same code reports 0.00%, because a switched segment does
 * not reorder; that is why it went unnoticed for so long.
 *
 * It sits ahead of the filters, the AGC and the resampler deliberately. Every one of those is
 * sequential, and [Agc] carries a look-ahead DELAY LINE, so feeding them out of order does not
 * merely process packets in an odd order — it emits audio belonging to one packet under the
 * identity of another. On iOS that produced a steady tick described as "clicks and pops like a
 * vinyl" until the ordering was restored upstream of the chain.
 *
 * Holding a packet back costs latency only while there is a hole to wait for, and never more than
 * [window] packets' worth. In-order traffic passes straight through without even a copy.
 */
class Resequencer(private val window: Int = 8) {
    private val pending = HashMap<Int, ShortArray>()
    private val lengths = HashMap<Int, Int>()
    private var next = -1
    private var missingFrames = 0

    /** Holes given up on: real loss, as opposed to what the old gate called loss. */
    var lost = 0; private set
    /** Packets that arrived out of order and were put back in place. */
    var reordered = 0; private set
    /** Arrived after their slot had already been released: the only unusable ones. */
    var tooLate = 0; private set
    var duplicates = 0; private set

    /**
     * Takes one decoded packet and hands whatever is now deliverable to [emit], in order, with
     * the frames missing immediately before it. Usually that is just this packet; after a hole
     * fills it can be several at once, and while a hole is outstanding it can be none.
     */
    fun accept(seq: Int, pcm: ShortArray, n: Int, channels: Int,
               emit: (ShortArray, Int, Int) -> Unit) {
        if (next < 0) next = seq
        val ahead = (seq - next) and 0xFFFF

        if (ahead >= 0x8000) { tooLate++; return }        // behind: its slot has gone
        /* A jump this far ahead is a restart or a long outage, not reordering. Holding the new
         * packets and then walking `next` up to meet them would report hundreds of phantom
         * losses and do it one iteration at a time, so resynchronise instead. */
        if (ahead > RESYNC_AHEAD) {
            pending.clear(); lengths.clear(); next = seq; missingFrames = 0
        }
        if (pending.containsKey(seq)) { duplicates++; return }

        // In order with nothing held: the common case, and it costs not even a copy.
        if (ahead == 0 && pending.isEmpty()) {
            next = (next + 1) and 0xFFFF
            emit(pcm, n, takeMissing())
            return
        }

        if (ahead > 0) reordered++
        pending[seq] = pcm.copyOf(n)
        lengths[seq] = n
        release(emit)

        /* A hole still outstanding more than `window` packets later is not coming. Give up on it
         * and release whatever was queued behind it.
         *
         * The release call inside this loop is what makes it terminate: advancing `next` alone
         * leaves `pending` the same size, so an earlier version of this loop spun forever on the
         * network thread the moment a hole outlived the window — audio stopped within seconds and
         * every counter froze. The Swift original drained inside the loop; splitting this into
         * two functions dropped that, and nothing tested it. */
        while (pending.size > window) {
            lost++
            missingFrames += n / channels
            next = (next + 1) and 0xFFFF
            release(emit)
        }
    }

    /** Releases every packet that is now in order, oldest first. */
    private fun release(emit: (ShortArray, Int, Int) -> Unit) {
        while (true) {
            val key = next and 0xFFFF
            val p = pending.remove(key) ?: return
            val l = lengths.remove(key) ?: return
            next = (next + 1) and 0xFFFF
            emit(p, l, takeMissing())
        }
    }

    private fun takeMissing(): Int { val m = missingFrames; missingFrames = 0; return m }

    private companion object {
        /** 64 packets is 320 ms at the 5 ms frame size, far beyond any plausible displacement. */
        const val RESYNC_AHEAD = 64
    }

    fun reset() {
        pending.clear(); lengths.clear(); next = -1; missingFrames = 0
        lost = 0; reordered = 0; tooLate = 0; duplicates = 0
    }
}
