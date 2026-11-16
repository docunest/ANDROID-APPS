package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The round GTA-style mini-map floating over Waze (owner 2026-11-16 "where is my floating circular
 * thingy"; 2026-11-17 "show way longer horizon … not north … me off centre … a draggable dot on the
 * edge like a Nest thermostat … a smart auto mode").
 *  - Heading-up, the van low in the circle: ~¾ of it is the road ahead.
 *  - Zoom dial: drag the dot around the ring (clockwise = further out). Tap the dot = Auto.
 *  - Auto: close-up near the trip's start and end, far out mid-trip (a town on the way doesn't
 *    zoom it in — [AutoZoom]); no trip: by the averaged speed.
 *  - Bottom label: the next diesel. Tap the middle = the dock map; drag the middle = move it.
 * Hidden while the dock map is open.
 */
class MiniMap(private val ctx: Context, private val wm: WindowManager) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var map: MapView
    private lateinit var trip: TripOverlay
    private lateinit var feeder: MapFeeder
    private lateinit var label: TextView
    private lateinit var dial: Dial
    private val auto = AutoZoom()
    private var lastZoom = 0.0
    private var lastLabelAt = 0L
    private var heading: Float? = null
    private var fedLat = 0.0; private var fedLng = 0.0; private var fedSpan = 0.0
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val tick = object : Runnable {
        override fun run() {
            runCatching { update() }.onFailure { DebugLog.log("MINIMAP", "update: ${it.message}") }
            main.postDelayed(this, 500)
        }
    }
    private val filterListener: () -> Unit = { if (root != null) feeder.refresh() }
    private val tripListener: () -> Unit = { if (root != null) { feeder.refresh(); map.invalidate() } }

    val showing get() = root != null

    /** Show or hide as the settings and the dock map say. */
    fun sync() {
        val want = prefs.miniMap && Settings.canDrawOverlays(ctx) && DockActivity.current == null
        if (want && root == null) runCatching { show() }.onFailure { DebugLog.log("MINIMAP", "can't show: ${it.message}") }
        if (!want && root != null) hide()
    }

    /** Metres across the circle now: the ring's choice, else Auto. */
    private fun spanM(): Double = prefs.miniSpanM.takeIf { it > 0 }?.toDouble() ?: when (auto.phase) {
        AutoZoom.Phase.TOWN -> 1_500.0
        AutoZoom.Phase.ROAD -> 6_000.0
        AutoZoom.Phase.OPEN -> 18_000.0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        OsmConfig.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = ctx.packageName
        }
        MapFilter.load(ctx)
        val size = dp(prefs.miniSizeDp.coerceIn(140, 360))
        val frame = FrameLayout(ctx)
        // The map itself: clipped round.
        val disc = FrameLayout(ctx).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            elevation = dp(6).toFloat()
        }
        map = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(14.0)
        }
        trip = TripOverlay(ctx, drawVan = true)
        val pois = PoiOverlay(ctx) {}
        map.overlays.add(trip)
        map.overlays.add(pois)
        feeder = MapFeeder(ctx, map, pois, maxPois = 120, autoListen = false)
        disc.addView(map, FrameLayout.LayoutParams(size, size))
        frame.addView(disc, FrameLayout.LayoutParams(size, size))
        // Ring + zoom dial on top (not clipped, drawn inside the circle).
        dial = Dial(ctx).apply { elevation = dp(7).toFloat() }
        frame.addView(dial, FrameLayout.LayoutParams(size, size))
        label = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(Color.argb(210, 17, 24, 39), dp(10).toFloat())
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })

        val dm = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.miniX.takeIf { it >= 0 } ?: dp(12)
            y = prefs.miniY.takeIf { it >= 0 } ?: (dm.heightPixels - size - dp(110)).coerceAtLeast(0)
            x = x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
            y = y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        }
        // Touch: on the ring = zoom dial; in the middle = drag to move, tap = the dock map.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var onRing = false
        dial.setOnTouchListener { _, e ->
            val cx = size / 2f; val cy = size / 2f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    onRing = hypot(e.x - cx, e.y - cy) > size / 2f - dp(44)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        moved = true
                        if (onRing) {
                            dial.setT(Dial.angleToT(Dial.angleOf(e.x - cx, e.y - cy)), manual = true)
                            applySpan(Dial.tToSpan(dial.t))
                        } else {
                            params.x = (startX + dx.toInt()).coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
                            params.y = (startY + dy.toInt()).coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
                            root?.let { runCatching { wm.updateViewLayout(it, params) } }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    onRing && moved -> prefs.miniSpanM = Dial.tToSpan(dial.t).toInt()
                    onRing && dial.nearKnob(e.x, e.y) -> {
                        prefs.miniSpanM = 0 // tap the dot = Auto
                        Toast.makeText(ctx, "Mini-map zoom: Auto", Toast.LENGTH_SHORT).show()
                        lastZoom = 0.0
                        update()
                    }
                    onRing -> {
                        dial.setT(Dial.angleToT(Dial.angleOf(e.x - cx, e.y - cy)), manual = true)
                        prefs.miniSpanM = Dial.tToSpan(dial.t).toInt()
                        applySpan(prefs.miniSpanM.toDouble())
                    }
                    moved -> { prefs.miniX = params.x; prefs.miniY = params.y }
                    else -> ctx.startActivity(Intent(ctx, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            true
        }
        wm.addView(frame, params)
        root = frame
        map.onResume()
        MapFilter.addListener(filterListener)
        Trip.addListener(tripListener)
        main.post(tick)
        DebugLog.log("MINIMAP", "shown (${prefs.miniSizeDp} dp)")
    }

    fun hide() {
        val r = root ?: return
        main.removeCallbacks(tick)
        MapFilter.removeListener(filterListener)
        Trip.removeListener(tripListener)
        runCatching { map.onPause(); map.onDetach() }
        runCatching { wm.removeView(r) }
        root = null
    }

    /** Size changed in Settings. */
    fun relayout() { if (root != null) { hide(); sync() } }

    /** While the dial turns: zoom now and show the span in the label. */
    private fun applySpan(spanM: Double) {
        lastZoom = 0.0
        update(spanOverride = spanM)
        label.text = "🔍 " + km(spanM.toFloat()) + " across"
        label.visibility = View.VISIBLE
        lastLabelAt = System.currentTimeMillis() - 7_000 // next diesel back in ~3 s
    }

    private fun update(spanOverride: Double? = null) {
        val here = Trip.here(ctx) ?: return
        auto.feed(here)
        trip.here = here
        val spanM = spanOverride ?: spanM()
        if (spanOverride == null) dial.setT(Dial.spanToT(spanM), manual = prefs.miniSpanM > 0)

        // Heading: along the planned road when on it (steady), else the GPS course while moving.
        val target: Float? = run {
            val r = Trip.route
            val p = Trip.progress(here.latitude, here.longitude)
            if (r != null && !r.straight && p != null && p.offM < 300 && p.leftM > 300) {
                val (a1, b1) = RouteGeo.pointAt(r.lat, r.lng, r.cum, p.alongM)
                val (a2, b2) = RouteGeo.pointAt(r.lat, r.lng, r.cum, p.alongM + 250)
                bearing(a1, b1, a2, b2)
            } else if (here.hasBearing() && here.hasSpeed() && here.speed > 2.5f) here.bearing else null
        }
        heading = when {
            target == null -> heading
            heading == null -> target
            else -> {
                val diff = ((target - heading!! + 540f) % 360f) - 180f
                (heading!! + diff * 0.4f + 360f) % 360f
            }
        }
        val hd = heading ?: 0f
        trip.heading = heading
        map.mapOrientation = -hd

        val sizePx = map.width.takeIf { it > 0 } ?: return
        // Web Mercator: metres per pixel = 156543 · cos(lat) / 2^zoom (256 px tiles, scaled to dpi).
        val tilePx = 256 * density
        val zoom = (ln(156_543.03 * cos(Math.toRadians(here.latitude)) * sizePx * 256 / tilePx / spanM) / ln(2.0)).coerceIn(5.0, 18.0)
        if (abs(zoom - lastZoom) > 0.1) { map.controller.setZoom(zoom); lastZoom = zoom }
        // The van sits low: the centre is 28 % of the span ahead of it.
        val ahead = spanM * 0.28
        val b = Math.toRadians(hd.toDouble())
        val cLat = here.latitude + ahead * cos(b) / 111_320.0
        val cLng = here.longitude + ahead * sin(b) / (111_320.0 * cos(Math.toRadians(here.latitude)))
        map.controller.setCenter(GeoPoint(cLat, cLng))
        map.invalidate()
        // Places: re-read when the view moved a good bit (not every half second).
        if (RouteGeo.distM(cLat, cLng, fedLat, fedLng) > spanM * 0.15 || abs(spanM - fedSpan) > fedSpan * 0.2) {
            fedLat = cLat; fedLng = cLng; fedSpan = spanM
            feeder.refresh()
        }
        val now = System.currentTimeMillis()
        if (now - lastLabelAt > 10_000) {
            lastLabelAt = now
            Thread {
                val text = nextDiesel(here.latitude, here.longitude, here.speed.takeIf { here.hasSpeed() })
                main.post { label.text = text ?: ""; label.visibility = if (text == null) View.GONE else View.VISIBLE }
            }.start()
        }
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = lat2 - lat1
        return ((Math.toDegrees(atan2(x, y)) + 360) % 360).toFloat()
    }

    /** "⛽ 12 km · ~8 min" — along the route when on it, else the nearest. */
    private fun nextDiesel(lat: Double, lng: Double, speed: Float?): String? {
        if ("fuel" !in MapFilter.layers(ctx)) return null
        val p = Trip.progress(lat, lng)
        val m = if (p != null && p.offM < 2000) Trip.ahead(p.alongM, setOf("fuel")) { MapFilter.matches(it) }.firstOrNull()?.let { it.km * 1000 - p.alongM }
        else OfflinePois.nearest(ctx, lat, lng, "fuel", 60_000.0, 5).firstOrNull { MapFilter.matches(it) }?.distM
        return m?.let { "⛽ " + aheadText(it, speed).removePrefix("in ") }
    }

    /** The ring and the zoom dot (thermostat-style: a 270° sweep, the gap at the bottom holds the label). */
    private class Dial(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = Color.argb(235, 17, 24, 39) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.argb(150, 255, 255, 255); strokeCap = Paint.Cap.ROUND }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.WHITE }
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 12 * d; isFakeBoldText = true }
        private val oval = RectF()
        var t = 0.5f
            private set
        private var manual = false
        private val knobR get() = 13 * d
        private val trackR get() = width / 2f - 16 * d

        fun setT(v: Float, manual: Boolean) {
            if (abs(v - t) < 0.002f && manual == this.manual) return
            t = v.coerceIn(0f, 1f); this.manual = manual; invalidate()
        }

        private fun knobXY(): Pair<Float, Float> {
            val a = Math.toRadians((-135.0 + 270.0 * t))
            return (width / 2f + trackR * sin(a)).toFloat() to (height / 2f - trackR * cos(a)).toFloat()
        }

        fun nearKnob(x: Float, y: Float): Boolean = knobXY().let { (kx, ky) -> hypot(x - kx, y - ky) < 30 * d }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            c.drawCircle(cx, cy, width / 2f - 2.5f * d, ring)
            oval.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
            // Android arcs start at 3 o'clock; the sweep starts at 7:30 (−135° from the top).
            c.drawArc(oval, 135f, 270f, false, track)
            val (kx, ky) = knobXY()
            knob.color = if (manual) Color.rgb(245, 158, 11) else Color.rgb(22, 163, 74)
            c.drawCircle(kx, ky, knobR, knob)
            c.drawCircle(kx, ky, knobR, knobEdge)
            c.drawText(if (manual) "±" else "A", kx, ky + letter.textSize / 3, letter)
        }

        companion object {
            const val MIN_M = 500.0
            const val MAX_M = 60_000.0
            /** Degrees from the top, clockwise. */
            fun angleOf(dx: Float, dy: Float) = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
            fun angleToT(a: Float) = when {
                a in -135f..135f -> (a + 135f) / 270f
                a > 135f -> 1f          // the gap at the bottom: nearest end
                else -> 0f
            }
            fun tToSpan(t: Float) = MIN_M * (MAX_M / MIN_M).pow(t.toDouble())
            fun spanToT(m: Double) = (ln(m / MIN_M) / ln(MAX_M / MIN_M)).toFloat().coerceIn(0f, 1f)
        }
    }
}
