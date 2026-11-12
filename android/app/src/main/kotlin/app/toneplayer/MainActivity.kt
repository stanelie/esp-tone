package app.toneplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** A transmitter in the list: remembered from earlier, and/or seen in the current scan. */
    private data class Row(var device: Device, var available: Boolean)

    private lateinit var store: DeviceStore
    private val rows = LinkedHashMap<String, Row>()
    private val adapter = RowAdapter()
    private lateinit var statusView: TextView
    private val bg = Executors.newSingleThreadExecutor()

    private var wifi: Network? = null
    private var discovery: Discovery? = null
    private var scanning = false
    private var quietScan = false                 // a periodic scan: leaves the status line alone
    private val seen = HashSet<String>()          // ids found by the scan in progress
    private lateinit var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var resumed = false

    /** Rescans every [SCAN_INTERVAL_MS] while the screen is shown, so the list stays current by itself. */
    private val periodicScan = Runnable { if (resumed) startScan(quiet = true) }

    /** Set while auto-connect is waiting for a scan to find the device at its new address. */
    private var pendingAuto: Device? = null

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** While playing, the status line shows the delay and how often audio had to be filled in. */
    private val statsTick = object : Runnable {
        override fun run() {
            val state = Player.state
            if (state !is Player.State.Playing) return
            val s = Player.jitter?.stats
            statusView.text = if (s == null) "Playing ${state.device.name}" else
                "Playing ${state.device.name}\n" +
                    "Delay ≈ ${s.bufferedMs + (if (s.realOutMs >= 0) s.realOutMs else s.outputMs)} ms · buf ${s.bufferedMs} · " +
                    "target ${s.targetMs}${if (Player.bufferMs == 0) " auto" else ""} · " +
                    "out ${if (s.realOutMs >= 0) s.realOutMs else s.outputMs}" +
                    "${if (s.realOutMs >= 0) " real" else ""}\n" +
                    "drop ${s.dropouts} · filled ${s.concealedMs} ms · " +
                    "trim ${s.trimmedMs} ms · glitch ${s.outputUnderruns}"
            ui.postDelayed(this, 1000)
        }
    }

    private val playerListener: (Player.State) -> Unit = { state ->
        ui.removeCallbacks(statsTick)
        when (state) {
            is Player.State.Playing -> statsTick.run()
            is Player.State.Reconnecting ->
                statusView.text = "Connection lost, reconnecting to ${state.device.name}…"
            is Player.State.Error -> statusView.text = "Stream error: ${state.message}"
            Player.State.Idle -> if (!scanning && pendingAuto == null) statusView.text = "Stopped"
            is Player.State.Connecting -> {}
        }
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        Player.loadSettings(this)
        wifi = Wifi.bind(this)
        store.all().forEach { rows[it.id] = Row(it, available = false) }
        buildUi()
        Player.listeners.add(playerListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Auto-connect only on a genuine launch: not on rotation, and not if already playing.
        if (!autoConnectDone && Player.state is Player.State.Idle) {
            autoConnectDone = true
            startAutoConnect()
        }
        startScan(spinner = false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!scanning) ui.postDelayed(periodicScan, SCAN_INTERVAL_MS)
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(periodicScan)
        super.onPause()
    }

    override fun onDestroy() {
        Player.listeners.remove(playerListener)
        ui.removeCallbacks(statsTick)
        ui.removeCallbacks(periodicScan)
        discovery?.stop()
        super.onDestroy()
    }

    // ---- connecting ----

    private fun connect(device: Device) {
        store.save(device)
        pendingAuto = null
        statusView.text = "Connecting to ${device.name}…"
        Player.requestPlay(this, device)
    }

    /**
     * Resumes whatever was playing when the app was last used. The flag is set when playback
     * starts and cleared when the user stops, so quitting while playing resumes, and quitting after
     * a deliberate stop stays stopped.
     */
    private fun startAutoConnect() {
        val target = store.all().firstOrNull { it.resume } ?: return
        statusView.text = "Resuming ${target.name}…"
        // Fast path: ask the last known address whether it is still this device.
        bg.execute {
            val info = Probe.info(target.host, target.port, 800)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (info != null && info.id == target.id) {
                    Log.i(TAG, "auto-connect: ${target.name} still at ${target.host}")
                    connect(target)
                } else {
                    // The address changed (or the device is off): wait for the scan to find it.
                    Log.i(TAG, "auto-connect: ${target.host} no longer answers, waiting for scan")
                    pendingAuto = target
                    statusView.text = "${target.name} moved; scanning…"
                }
            }
        }
    }

    // ---- discovery ----

    /**
     * Looks for transmitters. Rows stay as they are while it runs; when it is done, those it did not
     * find are marked "Not found". A [quiet] (periodic) scan doesn't touch the status line, and is
     * skipped while a stream is playing or connecting: probing ~500 addresses would compete with the audio.
     */
    private fun startScan(quiet: Boolean = false, spinner: Boolean = !quiet) {
        ui.removeCallbacks(periodicScan)
        if (quiet && (Player.state !is Player.State.Idle && Player.state !is Player.State.Error)) {
            ui.postDelayed(periodicScan, SCAN_INTERVAL_MS)
            return
        }
        discovery?.stop()
        seen.clear()
        scanning = true
        quietScan = quiet
        if (!quiet) {
            // The spinner means "you asked for this"; on the very first scan it just looks like
            // the list is stuck mid-pull-to-refresh before the user has touched anything.
            if (spinner) refresh.isRefreshing = true
            if (pendingAuto == null) statusView.text = "Scanning…"
        }
        discovery = Discovery(this, wifi, ::onFound, ::onScanDone).also { it.start() }
    }

    private fun onFound(f: Found) {
        val previous = rows[f.id]?.device
        val device = Device(f.id, f.name, f.host, f.port, previous?.resume ?: false)
        rows[f.id] = Row(device, available = true)
        seen += f.id
        // Keep the remembered address current, so the next launch connects directly.
        store.updateAddress(f.id, f.host, f.port)
        adapter.notifyDataSetChanged()

        val waiting = pendingAuto
        if (waiting != null && waiting.id == f.id) {
            Log.i(TAG, "auto-connect: found ${f.name} at new address ${f.host}")
            connect(device)
        } else if (scanning && !quietScan && pendingAuto == null && Player.state is Player.State.Idle) {
            statusView.text = "Found ${seen.size} transmitter(s)…"
        }
    }

    private fun onScanDone() {
        scanning = false
        refresh.isRefreshing = false
        if (resumed) ui.postDelayed(periodicScan, SCAN_INTERVAL_MS)
        val playing = Player.state.deviceId()

        /* The sweep only covers our own subnet, so "not in this scan" does not mean gone: a
         * transmitter reached over a VPN or any routed network is never in [seen] while being
         * perfectly reachable. Marking those unavailable is what made an Add-IP transmitter go
         * grey the moment the app was reopened, and then refuse to play until it was deleted and
         * re-added. So give each one a direct probe at its remembered address before calling it
         * gone. There are only ever a handful of remembered devices, so this is one UDP round trip
         * each. The one playing is left alone - it may simply not answer probes while streaming. */
        // Every row the sweep missed, not just the ones currently shown as available: a routed
        // transmitter that went grey has to be able to come back on its own.
        val unseen = rows.values
            .filter { it.device.id !in seen && it.device.id != playing }
            .map { it.device }
        if (unseen.isEmpty()) {
            finishScan()
            return
        }
        bg.execute {
            // Generous timeout: a VPN round trip is a lot slower than a LAN one.
            val checked = unseen.map { it to (Probe.info(it.host, it.port, 1500)?.id == it.id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checked.forEach { (d, reachable) ->
                    rows[d.id]?.available = reachable
                    if (reachable) seen += d.id
                }
                finishScan()
            }
        }
    }

    private fun finishScan() {
        rows.values.forEach { if (it.device.id !in seen && it.device.id != Player.state.deviceId()) it.available = false }
        adapter.notifyDataSetChanged()
        val waiting = pendingAuto
        if (waiting != null) {
            pendingAuto = null
            statusView.text = "Could not find ${waiting.name}. Is it powered on?"
        } else if (Player.state is Player.State.Idle) {
            val n = rows.values.count { it.available }
            statusView.text = if (n == 0) {
                val where = Discovery.networkLabel(this, wifi)?.let { " on $it" } ?: ""
                "No transmitters found$where. For another network, use Add IP."
            } else {
                "$n transmitter(s) found"
            }
        }
    }

    /**
     * Discovery cannot cross subnets (mDNS does not route and the scan covers only our own
     * network), so a transmitter on a routed network can be added by address.
     */
    private fun promptForAddress() {
        val input = EditText(this).apply {
            hint = "10.8.1.168 or 10.8.1.168:1234"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Add transmitter by address")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> addByAddress(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addByAddress(text: String) {
        val host = text.substringBefore(':').trim()
        val given = text.substringAfter(':', "").trim().toIntOrNull()
        if (host.isEmpty()) return
        statusView.text = "Checking $host…"
        bg.execute {
            // Without a port, try the tone transmitter's HTTP port, then an EASSP microphone.
            var port = given ?: Discovery.STREAM_PORT
            var info = Probe.info(host, port, 2000)
            if (info == null && given == null) {
                port = Eassp.PORT
                info = Probe.info(host, port, 2000)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (info == null) {
                    statusView.text = "No transmitter answered at $host:$port"
                } else {
                    // An entry at this very address under another id is the same unit from before a
                    // firmware change that gave it a real id: replace it, keeping its Auto flag.
                    val stale = store.all().filter { it.host == host && it.port == port && it.id != info.id }
                    stale.forEach { store.remove(it.id); rows.remove(it.id) }
                    val resume = rows[info.id]?.device?.resume ?: stale.any { it.resume }
                    val device = Device(info.id, info.name, host, port, resume)
                    store.save(device)   // remembered, so it also survives restarts
                    onFound(Found(info.id, info.name, host, port))
                    statusView.text = "Added ${info.name}"
                }
            }
        }
    }

    // ---- deleting ----

    /**
     * Deletes a transmitter: forgotten from the store and taken out of the list. Discovery is free
     * to find it again on the next scan, and that is wanted - this is a diagnostic tool, so a
     * transmitter reappearing is information, and one that stayed hidden would be a fault.
     *
     * What the old version got wrong was keeping the row whenever the device happened to be
     * switched on, so the delete looked like it had done nothing at all.
     */
    private fun deleteDevice(row: Row) {
        val d = row.device
        AlertDialog.Builder(this)
            .setTitle("Delete ${d.name}?")
            .setMessage("${d.host}:${d.port}  ·  id ${d.id}")
            .setPositiveButton("Delete") { _, _ ->
                if (Player.state.deviceId() == d.id) Player.requestStop(this)
                store.remove(d.id)
                rows.remove(d.id)
                seen.remove(d.id)
                adapter.notifyDataSetChanged()
                statusView.text = "Deleted ${d.name}"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Tapping the name goes straight to the device's own settings (no long-press menu). */
    private fun openSettings(d: Device) {
        if (d.port == Eassp.PORT) {
            startActivity(
                Intent(this, DeviceSettingsActivity::class.java)
                    .putExtra("host", d.host).putExtra("name", d.name)
            )
        } else {
            // Only EASSP microphones carry a settings table; show what we know instead.
            AlertDialog.Builder(this)
                .setTitle(d.name)
                .setMessage("${d.host}:${d.port}\nid ${d.id}\n\nThis transmitter has no remote settings.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---- UI ----

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusView = TextView(this).apply {
            // Fixed at three lines: the band used to be one line when idle and three or four while
            // playing, which shoved the list up and down. Compact wording keeps each line to one
            // row at this size.
            textSize = 13f
            minLines = 3
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(4), dp(2))
        }

        /* The banner carries the only action that is not on a device row: adding one by address.
         * Stop lives on the playing row, and the buffer is a per-playback setting, so it sits with
         * the rest of them in the device's settings screen - which leaves no button bar at all. */
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        banner.addView(
            statusView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        banner.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Add a transmitter by address"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { promptForAddress() }
        })
        root.addView(banner)

        // Pull down on the list to rescan now (it also rescans by itself every 15 s).
        refresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setOnRefreshListener { startScan() }
        }
        root.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Every action lives in the row itself: the name opens the device's settings, play/stop
        // is on the right, and a sideways swipe deletes. No long-press menu, no trash button.
        refresh.addView(ListView(this).apply { adapter = this@MainActivity.adapter })

        setContentView(root)
    }

    private fun Player.State.deviceId(): String? = when (this) {
        is Player.State.Connecting -> device.id
        is Player.State.Playing -> device.id
        is Player.State.Reconnecting -> device.id
        is Player.State.Error -> null
        Player.State.Idle -> null
    }

    /**
     * A list row that can be swiped sideways to delete, now that the trash button is gone.
     *
     * Done by intercepting the touch rather than with a listener on the row, because the row's
     * children (the name, the play button) are clickable and would swallow the gesture before any
     * listener on the parent ever saw it. The intercept only claims the gesture once it is clearly
     * horizontal, so vertical scrolling of the list still belongs to the ListView.
     */
    private inner class SwipeRow(context: android.content.Context) : LinearLayout(context) {
        var onDismiss: (() -> Unit)? = null
        /* Screen coordinates, deliberately: translating the view moves ev.x's own frame of
         * reference with it, so measuring the drag from ev.x feeds back on itself and the row
         * shudders instead of following the finger (measured: translationX oscillating
         * 111, 25, 157, 66, 187, 94...). rawX is unaffected by the translation. */
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    // Clearly sideways, not a scroll that happens to wobble.
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; dragging = false
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (!dragging && Math.abs(dx) > slop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        translationX = dx
                        val w = if (width > 0) width else 1
                        alpha = (1f - Math.abs(dx) / w).coerceIn(0.25f, 1f)
                    }
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = translationX
                    val far = width > 0 && Math.abs(dx) > width / 3f
                    if (dragging && far && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat())
                            .alpha(0f).setDuration(150)
                            .withEndAction { settle(); onDismiss?.invoke() }.start()
                    } else {
                        animate().translationX(0f).alpha(1f).setDuration(150).start()
                    }
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        /** Rows are recycled, so a dismissed or half-dragged one must not come back shifted. */
        fun settle() {
            animate().cancel()
            translationX = 0f
            alpha = 1f
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        private fun sorted() = rows.values.sortedWith(
            compareByDescending<Row> { it.available }.thenBy { it.device.name }
        )

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = sorted()[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)
            val d = row.device
            val view = convertView as? SwipeRow ?: SwipeRow(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                /* 0 */ addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            textSize = 17f
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(TextView(this@MainActivity).apply { textSize = 13f })
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                /* 1 */ addView(rowIcon(android.R.drawable.ic_media_play))
            }

            val texts = view.getChildAt(0) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            val subtitle = texts.getChildAt(1) as TextView
            val playStop = view.getChildAt(1) as ImageButton

            view.settle()                       // recycled rows must start square
            view.onDismiss = { deleteDevice(row) }

            val state = Player.state
            val active = state.deviceId() == d.id
            val status = when {
                state is Player.State.Playing && active -> "Playing"
                state is Player.State.Connecting && active -> "Connecting…"
                state is Player.State.Reconnecting && active -> "Reconnecting…"
                row.available -> "Available"
                else -> "Not found"
            }

            title.text = d.name
            // Green when the transmitter is answering on the network, grey when it is not.
            title.setTextColor(if (row.available) COLOUR_ON_NETWORK else COLOUR_OFF_NETWORK)
            texts.setOnClickListener { openSettings(d) }
            subtitle.text = "$status  ·  ${d.host}:${d.port}  ·  id ${d.id}"
            subtitle.setTextColor(COLOUR_OFF_NETWORK)

            // android.R has no stop icon, and a pause glyph would wrongly suggest "resumable",
            // so the stop state draws a plain square.
            if (active) playStop.setImageDrawable(stopSquare())
            else playStop.setImageResource(android.R.drawable.ic_media_play)
            playStop.contentDescription = if (active) "Stop ${d.name}" else "Play ${d.name}"
            playStop.setOnClickListener {
                /* Try regardless of what the scan thinks. Discovery cannot cross subnets, so a
                 * grey row may still be perfectly reachable - refusing to even attempt it was the
                 * other half of the VPN fault. If it really is unreachable the player says so. */
                if (active) Player.requestStop(this@MainActivity) else connect(d)
            }
            return view
        }

        private fun stopSquare(): android.graphics.drawable.Drawable {
            val side = dp(15)
            return android.graphics.drawable.ShapeDrawable(
                android.graphics.drawable.shapes.RectShape()
            ).apply {
                paint.color = Color.parseColor("#B00020")
                intrinsicWidth = side
                intrinsicHeight = side
            }
        }

        private fun rowIcon(resId: Int) = ImageButton(this@MainActivity).apply {
            setImageResource(resId)
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    companion object {
        private const val TAG = "TonePlayer"
        private const val SCAN_INTERVAL_MS = 15_000L   // between the end of one scan and the next
        private val COLOUR_ON_NETWORK = Color.parseColor("#1B7F3B")
        private val COLOUR_OFF_NETWORK = Color.parseColor("#777777")
        private var autoConnectDone = false
    }
}
