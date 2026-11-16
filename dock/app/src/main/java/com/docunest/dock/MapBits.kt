package com.docunest.dock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** What the map shows (owner 2026-11-16): group on/off buttons (on by default, parking off) + a name filter. Shared by the dock map and the mini-map. */
object MapFilter {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("mapfilter", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; main.post { ls.forEach { it() } } }

    /** Groups switched away from their default (on-by-default ones that are off, off-by-default ones that are on). */
    private fun flipped(ctx: Context): Set<String> = sp(ctx).getStringSet("flipped", null) ?: run {
        // Before 2026-11-17 the pref was "off" (all groups on by default).
        val off = sp(ctx).getStringSet("off", emptySet())!!
        off.filter { id -> OfflinePois.GROUPS.any { it.id == id && it.onByDefault } }.toSet()
    }
    fun groups(ctx: Context): Set<String> = flipped(ctx).let { f -> OfflinePois.GROUPS.filter { it.onByDefault != (it.id in f) }.map { it.id }.toSet() }
    fun toggle(ctx: Context, id: String) {
        val f = flipped(ctx).toMutableSet()
        if (!f.remove(id)) f += id
        sp(ctx).edit().putStringSet("flipped", f).apply()
        changed()
    }
    fun layers(ctx: Context): Set<String> = groups(ctx).let { on -> OfflinePois.GROUPS.filter { it.id in on }.flatMap { it.layers }.toSet() }

    var text: String = ""
        private set
    fun setText(ctx: Context, t: String) {
        text = t.trim()
        sp(ctx).edit().putString("text", text).apply()
        changed()
    }
    fun load(ctx: Context) { text = sp(ctx).getString("text", "") ?: "" }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    /** Name filter: "lidl", "mercadona", "24/7", "diesel" … over name, brand and facts. */
    fun matches(p: OfflinePois.Poi): Boolean {
        if (text.isEmpty()) return true
        val hay = norm(listOfNotNull(p.name, p.brand, p.info, OfflinePois.LABEL[p.layer]).joinToString(" "))
        return norm(text).split(' ').filter { it.isNotBlank() }.all { it in hay }
    }
}

/**
 * The places on a map as their icons (old-school GPS style). Icons stay upright when the map turns
 * (mini-map is heading-up). Tap → [onTap] with every place under the finger, nearest first.
 */
class PoiOverlay(context: Context, private val onTap: (List<OfflinePois.Poi>) -> Unit) : Overlay() {
    @Volatile var pois: List<OfflinePois.Poi> = emptyList()
    /** Places on the route: drawn a bit bigger, with a ring. */
    @Volatile var onRoute: Set<String> = emptySet()
    private val density = context.resources.displayMetrics.density
    private val r = 13 * density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15 * density }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.rgb(37, 99, 235) }
    private val small = Paint(text).apply { textSize = 10 * density }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 17, 24, 39) }
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)
    private var drawn: List<Pair<OfflinePois.Poi, Point>> = emptyList()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val out = ArrayList<Pair<OfflinePois.Poi, Point>>(pois.size)
        val w = map.width; val h = map.height
        val turn = map.mapOrientation
        val m = maxOf(w, h) / 2 + 40 // a turned map shows corners beyond width × height
        // Low priority first, so diesel / rest / shops end up on top.
        for (p in pois.asReversed()) {
            gp.setCoords(p.lat, p.lng)
            proj.toPixels(gp, pt)
            if (turn == 0f && (pt.x < -40 || pt.y < -40 || pt.x > w + 40 || pt.y > h + 40)) continue
            if (turn != 0f && (abs(pt.x - w / 2) > m || abs(pt.y - h / 2) > m)) continue
            val big = p.osm in onRoute
            val street = OfflinePois.streetSide(p)
            val rr = if (big) r * 1.2f else if (street) r * 0.75f else r
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            c.drawCircle(x, y, rr, bg)
            if (big) c.drawCircle(x, y, rr, ring)
            if (turn != 0f) { c.save(); c.rotate(-turn, x, y) }
            val icon = if (street) small else text
            c.drawText(OfflinePois.ICON[p.layer] ?: "📍", x, y + icon.textSize / 3, icon)
            // Context icon top-right: ⬇️ underground, 🚐 campers OK, 🆓 free, 💶 paid, 🌙 24/7 …
            OfflinePois.badge(p)?.let { b ->
                val bx = x + rr * 0.85f; val by = y - rr * 0.85f
                c.drawCircle(bx, by, 8 * density, badgeBg)
                c.drawText(b, bx, by + small.textSize / 3, small)
            }
            if (turn != 0f) c.restore()
            out += p to Point(pt)
        }
        drawn = out
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val lim = (r * 1.8f) * (r * 1.8f)
        val hits = drawn.map { (p, q) -> p to (q.x - e.x) * (q.x - e.x) + (q.y - e.y) * (q.y - e.y) }
            .filter { it.second <= lim }.sortedBy { it.second }.map { it.first }
        if (hits.isEmpty()) return false
        onTap(hits)
        return true
    }
}

/** The trip on a map: route line (dashed = straight line, no road route yet), start, destination, and — for the mini-map — the van. */
class TripOverlay(context: Context, private val drawVan: Boolean) : Overlay() {
    private val density = context.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * density; color = Color.argb(200, 37, 99, 235)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(18 * density, 12 * density), 0f) }
    private val flag = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 24 * density }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 163, 74) }
    private val policeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 220, 38, 38) }
    private val police = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17 * density }
    private val van = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 158, 11) }
    private val vanEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.BLACK }
    private val path = Path()
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)
    @Volatile var here: Location? = null
    /** Direction the van points (degrees, 0 = north); the mini-map passes its smoothed heading. */
    @Volatile var heading: Float? = null

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        Trip.route?.let { r ->
            path.reset()
            for (i in r.lat.indices) {
                gp.setCoords(r.lat[i], r.lng[i]); proj.toPixels(gp, pt)
                if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
            }
            c.drawPath(path, if (r.straight) dashed else line)
        }
        Trip.start?.let { (la, ln) ->
            gp.setCoords(la, ln); proj.toPixels(gp, pt)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7 * density, startDot)
        }
        Trip.dest?.let { d ->
            gp.setCoords(d.lat, d.lng); proj.toPixels(gp, pt)
            c.save(); c.rotate(-map.mapOrientation, pt.x.toFloat(), pt.y.toFloat())
            c.drawText("🏁", pt.x.toFloat() + 8 * density, pt.y.toFloat(), flag)
            c.restore()
        }
        // Police reported in Waze (Police.kt): 🚓 on a red disc, upright on a turned map.
        for (rep in Police.reports.values) {
            if (rep.done) continue
            gp.setCoords(rep.lat, rep.lng); proj.toPixels(gp, pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat()
            c.drawCircle(x, y, 15 * density, policeBg)
            c.save(); c.rotate(-map.mapOrientation, x, y)
            c.drawText("🚓", x, y + 6 * density, police)
            c.restore()
        }
        val h = here
        if (drawVan && h != null) {
            gp.setCoords(h.latitude, h.longitude); proj.toPixels(gp, pt)
            val s = 11 * density
            c.save()
            c.translate(pt.x.toFloat(), pt.y.toFloat())
            // The canvas is already turned with the map: a bearing in map space points the right way on screen.
            (heading ?: h.bearing.takeIf { h.hasBearing() })?.let { c.rotate(it) }
            val arrow = Path().apply { moveTo(0f, -s * 1.3f); lineTo(s, s); lineTo(0f, s * 0.45f); lineTo(-s, s); close() }
            c.drawPath(arrow, van); c.drawPath(arrow, vanEdge)
            c.restore()
        }
    }
}

/**
 * Keeps a map's [PoiOverlay] filled for what's visible: offline packs in the box (fewer kinds when
 * zoomed far out), plus every place on the route. Debounced, queried off the main thread.
 */
class MapFeeder(private val ctx: Context, private val map: MapView, private val overlay: PoiOverlay, private val maxPois: Int = 400,
                autoListen: Boolean = true) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    private val run = Runnable { query() }

    init {
        if (autoListen) map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = true.also { refresh() }
            override fun onZoom(event: ZoomEvent?) = true.also { refresh() }
        }, 250))
    }

    fun refresh() { main.removeCallbacks(run); main.postDelayed(run, 150) }

    private fun query() {
        if (map.width == 0) return
        val box: BoundingBox = map.boundingBox
        val my = ++gen
        bg.post {
            val layers = MapFilter.layers(ctx)
            val spanKm = RouteGeo.distM(box.latSouth, box.lonWest, box.latSouth, box.lonEast) / 1000
            val boxLayers = when {
                spanKm > 150 -> emptySet()
                spanKm > 50 -> layers intersect setOf("fuel", "rest", "aire", "dump")
                spanKm > 12 -> layers - "parking" // parking only when zoomed in (there are thousands)
                else -> layers
            }
            val inBox = if (boxLayers.isEmpty()) emptyList()
            else OfflinePois.inBox(ctx, box.latSouth, box.latNorth, box.lonWest, box.lonEast, boxLayers, perLayer = 150)
                // Street-side parking (every street has some): only when zoomed right in.
                .filter { MapFilter.matches(it) && (spanKm <= 4 || !OfflinePois.streetSide(it)) }
            val routeLayers = if (spanKm > 12) layers - "parking" else layers
            val route = Trip.pois.filter { it.poi.layer in routeLayers && MapFilter.matches(it.poi) }
            val routeIds = route.mapTo(HashSet()) { it.poi.osm }
            val all = (route.map { it.poi } + inBox).distinctBy { it.osm }
                .sortedBy { OfflinePois.PRIORITY.indexOf(it.layer).let { i -> if (i < 0) 99 else i } }
                .take(maxPois)
            main.post {
                if (my != gen) return@post
                overlay.pois = all
                overlay.onRoute = routeIds
                map.invalidate()
            }
        }
    }

    companion object {
        private val bg by lazy { Handler(HandlerThread("map-pois").apply { start() }.looper) }
    }
}

/**
 * Smart Auto zoom (owner 2026-11-17): close-up near the start and the end of a trip (town: "I may
 * want way more details"), far out in the middle of a long drive — and passing through a town on
 * the way does NOT zoom in (speed is averaged over minutes, and mid-route never goes to town).
 * With no trip: by the averaged speed.
 */
class AutoZoom {
    enum class Phase { TOWN, ROAD, OPEN }
    private var ema = -1.0
    private var lastAt = 0L
    var phase = Phase.TOWN
        private set
    val avgKmh get() = ema.coerceAtLeast(0.0)

    fun feed(here: Location, now: Long = SystemClock.elapsedRealtime()): Phase {
        val kmh = if (here.hasSpeed()) here.speed * 3.6 else 0.0
        if (ema < 0) ema = kmh
        else {
            val dt = ((now - lastAt) / 1000.0).coerceIn(0.0, 60.0)
            ema += (kmh - ema) * (1 - exp(-dt / TAU_S))
        }
        lastAt = now
        val p = Trip.progress(here.latitude, here.longitude)?.takeIf { it.offM < 2000 }
        phase = phaseFor(p?.alongM, p?.leftM, ema, phase)
        return phase
    }

    companion object {
        /** Speed average time constant: a 3-minute town crossing barely moves it. */
        const val TAU_S = 150.0

        fun phaseFor(alongM: Double?, leftM: Double?, avgKmh: Double, prev: Phase): Phase {
            // Hysteresis: a threshold must be passed by 8 km/h to change.
            fun above(limit: Double, wasAbove: Boolean) = if (wasAbove) avgKmh > limit - 8 else avgKmh > limit + 8
            if (alongM != null && leftM != null) {
                if (alongM < 3_000 || leftM < 5_000) return Phase.TOWN
                return if (above(75.0, prev == Phase.OPEN)) Phase.OPEN else Phase.ROAD
            }
            return when {
                above(75.0, prev == Phase.OPEN) -> Phase.OPEN
                above(30.0, prev != Phase.TOWN) -> Phase.ROAD
                else -> Phase.TOWN
            }
        }
    }
}

/** Dock map views: Auto, whole route, or a fixed span (＋ / － step through [STEPS]). */
object MapViews {
    const val AUTO = "auto"
    const val ROUTE = "route"
    /** Fixed spans across the map, km (the owner's 3 / 25 / 80 plus steps between). */
    val STEPS = listOf(1.0, 3.0, 8.0, 25.0, 80.0, 250.0)
    /** Auto spans on the dock map, km. */
    fun autoSpan(phase: AutoZoom.Phase) = when (phase) {
        AutoZoom.Phase.TOWN -> 3.0
        AutoZoom.Phase.ROAD -> 25.0
        AutoZoom.Phase.OPEN -> 80.0
    }
    fun zoomStep(currentKm: Double, closer: Boolean): Double =
        if (closer) STEPS.lastOrNull { it < currentKm * 0.9 } ?: STEPS.first()
        else STEPS.firstOrNull { it > currentKm * 1.1 } ?: STEPS.last()

    fun spanKm(map: MapView): Double {
        val b = map.boundingBox
        return RouteGeo.distM(b.centerLatitude, b.lonWest, b.centerLatitude, b.lonEast) / 1000
    }

    /** Show [spanKm] around the van, looking ahead (along the route when there is one). */
    fun follow(map: MapView, here: Location, spanKm: Double) {
        if (map.width == 0 || map.height == 0) return
        var cLat = here.latitude; var cLng = here.longitude
        val aheadM = if (spanKm <= 3.0) 0.0 else spanKm * 1000 * 0.3
        if (aheadM > 0) {
            val r = Trip.route
            val p = Trip.progress(here.latitude, here.longitude)
            if (r != null && p != null && p.offM < 2000) {
                RouteGeo.pointAt(r.lat, r.lng, r.cum, p.alongM + aheadM).let { (la, ln) -> cLat = la; cLng = ln }
            } else if (here.hasBearing() && here.speed > 3) {
                val b = Math.toRadians(here.bearing.toDouble())
                cLat += aheadM * cos(b) / 111_320.0
                cLng += aheadM * sin(b) / (111_320.0 * cos(Math.toRadians(cLat)))
            }
        }
        val half = spanKm * 1000 / 2
        val dLat = half / 111_320.0
        val dLng = half / (111_320.0 * cos(Math.toRadians(cLat)).coerceAtLeast(0.2))
        map.zoomToBoundingBox(BoundingBox(cLat + dLat, cLng + dLng, cLat - dLat, cLng - dLng), false)
    }

    fun showRoute(map: MapView): Boolean {
        val r = Trip.route ?: return false
        if (map.width == 0) return false
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (i in r.lat.indices) { s = minOf(s, r.lat[i]); n = maxOf(n, r.lat[i]); w = minOf(w, r.lng[i]); e = maxOf(e, r.lng[i]) }
        val padLat = (n - s) * 0.08 + 0.005; val padLng = (e - w) * 0.08 + 0.005
        map.zoomToBoundingBox(BoundingBox(n + padLat, e + padLng, s - padLat, w - padLng), false)
        return true
    }
}

/** "in 12 km · ~8 min" — minutes at the current speed (≥ 30 km/h), else at 80 km/h. */
fun aheadText(m: Double, speedMs: Float?): String {
    val kmh = ((speedMs ?: 0f) * 3.6f).let { if (it >= 30f) it else 80f }
    val min = (m / 1000 / kmh * 60).toInt()
    return "in ${km(m.toFloat())}" + if (m > 400) " · ~${if (min < 1) "<1" else "$min"} min" else ""
}
