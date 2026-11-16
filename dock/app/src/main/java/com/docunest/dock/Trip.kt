package com.docunest.dock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Road ahead (owner 2026-11-16: "if there is a route, I want to see what's along the route and
 * what's close to me"). The trip = where the van is going:
 *  - Waze's destination: the newest row of RECENTS in Waze's user.db (written when navigation
 *    starts — monorepo docs/ksw/docs/waze-debug-log.md §4), read from a root copy that is deleted
 *    right after; or
 *  - the place the owner picked in the dock (Nav.go / Nav.goTo).
 * The start is locked where the van was when the trip began. The route comes from MapPal
 * (/api/dock/road, OSRM); with no signal it is a straight line until the route can be fetched.
 * Places along it come from the offline packs ([OfflinePois.corridor]) — all kinds, the map
 * toggles only filter.
 */
object Trip {
    data class Dest(val lat: Double, val lng: Double, val title: String, val source: String, val at: Long)

    class Route(val lat: DoubleArray, val lng: DoubleArray, val straight: Boolean, val distanceM: Double, val durationS: Long?, val builtAt: Long) {
        val cum: DoubleArray = RouteGeo.cumulative(lat, lng)
        val lengthM get() = if (cum.isEmpty()) 0.0 else cum.last()
    }

    data class Progress(val alongM: Double, val offM: Double, val leftM: Double)

    @Volatile var dest: Dest? = null
        private set
    @Volatile var start: Pair<Double, Double>? = null
        private set
    @Volatile var route: Route? = null
        private set
    @Volatile var pois: List<OfflinePois.RoutePoi> = emptyList()
        private set
    @Volatile var status: String = "No trip"
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { Handler(HandlerThread("trip").apply { start() }.looper) }
    @Volatile private var loaded = false
    private var lastWazeStamp = ""
    private var lastRouteTry = 0L
    private var offSince = 0L
    private const val ARRIVED_M = 250.0
    private const val OFF_ROUTE_M = 700.0

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { runCatching { it() } } }
    }
    private fun say(s: String) { status = s; DebugLog.log("TRIP", s); changed() }

    private fun file(ctx: Context) = File(ctx.filesDir, "trip.json")

    /** Load the saved trip (car off mid-drive) once. */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val app = ctx.applicationContext
        worker.post { runCatching { load(app) }.onFailure { DebugLog.log("TRIP", "saved trip unreadable: ${it.message}") } }
    }

    /** The owner picked a place in the dock (and is driving there). */
    fun setDockDestination(ctx: Context, lat: Double, lng: Double, title: String?) {
        val app = ctx.applicationContext
        worker.post { begin(app, Dest(lat, lng, title?.takeIf { it.isNotBlank() } ?: "Picked place", "dock", System.currentTimeMillis())) }
    }

    fun end(ctx: Context) {
        val app = ctx.applicationContext
        worker.post {
            dest = null; start = null; route = null; pois = emptyList()
            file(app).delete()
            say("No trip")
        }
    }

    /** New route from where the van is now (off the planned road, or the owner asked). */
    fun reroute(ctx: Context) {
        val app = ctx.applicationContext
        worker.post { dest?.let { begin(app, it, keepStart = false) } }
    }

    /** Every ~30 s from the dock service: Waze's destination, arrival, off-route, fetch the real route. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        init(app)
        worker.post {
            runCatching { pollWaze(app) }.onFailure { DebugLog.log("TRIP", "Waze destination unreadable: ${it.message}") }
            val d = dest ?: return@post
            val here = here(app) ?: return@post
            if (RouteGeo.distM(here.latitude, here.longitude, d.lat, d.lng) < ARRIVED_M) {
                say("Arrived: ${d.title}")
                dest = null; route = null; pois = emptyList(); start = null
                file(app).delete()
                changed()
                return@post
            }
            val r = route
            val now = System.currentTimeMillis()
            if (r == null || (r.straight && now - lastRouteTry > 3 * 60_000)) {
                buildRoute(app, d, here.latitude, here.longitude)
                return@post
            }
            val p = progress(here.latitude, here.longitude)
            if (p != null && !r.straight && p.offM > OFF_ROUTE_M) {
                if (offSince == 0L) offSince = now
                if (now - offSince > 60_000 && now - lastRouteTry > 3 * 60_000) {
                    DebugLog.log("TRIP", "off the route by ${p.offM.toInt()} m — new route from here")
                    offSince = 0L
                    buildRoute(app, d, here.latitude, here.longitude)
                }
            } else offSince = 0L
        }
    }

    fun here(ctx: Context): Location? =
        GpsDoctor.fix?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000 }
            ?: DockActivity.lastLocation ?: Locator.lastKnownAny(ctx)

    fun progress(lat: Double, lng: Double): Progress? {
        val r = route ?: return null
        if (r.lat.size < 2) return null
        val p = RouteGeo.project(r.lat, r.lng, r.cum, lat, lng)
        return Progress(p.along, p.off, (r.lengthM - p.along).coerceAtLeast(0.0))
    }

    /** Places still ahead (from 200 m behind), of [layers], matching [filter]. */
    fun ahead(alongM: Double, layers: Set<String>, filter: (OfflinePois.Poi) -> Boolean): List<OfflinePois.RoutePoi> =
        pois.filter { it.km * 1000 >= alongM - 200 && it.poi.layer in layers && filter(it.poi) }

    // --- internals (worker thread) ---------------------------------------------------------------

    private fun begin(app: Context, d: Dest, keepStart: Boolean = true) {
        val here = here(app)
        val prev = dest
        // Waze sent to a stop on the way (diesel, shop) — keep the long trip on the map.
        val r0 = route
        if (d.source == "waze" && prev != null && r0 != null && !r0.straight) {
            val p = RouteGeo.project(r0.lat, r0.lng, r0.cum, d.lat, d.lng)
            if (p.off < 3000 && p.along < r0.lengthM - 2000 && RouteGeo.distM(prev.lat, prev.lng, d.lat, d.lng) > 2000) {
                say("Stop on the way: ${d.title} — trip to ${prev.title} kept")
                dest = prev.copy(at = d.at)
                save(app)
                return
            }
        }
        val same = prev != null && RouteGeo.distM(prev.lat, prev.lng, d.lat, d.lng) < 300
        dest = if (same && prev!!.source == "dock" && d.source == "waze") prev.copy(at = d.at) else d
        if (!same || !keepStart || start == null) start = here?.let { it.latitude to it.longitude } ?: start
        val from = if (keepStart && same) start else here?.let { it.latitude to it.longitude }
        if (from == null) {
            say("Trip to ${dest!!.title} — waiting for a position")
            save(app)
            return
        }
        say("Trip to ${dest!!.title} (${if (dest!!.source == "waze") "from Waze" else "picked in the dock"})")
        buildRoute(app, dest!!, from.first, from.second)
    }

    private fun buildRoute(app: Context, d: Dest, fromLat: Double, fromLng: Double) {
        lastRouteTry = System.currentTimeMillis()
        val r = runCatching { serverRoute(app, fromLat, fromLng, d) }.getOrElse {
            DebugLog.log("TRIP", "no road route (${it.message}) — straight line for now")
            val (la, ln) = RouteGeo.straight(fromLat, fromLng, d.lat, d.lng)
            Route(la, ln, true, RouteGeo.distM(fromLat, fromLng, d.lat, d.lng), null, System.currentTimeMillis())
        }
        if (dest != d && dest?.let { RouteGeo.distM(it.lat, it.lng, d.lat, d.lng) > 300 } == true) return // a newer trip started
        route = r
        pois = if (OfflinePois.available(app)) runCatching {
            OfflinePois.corridor(app, r.lat, r.lng, r.cum, OfflinePois.RADIUS.keys)
        }.getOrDefault(emptyList()) else emptyList()
        say(buildString {
            append("${d.title}: ${(r.lengthM / 1000).toInt()} km")
            if (r.straight) append(" (straight line — no road route yet)")
            append(" · ${pois.size} places along it")
            if (!OfflinePois.available(app)) append(" · install offline data for places")
        })
        save(app)
    }

    private fun serverRoute(app: Context, fromLat: Double, fromLng: Double, d: Dest): Route {
        val body = JSONObject()
            .put("from", JSONObject().put("lat", fromLat).put("lng", fromLng))
            .put("to", JSONObject().put("lat", d.lat).put("lng", d.lng))
            .put("layers", JSONArray().put("fuel")) // only the line is used; places come from the packs
        val j = Api(app).call("POST", "/api/dock/road", body, readTimeoutMs = 65_000).getJSONObject("route")
        val line = j.getJSONArray("line")
        if (line.length() < 2) throw IllegalStateException("empty route")
        val la = DoubleArray(line.length()) { line.getJSONArray(it).getDouble(0) }
        val ln = DoubleArray(line.length()) { line.getJSONArray(it).getDouble(1) }
        return Route(la, ln, false, j.optDouble("distanceM", 0.0), j.optLong("durationS"), System.currentTimeMillis())
    }

    /** Waze's current destination: newest RECENTS row, if Waze's database changed since the last look. */
    private fun pollWaze(app: Context) {
        if (!Apps.installed(app, Apps.WAZE)) return
        val stamp = Root.output("stat -c %Y /data/data/com.waze/user.db /data/data/com.waze/user.db-wal 2>/dev/null; true")?.trim() ?: return
        if (stamp.isEmpty() || stamp == lastWazeStamp) return
        lastWazeStamp = stamp
        val dir = File(app.cacheDir, "wz").apply { mkdirs() }
        val copy = File(dir, "u.db")
        try {
            Root.run(
                "cp /data/data/com.waze/user.db ${copy.path}",
                "cp /data/data/com.waze/user.db-wal ${copy.path}-wal 2>/dev/null",
                "rm -f ${copy.path}-shm",
                "chmod 666 ${copy.path}*",
                "true",
            )
            if (!copy.exists()) return
            val row = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery(
                    "SELECT r.name, p.street, p.city, p.latitude, p.longitude, r.access_time FROM RECENTS r " +
                        "LEFT JOIN PLACES p ON p.id = r.place_id ORDER BY r.access_time DESC LIMIT 1", null,
                ).use { c ->
                    if (!c.moveToFirst()) null
                    else listOf(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4), c.getLong(5))
                }
            } ?: return
            var lat = row[3] as Double
            var lng = row[4] as Double
            if (kotlin.math.abs(lat) > 90 || kotlin.math.abs(lng) > 180) { lat /= 1e6; lng /= 1e6 }
            var at = row[5] as Long
            if (at in 1..99_999_999_999L) at *= 1000 // seconds → ms
            if (lat == 0.0 && lng == 0.0) return
            if (System.currentTimeMillis() - at > 6 * 3600_000L) return // an old search, not this drive
            val cur = dest
            if (cur != null && cur.at >= at) return
            val title = listOfNotNull((row[0] as String?)?.takeIf { it.isNotBlank() }, (row[2] as String?)?.takeIf { it.isNotBlank() })
                .distinct().joinToString(", ").ifEmpty { "Waze destination" }
            DebugLog.log("TRIP", "Waze destination: $title")
            begin(app, Dest(lat, lng, title, "waze", at))
        } finally {
            // Waze history is personal: never keep the copy.
            dir.listFiles()?.forEach { it.delete() }
            Root.run("rm -f ${copy.path} ${copy.path}-wal ${copy.path}-shm", "true")
        }
    }

    private fun save(app: Context) {
        val d = dest ?: return
        val r = route
        val j = JSONObject()
            .put("dest", JSONObject().put("lat", d.lat).put("lng", d.lng).put("title", d.title).put("source", d.source).put("at", d.at))
        start?.let { j.put("start", JSONArray().put(it.first).put(it.second)) }
        if (r != null) j.put("route", JSONObject()
            .put("lat", JSONArray(r.lat.toList())).put("lng", JSONArray(r.lng.toList()))
            .put("straight", r.straight).put("distanceM", r.distanceM).put("durationS", r.durationS ?: 0).put("builtAt", r.builtAt))
        runCatching { file(app).writeText(j.toString()) }
    }

    private fun load(app: Context) {
        val f = file(app)
        if (!f.exists()) return
        val j = JSONObject(f.readText())
        val d = j.getJSONObject("dest")
        dest = Dest(d.getDouble("lat"), d.getDouble("lng"), d.getString("title"), d.getString("source"), d.getLong("at"))
        j.optJSONArray("start")?.let { start = it.getDouble(0) to it.getDouble(1) }
        j.optJSONObject("route")?.let { r ->
            val la = r.getJSONArray("lat"); val ln = r.getJSONArray("lng")
            route = Route(DoubleArray(la.length()) { la.getDouble(it) }, DoubleArray(ln.length()) { ln.getDouble(it) },
                r.optBoolean("straight"), r.optDouble("distanceM"), r.optLong("durationS").takeIf { it > 0 }, r.optLong("builtAt"))
        }
        route?.let { r -> if (OfflinePois.available(app)) pois = OfflinePois.corridor(app, r.lat, r.lng, r.cum, OfflinePois.RADIUS.keys) }
        say("Trip to ${dest!!.title} (saved)")
    }
}
