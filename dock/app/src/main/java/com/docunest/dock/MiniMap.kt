package com.docunest.dock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.docunest.dock.MiniModes.Mode
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
 * The round GTA-style mini-map floating over Waze (owner 2026-11-16/17). Since the 2026-11-17 drive:
 *  - Modes (MiniModes): City / Autobahn / Explore, Auto picks City or Autobahn by averaged speed.
 *  - Smooth: drawn ~15×/s, the van moved along its course between GPS fixes (was 2×/s, jumpy).
 *  - The blue route line only while we're on the dock's route (it's OSRM's guess, not Waze's).
 *  - Diesel countdown only on Autobahn, whole km.
 *  - 30 % smaller; the zoom dot is faint until touched.
 *  - Tap or hold the middle = a bigger map over Waze (pinch, pan, tap a place → Waze), with the
 *    mode buttons and Hide. Hide switches it off; the "Mini-map" app icon brings it back.
 *  - Drag the middle = move it. The ring = zoom dial (tap the dot = Auto zoom).
 * Hidden while the dock map is open.
 */
class MiniMap(private val ctx: Context, private val wm: WindowManager) {
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var disc: FrameLayout
    private lateinit var map: MapView
    private lateinit var trip: TripOverlay
    private lateinit var feeder: MapFeeder
    private lateinit var label: TextView
    private lateinit var dial: Dial
    private lateinit var panel: LinearLayout
    private lateinit var card: LinearLayout
    private val auto = AutoZoom()
    private val course = MiniModes.OnCourse()
    private var mode = Mode.CITY
    private var lastLabelAt = 0L
    private var fedLat = 0.0; private var fedLng = 0.0; private var fedSpan = 0.0
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var smallPx = 0
    private var bigPx = 0
    private var screenW = 0; private var screenH = 0
    /** Overlays start below the status bar on the unit (seen 2026-11-17: 52 px), so the big view must fit under it. */
    private var topPx = 0

    // Expanded view state.
    private var expanded = false
    /** The owner panned / pinched: stop following until ◎. */
    private var free = false
    private var lastTouchAt = 0L

    // What the frame loop draws towards (set by the slower tick).
    @Volatile private var fix: Location? = null
    private var targetHeading: Float? = null
    private var targetZoom = 0.0
    private var spanNow = 2_000.0
    // What is drawn now.
    private var shownLat = Double.NaN; private var shownLng = 0.0
    private var shownHeading: Float? = null
    private var shownZoom = 0.0

    /** Every 500 ms: position, mode, places, label. */
    private val tick = object : Runnable {
        override fun run() {
            runCatching { update() }.onFailure { DebugLog.log("MINIMAP", "update: ${it.message}") }
            main.postDelayed(this, 500)
        }
    }
    /** ~15×/s: glide the map between fixes (owner: "updates every 2 seconds … keeps jaggering"). */
    private val frame = object : Runnable {
        override fun run() {
            runCatching { drawFrame() }.onFailure { DebugLog.log("MINIMAP", "frame: ${it.message}") }
            main.postDelayed(this, FRAME_MS)
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

    private fun chosenMode(): Mode? = MiniModes.parse(prefs.miniMode)

    /** Metres across now: the dial's choice, else the mode's. */
    private fun spanM(): Double = prefs.miniSpanM.takeIf { it > 0 }?.toDouble() ?: MiniModes.spanM(mode)

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        OsmConfig.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = ctx.packageName
        }
        MapFilter.load(ctx)
        val dm = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        screenW = dm.widthPixels; screenH = dm.heightPixels
        topPx = ctx.resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it > 0 }
            ?.let { ctx.resources.getDimensionPixelSize(it) } ?: dp(24)
        smallPx = dp(prefs.miniSizeDp.coerceIn(110, 300))
        // "Twice as big as it's right now" (240 dp then), as much as the screen allows.
        bigPx = minOf(dp(480), screenH - topPx - dp(4), screenW / 2)
        expanded = false; free = false
        mode = chosenMode() ?: Mode.CITY

        val frameView = FrameLayout(ctx)
        disc = FrameLayout(ctx).apply {
            clipToOutline = true
            elevation = dp(6).toFloat()
        }
        setDiscShape(round = true)
        map = MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
            controller.setZoom(14.0)
        }
        trip = TripOverlay(ctx, drawVan = true)
        val pois = PoiOverlay(ctx) { hits -> if (expanded) hits.firstOrNull()?.let { showCard(it) } }
        map.overlays.add(trip)
        map.overlays.add(pois)
        feeder = MapFeeder(ctx, map, pois, maxPois = 120, autoListen = false)
        // In the big view: the owner moves the map → stop following; feed places for what's shown.
        map.setOnTouchListener { _, e ->
            lastTouchAt = SystemClock.elapsedRealtime()
            if (expanded && e.actionMasked == MotionEvent.ACTION_MOVE) { free = true; feeder.refresh() }
            if (expanded && e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) free = true
            if (expanded && e.actionMasked == MotionEvent.ACTION_UP && free) feeder.refresh()
            false
        }
        disc.addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frameView.addView(disc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        dial = Dial(ctx).apply { elevation = dp(7).toFloat() }
        frameView.addView(dial, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        label = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = rounded(Color.argb(200, 17, 24, 39), dp(10).toFloat())
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        frameView.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        // Big view: buttons down the right edge, place card along the bottom.
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; elevation = dp(9).toFloat() }
        frameView.addView(panel, FrameLayout.LayoutParams(dp(64), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(6), dp(6), 0)
        })
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = rounded(Color.argb(235, 17, 24, 39), dp(12).toFloat())
            visibility = View.GONE
            elevation = dp(9).toFloat()
        }
        frameView.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(6), 0, dp(76), dp(6))
        })
        // Outside the big view: close it (the touch still reaches Waze).
        frameView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && expanded) collapse()
            false
        }

        params = WindowManager.LayoutParams(
            smallPx, smallPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (prefs.miniX.takeIf { it >= 0 } ?: dp(12)).coerceIn(0, (screenW - smallPx).coerceAtLeast(0))
            y = (prefs.miniY.takeIf { it >= 0 } ?: (screenH - smallPx - dp(110))).coerceIn(0, (screenH - smallPx).coerceAtLeast(0))
        }
        setupDialTouch()
        wm.addView(frameView, params)
        root = frameView
        map.onResume()
        MapFilter.addListener(filterListener)
        Trip.addListener(tripListener)
        main.post(tick)
        main.post(frame)
        DebugLog.log("MINIMAP", "shown (${prefs.miniSizeDp} dp, mode ${prefs.miniMode})")
    }

    private fun setDiscShape(round: Boolean) {
        val r = dp(14).toFloat()
        disc.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                if (round) outline.setOval(0, 0, view.width, view.height) else outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        disc.invalidateOutline()
    }

    /** Small circle: ring = zoom dial; middle: drag = move, tap or hold = the big view. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDialTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var onRing = false
        val hold = Runnable { if (!moved && !onRing) expand() }
        dial.setOnTouchListener { _, e ->
            if (expanded) return@setOnTouchListener false
            val size = smallPx
            val cx = size / 2f; val cy = size / 2f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    onRing = hypot(e.x - cx, e.y - cy) > size / 2f - dp(38)
                    if (onRing) dial.active = true
                    else main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        moved = true
                        main.removeCallbacks(hold)
                        if (onRing) {
                            dial.setT(Dial.angleToT(Dial.angleOf(e.x - cx, e.y - cy)), manual = true)
                            applySpan(Dial.tToSpan(dial.t))
                        } else {
                            params.x = (startX + dx.toInt()).coerceIn(0, (screenW - size).coerceAtLeast(0))
                            params.y = (startY + dy.toInt()).coerceIn(0, (screenH - size).coerceAtLeast(0))
                            root?.let { runCatching { wm.updateViewLayout(it, params) } }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    dial.active = false
                    if (e.actionMasked == MotionEvent.ACTION_UP) when {
                        onRing && moved -> prefs.miniSpanM = Dial.tToSpan(dial.t).toInt()
                        onRing && dial.nearKnob(e.x, e.y) -> {
                            prefs.miniSpanM = 0 // tap the dot = Auto zoom
                            flash("Zoom: Auto")
                        }
                        onRing -> {
                            dial.setT(Dial.angleToT(Dial.angleOf(e.x - cx, e.y - cy)), manual = true)
                            prefs.miniSpanM = Dial.tToSpan(dial.t).toInt()
                            applySpan(prefs.miniSpanM.toDouble())
                        }
                        moved -> { prefs.miniX = params.x; prefs.miniY = params.y }
                        !expanded -> expand() // tap: the big view (no longer the full dock map)
                    }
                }
            }
            true
        }
    }

    fun hide() {
        val r = root ?: return
        main.removeCallbacks(tick)
        main.removeCallbacks(frame)
        main.removeCallbacks(idleCollapse)
        MapFilter.removeListener(filterListener)
        Trip.removeListener(tripListener)
        runCatching { map.onPause(); map.onDetach() }
        runCatching { wm.removeView(r) }
        root = null
        shownLat = Double.NaN; shownHeading = null; shownZoom = 0.0
    }

    /** Size changed in Settings. */
    fun relayout() { if (root != null) { hide(); sync() } }

    // --- big view ---------------------------------------------------------------------------------

    private val idleCollapse = object : Runnable {
        override fun run() {
            if (!expanded) return
            if (SystemClock.elapsedRealtime() - lastTouchAt > 60_000) collapse() else main.postDelayed(this, 5_000)
        }
    }

    private fun expand() {
        if (expanded || root == null) return
        expanded = true; free = false
        lastTouchAt = SystemClock.elapsedRealtime()
        // Grow from where the circle is, kept on screen.
        params.width = bigPx; params.height = bigPx
        params.x = (params.x - (bigPx - smallPx) / 2).coerceIn(0, (screenW - bigPx).coerceAtLeast(0))
        params.y = (params.y - (bigPx - smallPx) / 2).coerceIn(0, (screenH - topPx - bigPx).coerceAtLeast(0))
        setDiscShape(round = false)
        dial.visibility = View.GONE
        label.visibility = View.GONE
        map.setMultiTouchControls(true)
        renderPanel()
        panel.visibility = View.VISIBLE
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
        shownZoom = 0.0 // re-fit the zoom to the bigger view
        main.postDelayed({ feeder.refresh() }, 300)
        main.postDelayed(idleCollapse, 5_000)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false; free = false
        main.removeCallbacks(idleCollapse)
        params.width = smallPx; params.height = smallPx
        params.x = (prefs.miniX.takeIf { it >= 0 } ?: dp(12)).coerceIn(0, (screenW - smallPx).coerceAtLeast(0))
        params.y = (prefs.miniY.takeIf { it >= 0 } ?: (screenH - smallPx - dp(110))).coerceIn(0, (screenH - smallPx).coerceAtLeast(0))
        setDiscShape(round = true)
        map.setMultiTouchControls(false)
        panel.visibility = View.GONE
        card.visibility = View.GONE
        dial.visibility = View.VISIBLE
        lastLabelAt = 0L
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
        shownZoom = 0.0
        main.postDelayed({ feeder.refresh() }, 300)
    }

    private fun button(text: String, active: Boolean, size: Float = 15f, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 2
        background = rounded(if (active) Color.rgb(37, 99, 235) else Color.argb(225, 17, 24, 39), dp(12).toFloat())
        setOnClickListener { lastTouchAt = SystemClock.elapsedRealtime(); onClick() }
    }

    /** ✕ close · ◎ follow · Auto · 🏙 City · 🛣 Autobahn · 🧭 Explore · Hide — each ≥ 48 dp. */
    private fun renderPanel() {
        panel.removeAllViews()
        val chosen = prefs.miniMode
        val h = ((bigPx - dp(12)) / 7 - dp(5)).coerceIn(dp(48), dp(60))
        fun add(v: TextView) = panel.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { bottomMargin = dp(5) })
        add(button("✕", false, 20f) { collapse() })
        add(button("◎", !free, 20f) { free = false; shownZoom = 0.0; feeder.refresh(); renderPanel() })
        add(button("Auto\n${if (chosen == MiniModes.AUTO) mode.icon else ""}".trim(), chosen == MiniModes.AUTO, 12f) { setMode(MiniModes.AUTO) })
        for (m in Mode.entries) add(button("${m.icon}\n${m.title}", chosen == m.name, 11f) { setMode(m.name) })
        add(button("Hide", false, 12f) { hideForGood() })
    }

    private fun setMode(m: String) {
        prefs.miniMode = m
        prefs.miniSpanM = 0 // the mode picks the zoom again
        chosenMode()?.let { mode = it }
        DebugLog.log("MINIMAP", "mode: $m")
        applyMode()
        renderPanel()
    }

    /** Owner: "completely hide it … reopen from the launcher". */
    private fun hideForGood() {
        prefs.miniMap = false
        hide()
        Toast.makeText(ctx, "Mini-map hidden — open it again with the “Mini-map” app icon", Toast.LENGTH_LONG).show()
    }

    /** Tap a place in the big view: what it is, how far, Waze / ✕. */
    private fun showCard(p: OfflinePois.Poi) {
        lastTouchAt = SystemClock.elapsedRealtime()
        val here = fix
        val sub = listOfNotNull(
            OfflinePois.LABEL[p.layer], p.brand?.takeIf { it != p.title }, p.info,
            here?.let { km(OfflinePois.distM(it.latitude, it.longitude, p.lat, p.lng).toFloat()) + " away" },
        ).joinToString(" · ")
        card.removeAllViews()
        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(ctx).apply {
            this.text = "${p.icon} ${p.title}"; textSize = 15f; setTextColor(Color.WHITE); paint.isFakeBoldText = true
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        text.addView(TextView(ctx).apply {
            this.text = sub; textSize = 11f; setTextColor(Color.rgb(156, 163, 175)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(button("Waze", true, 15f) {
            Trip.setDockDestination(ctx, p.lat, p.lng, p.title)
            Nav.goTo(ctx, p.lat, p.lng, Prefs.NAV_WAZE)
            collapse()
        }.apply { background = rounded(Color.rgb(51, 204, 255), dp(12).toFloat()); setTextColor(Color.BLACK) },
            LinearLayout.LayoutParams(dp(76), dp(52)).apply { marginStart = dp(6) })
        card.addView(button("✕", false, 18f) { card.visibility = View.GONE },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(6) })
        card.visibility = View.VISIBLE
    }

    // --- following --------------------------------------------------------------------------------

    /** While the dial turns: zoom now and show the span in the label. */
    private fun applySpan(spanM: Double) {
        spanNow = spanM
        targetZoom = zoomFor(spanM)
        shownZoom = 0.0
        label.text = "🔍 " + km(spanM.toFloat()) + " across"
        label.visibility = View.VISIBLE
        lastLabelAt = System.currentTimeMillis() - 7_000 // back to normal in ~3 s
    }

    private fun flash(text: String) {
        if (expanded) return
        label.text = text
        label.visibility = View.VISIBLE
        lastLabelAt = System.currentTimeMillis() - 7_000
    }

    /** What the mode decides beyond the zoom: route line, places, label. */
    private fun applyMode() {
        feeder.corridor = mode == Mode.AUTOBAHN
        feeder.refresh()
        if (mode != Mode.AUTOBAHN && !expanded) label.visibility = View.GONE
        lastLabelAt = 0L
    }

    private fun zoomFor(spanM: Double): Double {
        val here = fix ?: return 14.0
        val sizePx = (if (expanded) bigPx else smallPx).takeIf { it > 0 } ?: return 14.0
        // Web Mercator: metres per pixel = 156543 · cos(lat) / 2^zoom (256 px tiles, scaled to dpi).
        val tilePx = 256 * density
        return (ln(156_543.03 * cos(Math.toRadians(here.latitude)) * sizePx * 256 / tilePx / spanM) / ln(2.0)).coerceIn(5.0, 18.0)
    }

    /** The slow part (every 500 ms): fix, mode, heading target, places, label. */
    private fun update() {
        val here = Trip.here(ctx) ?: return
        fix = here
        auto.feed(here)
        val prevMode = mode
        mode = chosenMode() ?: MiniModes.detect(auto.avgKmh, mode)
        if (mode != prevMode) {
            DebugLog.log("MINIMAP", "auto mode: ${mode.title} (avg ${auto.avgKmh.toInt()} km/h)")
            if (prefs.miniSpanM == 0) shownZoom = 0.0
            applyMode()
            flash("${mode.icon} ${mode.title}")
            if (expanded) renderPanel()
        }
        dial.police = Police.active != null && prefs.policeFlash

        val p = Trip.progress(here.latitude, here.longitude)
        val onCourse = course.feed(p?.offM, System.currentTimeMillis())
        trip.showRoute = mode != Mode.EXPLORE && onCourse

        // Heading: along the planned road when on it (steady), else the GPS course while moving.
        val r = Trip.route
        targetHeading = if (r != null && !r.straight && p != null && onCourse && p.leftM > 300) {
            val (a1, b1) = RouteGeo.pointAt(r.lat, r.lng, r.cum, p.alongM)
            val (a2, b2) = RouteGeo.pointAt(r.lat, r.lng, r.cum, p.alongM + 250)
            bearing(a1, b1, a2, b2)
        } else if (here.hasBearing() && here.hasSpeed() && here.speed > 2.5f) here.bearing else targetHeading

        spanNow = spanM()
        if (!dial.active) dial.setT(Dial.spanToT(spanNow), manual = prefs.miniSpanM > 0)
        targetZoom = zoomFor(spanNow)

        // Places: re-read when the view moved a good bit (not every frame).
        if (!free && !shownLat.isNaN() && (RouteGeo.distM(shownLat, shownLng, fedLat, fedLng) > spanNow * 0.15 || abs(spanNow - fedSpan) > fedSpan * 0.2)) {
            fedLat = shownLat; fedLng = shownLng; fedSpan = spanNow
            feeder.refresh()
        }
        // Diesel countdown: Autobahn only, every 15 s, whole km (owner: precision doesn't matter there).
        val now = System.currentTimeMillis()
        if (!expanded && now - lastLabelAt > 15_000) {
            lastLabelAt = now
            if (mode != Mode.AUTOBAHN) label.visibility = View.GONE
            else Thread {
                val m = dieselM(here.latitude, here.longitude)
                main.post {
                    if (expanded || mode != Mode.AUTOBAHN) return@post
                    label.text = m?.let { MiniModes.dieselText(it) } ?: ""
                    label.visibility = if (m == null) View.GONE else View.VISIBLE
                }
            }.start()
        }
    }

    /** The fast part (~15×/s): glide position, heading and zoom towards their targets. */
    private fun drawFrame() {
        val f = fix ?: return
        if (map.width == 0) return
        if (expanded && free) return // the owner is looking around
        val ageS = (SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1e9
        val course = targetHeading ?: (if (f.hasBearing()) f.bearing else 0f)
        val (pLat, pLng) = MiniModes.predict(f.latitude, f.longitude, if (f.hasSpeed()) f.speed else 0f, if (f.hasBearing()) f.bearing else course, ageS)
        var changed = false
        if (shownLat.isNaN() || RouteGeo.distM(shownLat, shownLng, pLat, pLng) > 300) {
            shownLat = pLat; shownLng = pLng; changed = true
        } else {
            val dLat = pLat - shownLat; val dLng = pLng - shownLng
            if (abs(dLat) > 1e-7 || abs(dLng) > 1e-7) { shownLat += dLat * 0.35; shownLng += dLng * 0.35; changed = true }
        }
        val hd = shownHeading.let { s ->
            if (s == null) course
            else {
                val turn = MiniModes.turn(s, course)
                if (abs(turn) > 0.3f) { changed = true; (s + turn * 0.15f + 360f) % 360f } else s
            }
        }
        shownHeading = hd
        if (shownZoom == 0.0) { shownZoom = targetZoom; map.controller.setZoom(shownZoom); changed = true }
        else if (abs(targetZoom - shownZoom) > 0.01) {
            shownZoom += (targetZoom - shownZoom) * 0.2
            map.controller.setZoom(shownZoom); changed = true
        }
        if (!changed) return
        val shown = Location(f).apply { latitude = shownLat; longitude = shownLng }
        trip.here = shown
        trip.heading = hd
        map.mapOrientation = -hd
        // The van sits low: the centre is 28 % of the span ahead (big view: 20 %).
        val ahead = spanNow * if (expanded) 0.20 else 0.28
        val b = Math.toRadians(hd.toDouble())
        val cLat = shownLat + ahead * cos(b) / 111_320.0
        val cLng = shownLng + ahead * sin(b) / (111_320.0 * cos(Math.toRadians(shownLat)))
        map.controller.setCenter(GeoPoint(cLat, cLng))
        map.invalidate()
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = lat2 - lat1
        return ((Math.toDegrees(atan2(x, y)) + 360) % 360).toFloat()
    }

    /** Metres to the next diesel: along the route when on it, else the nearest. */
    private fun dieselM(lat: Double, lng: Double): Double? {
        if ("fuel" !in MapFilter.layers(ctx)) return null
        val p = Trip.progress(lat, lng)
        return if (p != null && p.offM < 2000) Trip.ahead(p.alongM, setOf("fuel")) { MapFilter.matches(it) }.firstOrNull()?.let { it.km * 1000 - p.alongM }
        else OfflinePois.nearest(ctx, lat, lng, "fuel", 60_000.0, 5).firstOrNull { MapFilter.matches(it) }?.distM
    }

    /**
     * The ring and the zoom dot (thermostat-style: a 270° sweep, the gap at the bottom holds the label).
     * Owner 2026-11-17: the orange dot was too much — faint grey until touched.
     */
    private class Dial(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.argb(200, 17, 24, 39) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        private val oval = RectF()
        var t = 0.5f
            private set
        private var manual = false
        /** Finger on the ring: full contrast. */
        var active = false
            set(v) { if (v != field) { field = v; invalidate() } }
        /** Police ahead: the ring flashes red / blue too. */
        var police = false
            set(v) { if (v != field) { field = v; invalidate() } }
        private val knobR get() = (if (active) 13 else 9) * d
        private val trackR get() = width / 2f - 13 * d

        fun setT(v: Float, manual: Boolean) {
            if (abs(v - t) < 0.002f && manual == this.manual) return
            t = v.coerceIn(0f, 1f); this.manual = manual; invalidate()
        }

        private fun knobXY(): Pair<Float, Float> {
            val a = Math.toRadians((-135.0 + 270.0 * t))
            return (width / 2f + trackR * sin(a)).toFloat() to (height / 2f - trackR * cos(a)).toFloat()
        }

        fun nearKnob(x: Float, y: Float): Boolean = knobXY().let { (kx, ky) -> hypot(x - kx, y - ky) < 28 * d }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (police) {
                val red = (SystemClock.elapsedRealtime() / 500) % 2 == 0L
                ring.color = if (red) Color.rgb(255, 30, 30) else Color.rgb(40, 100, 255)
                ring.strokeWidth = 8 * d
                postInvalidateDelayed(250)
            } else { ring.color = Color.argb(200, 17, 24, 39); ring.strokeWidth = 4 * d }
            c.drawCircle(cx, cy, width / 2f - ring.strokeWidth / 2, ring)
            oval.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
            track.color = if (active) Color.argb(170, 255, 255, 255) else Color.argb(55, 255, 255, 255)
            // Android arcs start at 3 o'clock; the sweep starts at 7:30 (−135° from the top).
            c.drawArc(oval, 135f, 270f, false, track)
            val (kx, ky) = knobXY()
            if (active) {
                knob.color = if (manual) Color.rgb(245, 158, 11) else Color.rgb(22, 163, 74)
                knobEdge.color = Color.WHITE
                letter.color = Color.WHITE
            } else {
                knob.color = Color.argb(110, 31, 41, 55)
                knobEdge.color = Color.argb(120, 255, 255, 255)
                letter.color = Color.argb(170, 255, 255, 255)
            }
            letter.textSize = (if (active) 12 else 9) * d
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

    companion object {
        /** ~15 frames/s: smooth enough, light on the msm8953. */
        const val FRAME_MS = 66L
    }
}
