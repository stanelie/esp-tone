package app.toneplayer

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks a host what it is: GET /info for an esp-tone transmitter (a one-line JSON description), or
 * an EASSP DISCOVER when [port] is [Eassp.PORT].
 */
object Probe {
    data class Info(val id: String, val name: String)

    fun info(host: String, port: Int, timeoutMs: Int = 1000): Info? {
        if (port == Eassp.PORT) return Eassp.probe(host, timeoutMs)?.let { Info(it.id, it.name) }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://$host:$port/info").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            // Bounded read: a transmitter on older firmware streams audio forever.
            val buf = ByteArray(1024)
            var len = 0
            conn.inputStream.use { input ->
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
            }
            val json = JSONObject(String(buf, 0, len).trim().lines().last())
            if (!json.has("rate")) return null
            val name = json.optString("name", host)
            Info(json.optString("id", "").ifEmpty { name }, name)
        } catch (e: Exception) {
            Log.d("TonePlayer", "probe $host:$port failed: $e")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
