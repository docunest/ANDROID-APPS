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
import kotlin.math.cos

/** What the map shows (owner 2026-11-16): group on/off buttons (all on by default) + a name filter. Shared by the dock map and the mini-map. */
object MapFilter {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("mapfilter", Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; main.post { ls.forEach { it() } } }

    fun groups(ctx: Context): Set<String> = sp(ctx).getStringSet("off", emptySet())!!.let { off -> OfflinePois.GROUPS.map { it.id }.filter { it !in off }.toSet() }
    fun toggle(ctx: Context, id: String) {
        val off = sp(ctx).getStringSet("off", emptySet())!!.toMutableSet()
        if (!off.remove(id)) off += id
        sp(ctx).edit().putStringSet("off", off).apply()
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

/** The places on a map as their icons (old-school GPS style). Tap → [onTap]. */
class PoiOverlay(context: Context, private val onTap: (OfflinePois.Poi) -> Unit) : Overlay() {
    @Volatile var pois: List<OfflinePois.Poi> = emptyList()
    /** Places on the route: drawn a bit bigger, with a ring. */
    @Volatile var onRoute: Set<String> = emptySet()
    private val density = context.resources.displayMetrics.density
    private val r = 13 * density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15 * density }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.rgb(37, 99, 235) }
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)
    private var drawn: List<Pair<OfflinePois.Poi, Point>> = emptyList()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val out = ArrayList<Pair<OfflinePois.Poi, Point>>(pois.size)
        val w = map.width; val h = map.height
        // Low priority first, so diesel / rest / shops end up on top.
        for (p in pois.asReversed()) {
            gp.setCoords(p.lat, p.lng)
            proj.toPixels(gp, pt)
            if (pt.x < -40 || pt.y < -40 || pt.x > w + 40 || pt.y > h + 40) continue
            val big = p.osm in onRoute
            val rr = if (big) r * 1.2f else r
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rr, bg)
            if (big) c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rr, ring)
            c.drawText(OfflinePois.ICON[p.layer] ?: "📍", pt.x.toFloat(), pt.y + text.textSize / 3, text)
            out += p to Point(pt)
        }
        drawn = out
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val hit = drawn.minByOrNull { (_, q) -> (q.x - e.x) * (q.x - e.x) + (q.y - e.y) * (q.y - e.y) } ?: return false
        val (p, q) = hit
        val d2 = (q.x - e.x) * (q.x - e.x) + (q.y - e.y) * (q.y - e.y)
        if (d2 > (r * 1.8f) * (r * 1.8f)) return false
        onTap(p)
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
    private val van = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 158, 11) }
    private val vanEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.BLACK }
    private val path = Path()
    private val pt = Point()
    private val gp = GeoPoint(0.0, 0.0)
    @Volatile var here: Location? = null

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
            c.drawText("🏁", pt.x.toFloat() + 8 * density, pt.y.toFloat(), flag)
        }
        val h = here
        if (drawVan && h != null) {
            gp.setCoords(h.latitude, h.longitude); proj.toPixels(gp, pt)
            val s = 11 * density
            c.save()
            c.translate(pt.x.toFloat(), pt.y.toFloat())
            if (h.hasBearing()) c.rotate(h.bearing - map.mapOrientation)
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
class MapFeeder(private val ctx: Context, private val map: MapView, private val overlay: PoiOverlay, private val maxPois: Int = 400) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    private val run = Runnable { query() }
    /** Distance (m) to the next essential (diesel / rest / shop / aire) ahead or around — for Auto zoom. */
    @Volatile var nextEssentialM: Double? = null

    init {
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = true.also { refresh() }
            override fun onZoom(event: ZoomEvent?) = true.also { refresh() }
        }, 250))
    }

    fun refresh() { main.removeCallbacks(run); main.postDelayed(run, 150) }

    private fun query() {
        if (map.width == 0) return
        val box: BoundingBox = map.boundingBox
        val my = ++gen
        val here = Trip.here(ctx)
        bg.post {
            val layers = MapFilter.layers(ctx)
            val spanKm = RouteGeo.distM(box.latSouth, box.lonWest, box.latSouth, box.lonEast) / 1000
            val boxLayers = when {
                spanKm > 150 -> emptySet()
                spanKm > 50 -> layers intersect setOf("fuel", "rest", "aire", "dump")
                else -> layers
            }
            val inBox = if (boxLayers.isEmpty()) emptyList()
            else OfflinePois.inBox(ctx, box.latSouth, box.latNorth, box.lonWest, box.lonEast, boxLayers, perLayer = 150).filter { MapFilter.matches(it) }
            val route = Trip.pois.filter { it.poi.layer in layers && MapFilter.matches(it.poi) }
            val routeIds = route.mapTo(HashSet()) { it.poi.osm }
            val all = (route.map { it.poi } + inBox).distinctBy { it.osm }
                .sortedBy { OfflinePois.PRIORITY.indexOf(it.layer).let { i -> if (i < 0) 99 else i } }
                .take(maxPois)
            // Next essential: along the route if there is one, else straight-line around.
            val essentials = layers intersect setOf("fuel", "rest", "grocery", "aire")
            val next = if (here == null || essentials.isEmpty()) null else {
                val p = Trip.progress(here.latitude, here.longitude)
                if (p != null && p.offM < 2000) Trip.ahead(p.alongM, essentials) { MapFilter.matches(it) }.firstOrNull()?.let { it.km * 1000 - p.alongM }
                else essentials.mapNotNull { l -> OfflinePois.nearest(ctx, here.latitude, here.longitude, l, 80_000.0, 1).firstOrNull()?.distM }.minOrNull()
            }
            main.post {
                if (my != gen) return@post
                overlay.pois = all
                overlay.onRoute = routeIds
                nextEssentialM = next
                map.invalidate()
            }
        }
    }

    companion object {
        private val bg by lazy { Handler(HandlerThread("map-pois").apply { start() }.looper) }
    }
}

/** The four views (owner 2026-10/11): whole route, 3 km (1.5 km back), 25 km, 80 km — and Auto picks one. */
object MapViews {
    const val AUTO = "auto"
    const val ROUTE = "route"
    val SPANS = linkedMapOf("3" to 3.0, "25" to 25.0, "80" to 80.0)

    /** Auto: slow (town) → 3 km; next diesel / rest / shop within 3 km → 3; within 25 → 25; else 80. */
    fun autoSpan(speedKmh: Float?, nextEssentialM: Double?): Double = when {
        speedKmh != null && speedKmh < 35 -> 3.0
        nextEssentialM != null && nextEssentialM <= 3_000 -> 3.0
        nextEssentialM != null && nextEssentialM <= 25_000 -> 25.0
        else -> 80.0
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
                cLng += aheadM * kotlin.math.sin(b) / (111_320.0 * cos(Math.toRadians(cLat)))
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
