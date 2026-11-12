package app.toneplayer

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Device settings, with the UI built entirely from what the device reports
 * ([DeviceSettings.fetch]) — allowed values, whether each setting is applicable, and when it takes
 * effect. Nothing about the settings is hardcoded here, so a new device setting shows up with no
 * app change and the app can never offer a value the firmware would refuse.
 *
 * Playback settings (gain, buffer) are separate and local: they are applied by this app, not the
 * device, so they are listed apart and take effect at once.
 */
class DeviceSettingsActivity : Activity() {

    private lateinit var host: String
    private lateinit var status: TextView
    private lateinit var deviceSection: LinearLayout
    private val bg = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra("host") ?: return finish()
        title = "${intent.getStringExtra("name") ?: "Device"} settings"
        buildUi()
        load()
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private lateinit var root: LinearLayout

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(2))
        alpha = 0.7f
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(heading("DEVICE"))
        deviceSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceSection)

        root.addView(heading("PLAYBACK  (this phone, applied immediately)"))
        val gains = listOf(1, 2, 4, 8, 16, 32, 64, 128)
        root.addView(localRow("Gain", gains, Player.gainX, gains.map { db(it) }) { g ->
            Player.saveGainX(this, g)
            status.text = "Gain ${db(g)}" + if (g >= 64) " — back off if it distorts" else ""
        })
        val buffers = listOf(0, 10, 20, 30, 40, 60, 80, 120, 200, 300, 500, 750, 1000, 1500, 2000)
        root.addView(localRow("Jitter buffer", buffers, Player.bufferMs, buffers.map { ms(it) }) { b ->
            Player.saveBufferMs(this, b)
            status.text = when (b) {
                0 -> "Buffer automatic — follows the worst gaps on the link"
                else -> "Buffer ${ms(b)} — lower is less delay, higher rides out more jitter"
            }
        })

        val lowCuts = listOf(0, 50, 80, 120, 160, 250, 400, 600)
        root.addView(localRow("Low cut", lowCuts, Player.lowCutHz, lowCuts.map { hz(it) }) { f ->
            Player.saveLowCut(this, f)
            status.text = if (f == 0) "Low cut off" else "Low cut ${hz(f)}, 12 dB/octave"
        })
        val highCuts = listOf(0, 2000, 3000, 4000, 6000, 8000, 10000, 12000, 15000)
        root.addView(localRow("High cut", highCuts, Player.highCutHz, highCuts.map { hz(it) }) { f ->
            Player.saveHighCut(this, f)
            status.text = if (f == 0) "High cut off" else "High cut ${hz(f)}, 12 dB/octave"
        })

        root.addView(localRow(
            "Limiter (AGC)", listOf(0, 1), if (Player.agcOn) 1 else 0, listOf("Off", "On"),
        ) { on ->
            Player.saveAgcOn(this, on == 1)
            status.text = if (on == 1)
                "Limiter on — ducks before a peak overflows, recovers in ~0.15 s, adds 2 ms delay"
            else
                "Limiter off — gain is a plain multiplier and loud sounds clip"
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(Button(this).apply {
            text = "Reload"
            setOnClickListener { load() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** A buffer target for the spinner; 0 is the automatic mode. */
    private fun ms(v: Int): String = when {
        v == 0 -> "Auto"
        v < 1000 -> "$v ms"
        else -> "%.1f s".format(v / 1000.0)
    }

    /** A cutoff for the spinner; 0 means the filter is bypassed. */
    private fun hz(f: Int): String = when {
        f == 0 -> "Off"
        f >= 1000 -> "${f / 1000} kHz"
        else -> "$f Hz"
    }

    /** A plain multiplier as decibels, which is how gain is usually read. x32 -> "+30 dB". */
    private fun db(multiplier: Int): String {
        val v = Math.round(20.0 * Math.log10(multiplier.toDouble())).toInt()
        return if (v == 0) "0 dB" else "+$v dB"
    }

    /** A row backed by a local app preference rather than by the device. */
    private fun localRow(
        label: String,
        values: List<Int>,
        current: Int,
        texts: List<String> = values.map { "x$it" },
        onPick: (Int) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(TextView(this).apply { text = label; textSize = 15f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@DeviceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, texts)
            val i = values.indexOf(current)
            if (i >= 0) setSelection(i)
            // setSelection() above fires this too; ignore that first call so merely opening the
            // screen does not rewrite the preference.
            var primed = false
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (!primed) { primed = true; return }
                    onPick(values[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        return row
    }

    private fun load() {
        status.text = "Reading settings from $host…"
        deviceSection.removeAllViews()
        bg.execute {
            val items = DeviceSettings.fetch(host)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (items == null) {
                    status.text = "$host did not answer. It may be running firmware without the " +
                        "settings protocol, or be offline."
                    return@runOnUiThread
                }
                if (items.isEmpty()) {
                    status.text = "$host reports no adjustable settings."
                    return@runOnUiThread
                }
                items.forEach { deviceSection.addView(deviceRow(it)) }
                status.text = "Settings read from $host."
            }
        }
    }

    /** Built from the descriptor: allowed values, applicability and when it applies all come from the device. */
    private fun deviceRow(item: DeviceSettings.Item): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply { text = item.label; textSize = 15f }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))

        val values = if (item.isEnum) item.values else emptyList()
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@DeviceSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, values.map { item.textFor(it) })
            val i = values.indexOfFirst { it == item.current }
            if (i >= 0) setSelection(i)
            isEnabled = item.applicable && item.isEnum
            alpha = if (isEnabled) 1f else 0.4f
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val picked = values[pos]
                    if (picked == item.current || busy) return
                    apply(item, picked)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        label.alpha = sp.alpha
        row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        wrap.addView(row)

        val note = buildString {
            append(item.whenText())
            if (!item.applicable) append(" · not applicable right now")
            if (!item.isEnum && item.values.size == 2)
                append(" · range ${item.values[0]}…${item.values[1]}")
        }
        if (note.isNotBlank()) {
            wrap.addView(TextView(this).apply {
                text = note
                textSize = 12f
                alpha = 0.6f
            })
        }
        return wrap
    }

    private fun apply(item: DeviceSettings.Item, value: Long) {
        busy = true
        status.text = "Setting ${item.label} to $value…"
        bg.execute {
            val r = DeviceSettings.set(host, item.id, value)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                if (r == null) {
                    status.text = "No answer from $host; the setting may not have been applied."
                } else if (r.first != 0) {
                    // The device refused it. Re-read rather than leave the UI showing a value the
                    // device never accepted.
                    status.text = "${item.label}: ${DeviceSettings.statusText(r.first)}."
                }
                load()
            }
        }
    }
}
