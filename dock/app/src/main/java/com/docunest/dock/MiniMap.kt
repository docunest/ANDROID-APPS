package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln

/**
 * The round GTA-style mini-map floating over Waze (owner 2026-11-16: "where is my floating circular
 * thingy … I want this to be visible"). North-up, the van in the middle, the route, and the places
 * that pass the map filter. Zooms with speed: town ~1.2 km across, road ~3 km, motorway ~6 km.
 * Bottom label: the next diesel. Tap = the dock map; drag = move it. Hidden while the dock map is open.
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
    private var lastZoom = 0.0
    private var lastLabelAt = 0L
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val tick = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 1000)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        OsmConfig.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = ctx.packageName
        }
        MapFilter.load(ctx)
        val size = dp(prefs.miniSizeDp.coerceIn(140, 360))
        val frame = FrameLayout(ctx).apply {
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
        feeder = MapFeeder(ctx, map, pois, maxPois = 120)
        frame.addView(map, FrameLayout.LayoutParams(size, size))
        // Ring (GTA-style) + the next-diesel label.
        frame.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(4), Color.argb(230, 17, 24, 39))
            }
        }, FrameLayout.LayoutParams(size, size))
        label = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(Color.argb(210, 17, 24, 39), dp(10).toFloat())
            visibility = View.GONE
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        // Touch catcher: drag moves the window, tap opens the dock map.
        val touch = View(ctx)
        frame.addView(touch, FrameLayout.LayoutParams(size, size))

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
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        touch.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        moved = true
                        params.x = (startX + dx.toInt()).coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
                        root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { prefs.miniX = params.x; prefs.miniY = params.y }
                    else ctx.startActivity(Intent(ctx, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun update() {
        val here = Trip.here(ctx) ?: return
        trip.here = here
        val kmh = if (here.hasSpeed()) here.speed * 3.6f else 0f
        val spanM = when {
            kmh < 35 -> 1_200.0
            kmh < 80 -> 3_000.0
            else -> 6_000.0
        }
        val sizePx = map.width.takeIf { it > 0 } ?: return
        // Web Mercator: metres per pixel = 156543 · cos(lat) / 2^zoom (256 px tiles, scaled to dpi).
        val tilePx = 256 * density
        val zoom = (ln(156_543.03 * cos(Math.toRadians(here.latitude)) * sizePx * 256 / tilePx / spanM) / ln(2.0)).coerceIn(5.0, 18.0)
        if (abs(zoom - lastZoom) > 0.25) { map.controller.setZoom(zoom); lastZoom = zoom }
        map.controller.setCenter(GeoPoint(here.latitude, here.longitude))
        map.invalidate()
        val now = System.currentTimeMillis()
        if (now - lastLabelAt > 10_000) {
            lastLabelAt = now
            Thread {
                val text = nextDiesel(here.latitude, here.longitude, here.speed.takeIf { here.hasSpeed() })
                main.post { label.text = text ?: ""; label.visibility = if (text == null) View.GONE else View.VISIBLE }
            }.start()
        }
    }

    /** "⛽ 12 km · ~8 min" — along the route when on it, else the nearest. */
    private fun nextDiesel(lat: Double, lng: Double, speed: Float?): String? {
        if ("fuel" !in MapFilter.layers(ctx)) return null
        val p = Trip.progress(lat, lng)
        val m = if (p != null && p.offM < 2000) Trip.ahead(p.alongM, setOf("fuel")) { MapFilter.matches(it) }.firstOrNull()?.let { it.km * 1000 - p.alongM }
        else OfflinePois.nearest(ctx, lat, lng, "fuel", 60_000.0, 5).firstOrNull { MapFilter.matches(it) }?.distM
        return m?.let { "⛽ " + aheadText(it, speed).removePrefix("in ") }
    }
}
