package app.toneplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Found(val id: String, val name: String, val host: String, val port: Int)

/**
 * Finds transmitters two ways at once: mDNS/DNS-SD (fast, needs multicast) and a scan of the
 * attached network for the stream port (slower, works when multicast is blocked). Every
 * candidate is confirmed with /info, which also yields its stable id.
 *
 * [onFound] and [onDone] are called on the main thread. [onDone] fires once both methods have
 * finished.
 */
class Discovery(
    private val context: Context,
    private val network: Network?,
    private val onFound: (Found) -> Unit,
    private val onDone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(64)
    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("toneplayer")

    @Volatile private var cancelled = false
    private var reported = false
    private var mdnsThread: Thread? = null
    private var nsdDone = false
    private var easspDone = false
    private var scanDone = false

    fun start() {
        Log.i(TAG, "discovery: network=$network scan=${networkLabel(context, network)} targets=${scanTargets().size}")
        multicastLock.setReferenceCounted(false)
        multicastLock.acquire()
        startMdns()
        startEassp()
        startScan()
        main.postDelayed({ nsdDone = true; checkDone() }, NSD_WINDOW_MS)
    }

    fun stop() {
        if (cancelled) return
        cancelled = true
        main.removeCallbacksAndMessages(null)
        mdnsThread?.interrupt()
        pool.shutdownNow()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun checkDone() {
        if (cancelled || reported || !nsdDone || !easspDone || !scanDone) return
        reported = true
        stop()
        onDone()
    }

    private fun emit(host: String, port: Int) {
        val info = Probe.info(host, port) ?: return
        Log.i(TAG, "discovery: $host:$port is ${info.name} (${info.id})")
        main.post { if (!cancelled) onFound(Found(info.id, info.name, host, port)) }
    }

    // ---- mDNS ----

    // Our own minimal mDNS query instead of NsdManager, which is unreliable on Samsung phones.
    // It asks for PTR _esptone._tcp.local and takes the sender's address from any answer.
    private fun startMdns() {
        val thread = Thread {
            var socket: MulticastSocket? = null
            try {
                socket = try {
                    MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(MDNS_PORT)) }
                } catch (e: Exception) {
                    Log.i(TAG, "discovery: mDNS port busy ($e), using an ephemeral port")
                    MulticastSocket(null).apply { bind(InetSocketAddress(0)) }
                }
                network?.bindSocket(socket)
                val group = InetAddress.getByName(MDNS_GROUP)
                try { socket.joinGroup(group) } catch (e: Exception) { Log.i(TAG, "discovery: mDNS join failed: $e") }
                socket.soTimeout = 250
                val query = buildQuery()
                val seen = HashSet<String>()
                val buf = ByteArray(1500)
                val end = System.currentTimeMillis() + NSD_WINDOW_MS
                var nextSend = 0L
                while (!cancelled && System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                        nextSend = now + QUERY_INTERVAL_MS
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val from = p.address as? Inet4Address ?: continue
                    if (p.length < 12 || (buf[2].toInt() and 0x80) == 0) continue       // not a response
                    if (!String(buf, 0, p.length, Charsets.ISO_8859_1).contains("_esptone")) continue
                    val host = from.hostAddress ?: continue
                    if (seen.add(host)) {
                        Log.i(TAG, "discovery: mDNS answer from $host")
                        pool.execute { emit(host, STREAM_PORT) }
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) Log.i(TAG, "discovery: mDNS failed: $e")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                main.post { nsdDone = true; checkDone() }
            }
        }
        thread.isDaemon = true
        mdnsThread = thread
        thread.start()
    }

    // ---- EASSP microphones ----

    // Broadcasts DISCOVER on the attached network; microphones answer with INFO, which already
    // carries the id and name, so no probe is needed. Bound to 3950 when free, to also catch the
    // INFO announcements idle microphones broadcast there.
    private fun startEassp() {
        val thread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = try {
                    DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(Eassp.PORT)) }
                } catch (_: Exception) {
                    DatagramSocket()
                }
                network?.bindSocket(socket)
                socket.broadcast = true
                socket.soTimeout = 250
                val targets = listOfNotNull(
                    attached(context, network)?.let { InetAddress.getByName(dotted(it.broadcast)) },
                    InetAddress.getByName("255.255.255.255"),
                )
                val query = Eassp.packet(Eassp.CMD_DISCOVER, 0)
                val seen = HashSet<String>()
                val buf = ByteArray(512)
                val end = System.currentTimeMillis() + NSD_WINDOW_MS
                var nextSend = 0L
                while (!cancelled && System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        for (t in targets) {
                            try { socket.send(DatagramPacket(query, query.size, t, Eassp.PORT)) } catch (_: Exception) {}
                        }
                        nextSend = now + QUERY_INTERVAL_MS
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val info = Eassp.parseInfo(buf, p.length) ?: continue
                    val host = (p.address as? Inet4Address)?.hostAddress ?: continue
                    if (seen.add(host)) {
                        Log.i(TAG, "discovery: EASSP $host is ${info.name} (${info.id})")
                        main.post { if (!cancelled) onFound(Found(info.id, info.name, host, Eassp.PORT)) }
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) Log.i(TAG, "discovery: EASSP failed: $e")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                main.post { easspDone = true; checkDone() }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    private fun buildQuery(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))                    // header: 1 question
        for (label in listOf("_esptone", "_tcp", "local")) {
            out.write(label.length); out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf(0, 12, 0, 1))                                              // PTR, class IN
        return out.toByteArray()
    }

    // ---- subnet scan ----

    private fun startScan() {
        val hosts = scanTargets()
        if (hosts.isEmpty()) {
            scanDone = true
            return
        }
        val remaining = AtomicInteger(hosts.size)
        for (host in hosts) {
            try {
                pool.execute {
                    if (!cancelled && reachable(host, STREAM_PORT)) emit(host, STREAM_PORT)
                    if (remaining.decrementAndGet() == 0) main.post { scanDone = true; checkDone() }
                }
            } catch (_: Exception) {
                break   // pool shut down
            }
        }
    }

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS); true }
    } catch (_: Exception) {
        false
    }

    /** Every address on the attached network (real prefix length, capped at a /20) except our own. */
    private fun scanTargets(): List<String> {
        val a = attached(context, network) ?: return emptyList()
        return (a.net + 1 until a.broadcast).filter { it != a.own }.map { dotted(it) }
    }

    private class Attached(val own: Long, val net: Long, val broadcast: Long, val prefix: Int)

    companion object {
        private fun dotted(a: Long) = "${(a shr 24) and 255}.${(a shr 16) and 255}.${(a shr 8) and 255}.${a and 255}"

        private fun attached(context: Context, network: Network?): Attached? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.getLinkProperties(network ?: cm.activeNetwork) ?: return null
            val link = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            val own = ByteBuffer.wrap(link.address.address).int.toLong() and 0xFFFFFFFFL
            val prefix = link.prefixLength.coerceAtLeast(MIN_SCAN_PREFIX)
            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val net = own and mask
            return Attached(own, net, net or (mask.inv() and 0xFFFFFFFFL), prefix)
        }

        /** The network that gets scanned, as "a.b.c.d/prefix", for telling the user where we looked. */
        fun networkLabel(context: Context, network: Network?): String? =
            attached(context, network)?.let { "${dotted(it.net)}/${it.prefix}" }

        private const val TAG = "TonePlayer"
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val QUERY_INTERVAL_MS = 1500L
        const val STREAM_PORT = 1234
        private const val CONNECT_TIMEOUT_MS = 400
        private const val NSD_WINDOW_MS = 6000L
        private const val MIN_SCAN_PREFIX = 20
    }
}
