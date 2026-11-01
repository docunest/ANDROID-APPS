package com.docunest.camper

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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * The always-on part: a foreground service (Android's protected category) that polls the devices every 30 s,
 * applies the rules and serves the UI. Independent of the kiosk screen: if the activity dies, control continues.
 */
class CamperService : Service() {
    companion object {
        private const val CHANNEL = "camper"
        @Volatile var running = false

        fun start(ctx: Context) {
            val i = Intent(ctx, CamperService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var controller: Controller? = null
    private var server: HttpServer? = null
    private var remote: RemoteSync? = null
    private var loop: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()
        if (!running) startControl()
        return START_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camper control", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Camper control running")
            .setContentText("Floor heating and AC are being managed")
            .setSmallIcon(R.drawable.ic_camper)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun startControl() {
        running = true
        // Stay awake and keep Wi-Fi + broadcast reception up: the screen may be dimmed for hours.
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camper:control").apply { acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "camper:wifi").apply { acquire() }
        multicast = wm.createMulticastLock("camper:discovery").apply { setReferenceCounted(false); acquire() }

        val c = Controller(applicationContext)
        controller = c
        server = HttpServer(assets, c).also { runCatching { it.start() }.onFailure { e -> Log.e(Controller.TAG, "server", e) } }
        val cfg = Config.load(applicationContext)
        val url = cfg["CAMPER_REMOTE_URL"]; val token = cfg["CAMPER_REMOTE_TOKEN"]
        if (url != null && token != null) remote = RemoteSync(c, url, token).also { it.start() }
        loop = Thread({
            while (running) {
                runCatching { c.poll() }.onFailure { Log.e(Controller.TAG, "poll", it) }
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            }
        }, "camper-control").apply { start() }
    }

    override fun onDestroy() {
        running = false
        loop?.interrupt()
        server?.stop()
        remote?.stop()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        runCatching { multicast?.release() }
        super.onDestroy()
    }
}
