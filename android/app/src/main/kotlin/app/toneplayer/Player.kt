package app.toneplayer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide playback state. The foreground [PlaybackService] drives it; the UI observes it.
 * Listeners are always called on the main thread.
 */
object Player {
    sealed class State {
        object Idle : State()
        data class Connecting(val device: Device) : State()
        data class Playing(val device: Device) : State()
        /** The stream was interrupted and the service is trying to get it back. */
        data class Reconnecting(val device: Device) : State()
        data class Error(val device: Device, val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Worker? = null

    private interface Worker {
        fun cancel()
        fun start()
    }

    @Volatile var state: State = State.Idle
        private set

    val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    /** Ask the service to play [device]. Safe to call while something else is playing. */
    fun requestPlay(context: Context, device: Device) {
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra("id", device.id).putExtra("name", device.name)
                .putExtra("host", device.host).putExtra("port", device.port)
        )
    }

    fun requestStop(context: Context) {
        context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
    }

    // Called by the service, on the main thread.
    internal fun start(device: Device) {
        worker?.cancel()
        val t: Worker = if (device.port == Eassp.PORT) EasspThread(device) else StreamThread(device)
        worker = t
        publish(State.Connecting(device))
        t.start()
    }

    internal fun markReconnecting(device: Device) = publish(State.Reconnecting(device))

    internal fun stop() {
        worker?.cancel()
        worker = null
        publish(State.Idle)
    }

    private fun publish(s: State) {
        Log.i("TonePlayer", "state: $s")
        state = s
        listeners.forEach { it(s) }
    }

    /** Target depth of the jitter buffer in ms, or 0 for automatic. Applies live, also to a stream already playing. */
    @Volatile var bufferMs = DEFAULT_BUFFER_MS

    /** Buffer and concealment figures for the stream playing now, or null. */
    @Volatile var jitter: JitterPlayer? = null
        private set

    const val DEFAULT_BUFFER_MS = 0

    /** 32 = +30 dB, the level the device used to apply itself. */
    const val DEFAULT_GAIN_X = 32

    /**
     * When on, [Agc] replaces the plain saturating gain: same loudness when nothing is loud, but
     * it ducks before a peak overflows and recovers in ~0.15 s. Off by default, so the default
     * path stays exactly the untouched signal times a constant.
     */
    @Volatile var agcOn = false
        private set

    /** Playback-side low cut / high cut in Hz; 0 is off. See [Filters]. */
    @Volatile var lowCutHz = 0
        private set
    @Volatile var highCutHz = 0
        private set
    private const val PREFS = "settings"

    private var filesDir: java.io.File? = null   // for JitterPlayer.captureDir
    private var audioManager: android.media.AudioManager? = null

    /* Playback runs at the phone's own output rate so the low-latency fast mixer will take the
     * track; the microphone's rate is converted on the way in. See Resampler. */
    private var agc: Agc? = null
    private var filters: Filters? = null
    private var resampler: Resampler? = null
    private var resampled = ShortArray(0)
    private var rateRatio = 1.0        // output frames per source frame
    private var srcRate = 1            // the microphone's rate, for the rate loop's dt
    private var srcChannels = 1

    fun loadSettings(context: Context) {
        filesDir = context.filesDir
        audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        bufferMs = p.getInt("buffer_ms", DEFAULT_BUFFER_MS)
        gainX = p.getInt("gain_x", DEFAULT_GAIN_X)
        agcOn = p.getBoolean("agc_on", false)
        lowCutHz = p.getInt("low_cut_hz", 0)
        highCutHz = p.getInt("high_cut_hz", 0)
    }

    fun saveLowCut(context: Context, hz: Int) {
        lowCutHz = hz
        filters?.configure(lowCutHz, highCutHz)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("low_cut_hz", hz).apply()
    }

    fun saveHighCut(context: Context, hz: Int) {
        highCutHz = hz
        filters?.configure(lowCutHz, highCutHz)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("high_cut_hz", hz).apply()
    }

    fun saveAgcOn(context: Context, on: Boolean) {
        agcOn = on
        agc?.reset()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("agc_on", on).apply()
    }

    /**
     * Playback gain, as a plain multiplier (32 = +30 dB). Applied here rather than on the device:
     * the microphone's own noise floor sits ~35 dB above 16-bit quantization noise, so there is no
     * low-level detail that device-side gain could preserve, and gaining on the device would bake
     * clipping irreversibly into the transmitted stream. Doing it here keeps the wire data pristine
     * and the setting instantly adjustable.
     */
    @Volatile var gainX = DEFAULT_GAIN_X

    fun saveGainX(context: Context, g: Int) {
        gainX = g.coerceIn(1, 256)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("gain_x", gainX).apply()
    }

    fun saveBufferMs(context: Context, ms: Int) {
        bufferMs = ms
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("buffer_ms", ms).apply()
    }

    /** A low-latency track with the smallest buffer: [JitterPlayer] does the buffering. */
    private fun newTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuf * 2)   // capacity; JitterPlayer uses as little of it as works
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Starts playout of a new stream format; [onPlaying] runs when the first real audio is heard. */
    /** The phone's own output rate, which is the only rate the fast mixer accepts. */
    private fun nativeRate(fallback: Int): Int {
        val n = audioManager
            ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: return fallback
        return if (n in 8000..192000) n else fallback
    }

    private fun newJitter(source: Int, channels: Int, onPlaying: () -> Unit): JitterPlayer {
        val rate = nativeRate(source)
        agc = Agc(source)        // runs on the packet, before the resampler
        filters = Filters(source, channels).also { it.configure(lowCutHz, highCutHz) }
        resampler = if (rate != source) Resampler(source, rate, channels) else null
        rateRatio = rate.toDouble() / source.toDouble()
        srcRate = source
        srcChannels = channels
        val track = newTrack(rate, channels)
        Log.i("TonePlayer", "track: $rate Hz" +
            "${if (resampler != null) " (resampled from $source)" else " (source rate, no conversion)"}" +
            ", output capacity ${track.bufferCapacityInFrames * 1000 / rate} ms" +
            ", target ${if (bufferMs == 0) "auto" else "$bufferMs ms"}")
        /* Android's low-latency fast mixer only takes tracks at the device's own output rate and
         * block size; anything else is resampled on the deep-buffer path, which costs well over
         * 100 ms. Our rates (32051 / 43860 Hz) are nothing like the usual native 48000, so log
         * both to see how far off we are. */
        audioManager?.let { am ->
            val native = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            Log.i("TonePlayer", "device output: $native Hz, " +
                "${am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} frames/buffer" +
                " (stream is $rate Hz -> ${if (native == rate.toString()) "native" else "RESAMPLED, no fast path"})")
        }
        val outBlock = audioManager
            ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull() ?: 0
        return JitterPlayer(track, rate, channels, { bufferMs }, onPlaying, outBlock)
            .also { it.captureDir = filesDir; jitter = it }
    }

    /**
     * Hands one packet of microphone-rate audio to the playout, converting it to the output rate
     * first when they differ. [missingFrames] is in source frames and is scaled with it.
     */
    private fun pushAudio(j: JitterPlayer, pcm: ShortArray, n: Int, missingFrames: Int = 0) {
        val rs = resampler
        if (rs == null) {
            j.push(pcm, n, missingFrames)
            return
        }
        /* Close the rate loop before converting: the ratio this packet is resampled at is what
         * holds the jitter buffer at its target. Without it the ratio is fixed, the microphone's
         * clock and the phone's slowly disagree, and the only thing able to remove the surplus is
         * the delay trimmer — which splices audio and is audible doing it. See Resampler.correct. */
        rs.correct(j.depthErrorMs(), (n / srcChannels).toDouble() / srcRate)
        j.driftPercent = rs.trimPercent

        val need = rs.maxOutSamples(n)
        if (resampled.size < need) resampled = ShortArray(need)
        val out = rs.process(pcm, n, resampled)
        if (out > 0) j.push(resampled, out, (missingFrames * rateRatio).toInt())
    }

    private fun JitterPlayer.close() {
        release()
        if (jitter === this) jitter = null
    }

    /** Plays an EASSP microphone (see [Eassp]): control over UDP 3950, audio over UDP or TCP. */
    private class EasspThread(private val device: Device) : Thread("eassp-stream"), Worker {
        @Volatile private var cancelled = false
        @Volatile private var ctl: DatagramSocket? = null
        @Volatile private var audio: java.io.Closeable? = null
        private var seq = 1

        override fun cancel() {
            cancelled = true
            try { audio?.close() } catch (_: Exception) {}   // unblocks the receive
        }

        private fun post(s: Player.State) {
            main.post { if (worker === this && !cancelled) publish(s) }
        }

        override fun run() {
            var out: JitterPlayer? = null
            val addr = InetAddress.getByName(device.host)
            try {
                val c = DatagramSocket()
                ctl = c
                c.soTimeout = 500
                fun send(p: ByteArray) = c.send(DatagramPacket(p, p.size, addr, Eassp.PORT))

                // Learn the transport, then ask for the stream.
                var info: Eassp.Info? = null
                val buf = ByteArray(1500)
                for (i in 0 until 6) {
                    send(Eassp.packet(Eassp.CMD_DISCOVER, seq++))
                    val p = DatagramPacket(buf, buf.size)
                    try { c.receive(p) } catch (_: SocketTimeoutException) { continue }
                    info = Eassp.parseInfo(buf, p.length) ?: continue
                    break
                }
                if (info == null) throw java.io.IOException("Microphone does not answer")
                if (info.transport != Eassp.TRANSPORT_UDP && info.transport != Eassp.TRANSPORT_TCP) {
                    post(Player.State.Error(device, "Microphone is in raw 802.11 mode; set AT+XPORT=0"))
                    return
                }
                Log.i("TonePlayer", "eassp: $info")

                val frames: () -> Pair<ByteArray, Int>
                if (info.transport == Eassp.TRANSPORT_UDP) {
                    val a = DatagramSocket()
                    audio = a
                    a.receiveBufferSize = 256 * 1024
                    a.soTimeout = 1000
                    send(Eassp.configure(seq++, a.localPort))
                    val pkt = ByteArray(16384)
                    frames = {
                        val p = DatagramPacket(pkt, pkt.size)
                        a.receive(p)
                        Pair(pkt, p.length)
                    }
                } else {
                    // TCP: the ESP listens on the port we name; each frame is prefixed with a big-endian length.
                    send(Eassp.configure(seq++, TCP_STREAM_PORT))
                    var sock: Socket? = null
                    val until = System.currentTimeMillis() + 4000
                    while (sock == null && !cancelled) {
                        try {
                            sock = Socket().apply { connect(InetSocketAddress(addr, TCP_STREAM_PORT), 1000); soTimeout = 1000 }
                        } catch (e: Exception) {
                            if (System.currentTimeMillis() > until) throw e
                            sleep(200)
                        }
                    }
                    if (sock == null) return
                    audio = sock
                    val input = DataInputStream(sock.getInputStream())
                    val pkt = ByteArray(65536)
                    frames = {
                        val n = input.readUnsignedShort()
                        input.readFully(pkt, 0, n)
                        Pair(pkt, n)
                    }
                }

                val adpcm = Eassp.AdpcmDecoder()
                var pcm = ShortArray(8192)
                var format = ""
                var lastSeq = -1
                var lastHeartbeat = System.currentTimeMillis()
                var lastAudio = System.currentTimeMillis()
                var received = 0   // audio packets received this stream; reported in the heartbeat
                val reseq = Resequencer()

                while (!cancelled) {
                    val now = System.currentTimeMillis()
                    if (info.transport == Eassp.TRANSPORT_UDP && format.isEmpty() && now - lastHeartbeat >= CONFIGURE_RETRY_MS) {
                        // No audio yet: the CONFIGURE was probably lost on the air (their server does the same).
                        send(Eassp.configure(seq++, (audio as DatagramSocket).localPort))
                        lastHeartbeat = now
                    } else if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        send(Eassp.discoverHeartbeat(seq++, received))
                        lastHeartbeat = now
                    }
                    val (b, len) = try {
                        frames()
                    } catch (_: SocketTimeoutException) {
                        if (now - lastAudio > NO_AUDIO_MS) throw java.io.IOException("No audio from the microphone")
                        continue
                    }
                    if (len < 16) continue
                    lastAudio = System.currentTimeMillis()
                    received++   // uplink-liveness: tell the device (via the heartbeat) we're getting data

                    val codec = Eassp.u8(b, 6)
                    val rate = Eassp.rateFromEnum(Eassp.u8(b, 7))
                    val channels = Eassp.u8(b, 8).coerceIn(1, 2)
                    val bits = Eassp.le16(b, 14)

                    // A new format (first frame, or the ESP was reconfigured) needs a new AudioTrack.
                    val f = "$codec/$rate/$channels/$bits"
                    if (f != format) {
                        out?.close()
                        val real = Eassp.actualRate(rate, bits)
                        Log.i("TonePlayer", "eassp: format codec=$codec rate=$rate (really $real) ch=$channels bits=$bits")
                        out = newJitter(real, channels) { post(Player.State.Playing(device)) }
                        format = f
                        lastSeq = -1
                    }
                    val j = out ?: continue

                    /* The device numbers only packets it managed to send. A gap here is not
                     * necessarily loss, though: it is just as likely the next packet overtaking
                     * one still in flight, so the decision is deferred to the resequencer below
                     * rather than made here. See Resequencer. */
                    val seqNo = Eassp.le16(b, 0)

                    val payload = len - 16
                    val n = when (codec) {
                        Eassp.CODEC_ADPCM -> {
                            val block = payload / channels
                            if (pcm.size < block * 2 * channels) pcm = ShortArray(block * 2 * channels)
                            var count = 0
                            for (ch in 0 until channels) {
                                count = adpcm.decode(b, 16 + ch * block, block, pcm, ch, channels)
                            }
                            count * channels
                        }
                        Eassp.CODEC_PCM -> {
                            val bytes = if (bits == 24) 3 else 2
                            val count = payload / bytes
                            if (pcm.size < count) pcm = ShortArray(count)
                            for (k in 0 until count) {
                                val o = 16 + k * bytes + (bytes - 2)   // the top two bytes of each sample
                                pcm[k] = Eassp.le16(b, o).toShort()
                            }
                            count
                        }
                        else -> 0
                    }
                    if (n == 0) continue

                    /* Order first, then everything stateful. The filters, the AGC and the
                     * resampler all carry state across packets, and the AGC's look-ahead means a
                     * call returns audio from slightly EARLIER than the packet it was given — so
                     * feeding them out of order mislabels audio rather than merely reordering it.
                     * Restoring order here is what makes the rest of the chain correct. */
                    reseq.accept(seqNo, pcm, n, channels) { s, len, missing ->
                        // Gain, applied once for every codec, saturating. Non-destructive: the
                        // data on the wire is untouched, so this can be turned back down freely.
                        /* Filters first, then level. A low cut after the limiter would have the
                         * limiter ducking for rumble that is about to be removed. */
                        filters?.process(s, len)

                        val g = gainX
                        val a = agc
                        if (agcOn && a != null) {
                            // The limiter has to see the signal BEFORE it is clipped, so it
                            // applies the fixed gain itself rather than after the loop below.
                            a.process(s, len, g)
                        } else if (g != 1) {
                            for (k in 0 until len) {
                                val v = s[k] * g
                                s[k] = if (v > 32767) 32767
                                       else if (v < -32768) (-32768).toShort() else v.toShort()
                            }
                        }
                        pushAudio(j, s, len, missing)
                        j.reqLost = reseq.lost
                        j.reqReordered = reseq.reordered
                        j.reqTooLate = reseq.tooLate
                    }
                }
            } catch (e: Exception) {
                Log.w("TonePlayer", "eassp stream ended (cancelled=$cancelled)", e)
                if (!cancelled) post(Player.State.Error(device, e.message ?: e.javaClass.simpleName))
            } finally {
                // Tell the microphone to stop now rather than after its 15 s watchdog.
                try {
                    val p = Eassp.packet(Eassp.CMD_STOP, seq++)
                    ctl?.send(DatagramPacket(p, p.size, addr, Eassp.PORT))
                } catch (_: Exception) {}
                ctl?.close()
                try { audio?.close() } catch (_: Exception) {}
                out?.close()
            }
        }

        companion object {
            // Heartbeat also carries our received-packet count (uplink-liveness signal), so send it
            // briskly — well under the device's 15 s stream watchdog — to keep that count fresh.
            private const val HEARTBEAT_MS = 1500L
            private const val CONFIGURE_RETRY_MS = 500L
            // Give the device time to notice a blackholed uplink (~5 s) and rejoin Wi-Fi before we
            // give up and tear the stream down; on give-up the service auto-reconnects.
            private const val NO_AUDIO_MS = 7000L
            private const val TCP_STREAM_PORT = 5000
        }
    }

    private class StreamThread(private val device: Device) : Thread("tone-stream"), Worker {
        @Volatile private var cancelled = false
        @Volatile private var conn: HttpURLConnection? = null

        override fun cancel() {
            cancelled = true
            conn?.disconnect()   // unblocks the read
        }

        // Thread has its own nested State, so Player.State must be spelled out in this class.
        private fun post(s: Player.State) {
            main.post { if (worker === this && !cancelled) publish(s) }
        }

        override fun run() {
            var out: JitterPlayer? = null
            try {
                // No trailing slash: matches what was verified with VLC.
                val c = URL("http://${device.host}:${device.port}").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 3000
                c.readTimeout = 5000
                if (c.responseCode != 200) {
                    post(Player.State.Error(device, "Transmitter busy or refused (${c.responseCode})"))
                    return
                }
                val input = DataInputStream(c.inputStream)

                // 44-byte PCM WAV header: channels at 22, sample rate at 24 (little-endian).
                val header = ByteArray(44)
                input.readFully(header)
                val channels = le16(header, 22)
                val rate = le32(header, 24)
                val j = newJitter(rate, channels) { post(Player.State.Playing(device)) }
                out = j

                // Whatever has arrived, in pieces of up to 5 ms, so the buffer sees audio as soon as it lands.
                val chunk = ByteArray(rate / 200 * 2 * channels)
                val pcm = ShortArray(chunk.size / 2)
                var carry = 0   // an odd byte left from the previous read
                while (!cancelled) {
                    val got = input.read(chunk, carry, chunk.size - carry)
                    if (got < 0) throw java.io.EOFException("Stream ended")
                    val have = carry + got
                    val frameBytes = 2 * channels
                    val whole = have / frameBytes * frameBytes
                    for (k in 0 until whole / 2) pcm[k] = le16(chunk, k * 2).toShort()
                    pushAudio(j, pcm, whole / 2)
                    carry = have - whole
                    System.arraycopy(chunk, whole, chunk, 0, carry)
                }
            } catch (e: Exception) {
                Log.w("TonePlayer", "stream thread ended (cancelled=$cancelled)", e)
                if (!cancelled) post(Player.State.Error(device, e.message ?: e.javaClass.simpleName))
            } finally {
                out?.close()
                conn?.disconnect()
            }
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    }
}
