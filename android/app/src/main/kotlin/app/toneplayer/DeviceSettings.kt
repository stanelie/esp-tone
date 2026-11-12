package app.toneplayer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Client for the device's self-describing settings protocol (see SPEC.md §8.2 and
 * tools/spike_settings.py, which is the reference implementation).
 *
 * Nothing here knows what settings exist, what values are legal, or when they take effect — the
 * device reports all of it and the UI is built from that. This is deliberate: every settings bug in
 * the old app came from duplicating constraints the firmware actually owns (offering gain 0–64 when
 * only 4–8 worked, offering gain while AGC silently ignored it, showing saved values as live).
 */
object DeviceSettings {
    const val PORT = 3950

    private const val M0 = 0xE7
    private const val M1 = 0x03
    private const val MSG_GET = 1
    private const val MSG_SETTINGS = 2
    private const val MSG_SET = 3
    private const val MSG_RESULT = 4

    const val WHEN_NOW = 0
    const val WHEN_STREAM_RESTART = 1
    const val WHEN_REBOOT = 2

    /** One setting as the device describes it. [values] is the allowed set (enum) or [min,max]. */
    data class Item(
        val id: Int,
        val kind: Int,              // 1 = enum, 2 = range
        val applicable: Boolean,
        val appliesWhen: Int,
        val current: Long,
        val values: List<Long>,
        /** What each of [values] is called. The device names them: a codec id of 5 means nothing
         *  in a dropdown, and mapping it to "ADPCM" in the app would duplicate what the firmware
         *  owns. Empty only when talking to firmware older than this field. */
        val valueLabels: List<String>,
        val label: String,
    ) {
        /** The label for [v], falling back to the raw number. */
        fun textFor(v: Long): String {
            val i = values.indexOf(v)
            return if (i >= 0 && i < valueLabels.size) valueLabels[i] else v.toString()
        }

        val isEnum get() = kind == 1
        fun whenText() = when (appliesWhen) {
            WHEN_NOW -> "applies immediately"
            WHEN_STREAM_RESTART -> "applies when playback restarts"
            WHEN_REBOOT -> "applies after a reboot"
            else -> ""
        }
    }

    fun statusText(status: Int) = when (status) {
        0 -> "ok"
        1 -> "the device does not know that setting"
        2 -> "the device does not allow that value"
        3 -> "not applicable right now"
        else -> "unknown status $status"
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    /** Asks the device what it has. Null means it never answered (or speaks no settings protocol). */
    fun fetch(host: String, timeoutMs: Int = 2500): List<Item>? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 600
            val addr = InetAddress.getByName(host)
            val req = byteArrayOf(M0.toByte(), M1.toByte(), MSG_GET.toByte())
            val buf = ByteArray(1024)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                s.send(DatagramPacket(req, req.size, addr, PORT))
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                // A stream may be running, so other packets arrive here too; just skip them.
                if (p.length >= 4 && u8(buf, 0) == M0 && u8(buf, 1) == M1 &&
                    u8(buf, 2) == MSG_SETTINGS) {
                    return@use parse(buf, p.length)
                }
            }
            null
        }
    } catch (e: Exception) {
        Log.w("TonePlayer", "settings fetch failed: $e")
        null
    }

    private fun parse(b: ByteArray, len: Int): List<Item> {
        val out = ArrayList<Item>()
        var o = 4
        repeat(u8(b, 3)) {
            if (o + 10 > len) return out
            val id = u8(b, o); val kind = u8(b, o + 1)
            val applicable = u8(b, o + 2) != 0; val whenA = u8(b, o + 3)
            o += 4
            val cur = u32(b, o); o += 4
            val n = u8(b, o); o += 1
            if (o + 4 * n + 1 > len) return out
            val vals = ArrayList<Long>(n)
            for (i in 0 until n) { vals.add(u32(b, o)); o += 4 }
            val vlabels = ArrayList<String>(n)
            for (i in 0 until n) {
                if (o + 1 > len) return out
                val vl = u8(b, o); o += 1
                if (o + vl > len) return out
                vlabels.add(String(b, o, vl, Charsets.UTF_8)); o += vl
            }
            val ln = u8(b, o); o += 1
            if (o + ln > len) return out
            val label = String(b, o, ln, Charsets.UTF_8); o += ln
            out.add(Item(id, kind, applicable, whenA, cur, vals, vlabels, label))
        }
        return out
    }

    /**
     * Changes one setting. Returns status (see [statusText]) and the device's resulting value, or
     * null if it never answered. Generous timeout: applying a rate change re-inits I2S and the
     * device verifies capture actually came up before replying.
     */
    fun set(host: String, id: Int, value: Long, timeoutMs: Int = 5000): Pair<Int, Long>? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 800
            val addr = InetAddress.getByName(host)
            val req = byteArrayOf(
                M0.toByte(), M1.toByte(), MSG_SET.toByte(), id.toByte(),
                value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()
            )
            val buf = ByteArray(256)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                s.send(DatagramPacket(req, req.size, addr, PORT))
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                if (p.length >= 9 && u8(buf, 0) == M0 && u8(buf, 1) == M1 &&
                    u8(buf, 2) == MSG_RESULT && u8(buf, 3) == id) {
                    return@use Pair(u8(buf, 4), u32(buf, 5))
                }
            }
            null
        }
    } catch (e: Exception) {
        Log.w("TonePlayer", "settings set failed: $e")
        null
    }
}
