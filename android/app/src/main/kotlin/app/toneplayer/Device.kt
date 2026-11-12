package app.toneplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A transmitter. [id] is the stable identity; [host] and [port] are the last known address. */
data class Device(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /**
     * This is the transmitter that was playing when the app was last in use, so it is the one to
     * resume on launch. Set when playback starts and cleared when the user stops, rather than by a
     * checkbox: there is only ever one, and the app already knows which it is.
     */
    val resume: Boolean = false,
)

/** Remembers the devices the user has connected to, and which one auto-connects. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<Device> {
        val text = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Device(
                    o.getString("id"), o.getString("name"), o.getString("host"),
                    o.getInt("port"), o.optBoolean("resume", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Insert or replace by id. A device marked auto clears the flag on all others. */
    fun save(device: Device) {
        val list = all().filter { it.id != device.id }
            .map { if (device.resume) it.copy(resume = false) else it } + device
        write(list)
    }

    fun remove(id: String) = write(all().filter { it.id != id })

    /** Marks [id] as the one to resume, clearing the flag on every other device. */
    fun setResume(id: String, resume: Boolean) {
        write(all().map {
            if (it.id == id) it.copy(resume = resume) else if (resume) it.copy(resume = false) else it
        })
    }

    /** Keep the stored address current when a known device shows up somewhere new. */
    fun updateAddress(id: String, host: String, port: Int) {
        val list = all()
        if (list.none { it.id == id && (it.host != host || it.port != port) }) return
        write(list.map { if (it.id == id) it.copy(host = host, port = port) else it })
    }

    private fun write(list: List<Device>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                    .put("port", it.port).put("resume", it.resume)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
