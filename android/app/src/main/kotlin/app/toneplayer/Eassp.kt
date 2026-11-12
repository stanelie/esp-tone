package app.toneplayer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.abs

/**
 * EASSP, the protocol of the ESP8266-WiFi-MICROPHONE firmware (github.com/Dzantemir/ESP8266-WiFi-MICROPHONE).
 *
 * Control is UDP on port 3950: we send DISCOVER (the device answers INFO), CONFIGURE with a port
 * (the device starts sending audio to us there) and STOP. While streaming, the device stops by
 * itself if it hears no DISCOVER for 15 s, so the player repeats it as a heartbeat. Idle devices
 * also broadcast INFO to port 3950 every 1–5 s. Multi-byte fields are little-endian, except the
 * port in CONFIGURE, which is big-endian.
 *
 * A [Device] whose port is [PORT] is an EASSP device; any other port is the esp-tone HTTP protocol.
 */
object Eassp {
    const val PORT = 3950
    const val CMD_DISCOVER = 0x01
    const val CMD_CONFIGURE = 0x02
    const val CMD_STOP = 0x03
    const val CMD_INFO = 0x81

    const val CODEC_ADPCM = 5
    const val CODEC_PCM = 6
    const val TRANSPORT_UDP = 0
    const val TRANSPORT_TCP = 1

    data class Info(
        val id: String,           // MAC address, hex: the stable identity
        val name: String,         // DHCP hostname
        val status: Int,          // 0 idle, 1 streaming, 2 error
        val codec: Int,
        val channels: Int,
        val rate: Int,
        val bits: Int,
        val transport: Int,
    )

    fun packet(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteArray(8 + payload.size)
        b[0] = 0xEA.toByte(); b[1] = 0x55; b[2] = 1; b[3] = cmd.toByte()
        b[4] = seq.toByte(); b[5] = (seq shr 8).toByte()
        b[6] = payload.size.toByte(); b[7] = (payload.size shr 8).toByte()
        payload.copyInto(b, 8)
        return b
    }

    fun configure(seq: Int, streamPort: Int) =
        packet(CMD_CONFIGURE, seq, byteArrayOf((streamPort shr 8).toByte(), streamPort.toByte()))

    /**
     * A DISCOVER heartbeat carrying [received], the number of audio packets we have received this
     * stream, as a 4-byte little-endian payload. The device uses it as an uplink-liveness signal:
     * if it keeps sending while this count stops advancing, its uplink to us is dead and it rejoins
     * Wi-Fi. Older firmware ignores the payload, so this stays a plain DISCOVER to it.
     */
    fun discoverHeartbeat(seq: Int, received: Int) =
        packet(CMD_DISCOVER, seq, byteArrayOf(
            received.toByte(), (received shr 8).toByte(),
            (received shr 16).toByte(), (received shr 24).toByte()))

    /** Parses an INFO packet; null for anything else. */
    fun parseInfo(b: ByteArray, len: Int): Info? {
        if (len < 8 + 34 || b[0] != 0xEA.toByte() || b[1] != 0x55.toByte() || b[2].toInt() != 1) return null
        if ((b[3].toInt() and 0xFF) != CMD_INFO) return null
        val p = 8
        val mac = (0 until 6).joinToString("") { "%02x".format(b[p + 9 + it].toInt() and 0xFF) }
        val name = if (len >= p + 58) {
            String(b, p + 34, 24, Charsets.US_ASCII).substringBefore('\u0000').trim()
        } else ""
        return Info(
            id = mac,
            name = name.ifEmpty { "esp-mic" },
            status = u8(b, p),
            codec = u8(b, p + 1),
            channels = u8(b, p + 3),
            rate = le32(b, p + 4),
            bits = u8(b, p + 32),
            transport = u8(b, p + 33),
        )
    }

    /** Asks [host] directly for its INFO. */
    fun probe(host: String, timeoutMs: Int): Info? = try {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs.coerceAtMost(500)
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(512)
            val end = System.currentTimeMillis() + timeoutMs
            var info: Info? = null
            while (info == null && System.currentTimeMillis() < end) {
                val q = packet(CMD_DISCOVER, 0)
                s.send(DatagramPacket(q, q.size, addr, PORT))
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                if (p.address == addr) info = parseInfo(buf, p.length)
            }
            info
        }
    } catch (e: Exception) {
        Log.d("TonePlayer", "eassp probe $host failed: $e")
        null
    }

    /**
     * The rate the ESP really samples at: its I2S clock comes from 160 MHz through two integer
     * dividers, so e.g. 44100 Hz is really 43860 Hz. Same search as the firmware's i2s_set_rate().
     */
    fun actualRate(nominal: Int, bits: Int): Int {
        val base = 160_000_000f / (if (bits == 16) 32f else 48f)
        var best = Float.MAX_VALUE
        var rate = nominal.toFloat()
        for (i in 1 until 64) for (j in i until 64) {
            val r = base / i / j
            val d = abs(r - nominal)
            if (d < best) { best = d; rate = r }
        }
        return Math.round(rate)
    }

    /** Sample rate field of the audio packet header. */
    fun rateFromEnum(e: Int) = when (e) {
        0 -> 8000; 1 -> 11025; 2 -> 16000; 3 -> 22050; 4 -> 32000; 5 -> 44100; else -> 48000
    }

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun le16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    /** IMA ADPCM, DVI4 layout (RFC 3551): 4-byte header (predictor LE, step index, 0), first sample in the high nibble. */
    class AdpcmDecoder {
        fun decode(b: ByteArray, off: Int, len: Int, out: ShortArray, outOff: Int, stride: Int): Int {
            if (len < 4) return 0
            var predictor = (le16(b, off)).toShort().toInt()
            var index = u8(b, off + 2).coerceIn(0, 88)
            var o = outOff
            var n = 0
            for (k in off + 4 until off + len) {
                val byte = u8(b, k)
                for (code in intArrayOf(byte shr 4, byte and 0x0F)) {
                    val step = STEPS[index]
                    var diff = step shr 3
                    if (code and 4 != 0) diff += step
                    if (code and 2 != 0) diff += step shr 1
                    if (code and 1 != 0) diff += step shr 2
                    predictor = if (code and 8 != 0) predictor - diff else predictor + diff
                    predictor = predictor.coerceIn(-32768, 32767)
                    index = (index + INDEX[code]).coerceIn(0, 88)
                    out[o] = predictor.toShort()
                    o += stride
                    n++
                }
            }
            return n
        }

        companion object {
            private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
            private val STEPS = intArrayOf(
                7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
                50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
                253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
                1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
                3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
                12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
            )
        }
    }
}
