package app.toneplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the stream playing with the screen off by running as a foreground (media) service, and
 * brings it back when it is interrupted.
 *
 * Reconnecting: once a stream has played, any interruption is retried until the user presses
 * Stop. Each attempt first asks the last known address whether it is still the same device
 * (by id); if not, it scans for the device and continues from its new address.
 */
class PlaybackService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: DeviceStore
    private var wifiLock: WifiManager.WifiLock? = null

    /** The device the user wants to hear. Cleared by Stop; while set, interruptions are retried. */
    private var target: Device? = null
    private var wasPlaying = false
    private var attempt = 0
    private var discovery: Discovery? = null
    private val retry = Runnable { reconnect() }

    private val listener: (Player.State) -> Unit = { state ->
        when (state) {
            is Player.State.Idle -> shutdown()
            is Player.State.Connecting -> notify(state.device, "Connecting…")
            is Player.State.Reconnecting -> notify(state.device, "Reconnecting…")
            is Player.State.Playing -> {
                wasPlaying = true
                attempt = 0
                notify(state.device, "Playing")
            }
            is Player.State.Error ->
                // A first connection that never worked is reported and abandoned; an
                // interruption of a stream that was playing is retried.
                if (target != null && wasPlaying) scheduleReconnect() else shutdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = DeviceStore(this)
        Player.loadSettings(this)
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Player.listeners.add(listener)
    }

    override fun onDestroy() {
        Player.listeners.remove(listener)
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val device = Device(
                    intent.getStringExtra("id") ?: return START_NOT_STICKY,
                    intent.getStringExtra("name") ?: "Transmitter",
                    intent.getStringExtra("host") ?: return START_NOT_STICKY,
                    intent.getIntExtra("port", 1234),
                )
                main.removeCallbacks(retry)
                discovery?.stop()
                discovery = null
                target = device
                wasPlaying = false
                attempt = 0
                acquireWifiLock()
                /* Remember what is playing, so quitting while it plays resumes on next launch.
                 * Set here rather than in the UI because the notification's buttons reach the
                 * service directly and never go through Player.requestPlay/requestStop. */
                DeviceStore(this).setResume(device.id, true)
                startForegroundCompat(build(device, "Connecting…"))
                Player.start(device)
            }
            else -> {   // ACTION_STOP, or a restart with no intent
                // A deliberate stop: nothing to resume next launch.
                DeviceStore(this).let { st -> st.all().forEach { st.setResume(it.id, false) } }
                // A foreground start is still owed a startForeground() call.
                startForegroundCompat(build(null, "Stopping"))
                Player.stop()   // publishes Idle, which runs shutdown()
            }
        }
        return START_NOT_STICKY
    }

    // ---- reconnecting ----

    private fun scheduleReconnect() {
        val t = target ?: return
        discovery?.stop()
        discovery = null
        // Posted so listeners see the Error that triggered this before the Reconnecting state.
        main.post { if (target != null) Player.markReconnecting(t) }
        val delay = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        attempt++
        main.postDelayed(retry, delay)
    }

    private fun reconnect() {
        val t = target ?: return
        Thread {
            val info = Probe.info(t.host, t.port, 1000)
            main.post {
                if (target?.id != t.id) return@post   // stopped or replaced meanwhile
                if (info != null && info.id == t.id) Player.start(t) else rediscover()
            }
        }.start()
    }

    /** The saved address no longer answers: look for the same device (by id) elsewhere. */
    private fun rediscover() {
        discovery?.stop()
        discovery = Discovery(
            this, Wifi.bind(this),
            onFound = { f ->
                val current = target
                if (current != null && f.id == current.id) {
                    discovery?.stop()
                    discovery = null
                    val moved = current.copy(host = f.host, port = f.port)
                    target = moved
                    store.updateAddress(f.id, f.host, f.port)
                    Player.start(moved)
                }
            },
            onDone = {
                discovery = null
                scheduleReconnect()   // not found this time; back off and try again
            },
        ).also { it.start() }
    }

    private fun cleanup() {
        main.removeCallbacks(retry)
        discovery?.stop()
        discovery = null
        target = null
        wasPlaying = false
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun shutdown() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Keep the Wi-Fi radio at full performance while listening, so the screen going off doesn't
     * throttle it - and so the AP does not buffer our packets and release them in bursts, which
     * shows up directly as jitter-buffer depth.
     *
     * WIFI_MODE_FULL_HIGH_PERF is the right mode up to API 28 and is deprecated from API 29, where
     * WIFI_MODE_FULL_LOW_LATENCY replaces it (it also asks the firmware for a low-latency profile).
     * Note LOW_LATENCY only applies while the screen is on and this app is in the foreground.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode =
            if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "toneplayer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---- notification ----

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(device: Device, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(device, text))
    }

    private fun build(device: Device?, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(device?.name ?: "Tone Player")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_PLAY = "app.toneplayer.PLAY"
        const val ACTION_STOP = "app.toneplayer.STOP"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private val RETRY_DELAYS_MS = longArrayOf(500, 1000, 2000, 4000, 8000)   // then 8 s, until Stop
    }
}
