package com.docunest.dock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Auto doctor: keeps the GPS chip listened to while the dock runs, asks
 * [SkyJudge] once a second whether the GPS is broken or the sky is just
 * blocked (tunnel, underground car park, known no-GPS spot), and starts
 * [GpsDoctor.fix] on its own as soon as it's really broken.
 *
 * Doesn't nag: after a run that didn't help it waits 10 → 20 → 40 → 60 min
 * before the next; after Stop, 30 min. Places where the GPS was lost and
 * came back by itself after 2 min+ under cover are remembered as dead zones.
 *
 * The judge also runs (without fixing) whenever the doctor screen is open,
 * so the screen and the 📡 chip can say "tunnel — waiting".
 */
object AutoDoctor {
    private const val PREF = "autoGpsDoctor"
    private const val CHANNEL = "gps-doctor"
    private const val NOTE_ID = 7

    @Volatile var call: SkyJudge.Call? = null
        private set
    /** elapsedRealtime before which no auto run starts. */
    @Volatile var nextAllowedAt = 0L
        private set
    @Volatile var lastResult = ""
        private set
    private var failures = 0
    private var started = false
    private var on = false
    private lateinit var app: Context
    private var judge: SkyJudge? = null
    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
            main.postDelayed(this, 1000)
        }
    }

    fun enabled(context: Context) = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putBoolean(PREF, value).apply()
        start(context)
    }

    /** From the dock service and the doctor screen (main thread, idempotent). */
    fun start(context: Context) {
        app = context.applicationContext
        if (!started) {
            started = true
            GpsDoctor.onRunEnd = { result -> main.post { runEnded(result) } }
            main.post(tick)
        }
        val want = enabled(app)
        if (want && !on) {
            GpsDoctor.watch(app, "auto")
            Sensors.start(app, "auto")
        } else if (!want && on) {
            GpsDoctor.unwatch(app, "auto")
            Sensors.stop(app, "auto")
            notifyCancel()
        }
        on = want
    }

    val isOn: Boolean get() = on

    /** One line for the doctor screen. */
    fun status(): String {
        val c = call
        val now = SystemClock.elapsedRealtime()
        val base = when {
            !on -> "Off — fixes only when you press Fix GPS."
            GpsDoctor.running && GpsDoctor.auto -> "Fixing now…"
            c == null -> "Watching the GPS…"
            else -> c.why + if (c.fixNow && nextAllowedAt > now) " — next try in ${GpsDoctor.duration(nextAllowedAt - now)}" else ""
        }
        return if (lastResult.isNotEmpty() && on) "$base\nLast auto run: $lastResult" else base
    }

    private fun step() {
        if (on && !GpsDoctor.isWatching) GpsDoctor.watch(app, "auto") // permission may have come since
        if (!GpsDoctor.isWatching) {
            call = null
            return
        }
        val j = judge ?: SkyJudge { FixHistory.deadZones(app) }.also { judge = it; it.seed(lastKnown()) }
        val now = SystemClock.elapsedRealtime()
        val f = GpsDoctor.fix
        val fresh = f != null && now - GpsDoctor.fixAt < 5_000 && GpsDoctor.hasFix()
        val c = j.judge(
            SkyJudge.Reading(
                t = now,
                settingsOk = GpsDoctor.settingsOk(app),
                // The first 20 s after asking, a quiet chip is still waking up.
                chipTalks = GpsDoctor.chipTalks() || now - GpsDoctor.watchingSince < 20_000,
                fix = if (fresh) SkyJudge.Fix(f!!.latitude, f.longitude, if (f.hasSpeed()) f.speed else null) else null,
                strongSats = GpsDoctor.strongSats,
                driving = Sensors.driving(),
                fixing = GpsDoctor.running,
            ),
        )
        call = c
        j.selfRecoveredAt?.let {
            j.selfRecoveredAt = null
            FixHistory.addDeadZone(app, it)
        }
        if (on && c.fixNow && !GpsDoctor.running && now >= nextAllowedAt) {
            notify("GPS lost — auto doctor fixing", c.why, ongoing = true)
            GpsDoctor.fix(app, byAuto = true)
        }
    }

    /** After any run (manual too, so the auto doctor doesn't jump straight back in). */
    private fun runEnded(result: String) {
        val now = SystemClock.elapsedRealtime()
        val wasAuto = GpsDoctor.auto
        when (result) {
            "fixed" -> {
                failures = 0
                nextAllowedAt = now + 2 * 60_000L
            }
            "paused" -> nextAllowedAt = now + 30_000L // the judge holds while the sky stays blocked
            "stopped" -> nextAllowedAt = now + 30 * 60_000L
            else -> {
                failures++
                val minutes = (10L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(60L)
                nextAllowedAt = now + minutes * 60_000L
            }
        }
        if (!wasAuto) return
        lastResult = "${GpsDoctor.summary} (${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())})"
        when (result) {
            "fixed" -> notify("GPS is back", GpsDoctor.summary, timeoutMs = 20_000)
            "paused" -> notifyCancel()
            else -> notify("GPS still not working", GpsDoctor.summary + " Next auto try in ${GpsDoctor.duration(nextAllowedAt - now)}.")
        }
    }

    @SuppressLint("MissingPermission") // checked by Locator.hasPermission()
    private fun lastKnown(): SkyJudge.Fix? {
        if (!Locator.hasPermission(app)) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return null
        return SkyJudge.Fix(l.latitude, l.longitude, 0f) // parked: we're starting up
    }

    private fun notify(title: String, text: String, ongoing: Boolean = false, timeoutMs: Long = 0) = runCatching {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "GPS auto doctor", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            app, 7, Intent(app, GpsDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        nm.notify(NOTE_ID, b.build())
    }

    private fun notifyCancel() = runCatching {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
    }
}
