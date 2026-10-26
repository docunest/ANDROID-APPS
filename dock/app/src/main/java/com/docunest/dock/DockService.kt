package com.docunest.dock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * The floating dock button, always on top.
 *  - tap: open the dock panel (apps + places); tap again to close it
 *  - drag: move it anywhere
 *  - press and hold: the slide menu (apps + recent places) — slide onto one
 *    and lift to open it, without lifting in between
 * Also runs the sync loop and shows a card when a new place arrives.
 */
class DockService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var bubble: BubbleView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var fan: FanView? = null
    private var card: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val storeListener: () -> Unit = { refreshBadge() }
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Sync.pollSoon()
            checkUpdate()
        }
    }

    private val updateLoop = object : Runnable {
        override fun run() {
            checkUpdate()
            main.postDelayed(this, 60 * 60_000L)
        }
    }

    // Where the car is, for distances in the slide menu (the panel refreshes it too).
    private val locationLoop = object : Runnable {
        override fun run() {
            Locator.current(this@DockService) { loc -> if (loc != null) DockActivity.lastLocation = loc }
            main.postDelayed(this, 2 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        Sync.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            if (rootTried) return START_STICKY
            rootTried = true
            // Rooted head unit: grant ourselves (off the main thread — su can take a while), then retry.
            Thread {
                if (Root.grantAll(this)) {
                    prefs.rootGranted = true
                    main.post { start(this) }
                }
            }.start()
            return START_STICKY
        }
        if (bubble == null && Settings.canDrawOverlays(this)) {
            addBubble()
            Store.addListener(storeListener)
            Sync.onNewLinks = { showCard(it) }
            Sync.start(this)
            registerWakeups()
            main.postDelayed(updateLoop, 30_000L)
            main.post(locationLoop)
        } else if (intent?.action == ACTION_RELAYOUT) {
            relayoutBubble()
        }
        Sync.pollSoon()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dock button", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 2, Intent(this, DockActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle("Dock is on")
            .setContentText("Tap the floating button for apps and places.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun registerWakeups() {
        registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        })
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Sync.pollSoon()
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }

    private fun checkUpdate() = Sync.post { Updater.check(this) }

    private fun screen(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(it)
    }

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    )

    private fun idleAlpha() = prefs.bubbleAlpha.coerceIn(15, 100) / 100f

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(prefs.bubbleSizeDp.coerceIn(32, 96))
        val view = BubbleView(this).apply {
            alpha = idleAlpha()
            contentDescription = "Dock"
        }
        bubbleParams = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
        val holdMs = 280L
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val openFan = Runnable {
            if (!dragging) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showFan(view)
            }
        }

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    dragging = false
                    view.alpha = 1f
                    view.pressedLook = true
                    v.postDelayed(openFan, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val f = fan
                    if (f != null) {
                        f.hover(e.rawX, e.rawY)
                    } else {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            v.removeCallbacks(openFan)
                        }
                        if (dragging) {
                            bubbleParams.x = startX + dx.toInt()
                            bubbleParams.y = startY + dy.toInt()
                            wm.updateViewLayout(v, bubbleParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(openFan)
                    view.pressedLook = false
                    view.alpha = idleAlpha()
                    val f = fan
                    when {
                        f != null -> {
                            val chosen = if (e.actionMasked == MotionEvent.ACTION_UP) f.selected() else null
                            hideFan()
                            chosen?.action?.invoke()
                        }
                        dragging -> {
                            prefs.bubbleX = bubbleParams.x
                            prefs.bubbleY = bubbleParams.y
                        }
                        e.actionMasked == MotionEvent.ACTION_UP -> togglePanel()
                    }
                }
            }
            true
        }
        wm.addView(view, bubbleParams)
        bubble = view
        refreshBadge()
    }

    private fun relayoutBubble() {
        val b = bubble ?: return
        val size = dp(prefs.bubbleSizeDp.coerceIn(32, 96))
        bubbleParams.width = size
        bubbleParams.height = size
        b.alpha = idleAlpha()
        runCatching { wm.updateViewLayout(b, bubbleParams) }
    }

    private fun togglePanel() {
        val open = DockActivity.current
        if (open != null) open.finish()
        else startActivity(Intent(this, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refreshBadge() {
        bubble?.badge = Store.all().count { it.isNew }
    }

    // --- Press-and-slide menu -------------------------------------------

    private fun fanTargets(): List<FanTarget> {
        val t = mutableListOf<FanTarget>()
        val pkgs = (prefs.fanApps + listOfNotNull(prefs.dvrPackage.takeIf { it.isNotBlank() })).distinct()
        for (pkg in pkgs) {
            val app = Apps.entry(this, pkg) ?: continue
            t += FanTarget(app.label, app.icon, Palette.CARD, pill = false) { Apps.launch(this, pkg) }
        }
        if (prefs.fanBookmarkHere) {
            t += FanTarget("★ Save spot", null, Palette.AMBER, pill = false) { Actions.bookmarkHere(this) }
        }
        val nav = if (prefs.defaultNav == Prefs.NAV_GMAPS) "Google Maps" else "Waze"
        val here = DockActivity.lastLocation
        for (item in Store.sorted().take(prefs.fanLinks.coerceIn(0, 8))) {
            val bits = mutableListOf<String>()
            if (item.isNew) bits += "NEW"
            if (item.kind == "bookmark") bits += "★"
            if (here != null && item.hasPoint) {
                val d = FloatArray(1)
                android.location.Location.distanceBetween(here.latitude, here.longitude, item.lat!!, item.lng!!, d)
                bits += km(d[0])
            }
            if (item.approximate) bits += "approx."
            bits += "→ $nav"
            t += FanTarget(
                item.title, null,
                if (item.isNew) Color.parseColor("#92400E") else Color.argb(240, 31, 41, 55),
                pill = true, subtitle = bits.joinToString(" · "),
            ) { Nav.go(this, item) }
        }
        return t
    }

    private fun showFan(anchor: View) {
        if (fan != null) return
        val targets = fanTargets()
        if (targets.isEmpty()) return
        val m = screen()
        val view = FanView(this, targets)
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        wm.addView(view, params)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        view.layoutAround(loc[0] + anchor.width / 2f, loc[1] + anchor.height / 2f, m.widthPixels, m.heightPixels)
        // The gesture stays with the button's window (the fan is touch-through),
        // so the finger keeps steering without lifting.
        fan = view
    }

    private fun hideFan() {
        fan?.let { runCatching { wm.removeView(it) } }
        fan = null
    }

    // --- New place card ---------------------------------------------------

    private fun showCard(fresh: List<Item>) {
        if (bubble == null) return
        hideCard()
        val item = fresh.first()
        val nav = prefs.defaultNav
        val alt = if (nav == Prefs.NAV_GMAPS) Prefs.NAV_WAZE else Prefs.NAV_GMAPS
        val more = Store.all().count { it.isNew } - 1

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(245, 17, 24, 39), dp(18).toFloat(), Palette.AMBER, dp(2))
            setPadding(dp(14), dp(10), dp(10), dp(10))
            elevation = dp(10).toFloat()
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { hideCard(); Nav.go(this@DockService, item) }
        }
        text.addView(label("📍 New place from phone" + if (more > 0) "  (+$more more)" else "", 13f, Palette.AMBER))
        text.addView(label(item.title, 20f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(420) })
        val sub = listOfNotNull(item.address, if (item.approximate) "approximate (found by name)" else null, if (!item.hasPoint) "no map pin — opens the link" else null)
        if (sub.isNotEmpty()) text.addView(label(sub.joinToString(" · "), 13f, Palette.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(420) })
        root.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        root.addView(gap())
        root.addView(bigButton(if (nav == Prefs.NAV_GMAPS) "Drive · Maps" else "Drive · Waze", if (nav == Prefs.NAV_GMAPS) Palette.GMAPS else Palette.WAZE) {
            hideCard(); Nav.go(this, item, nav)
        })
        root.addView(gap())
        root.addView(bigButton(if (alt == Prefs.NAV_GMAPS) "Maps" else "Waze", Palette.CARD_HI, 15f) {
            hideCard(); Nav.go(this, item, alt)
        })
        if (more > 0) {
            root.addView(gap())
            root.addView(bigButton("All", Palette.CARD_HI, 15f) {
                hideCard(); startActivity(Intent(this, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        root.addView(gap())
        root.addView(bigButton("✕", Palette.CARD, 18f) {
            hideCard()
            Sync.patch(item.id, JSONObject().put("seen", true))
        })

        val m = screen()
        val params = overlayParams(minOf(m.widthPixels - dp(24), dp(760)), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(12)
        }
        wm.addView(root, params)
        card = root
        // Seen once it has been on screen a while; card itself stays until handled.
        main.postDelayed({ Sync.patch(item.id, JSONObject().put("seen", true)) }, 15_000L)
    }

    private fun hideCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    override fun onDestroy() {
        Sync.stop()
        Sync.onNewLinks = null
        Store.removeListener(storeListener)
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(wakeReceiver) }
        netCallback?.let { cb -> runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
        hideFan()
        hideCard()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "dock"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.docunest.dock.STOP"
        const val ACTION_RELAYOUT = "com.docunest.dock.RELAYOUT"
        private var rootTried = false

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, DockService::class.java).setAction(action))
        }

        fun stop(context: Context) =
            context.startService(Intent(context, DockService::class.java).setAction(ACTION_STOP))
    }
}
