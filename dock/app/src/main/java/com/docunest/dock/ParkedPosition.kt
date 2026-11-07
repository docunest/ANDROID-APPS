package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * "Where we parked" for the first minutes after a cold start. The head
 * unit's GPS starts from nothing at every ignition; in a garage it then
 * has no position at all and Waze won't plan a route.
 *
 *  - Saves the last good real GPS fix (every 15 s while fixes come; the
 *    last one before the garage is where we are).
 *  - Only right after boot, before the van moves ([ParkedLogic]) — never
 *    mid-travel, not even when the dock restarts (self-update) in a tunnel.
 *  - After a start with no sky, hands that position to
 *    every app as the GPS position via Android's mock location providers
 *    (gps, network, fused), once a second. Needs the "mock location app"
 *    permission: granted with root (`appops … android:mock_location
 *    allow`), else Developer options → Select mock location app → Dock.
 *  - While the mock is on, Android pauses the real GPS chip, so it hands
 *    back as soon as the van moves, or when a short real-GPS check every
 *    4 min finds sky.
 *
 * Mock fixes are flagged by Android and ignored by the GPS doctor, glitch
 * recorder and the saving here ([isMockFix]).
 */
object ParkedPosition {
    private const val PREF_ON = "restoreParked"
    private const val SAVE_EVERY_MS = 15_000L
    private const val MAX_SAVE_ACCURACY_M = 60f

    data class Saved(val lat: Double, val lng: Double, val acc: Float, val alt: Double?, val bearing: Float?, val at: Long)

    @Volatile var logic: ParkedLogic? = null
        private set
    /** Our mock providers are installed right now. */
    @Volatile var mocking = false
        private set
    @Volatile var problem = ""
        private set
    private var lastSave = 0L
    private var started = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
            main.postDelayed(this, 1000)
        }
    }

    /** The real GPS is paused or being checked by us: the auto doctor must not judge or fix meanwhile. */
    val holdsGps: Boolean
        get() = logic?.state.let { it == ParkedLogic.State.RESTORED || it == ParkedLogic.State.PROBING }

    fun enabled(context: Context) = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getBoolean(PREF_ON, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, value).apply()
        if (!value && ::app.isInitialized) stop("Switched off")
    }

    /** From the dock service at start (main thread, idempotent). */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        mockOff() // left over from a crash or an update
        // Boot = elapsedRealtime 0: the logic refuses unless the dock started within minutes of boot.
        logic = ParkedLogic(hasSaved = saved(app) != null, bootAt = 0L)
        if (!enabled(app)) logic?.stop("Switched off in settings")
        // The real GPS must be asked during the first look, auto doctor or not.
        GpsDoctor.watch(app, "parked")
        Sensors.start(app, "parked")
        main.post(tick)
    }

    /** Real fixes only, from [GpsWatch] (every GPS fix any app gets). */
    fun save(context: Context, loc: Location) {
        if (loc.isMockFix() || loc.provider != LocationManager.GPS_PROVIDER) return
        if (!loc.hasAccuracy() || loc.accuracy > MAX_SAVE_ACCURACY_M) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSave < SAVE_EVERY_MS) return
        lastSave = now
        context.getSharedPreferences("dock", Context.MODE_PRIVATE).edit()
            .putString("parkedFix", listOf(
                loc.latitude, loc.longitude, loc.accuracy,
                if (loc.hasAltitude()) loc.altitude else "", if (loc.hasBearing()) loc.bearing else "", System.currentTimeMillis(),
            ).joinToString(","))
            .apply()
    }

    fun saved(context: Context): Saved? = runCatching {
        val p = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getString("parkedFix", null)!!.split(',')
        Saved(p[0].toDouble(), p[1].toDouble(), p[2].toFloat(), p[3].toDoubleOrNull(), p[4].toFloatOrNull(), p[5].toLong())
    }.getOrNull()

    /** "Use parked position now" on the GPS doctor screen. */
    fun useNow(context: Context) {
        app = context.applicationContext
        if (saved(app) == null) {
            problem = "No position saved yet — it's saved while the GPS works."
            return
        }
        if (Sensors.driving() == true) {
            problem = "Not while driving — only for a parked van."
            return
        }
        if (logic == null) logic = ParkedLogic(hasSaved = true)
        if (!started) start(app)
        logic?.force(SystemClock.elapsedRealtime())
        step()
    }

    /** Hand back to the real GPS for the rest of this drive. */
    fun stop(reason: String) {
        logic?.stop(reason)
        if (::app.isInitialized) step()
    }

    private fun step() {
        val l = logic ?: return
        val now = SystemClock.elapsedRealtime()
        val f = GpsDoctor.fix // real fixes only (the doctor drops mock ones)
        val realFix = f != null && now - GpsDoctor.fixAt < 5_000 && GpsDoctor.hasFix()
        val state = if (l.state == ParkedLogic.State.DONE) l.state else l.tick(now, realFix, GpsDoctor.strongSats, Sensors.driving())
        when (state) {
            ParkedLogic.State.RESTORED -> {
                if (!mocking) mockOn()
                if (mocking) push()
            }
            else -> if (mocking) mockOff()
        }
        if (state == ParkedLogic.State.DONE) finish()
    }

    /** Nothing more to do this drive: stop our own GPS request and sensor use. */
    private fun finish() {
        if (!started) return
        started = false
        main.removeCallbacks(tick)
        GpsDoctor.unwatch(app, "parked")
        Sensors.stop(app, "parked")
    }

    // --- Mock providers -----------------------------------------------------

    private fun providers(): List<String> = listOfNotNull(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
    )

    private fun lm() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun mockOn() {
        val lm = lm()
        val failed = runCatching { providers().forEach { add(lm, it) } }.exceptionOrNull()
        if (failed is SecurityException && Root.run("appops set ${app.packageName} android:mock_location allow")) {
            runCatching { providers().forEach { add(lm, it) } }.onFailure { return mockFailed(it) }
        } else if (failed != null) return mockFailed(failed)
        mocking = true
        problem = ""
    }

    private fun mockFailed(e: Throwable) {
        mockOff()
        problem = if (e is SecurityException) "Android doesn't allow Dock to set the position: with root it's automatic; " +
            "else Developer options → Select mock location app → Dock."
        else "Couldn't set the position: ${e.message}"
        logic?.stop("Can't set the position (see below)")
    }

    @Suppress("DEPRECATION") // the ProviderProperties version is Android 12+ only
    @SuppressLint("WrongConstant")
    private fun add(lm: LocationManager, p: String) {
        runCatching { lm.removeTestProvider(p) }
        lm.addTestProvider(p, false, p == LocationManager.GPS_PROVIDER, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        lm.setTestProviderEnabled(p, true)
    }

    private fun push() {
        val s = saved(app) ?: return
        val lm = lm()
        for (p in providers()) runCatching {
            lm.setTestProviderLocation(p, Location(p).apply {
                latitude = s.lat
                longitude = s.lng
                accuracy = s.acc.coerceIn(10f, 50f)
                s.alt?.let { altitude = it }
                s.bearing?.let { bearing = it }
                speed = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
        }
    }

    private fun mockOff() {
        val lm = lm()
        for (p in providers()) runCatching { lm.removeTestProvider(p) }
        mocking = false
    }
}

/** Android flags positions from mock providers (ours, or any fake-GPS app). */
fun Location.isMockFix(): Boolean =
    if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
