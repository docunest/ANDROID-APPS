package lt.eb07.ponas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fake GPS for the whole tablet (it has no GPS chip). Ponas is Android's "mock location app":
 * this service fetches where the family is from MapPal (/api/position, token set over adb, never
 * in the APK) every [FETCH_MS], remembers it for offline days, and feeds it to the gps, network and
 * fused providers every second, so every app on the tablet sees "home".
 *
 * Setup over adb (see ponas/README.md → Fake GPS):
 *   appops set lt.eb07.ponas android:mock_location allow
 *   am broadcast -a lt.eb07.ponas.SET_POSITION_TOKEN -n lt.eb07.ponas/.FakeGpsReceiver --es token <T>
 * If Developer options are switched off in Settings, Android drops the mock app and this stops
 * (status says so); set it again with the first command.
 */
object FakeGps {
    const val API_URL = "https://map-pal-mike-base.vercel.app/api/position"
    const val FETCH_MS = 15 * 60_000L
    private const val PREFS = "ponas-fakegps"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_ACC = "acc"
    private const val KEY_LABEL = "label"
    private const val KEY_STATUS = "status"
    private const val KEY_FETCHED_AT = "fetchedAt"

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setToken(c: Context, token: String) = prefs(c).edit().putString(KEY_TOKEN, token.trim()).putLong(KEY_FETCHED_AT, 0).apply()

    /** For the parent menu. */
    fun statusText(c: Context): String {
        val p = prefs(c)
        val at = p.getLong(KEY_FETCHED_AT, 0)
        return buildString {
            append("Fake GPS: ").append(p.getString(KEY_STATUS, "not started")).append('\n')
            if (p.contains(KEY_LAT)) {
                append("Home: ").append(p.getString(KEY_LABEL, "")).append(" (±").append(p.getInt(KEY_ACC, 0)).append(" m)\n")
            }
            append("Last fetch: ").append(if (at == 0L) "never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at)))
            if (p.getString(KEY_TOKEN, null).isNullOrEmpty()) append("\nNo token: set it over adb")
        }
    }

    /** Start (or keep) the service. Call from a foreground activity: Android 14 forbids it from the background. */
    fun start(c: Context) {
        runCatching { c.startForegroundService(Intent(c, FakeGpsService::class.java)) }
            .onFailure { prefs(c).edit().putString(KEY_STATUS, "can't start: ${it.message}").apply() }
    }

    internal fun saveStatus(c: Context, s: String) {
        if (prefs(c).getString(KEY_STATUS, null) != s) prefs(c).edit().putString(KEY_STATUS, s).apply()
    }

    /** Blocking. Returns true if a new position was stored. */
    internal fun fetch(c: Context): Boolean {
        val p = prefs(c)
        val token = p.getString(KEY_TOKEN, null)
        if (token.isNullOrEmpty()) return false
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val home = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONObject("home")
                ?: throw java.io.IOException("no position known yet")
            p.edit()
                // strings, not floats: a float loses about a metre here
                .putString(KEY_LAT, home.getDouble("lat").toString()).putString(KEY_LNG, home.getDouble("lng").toString())
                .putInt(KEY_ACC, home.optInt("accuracyM", 100).coerceIn(5, 5000))
                .putString(KEY_LABEL, "${home.optString("label")} · ${home.optString("source")}")
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply()
            return true
        } finally {
            conn.disconnect()
        }
    }

    internal data class Home(val lat: Double, val lng: Double, val acc: Float)

    internal fun home(c: Context): Home? {
        val p = prefs(c)
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = p.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        return Home(lat, lng, p.getInt(KEY_ACC, 100).toFloat())
    }

    internal fun lastFetch(c: Context) = prefs(c).getLong(KEY_FETCHED_AT, 0)
}

class FakeGpsService : Service() {
    private val thread = HandlerThread("ponas-fakegps").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var lm: LocationManager
    private val added = mutableSetOf<String>()
    private var nextFetch = 0L
    private var retryAt = 0L

    private val providers: List<String>
        get() = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
                FakeGps.saveStatus(this@FakeGpsService, "error: ${e.message}")
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fakegps", "Fake GPS", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "fakegps")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Ponas: location = home")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("refetch", false) == true) nextFetch = 0
        return START_STICKY
    }

    private fun step() {
        val now = System.currentTimeMillis()
        if (now >= nextFetch) {
            nextFetch = now + FakeGps.FETCH_MS
            val ok = runCatching { FakeGps.fetch(this) }
            ok.exceptionOrNull()?.let {
                // offline is normal: keep the last home, try again in 2 min
                nextFetch = now + 2 * 60_000
                FakeGps.saveStatus(this, "fetch failed (${it.message}), using last home")
            }
        }
        val home = FakeGps.home(this) ?: return FakeGps.saveStatus(this, "waiting for the first position")
        if (now < retryAt) return
        try {
            for (p in providers) {
                if (p !in added) {
                    runCatching { lm.removeTestProvider(p) }
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                    lm.setTestProviderEnabled(p, true)
                    added += p
                }
                lm.setTestProviderLocation(p, locationOf(p, home))
            }
            val age = (now - FakeGps.lastFetch(this)) / 60_000
            FakeGps.saveStatus(this, if (FakeGps.lastFetch(this) == 0L) "on" else "on (position from $age min ago)")
        } catch (e: SecurityException) {
            added.clear()
            retryAt = now + 30_000
            FakeGps.saveStatus(this, "off: Ponas is not the mock location app (adb: appops set lt.eb07.ponas android:mock_location allow)")
        }
    }

    private fun locationOf(provider: String, h: FakeGps.Home) = Location(provider).apply {
        latitude = h.lat
        longitude = h.lng
        accuracy = h.acc
        altitude = 0.0
        speed = 0f
        bearing = 0f
        verticalAccuracyMeters = 10f
        speedAccuracyMetersPerSecond = 0.5f
        bearingAccuracyDegrees = 30f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        for (p in added) runCatching { lm.removeTestProvider(p) }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Sets the MapPal position token. Only the adb shell can send it: the receiver requires
 * android.permission.DUMP, which ordinary apps can't hold.
 */
class FakeGpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")?.trim().orEmpty()
        if (token.length < 20) {
            resultData = "token missing or too short"
            return
        }
        FakeGps.setToken(context, token)
        // ask a running service to fetch now (startService to a running service is allowed from the background)
        runCatching { context.startService(Intent(context, FakeGpsService::class.java).putExtra("refetch", true)) }
        resultData = "token saved"
    }
}
