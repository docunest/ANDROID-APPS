package com.docunest.dock

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * The full dock window: app tiles, "save this spot", and every place sent
 * from the phone or bookmarked — as a list (tap the name = drive there) and
 * on a map (tap a pin, or long-press anywhere, then Drive).
 */
class DockActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var map: MapView
    private lateinit var bar: LinearLayout
    private lateinit var gpsChip: TextView
    private lateinit var skyChip: TextView
    private lateinit var wifiChip: TextView
    private val gpsTicker = object : Runnable {
        override fun run() {
            renderGps()
            follow()
            if (skyOverlay?.isEnabled == true && ::map.isInitialized) map.invalidate() // satellites move / change
            gpsHandler.postDelayed(this, 2000)
        }
    }
    private val gpsHandler = Handler(Looper.getMainLooper())
    private var myLocation: MyLocationNewOverlay? = null
    private var skyOverlay: SkyMapOverlay? = null
    private var dropped: Marker? = null
    private var centeredOnce = false
    private var showMore = false
    /** Category chip chosen above the list (null = all; [Kinds.CITY] = the 🏙 badge). */
    private var kind: String? = null
    private lateinit var kinds: LinearLayout
    private val storeListener: () -> Unit = { render() }

    // Road ahead on this map (owner 2026-11-16): places from the offline packs, the trip, the views.
    private lateinit var poiOverlay: PoiOverlay
    private lateinit var tripOverlay: TripOverlay
    private lateinit var feeder: MapFeeder
    private lateinit var groupChips: LinearLayout
    private lateinit var viewChips: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var tripRow: LinearLayout
    private lateinit var tripText: TextView
    private lateinit var placesCol: View
    private lateinit var mapFrameView: View
    private lateinit var statusChips: View
    private lateinit var spanLabel: TextView
    private val autoZoom = AutoZoom()
    /** Left column: "places" (sent / saved), "route" (along the route), "near" (around the van). */
    private var listMode = "places"
    /** The map follows the van (Auto / 3 / 25 / 80 km) until it's dragged. */
    private var following = true
    private var lastListAt = 0L
    private val tripListener: () -> Unit = {
        if (::feeder.isInitialized) feeder.refresh()
        renderTrip()
        if (listMode != "places") render()
        if (::map.isInitialized) map.invalidate()
    }
    private val filterListener: () -> Unit = {
        renderGroups()
        if (::feeder.isInitialized) feeder.refresh()
        if (listMode != "places") render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        OsmConfig.getInstance().apply {
            load(this@DockActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        Sync.init(this)
        WifiDoctor.start(this)
        showMore = intent?.getBooleanExtra(EXTRA_SHOW_MORE, false) == true
        build()
        val missing = buildList {
            if (!Locator.hasPermission(this@DockActivity)) add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7)
    }

    override fun onResume() {
        super.onResume()
        current = this
        Store.addListener(storeListener)
        Updater.addListener(updaterListener)
        MapFilter.load(this)
        Trip.init(this)
        Trip.addListener(tripListener)
        MapFilter.addListener(filterListener)
        renderVersion()
        renderGroups()
        renderTrip()
        if (::feeder.isInitialized) feeder.refresh()
        map.onResume()
        runCatching { myLocation?.enableMyLocation() }
        render()
        gpsHandler.post(gpsTicker)
        Sync.pollSoon()
        Locator.current(this) { loc ->
            if (loc != null) {
                lastLocation = loc
                render()
                if (!centeredOnce && Store.all().none { it.isNew }) {
                    map.controller.setZoom(11.0)
                    map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        }
        // Make sure the dock service (polling + button) is running.
        DockService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_MORE, false)) showMore = true
    }

    override fun onPause() {
        Store.removeListener(storeListener)
        Updater.removeListener(updaterListener)
        Trip.removeListener(tripListener)
        MapFilter.removeListener(filterListener)
        gpsHandler.removeCallbacks(gpsTicker)
        runCatching { myLocation?.disableMyLocation() }
        map.onPause()
        if (current === this) current = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { myLocation?.disableMyLocation(); map.onPause(); map.onDetach() }
        centeredOnce = false
        build()
        map.onResume()
        runCatching { myLocation?.enableMyLocation() }
        render()
    }

    // --- Layout -------------------------------------------------------------

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // Actions (apps live in the press-and-slide menu — not repeated here)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(bigButton("★  Save this spot", Palette.AMBER, 18f) { Actions.bookmarkHere(this) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        top.addView(space(8))
        top.addView(versionBadge(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(64)))
        top.addView(space(8))
        top.addView(bigButton("⚙", Palette.CARD, 20f) { startActivity(Intent(this, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        top.addView(space(8))
        top.addView(bigButton("✕", Palette.CARD, 20f) { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))

        // Places list
        val placesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (landscape) {
            placesCol.addView(top)
            placesCol.addView(space(8))
        }
        if (!Settings.canDrawOverlays(this)) {
            placesCol.addView(bigButton("Setup needed — tap to grant permissions", Palette.RED, 14f) {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
        }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        placesCol.addView(tabs)
        tripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#172554"), dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(4), dp(4))
            visibility = View.GONE
        }
        tripText = label("", 14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        tripRow.addView(tripText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tripRow.addView(bigButton("↻", Palette.CARD_HI, 16f) { Trip.reroute(this); toast("New route from here…") }, LinearLayout.LayoutParams(dp(48), dp(48)))
        tripRow.addView(space(4))
        tripRow.addView(bigButton("✕", Palette.CARD_HI, 16f) {
            AlertDialog.Builder(this).setMessage("End this trip? (The route leaves the map.)")
                .setPositiveButton("End trip") { _, _ -> Trip.end(this) }.setNegativeButton("Keep", null).show()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        placesCol.addView(tripRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) })
        // (No "Places" heading: the tab says it. Sync status sits under the list.)
        status = label("", 11f, Palette.MUTED)
        kinds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        placesCol.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(kinds)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        placesCol.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        placesCol.addView(status)
        this.placesCol = placesCol

        // Map
        val mapFrame = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(40.2, -4.0)) // Iberia until we know better
        }
        mapFrame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tripOverlay = TripOverlay(this, drawVan = false)
        poiOverlay = PoiOverlay(this) { hits -> tapPois(hits) }
        map.overlays.add(tripOverlay)
        map.overlays.add(poiOverlay)
        feeder = MapFeeder(this, map, poiOverlay)
        // Dragging the map stops following the van (a view button starts it again).
        map.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && following) { following = false; renderViews() }
            false
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(240, 17, 24, 39), dp(14).toFloat())
            setPadding(dp(12), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        mapFrame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        // GPS and Wi-Fi state at a glance; tap = the doctor screens (diagnose + fix).
        fun chip(text: String, target: Class<*>) = label(text, 12f, bold = true).apply {
            background = rounded(Color.argb(215, 17, 24, 39), dp(10).toFloat())
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            setOnClickListener { startActivity(Intent(this@DockActivity, target)) }
        }
        gpsChip = chip("📡 GPS", GpsDoctorActivity::class.java)
        wifiChip = chip("📶 Wi-Fi", WifiDoctorActivity::class.java)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        chips.addView(gpsChip)
        chips.addView(space(6))
        skyChip = chip("☀ Clear sky", GpsDoctorActivity::class.java).apply {
            setTextColor(Palette.AMBER)
            setOnClickListener { ClearSky.hint(this@DockActivity); renderGps() }
        }
        chips.addView(skyChip)
        chips.addView(space(6))
        chips.addView(wifiChip)
        chips.addView(space(6))
        chips.addView(chip("🛰", GpsDoctorActivity::class.java).apply {
            alpha = if (prefs.skyOnMap) 1f else 0.5f
            setOnClickListener { v ->
                prefs.skyOnMap = !prefs.skyOnMap
                skyOverlay?.isEnabled = prefs.skyOnMap
                v.alpha = if (prefs.skyOnMap) 1f else 0.5f
                map.invalidate()
            }
        })
        // GPS / Wi-Fi state: small, bottom-left (hidden while a place card is open there).
        statusChips = chips
        mapFrame.addView(chips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(84), dp(8))
        })
        // What's on the map: one square icon button per kind (owner 2026-11-17: less crowded, still easy to hit).
        groupChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapFrame.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(groupChips)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(84), dp(8))
        })
        // Views: Auto · ＋ · span · － · whole route · ⤢ full width · mini-map switch.
        viewChips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mapFrame.addView(viewChips, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        renderGroups()
        renderViews()
        renderGps()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideBar()
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) dropPin(p)
                return true
            }
        }))
        myLocation = runCatching {
            MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { map.overlays.add(it) }
        }.getOrNull()
        // The satellites around the van, north-up like the map (🛰 chip toggles it).
        skyOverlay = SkyMapOverlay {
            (GpsDoctor.fix ?: lastLocation)?.let { GeoPoint(it.latitude, it.longitude) }
        }.also { it.isEnabled = prefs.skyOnMap; map.overlays.add(it) }

        mapFrameView = mapFrame
        if (landscape) {
            root.addView(placesCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(10) })
            root.addView(mapFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
        } else {
            root.addView(top)
            root.addView(space(10))
            root.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(space(10))
            root.addView(placesCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        applyWide()
    }

    /** ⤢: the map takes the whole window (the list hides); again = back. */
    private fun applyWide() {
        if (!::placesCol.isInitialized) return
        placesCol.visibility = if (prefs.mapWide) View.GONE else View.VISIBLE
    }

    // --- Version badge: "v15 · Up to date", spinner while checking/installing; tap = check now.
    private lateinit var versionText: TextView
    private lateinit var versionSpinner: android.widget.ProgressBar
    private val updaterListener: () -> Unit = { renderVersion() }

    private fun versionBadge(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.CARD, dp(14).toFloat())
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        setOnClickListener { Updater.checkSoon(this@DockActivity, force = true) }
        versionSpinner = android.widget.ProgressBar(context).apply { isIndeterminate = true }
        addView(versionSpinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        versionText = label("", 13f, Palette.MUTED).apply { maxLines = 2; maxWidth = dp(170) }
        addView(versionText)
        renderVersion()
    }

    private fun renderVersion() {
        if (!::versionText.isInitialized) return
        versionText.text = "v${BuildConfig.VERSION_CODE}\n${Updater.status}"
        versionSpinner.visibility = if (Updater.busy) View.VISIBLE else View.GONE
    }

    /** The 📡 chip: green with a fresh GPS fix (< 30 s), amber without one, red when GPS is off. */
    @SuppressLint("MissingPermission") // checked by Locator.hasPermission()
    private fun renderGps() {
        if (!::gpsChip.isInitialized) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val allowed = Locator.hasPermission(this)
        val last = if (allowed) runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else null
        val fresh = last != null && SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos < 30_000_000_000L
        val auto = AutoDoctor.call?.mode
        val (text, color) = when {
            !allowed || !GpsDoctor.locationOn(this) || !GpsDoctor.gpsEnabled(lm) -> "📡 GPS off · fix" to Palette.RED
            ParkedPosition.mocking -> "📡 Parked spot · no GPS yet" to Palette.WAZE
            fresh && last!!.isMockFix() -> "📡 Fake position (mock app?)" to Palette.AMBER
            fresh -> "📡 GPS ±${last!!.accuracy.toInt()} m" to Palette.GMAPS
            GpsDoctor.running -> "📡 Fixing GPS…" to Palette.AMBER
            auto == SkyJudge.Mode.TUNNEL -> "📡 Tunnel · waiting" to Palette.MUTED
            auto == SkyJudge.Mode.COVERED || auto == SkyJudge.Mode.DEAD_ZONE -> "📡 Under cover · waiting" to Palette.MUTED
            else -> "📡 No GPS fix · fix" to Palette.AMBER
        }
        gpsChip.text = text
        gpsChip.setTextColor(color)
        if (::skyChip.isInitialized) {
            skyChip.visibility = if (fresh || ParkedPosition.mocking) View.GONE else View.VISIBLE
            skyChip.text = if (ClearSky.active) "☀ Fixing now" else "☀ Clear sky"
        }

        // Wi-Fi: the watchdog in the dock service keeps this current.
        val w = WifiDoctor
        val now = SystemClock.elapsedRealtime()
        val (wText, wColor) = when {
            !w.enabled -> "📶 Wi-Fi off" to Palette.RED
            !w.connected && w.downSince == 0L -> "📶 Wi-Fi…" to Palette.MUTED
            !w.connected -> "📶 Wi-Fi lost ${GpsDoctor.duration(now - w.downSince)}" to Palette.RED
            w.internet == false -> "📶 no internet" to Palette.AMBER
            else -> "📶 ${w.ssid?.take(14) ?: "Wi-Fi"}" to (if (w.internet == true) Palette.GMAPS else Palette.TEXT)
        }
        wifiChip.text = wText
        wifiChip.setTextColor(wColor)
    }

    private fun space(d: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(d), dp(d)) }

    // --- Content ------------------------------------------------------------

    private fun render() {
        if (!::list.isInitialized) return
        status.text = Store.status
        val all = Store.sorted()
        renderKinds(all)
        val items = all.filter { matches(it) }
        val here = Near.here(this)
        val (main, more) = items.partition { Near.isMain(here, it) }
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(label("Nothing yet. Share a Google Maps place from the phone to \"Send to car\", or tap ★ Save this spot.", 15f, Palette.MUTED).apply {
                setPadding(0, dp(16), 0, 0)
            })
        }
        renderTabs()
        if (listMode != "places") {
            list.removeAllViews()
            renderRoadList()
            renderMarkers(if (showMore) items else main)
            return
        }
        main.forEach { list.addView(row(it)) }
        if (more.isNotEmpty()) {
            // Further than 300 km and older than 3 days: history, one tap away (map too).
            list.addView(bigButton(if (showMore) "Hide far & older places" else "Show more (${more.size}) — over 300 km & older than 3 days", Palette.CARD_HI, 15f) {
                showMore = !showMore
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(8), 0, dp(4)) }
            })
            if (showMore) more.forEach { list.addView(row(it)) }
        }
        renderMarkers(if (showMore) items else main)
    }

    private fun matches(i: Item) = when (val k = kind) {
        null -> true
        Kinds.CITY -> i.bigCity
        else -> i.category == k
    }

    /** ⛺ 3 · 🛒 6 · 🅿️ 5 … chips for the kinds in the history; tap = only those, again = all. */
    private fun renderKinds(items: List<Item>) {
        kinds.removeAllViews()
        val counts = Kinds.ORDER.map { k -> k to items.count { it.category == k } }.filter { it.second > 0 } +
            listOf(Kinds.CITY to items.count { it.bigCity }).filter { it.second > 0 }
        if (counts.size < 2) {
            kinds.visibility = View.GONE
            return
        }
        kinds.visibility = View.VISIBLE
        for ((k, n) in counts) {
            val on = kind == k
            val text = (if (k == Kinds.CITY) "🏙️ Cities" else "${Kinds.ICON[k]} ${Kinds.LABEL[k]}") + "  $n"
            kinds.addView(bigButton(text, if (on) Palette.BLUE else Palette.CARD, 14f) {
                kind = if (on) null else k
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { setMargins(0, dp(4), dp(6), dp(6)) })
        }
    }

    private fun subtitle(item: Item): String {
        val bits = mutableListOf<String>()
        item.kindIcon.takeIf { it.isNotEmpty() }?.let { bits += it }
        Near.label(Near.here(this), item)?.let { bits += it }
        Near.town(item.address, item.title)?.let { bits += it }
        if (item.isNew) bits += "NEW"
        bits += if (item.kind == "bookmark") "★ saved ${ago(item.createdAt)}" else "sent ${ago(item.createdAt)}"
        if (item.approximate) bits += "approx."
        if (!item.hasPoint) bits += "no pin"
        if (item.pending) bits += "not synced"
        if (item.openCount > 0) bits += "driven ${item.openCount}×"
        return bits.joinToString(" · ")
    }

    private fun row(item: Item): View {
        val nav = prefs.defaultNav
        val alt = if (nav == Prefs.NAV_GMAPS) Prefs.NAV_WAZE else Prefs.NAV_GMAPS
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (item.isNew) Color.parseColor("#3B2A0A") else Palette.CARD, dp(14).toFloat(),
                if (item.pinned) Palette.AMBER else null, dp(2))
            setPadding(dp(14), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        }
        // Photo of the place (kept 30 days) and the map of the area around it. Tap either = show on the big map.
        Photos.get(this, item)?.let { bmp ->
            r.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
                contentDescription = Photos.credit(this@DockActivity, item)
                setOnClickListener { focus(item) }
                setOnLongClickListener {
                    Toast.makeText(this@DockActivity, "📷 " + (Photos.credit(this@DockActivity, item) ?: "photo"), Toast.LENGTH_SHORT).show(); true
                }
            }, LinearLayout.LayoutParams(dp(96), dp(64)).apply { marginEnd = dp(8) })
        }
        val thumb = Thumbs.get(this, item)
        thumb?.let { bmp ->
            r.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
                setOnClickListener { focus(item) }
            }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            // The name is the button: one tap = navigate with the usual app.
            setOnClickListener { Nav.go(this@DockActivity, item, nav) }
            setOnLongClickListener { itemMenu(item); true }
        }
        text.addView(label((if (item.pinned) "📌 " else "") + item.title, 19f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        text.addView(label(subtitle(item), 13f, if (item.isNew) Palette.AMBER else Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Debug (temporary): every Waze method as a button.
        r.addView(bigButton("🧪", Palette.CARD_HI, 16f) {
            startActivity(NavTestActivity.intent(this, item.lat, item.lng, item.title, item.address, item.resolvedUrl ?: item.sourceUrl))
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(6) })
        if (item.hasPoint && thumb == null) {
            r.addView(bigButton("🗺", Palette.CARD_HI, 16f) { focus(item) }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(6) })
        }
        r.addView(
            bigButton(if (alt == Prefs.NAV_GMAPS) "G" else "W", if (alt == Prefs.NAV_GMAPS) Palette.GMAPS else Palette.WAZE, 18f) {
                // G = the place in Google Maps beside Waze (photos, reviews, hours; Directions one tap there).
                if (alt == Prefs.NAV_GMAPS) Split.preview(this, item) else Nav.go(this, item, alt)
            },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        return r
    }

    private fun itemMenu(item: Item) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "🧪 Waze test buttons" to {
            startActivity(NavTestActivity.intent(this, item.lat, item.lng, item.title, item.address, item.resolvedUrl ?: item.sourceUrl))
        }
        actions += (if (item.pinned) "Unpin" else "Pin to top") to { Sync.patch(item.id, JSONObject().put("pinned", !item.pinned)) }
        val url = item.resolvedUrl ?: item.sourceUrl
        if (url != null) actions += "Open original link" to {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Unit
        }
        actions += "Delete" to { Sync.delete(item.id) }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    // --- Map ----------------------------------------------------------------

    private fun renderMarkers(items: List<Item>) {
        map.overlays.removeAll { it is Marker && it !== dropped }
        val withPoints = items.filter { it.hasPoint }
        for (item in withPoints) {
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(item.lat!!, item.lng!!)
                title = item.title
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // Faded = approximate (found by name search), so you know to look closer.
                alpha = if (item.approximate) 0.55f else 1f
                setOnMarkerClickListener { _, _ -> showBar(item.title, subtitle(item), item.lat, item.lng, item); true }
            })
        }
        map.invalidate()
        if (!centeredOnce && withPoints.isNotEmpty()) {
            centeredOnce = true
            val newest = withPoints.firstOrNull { it.isNew }
            map.post {
                if (newest != null) {
                    following = false
                    renderViews()
                    map.controller.setZoom(13.0)
                    map.controller.setCenter(GeoPoint(newest.lat!!, newest.lng!!))
                    showBar(newest.title, subtitle(newest), newest.lat, newest.lng, newest)
                } else {
                    val pts = withPoints.take(15).map { GeoPoint(it.lat!!, it.lng!!) } +
                        listOfNotNull(lastLocation?.let { GeoPoint(it.latitude, it.longitude) })
                    if (pts.size == 1) {
                        map.controller.setZoom(12.0); map.controller.setCenter(pts[0])
                    } else {
                        runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false) }
                    }
                }
            }
        }
    }

    private fun focus(item: Item) {
        map.controller.animateTo(GeoPoint(item.lat!!, item.lng!!), 14.0, 600L)
        showBar(item.title, subtitle(item), item.lat, item.lng, item)
    }

    private fun dropPin(p: GeoPoint) {
        dropped?.let { map.overlays.remove(it) }
        dropped = Marker(map).apply {
            position = p
            title = "Dropped pin"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }.also { map.overlays.add(it) }
        map.invalidate()
        showBar("Dropped pin", "%.5f, %.5f".format(p.latitude, p.longitude), p.latitude, p.longitude, null, asTrip = true)
    }

    /** Bottom bar on the map: what's selected + one-tap Drive. */
    private fun showBar(title: String, sub: String, lat: Double, lng: Double, item: Item?, asTrip: Boolean = false) {
        val nav = prefs.defaultNav
        val alt = if (nav == Prefs.NAV_GMAPS) Prefs.NAV_WAZE else Prefs.NAV_GMAPS
        bar.removeAllViews()
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        text.addView(label(sub, 12f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        bar.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun go(app: String) {
            if (asTrip) Trip.setDockDestination(this, lat, lng, title)
            if (item != null) Nav.go(this, item, app) else Nav.goTo(this, lat, lng, app)
        }
        bar.addView(bigButton(if (nav == Prefs.NAV_GMAPS) "Drive · Maps" else "Drive · Waze", if (nav == Prefs.NAV_GMAPS) Palette.GMAPS else Palette.WAZE, 16f) { go(nav) })
        bar.addView(space(6))
        bar.addView(bigButton(if (alt == Prefs.NAV_GMAPS) "G" else "W", Palette.CARD_HI, 16f) {
            if (alt == Prefs.NAV_GMAPS && item != null) Split.preview(this, item) else go(alt)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.addView(space(6))
        bar.addView(bigButton("🧪", Palette.CARD_HI, 16f) {
            startActivity(NavTestActivity.intent(this, lat, lng, title, item?.address, item?.let { it.resolvedUrl ?: it.sourceUrl }))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.visibility = View.VISIBLE
        statusChips.visibility = View.GONE
    }

    private fun hideBar() {
        bar.visibility = View.GONE
        if (::statusChips.isInitialized) statusChips.visibility = View.VISIBLE
    }

    // --- Road ahead ---------------------------------------------------------------------------------

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** ⛽ 🅿️ 🛒 🚻 ⛺ ⚠️ 🛝 🏰 on/off (faded = off) + 🔎 name filter. */
    private fun renderGroups() {
        if (!::groupChips.isInitialized) return
        groupChips.removeAllViews()
        val on = MapFilter.groups(this)
        for (g in OfflinePois.GROUPS) {
            val active = g.id in on
            groupChips.addView(mapChip(g.icon, active, square = true) {
                MapFilter.toggle(this, g.id)
                toast("${g.icon} ${g.label}: ${if (active) "hidden" else "shown"}")
            }.apply {
                setOnLongClickListener { toast("${g.icon} ${g.label} — tap to ${if (active) "hide" else "show"}"); true }
            })
        }
        val t = MapFilter.text
        groupChips.addView(mapChip(if (t.isEmpty()) "🔎" else "🔎 $t ✕", t.isNotEmpty(), square = t.isEmpty()) {
            if (t.isNotEmpty()) MapFilter.setText(this, "") else askFilter()
        })
    }

    /** A map button: at least 48 dp, so it's easy to hit while driving. [square] = one icon, 52 × 48. */
    private fun mapChip(text: String, active: Boolean, square: Boolean = false, onClick: () -> Unit) = label(text, if (square) 20f else 13f, bold = active).apply {
        background = rounded(if (active) Color.argb(235, 37, 99, 235) else Color.argb(200, 17, 24, 39), dp(12).toFloat())
        alpha = if (active) 1f else 0.55f
        gravity = Gravity.CENTER
        minHeight = dp(48)
        if (square) minWidth = dp(52)
        setPadding(dp(if (square) 4 else 10), dp(4), dp(if (square) 4 else 10), dp(4))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
    }

    private fun askFilter() {
        val input = android.widget.EditText(this).apply { hint = "lidl, mercadona, 24/7, diesel ✓ …"; setSingleLine(); setText(MapFilter.text) }
        AlertDialog.Builder(this).setTitle("Show only places matching")
            .setView(input)
            .setPositiveButton("Filter") { _, _ -> MapFilter.setText(this, input.text.toString()) }
            .setNeutralButton("Clear") { _, _ -> MapFilter.setText(this, "") }
            .show()
    }

    private fun renderViews() {
        if (!::viewChips.isInitialized) return
        viewChips.removeAllViews()
        val mode = prefs.mapView
        fun add(text: String, active: Boolean, size: Float = 13f, onClick: () -> Unit) =
            viewChips.addView(mapChip(text, active, onClick = onClick).apply {
                textSize = size
                layoutParams = LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            })
        add("Auto", following && mode == MapViews.AUTO) { setView(MapViews.AUTO) }
        add("＋", false, 22f) { zoomBy(closer = true) }
        spanLabel = label("", 12f, bold = true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        viewChips.addView(spanLabel, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        add("－", false, 22f) { zoomBy(closer = false) }
        add("Route", following && mode == MapViews.ROUTE) {
            if (Trip.route == null) toast("No trip yet — start navigation in Waze, or tap a place → Waze") else setView(MapViews.ROUTE)
        }
        add(if (prefs.mapWide) "⤡" else "⤢", prefs.mapWide, 20f) {
            prefs.mapWide = !prefs.mapWide
            applyWide()
            renderViews()
            map.postDelayed({ follow(force = true) }, 300)
        }
        add(if (prefs.miniMap) "◉ Mini" else "○ Mini", false, 12f) {
            prefs.miniMap = !prefs.miniMap
            DockService.start(this, DockService.ACTION_MINIMAP)
            toast(if (prefs.miniMap) "Mini-map on — shows over Waze when this screen is closed" else "Mini-map off")
            renderViews()
        }
        renderSpan()
    }

    /** "25 km" under ＋ (what the map shows across now; "Auto 25 km" when Auto picked it). */
    private fun renderSpan() {
        if (!::spanLabel.isInitialized || !::map.isInitialized || map.width == 0) return
        val k = MapViews.spanKm(map)
        val t = if (k >= 10) "${k.toInt()} km" else if (k >= 1) "%.1f km".format(k) else "${(k * 1000).toInt()} m"
        spanLabel.text = if (following && prefs.mapView == MapViews.AUTO) "Auto\n$t" else t
    }

    /** ＋ / －: the next fixed span from what's shown now; follows the van again. */
    private fun zoomBy(closer: Boolean) {
        val now = MapViews.spanKm(map)
        val next = MapViews.zoomStep(now, closer)
        if (Trip.here(this) == null || !following) {
            following = false
            if (closer) map.controller.zoomIn() else map.controller.zoomOut()
            map.postDelayed({ renderSpan() }, 450)
            return
        }
        setView(if (next == next.toInt().toDouble()) next.toInt().toString() else next.toString())
    }

    private fun setView(id: String) {
        prefs.mapView = id
        following = true
        renderViews()
        if (id == MapViews.ROUTE) MapViews.showRoute(map) else follow(force = true)
        map.postDelayed({ renderSpan() }, 300)
    }

    private var lastFollowAt = 0L
    /** Every 2 s while following: keep the chosen view around the van (Auto: town 3 / road 25 / open road 80 km). */
    private fun follow(force: Boolean = false) {
        if (!::map.isInitialized) return
        renderTrip()
        val now = SystemClock.elapsedRealtime()
        if (listMode != "places" && now - lastListAt > 15_000) render()
        val here = Trip.here(this)
        here?.let { autoZoom.feed(it) }
        renderSpan()
        if (!following || prefs.mapView == MapViews.ROUTE) return
        if (!force && now - lastFollowAt < 1900) return
        lastFollowAt = now
        here ?: return
        val span = prefs.mapView.toDoubleOrNull() ?: MapViews.autoSpan(autoZoom.phase)
        MapViews.follow(map, here, span)
    }

    private fun renderTrip() {
        if (!::tripRow.isInitialized) return
        val d = Trip.dest
        if (listMode != "route") { tripRow.visibility = View.GONE; return }
        if (d == null) {
            tripRow.visibility = if (Trip.status.startsWith("Arrived")) View.VISIBLE else View.GONE
            tripText.text = "🏁 ${Trip.status}"
            return
        }
        tripRow.visibility = View.VISIBLE
        val here = Trip.here(this)
        val p = here?.let { Trip.progress(it.latitude, it.longitude) }
        val r = Trip.route
        tripText.text = buildString {
            append("🏁 ${d.title}")
            if (p != null) append(" · ${km(p.leftM.toFloat())} left")
            else if (r == null) append(" · finding the route…")
            append(if (d.source == "waze") " · from Waze" else " · picked here")
            if (r?.straight == true) append(" · straight line (no road route yet)")
        }
    }

    private fun renderTabs() {
        if (!::tabs.isInitialized) return
        tabs.removeAllViews()
        val left = Trip.dest?.let { Trip.here(this)?.let { h -> Trip.progress(h.latitude, h.longitude)?.leftM } }
        val routeTab = if (left != null) "🛣 Route · ${km(left.toFloat())}" else "🛣 Along route"
        for ((id, text) in listOf("places" to "📍 Places", "route" to routeTab, "near" to "🎯 Near me")) {
            tabs.addView(bigButton(text, if (listMode == id) Palette.BLUE else Palette.CARD, 14f) {
                listMode = id
                renderTrip()
                render()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(6), dp(6)) })
        }
    }

    /** "Along route" (what's ahead, in driving order) and "Near me" (around the van, nearest first). */
    private fun renderRoadList() {
        lastListAt = SystemClock.elapsedRealtime()
        kinds.visibility = View.GONE
        if (!OfflinePois.available(this)) {
            list.addView(label("No offline places installed yet.", 15f, Palette.MUTED))
            list.addView(bigButton("🗺 Install offline places (Settings)", Palette.BLUE, 15f) {
                startActivity(Intent(this, OfflineDataActivity::class.java))
            })
            return
        }
        val here = Trip.here(this)
        if (here == null) { list.addView(label("Waiting for a position…", 15f, Palette.MUTED)); return }
        val layers = MapFilter.layers(this)
        val speed = here.speed.takeIf { here.hasSpeed() }
        if (listMode == "route") {
            val p = Trip.progress(here.latitude, here.longitude)
            if (Trip.dest == null || p == null) {
                list.addView(label("No trip. Start navigation in Waze (the dock reads its destination), or tap a place → Drive, or long-press the map → Drive.", 15f, Palette.MUTED))
                return
            }
            val ahead = Trip.ahead(p.alongM, layers) { MapFilter.matches(it) && !OfflinePois.streetSide(it) }
            if (ahead.isEmpty()) list.addView(label("Nothing ahead that matches the buttons above the map.", 15f, Palette.MUTED))
            ahead.take(80).forEach { rp ->
                val sub = listOfNotNull(
                    aheadText(rp.km * 1000 - p.alongM, speed),
                    if (rp.offM > 150) "${if (rp.left) "left" else "right"} ${km(rp.offM.toFloat())} off" else if (rp.left) "left" else "right",
                    rp.poi.info,
                ).joinToString(" · ")
                list.addView(poiRow(rp.poi, sub))
            }
            return
        }
        val box = 0.15 // ~15 km
        Thread {
            val near = OfflinePois.inBox(this, here.latitude - box, here.latitude + box,
                here.longitude - box / kotlin.math.cos(Math.toRadians(here.latitude)), here.longitude + box / kotlin.math.cos(Math.toRadians(here.latitude)),
                layers, perLayer = 300)
                .filter { MapFilter.matches(it) && !OfflinePois.streetSide(it) } // street parking: map only
                .map { it.copy(distM = OfflinePois.distM(here.latitude, here.longitude, it.lat, it.lng)) }
                .sortedBy { it.distM }.take(80)
            runOnUiThread {
                if (listMode != "near") return@runOnUiThread
                list.removeAllViews()
                if (near.isEmpty()) list.addView(label("Nothing within ~15 km that matches the buttons above the map.", 15f, Palette.MUTED))
                near.forEach { list.addView(poiRow(it, listOfNotNull(km(it.distM.toFloat()), it.info).joinToString(" · "))) }
            }
        }.start()
    }

    private fun poiRow(p: OfflinePois.Poi, sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.CARD, dp(12).toFloat())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
        addView(label(p.icon, 22f), LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(this@DockActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(p.title, 16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(label(sub, 13f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        isClickable = true
        setOnClickListener {
            following = false
            renderViews()
            map.controller.animateTo(GeoPoint(p.lat, p.lng), 15.0, 500L)
            showPoi(p)
        }
    }

    /** Tap on the map's icons: several under the finger (or far out) → zoom in there; one → its card. */
    private fun tapPois(hits: List<OfflinePois.Poi>) {
        val z = map.zoomLevelDouble
        val p = hits.first()
        following = false
        renderViews()
        if (hits.size > 1 && z < 17) {
            val lat = hits.map { it.lat }.average(); val lng = hits.map { it.lng }.average()
            map.controller.animateTo(GeoPoint(lat, lng), (z + 2.5).coerceAtMost(18.0), 450L)
            return
        }
        if (z < 14) map.controller.animateTo(GeoPoint(p.lat, p.lng), 15.5, 450L)
        showPoi(p)
    }

    /** The place card on the map: what it is, how far, and Waze / Google Maps (details, photos, drive). */
    private fun showPoi(p: OfflinePois.Poi) {
        val here = Trip.here(this)
        val prog = here?.let { Trip.progress(it.latitude, it.longitude) }
        val onRoute = Trip.pois.firstOrNull { it.poi.osm == p.osm }
        val dist = when {
            onRoute != null && prog != null && prog.offM < 2000 && onRoute.km * 1000 > prog.alongM - 200 ->
                aheadText(onRoute.km * 1000 - prog.alongM, here.speed.takeIf { here.hasSpeed() }) + " on the route"
            here != null -> km(OfflinePois.distM(here.latitude, here.longitude, p.lat, p.lng).toFloat()) + " away"
            else -> null
        }
        val sub = listOfNotNull(
            OfflinePois.LABEL[p.layer],
            p.brand?.takeIf { it != p.title },
            p.info,
            dist,
        ).joinToString(" · ")
        bar.removeAllViews()
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label("${p.icon} ${p.title}", 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        text.addView(label(sub, 12f, Palette.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        bar.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(bigButton("Waze", Palette.WAZE, 16f) {
            // Drive there now: also the dock's trip (start = here).
            Trip.setDockDestination(this, p.lat, p.lng, p.title)
            Nav.goTo(this, p.lat, p.lng, Prefs.NAV_WAZE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(6) })
        bar.addView(bigButton("Google Maps", Palette.GMAPS, 15f) {
            // Its Google card (photos, reviews, hours) beside Waze; Directions is one tap there.
            val q = if (p.name != null) Uri.encode(p.name) else "${p.lat},${p.lng}(${Uri.encode(p.title)})"
            Split.openMaps(this, "geo:${p.lat},${p.lng}?q=$q")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginStart = dp(6) })
        bar.visibility = View.VISIBLE
        statusChips.visibility = View.GONE
    }

    companion object {
        const val EXTRA_SHOW_MORE = "showMore"

        /** The open panel, so the floating button can toggle it closed. */
        @Volatile var current: DockActivity? = null
        @Volatile var lastLocation: Location? = null
    }
}
