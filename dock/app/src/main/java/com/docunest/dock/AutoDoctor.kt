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
    /** Not 7: that's the Wi-Fi doctor's "hotspot not visible" notification. */
    private const val NOTE_ID = 17

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

    fun enabled(context: Context) = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getBoolean(PREF, true)

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

    /** "☀ Clear sky": no back-off, no waiting — the next tick starts a run. */
    fun clearSkyHint(context: Context) {
        app = context.applicationContext
        nextAllowedAt = 0L
        main.post { runCatching { step() } }
    }

    private fun step() {
        if (on && !GpsDoctor.isWatching) GpsDoctor.watch(app, "auto") // permission may have come since
        if (!GpsDoctor.isWatching) {
            call = null
            return
        }
        // Parked position shown / real GPS being checked: the chip is paused on purpose.
        if (ParkedPosition.holdsGps) {
            call = SkyJudge.Call(SkyJudge.Mode.COVERED, ParkedPosition.logic?.why ?: "Showing the parked position")
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
                skyKnown = GpsDoctor.skyKnown(),
            ),
        )
        // The owner says the sky is clear: whatever the judge thinks (tunnel, car park), fix now.
        val hinted = ClearSky.active
        if (hinted && fresh) ClearSky.clear("GPS is back")
        val use = if (hinted && !fresh) SkyJudge.Call(SkyJudge.Mode.BROKEN, "You said the sky is clear — fixing now", true, "clearsky") else c
        call = use
        DebugLog.change("AUTO", "judge", use.mode.name, "${use.why} · fix ${if (fresh) "±${f!!.accuracy.toInt()} m" else "none"} · strong ${GpsDoctor.strongSats} · " +
            "sky known ${GpsDoctor.skyKnown()} · driving ${Sensors.driving()} · ${GpsDoctor.perSystem}")
        lostNotice(use, fresh)
        j.selfRecoveredAt?.let {
            j.selfRecoveredAt = null
            FixHistory.addDeadZone(app, it)
        }
        if (on && use.fixNow && !GpsDoctor.running && (now >= nextAllowedAt || hinted)) {
            DebugLog.log("AUTO", "starting a fix run: ${use.why}")
            notify("GPS lost — auto doctor fixing", use.why, ongoing = true)
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
            // Stopped by hand: a short pause only (30 min held the auto doctor back while the GPS stayed broken).
            "stopped" -> nextAllowedAt = now + 3 * 60_000L
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
        // Ours survives the cold start (Android's own last-known is wiped with it).
        ParkedPosition.saved(app)?.let { return SkyJudge.Fix(it.lat, it.lng, 0f) }
        if (!Locator.hasPermission(app)) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return null
        return SkyJudge.Fix(l.latitude, l.longitude, 0f) // parked: we're starting up
    }

    private var lostShown = false

    /** Waiting out a "tunnel" / "car park" for 20 s: say so, with the ☀ button to skip the wait. */
    private fun lostNotice(c: SkyJudge.Call, fresh: Boolean) {
        val waiting = !fresh && !GpsDoctor.running && c.mode in setOf(SkyJudge.Mode.TUNNEL, SkyJudge.Mode.COVERED, SkyJudge.Mode.WAITING, SkyJudge.Mode.DEAD_ZONE)
        if (waiting && !lostShown && SystemClock.elapsedRealtime() - (GpsDoctor.fixAt.takeIf { it > 0 } ?: 0L) > 20_000) {
            lostShown = true
            notify("No GPS — ${c.why}", "Open sky? Tap ☀ Clear sky and the doctor fixes it now instead of waiting.", clearSkyAction = true)
        } else if (!waiting && lostShown) {
            lostShown = false
            if (fresh) notifyCancel()
        }
    }

    private fun notify(title: String, text: String, ongoing: Boolean = false, timeoutMs: Long = 0, clearSkyAction: Boolean = false) = runCatching {
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
        if (clearSkyAction) {
            val pi = PendingIntent.getService(app, 8, Intent(app, DockService::class.java).setAction(DockService.ACTION_CLEAR_SKY), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "☀ Clear sky — fix now", pi).build())
        }
        nm.notify(NOTE_ID, b.build())
    }

    private fun notifyCancel() = runCatching {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
    }
}
