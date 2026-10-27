package com.docunest.dock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var myLocation: MyLocationNewOverlay? = null
    private var dropped: Marker? = null
    private var centeredOnce = false
    private var showMore = false
    private val storeListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        OsmConfig.getInstance().apply {
            load(this@DockActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        Sync.init(this)
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
        renderVersion()
        map.onResume()
        runCatching { myLocation?.enableMyLocation() }
        render()
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
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Places", 20f, bold = true))
        head.addView(space(10))
        status = label("", 12f, Palette.MUTED)
        head.addView(status)
        placesCol.addView(head)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        placesCol.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                bar.visibility = View.GONE
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

        if (landscape) {
            root.addView(placesCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(space(10))
            root.addView(mapFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))
        } else {
            root.addView(top)
            root.addView(space(10))
            root.addView(mapFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(space(10))
            root.addView(placesCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
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

    private fun space(d: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(d), dp(d)) }

    // --- Content ------------------------------------------------------------

    private fun render() {
        if (!::list.isInitialized) return
        status.text = Store.status
        val items = Store.sorted()
        val here = Near.here(this)
        val (main, more) = items.partition { Near.isMain(here, it) }
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(label("Nothing yet. Share a Google Maps place from the phone to \"Send to car\", or tap ★ Save this spot.", 15f, Palette.MUTED).apply {
                setPadding(0, dp(16), 0, 0)
            })
        }
        main.forEach { list.addView(row(it)) }
        if (more.isNotEmpty()) {
            // Far away (> 150 km) and older than 2 days: history, one tap away.
            list.addView(bigButton(if (showMore) "Hide older far places" else "Show more (${more.size}) — far & older", Palette.CARD_HI, 15f) {
                showMore = !showMore
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(8), 0, dp(4)) }
            })
            if (showMore) more.forEach { list.addView(row(it)) }
        }
        renderMarkers(if (showMore) items else main)
    }

    private fun subtitle(item: Item): String {
        val bits = mutableListOf<String>()
        Near.label(Near.here(this), item)?.let { bits += it }
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
        if (item.hasPoint) {
            r.addView(bigButton("🗺", Palette.CARD_HI, 16f) { focus(item) }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(6) })
        }
        r.addView(
            bigButton(if (alt == Prefs.NAV_GMAPS) "G" else "W", if (alt == Prefs.NAV_GMAPS) Palette.GMAPS else Palette.WAZE, 18f) {
                Nav.go(this, item, alt)
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
        showBar("Dropped pin", "%.5f, %.5f".format(p.latitude, p.longitude), p.latitude, p.longitude, null)
    }

    /** Bottom bar on the map: what's selected + one-tap Drive. */
    private fun showBar(title: String, sub: String, lat: Double, lng: Double, item: Item?) {
        val nav = prefs.defaultNav
        val alt = if (nav == Prefs.NAV_GMAPS) Prefs.NAV_WAZE else Prefs.NAV_GMAPS
        bar.removeAllViews()
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        text.addView(label(sub, 12f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        bar.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun go(app: String) = if (item != null) Nav.go(this, item, app) else Nav.goTo(this, lat, lng, app)
        bar.addView(bigButton(if (nav == Prefs.NAV_GMAPS) "Drive · Maps" else "Drive · Waze", if (nav == Prefs.NAV_GMAPS) Palette.GMAPS else Palette.WAZE, 16f) { go(nav) })
        bar.addView(space(6))
        bar.addView(bigButton(if (alt == Prefs.NAV_GMAPS) "G" else "W", Palette.CARD_HI, 16f) { go(alt) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.addView(space(6))
        bar.addView(bigButton("🧪", Palette.CARD_HI, 16f) {
            startActivity(NavTestActivity.intent(this, lat, lng, title, item?.address, item?.let { it.resolvedUrl ?: it.sourceUrl }))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_SHOW_MORE = "showMore"

        /** The open panel, so the floating button can toggle it closed. */
        @Volatile var current: DockActivity? = null
        @Volatile var lastLocation: Location? = null
    }
}
