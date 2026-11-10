package com.docunest.dock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wi-Fi watchdog for the iPhone hotspot. Runs all the time inside the dock
 * service:
 *  - notices at once when Wi-Fi drops, works out why (iPhone ended it, the
 *    hotspot vanished, weak signal, or Android itself hung up — from
 *    wpa_supplicant's disconnect reason with root) and records it;
 *  - while connected, tells "no internet behind the hotspot" (iPhone has no
 *    mobile data — tunnel) apart from a broken Wi-Fi link by pinging the
 *    hotspot itself and probing the internet;
 *  - when lost, scans every few seconds and, if the hotspot is visible but
 *    Android doesn't rejoin, makes it rejoin (root): first a "user" connect
 *    through [RootWifi] — that also clears Android 10's no-internet block,
 *    which otherwise keeps it off the hotspot for good after a tunnel —
 *    then Wi-Fi off/on + connect — retried fast, no give-up ([WifiPlan]). If the hotspot isn't visible there's nothing
 *    to join: it says so (open Personal Hotspot on the iPhone) and keeps
 *    scanning.
 *  - with root, stops Android hanging up when the hotspot misses a few
 *    ARP replies (`cmd wifi set-ipreach-disconnect disabled`).
 *
 * Aggressive since 2026-11-08 (owner: only the iPhone hotspots + Starlink
 * are used, battery doesn't matter): "ours" = every saved network (root) +
 * every one we've been on; scans every 2 s while down and joins whichever
 * of ours shows up, retrying fast ([WifiPlan]); none visible but an iPhone
 * on the car's Bluetooth → [BtKick] (Bluetooth off/on fires the iPhone's
 * "turn hotspot on" automation).
 */
object WifiDoctor {
    private const val KEEP = 60
    private const val WEAK_RSSI = -80
    private const val PROBE_EVERY_MS = 60_000L
    private const val NOTIFY_ID = 7
    /** "Ours" seen this recently counts as visible. */
    private const val VISIBLE_MS = 6_000L
    private const val CHANNEL = "wifi"

    enum class Fault(val label: String) {
        IPHONE("iPhone"), ANDROID("Android"), SIGNAL("weak signal / range"), NO_DATA("no mobile data (tunnel?)"), UNKNOWN("unclear")
    }

    // --- Live state (worker thread writes, UI reads) -------------------------
    @Volatile var enabled = true
    @Volatile var connected = false
    @Volatile var ssid: String? = null
    @Volatile var rssi = 0
    @Volatile var linkMbps = 0
    @Volatile var freqMhz = 0
    @Volatile var gateway: String? = null
    /** Hotspot answers a ping (ms), null = no answer / not checked. */
    @Volatile var gatewayPingMs: Int? = null
    private var gatewayIface: String? = null
    /** Internet through Wi-Fi: true/false, null = not checked yet. */
    @Volatile var internet: Boolean? = null
    @Volatile var validated = false
    /** elapsedRealtime the target was last seen in a scan, and how strong. */
    @Volatile var targetSeenAt = 0L
    @Volatile var targetSeenRssi = 0
    @Volatile var downSince = 0L
    @Volatile var upSince = 0L
    @Volatile var noInternetSince = 0L
    /** Rejoin attempts during this drop. */
    @Volatile var restarts = 0
    /** Our networks: every saved one (root) + every one we've been on. */
    @Volatile var ours: Set<String> = emptySet()
    /** Ours seen in scans: SSID → dBm, and when any of them was last seen. */
    @Volatile var oursVisible: Map<String, Int> = emptyMap()
    @Volatile var oursSeenAt = 0L
    private val plan = WifiPlan()
    /** Rolling ping meter to the hotspot (every 2 s while connected) — the "about to drop" hint. */
    val link = LinkQuality()
    /** What the link looked like just before the last drop (for the toast and the event). */
    @Volatile var beforeLastDrop: String? = null
    private val pingThread = HandlerThread("wifi-ping").apply { start() }
    private val pinger = Handler(pingThread.looper)
    private val pingLoop = object : Runnable {
        override fun run() {
            val gw = gateway
            if (connected && gw != null) {
                link.add(ping(gw, waitSec = 1))
                DebugLog.change("WIFI", "link", link.level().name, "${link.text()} · via $rttMethod to $gw")
            }
            pinger.postDelayed(this, 2_000L)
        }
    }
    /** Android's auto-join state for the hotspot (root), e.g. "PERMANENTLY_DISABLED NO_INTERNET_PERMANENT". */
    @Volatile var joinStatus: String? = null
    @Volatile var lastAction = ""
    @Volatile var hasRoot: Boolean? = null

    private val thread = HandlerThread("wifi-doctor").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var started = false
    private var lastRestartAt = 0L
    private var lastProbeAt = 0L
    private var lastRssis = ArrayDeque<Int>()
    private var notified = false
    /** A drop is waiting for its reason; a quick comeback is noted here until it's written. */
    private var dropPending = false
    private var pendingAfter: String? = null
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun sp() = app.getSharedPreferences("dock", Context.MODE_PRIVATE)

    /** The network to keep: the last one we were connected to (the hotspot). */
    val target: String? get() = if (::app.isInitialized) sp().getString("wifiTarget", null) else null
    var autoFix: Boolean
        get() = sp().getBoolean("wifiAutoFix", true)
        set(v) { sp().edit().putBoolean("wifiAutoFix", v).apply(); changed() }
    var keepAlive: Boolean
        get() = sp().getBoolean("wifiKeepAlive", true)
        set(v) { sp().edit().putBoolean("wifiKeepAlive", v).apply(); changed() }
    var btKick: Boolean
        get() = sp().getBoolean("wifiBtKick", true)
        set(v) { sp().edit().putBoolean("wifiBtKick", v).apply(); changed() }

    private fun learned(): Set<String> = sp().getString("wifiKnown", "")!!.split('\n').filter { it.isNotBlank() }.toSet()

    /** Saved networks (root) + learned ones. Worker thread. */
    private fun loadOurs() {
        val saved = if (hasRoot == true) {
            val apk = app.applicationInfo.sourceDir
            Root.output("CLASSPATH=$apk app_process /system/bin ${RootWifi::class.java.name} list")
                ?.lines()?.lastOrNull { it.startsWith("list") }?.removePrefix("list")?.trim()
                ?.split(',')?.filter { it.isNotBlank() }
                ?.mapNotNull { h -> runCatching { String(h.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8) }.getOrNull() }
                .orEmpty()
        } else emptyList()
        ours = saved.toSet() + learned() + listOfNotNull(target)
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { check() }
            worker.removeCallbacks(this)
            // Down: every 2 s (scan + decide) — aggressive by request.
            worker.postDelayed(this, if (connected && internet != false) 15_000L else if (connected) 5_000L else 2_000L)
        }
    }

    /** From the dock service (idempotent). */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        BtKick.start(app)
        val kick = { worker.removeCallbacks(tick); worker.post(tick) }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) worker.post { runCatching { readScan() } } else kick()
            }
        }, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        })
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { kick() }
                    override fun onLost(network: Network) { kick() }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { kick() }
                },
            )
        }
        worker.post {
            hasRoot = Root.run("id")
            DebugLog.log("WIFI", "root: ${if (hasRoot == true) "yes" else "NO — forced rejoin, Wi-Fi restart and Bluetooth kick via root won't work"}")
            // Scans more often than 4 per 2 min (Android's default throttle).
            if (hasRoot == true) Root.run(
                "settings put global wifi_scan_throttle_enabled 0",
                // Android 10 drops Wi-Fi when the gateway misses ARP replies — iPhones in power save do. Not kept across reboots.
                "cmd wifi set-ipreach-disconnect disabled",
            )
            loadOurs()
        }
        worker.post(tick)
        pinger.post(pingLoop)
    }

    /** "Reconnect now" on the doctor screen: skip the waiting. */
    fun reconnectNow() = worker.post {
        runCatching { act(force = true) }
        check()
    }

    /** "Kick Bluetooth now" on the doctor screen (testing the iPhone automation). */
    fun kickNow() = worker.post {
        lastAction = "Bluetooth kick: " + BtKick.kick()
        changed()
    }

    // --- The check ------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun wifiNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    @SuppressLint("MissingPermission")
    private fun check() {
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wasConnected = connected
        enabled = wm.isWifiEnabled
        val net = wifiNetwork(cm)
        @Suppress("DEPRECATION") val info = wm.connectionInfo
        connected = net != null || (info != null && info.supplicantState == SupplicantState.COMPLETED && info.networkId != -1)
        if (connected) {
            val name = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
            if (name != null) {
                ssid = name
                if (name != target) sp().edit().putString("wifiTarget", name).apply()
                if (name !in learned()) {
                    sp().edit().putString("wifiKnown", (learned() + name).joinToString("\n")).apply()
                    ours = ours + name
                }
            }
            rssi = info?.rssi ?: 0
            linkMbps = info?.linkSpeed ?: 0
            freqMhz = info?.frequency ?: 0
            lastRssis.addLast(rssi)
            while (lastRssis.size > 6) lastRssis.removeFirst()
            validated = net?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
            // IPv4 first (iPhone: 172.20.10.1); an IPv6 link-local gateway needs ping6 with the interface.
            val lp = net?.let { cm.getLinkProperties(it) }
            val gws = lp?.routes?.filter { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.mapNotNull { it.gateway }.orEmpty()
            val gw = gws.firstOrNull { it is java.net.Inet4Address } ?: gws.firstOrNull()
            if (gw != null) {
                gateway = gw.hostAddress?.substringBefore('%')
                gatewayIface = if (gw is java.net.Inet6Address) lp?.interfaceName else null
            }
        }

        DebugLog.change("WIFI", "connection", if (!enabled) "OFF" else if (connected) "connected ${ssid ?: "?"}" else "DOWN",
            "rssi $rssi · ours visible ${oursVisible.keys} · bt ${BtKick.phoneConnected()} ${BtKick.phone ?: ""}")
        when {
            connected && !wasConnected -> back()
            !connected && wasConnected -> lost()
            !connected && downSince == 0L -> downSince = now() // started while down
        }

        BtKick.judge(oursSeenAt, connected)
        if (connected) {
            plan.next(WifiPlan.Input(now(), true, false, false, null, -1, false, enabled)) // resets the plan
            val probeDue = internet != true || now() - lastProbeAt > PROBE_EVERY_MS
            if (keepAlive || probeDue) gatewayPingMs = gateway?.let { ping(it) }
            if (probeDue && net != null) {
                lastProbeAt = now()
                val ok = probe(net)
                if (internet != false && !ok) {
                    noInternetSince = now()
                } else if (internet == false && ok && noInternetSince > 0) {
                    val gwOk = gatewayPingMs != null
                    record(
                        "No internet for ${GpsDoctor.duration(now() - noInternetSince)}",
                        if (gwOk) "The hotspot answered, the internet didn't — the iPhone had no mobile data (tunnel / no coverage)."
                        else "Wi-Fi stayed connected but the hotspot stopped answering.",
                        if (gwOk) Fault.NO_DATA else Fault.IPHONE, now() - noInternetSince,
                    )
                    noInternetSince = 0L
                }
                internet = ok
                DebugLog.change("WIFI", "internet", if (ok) "works" else "NONE", "hotspot ping ${gatewayPingMs ?: "no answer"} ms · ${ssid}")
            }
        } else {
            gatewayPingMs = null
            internet = null
            @Suppress("DEPRECATION") runCatching { wm.startScan() }
            if (autoFix) act(force = false)
            notifyLost()
        }
        changed()
    }

    private fun back() {
        val down = if (downSince > 0) now() - downSince else 0L
        if (down > 0) {
            val how = if (restarts > 0) "after Dock forced a rejoin ($restarts×)" else "by itself"
            val after = "back $how after ${GpsDoctor.duration(down)}"
            if (dropPending) pendingAfter = after else amendLast(after)
        }
        downSince = 0L
        upSince = now()
        restarts = 0
        link.clear()
        lastAction = ""
        internet = null
        lastProbeAt = 0L
        if (notified) {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_ID)
            notified = false
        }
    }

    private fun lost() {
        // How the link was just before: justified by bad reception, or sudden? Toast now, stored with the event.
        val (justified, before) = LinkQuality.beforeDrop(lastRssis.lastOrNull() ?: rssi, lastRssis.maxOrNull() ?: rssi, link)
        beforeLastDrop = before
        val lostName = ssid ?: "Wi-Fi"
        main.post {
            runCatching {
                android.widget.Toast.makeText(app, "📶 Wi-Fi lost ($lostName). $before", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        link.clear()
        runCatching { loadOurs() } // a network saved since the last drop counts too
        downSince = now()
        upSince = 0L
        restarts = 0
        noInternetSince = 0L
        val lastGood = lastRssis.maxOrNull() ?: rssi
        val lastSeen = lastRssis.lastOrNull() ?: rssi
        val name = ssid ?: "Wi-Fi"
        if (!enabled) {
            record("Lost $name", "Wi-Fi was switched off on the head unit.", Fault.ANDROID, null)
            return
        }
        // Give the scan a moment, then decide with the supplicant's reason (root) + whether the hotspot is still there.
        dropPending = true
        pendingAfter = null
        worker.postDelayed({
            runCatching {
                val reason = if (hasRoot == true) disconnectReason() else null
                joinStatus = if (hasRoot == true) rootWifi("status", name)?.substringAfter(' ', "")?.takeIf { it.isNotBlank() } else null
                val visible = now() - targetSeenAt < 12_000
                val switched = ssid?.takeIf { connected && it != name }
                val (why, fault) = if (switched != null) "Android switched to another network: $switched." to Fault.ANDROID
                else explain(reason, visible, lastSeen, lastGood)
                val block = joinStatus?.takeIf { it.contains("DISABLED") }
                    ?.let { " Android has also blocked auto-join for it ($it) — it won't rejoin by itself; Dock clears that." } ?: ""
                record("Lost $name", why + block + " " + before, fault, null, pendingAfter)
            }
            dropPending = false
            pendingAfter = null
            changed()
        }, 8_000)
    }

    private data class Reason(val code: Int, val local: Boolean)

    /** wpa_supplicant writes "CTRL-EVENT-DISCONNECTED bssid=… reason=3 locally_generated=1" to logcat. */
    private fun disconnectReason(): Reason? {
        val out = Root.output("logcat -d -t 3000 | grep CTRL-EVENT-DISCONNECTED | tail -n 1") ?: return null
        val line = out.lines().lastOrNull { it.contains("CTRL-EVENT-DISCONNECTED") } ?: return null
        // Only if it's from the last two minutes ("MM-dd HH:mm:ss.SSS" at the start).
        val stamp = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)").find(line)?.groupValues?.get(1)
        if (stamp != null) {
            val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
            val t = runCatching { SimpleDateFormat("yyyy MM-dd HH:mm:ss", Locale.US).parse("$year $stamp")?.time }.getOrNull()
            if (t != null && System.currentTimeMillis() - t > 120_000) return null
        }
        val code = Regex("reason=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return Reason(code, line.contains("locally_generated=1"))
    }

    private fun explain(r: Reason?, visible: Boolean, lastRssi: Int, bestRssi: Int): Pair<String, Fault> {
        val code = r?.let { " (code ${it.code}${if (it.local) ", from this device" else ", from the iPhone"})" } ?: ""
        if (r != null && !r.local) {
            return when (r.code) {
                3, 8 -> "The iPhone ended the connection — Personal Hotspot switched off or the iPhone stopped sharing$code."
                4 -> "The iPhone dropped the head unit for inactivity$code."
                5 -> "The iPhone has too many devices connected$code."
                15, 16, 2 -> "The iPhone rejected the security handshake — password or hotspot restarted$code."
                else -> "The iPhone ended the connection$code."
            } to Fault.IPHONE
        }
        if (r != null && r.local && r.code == 3 && visible && lastRssi > WEAK_RSSI) {
            return "Android hung up by itself while the hotspot was still there ($lastRssi dBm)$code." to Fault.ANDROID
        }
        if (lastRssi != 0 && lastRssi <= WEAK_RSSI) {
            return "Signal too weak ($lastRssi dBm, best $bestRssi) — the hotspot was too far or blocked$code." to Fault.SIGNAL
        }
        if (!visible) {
            return "The hotspot vanished — the iPhone stopped sharing (or went out of range)$code." to Fault.IPHONE
        }
        return if (r != null) "Connection broke while the hotspot was still visible — Wi-Fi driver / Android$code." to Fault.ANDROID
        else "Connection broke while the hotspot was still visible (no root, so no disconnect reason)." to Fault.UNKNOWN
    }

    // --- Getting back on ------------------------------------------------------

    private fun act(force: Boolean) {
        val down = if (downSince > 0) now() - downSince else 0L
        val root = hasRoot == true
        if (!enabled) {
            if (root) {
                lastAction = "Switching Wi-Fi on"
                Root.run("svc wifi enable")
            } else lastAction = "Wi-Fi is off — switch it on (no root)"
            return
        }
        val visible = now() - oursSeenAt < VISIBLE_MS
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val sup = runCatching { wm.connectionInfo?.supplicantState }.getOrNull()
        val joining = sup in setOf(
            SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED, SupplicantState.AUTHENTICATING,
            SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE, SupplicantState.COMPLETED,
        )
        val step = if (force) {
            when {
                visible -> WifiPlan.Step(WifiPlan.Action.JOIN, "joining now (asked)")
                BtKick.phoneConnected() == true && !BtKick.inCall(app) -> WifiPlan.Step(WifiPlan.Action.BT_KICK, "none of ours visible — Bluetooth kick (asked)")
                else -> WifiPlan.Step(WifiPlan.Action.NONE, "none of your networks visible, no iPhone on Bluetooth — scanning")
            }
        } else plan.next(
            WifiPlan.Input(
                now(), false, joining, visible,
                if (btKick) BtKick.phoneConnected() else false, BtKick.since, BtKick.inCall(app), enabled,
            ),
        )
        restarts = plan.joinTries
        if (step.action != WifiPlan.Action.NONE) DebugLog.log("WIFI", "action ${step.action}: ${step.why}")
        DebugLog.change("WIFI", "plan", step.why.substringBefore(" — ").substringBefore(" in "))
        lastAction = if (visible) "${best() ?: "hotspot"} visible — ${step.why}" else "Down ${GpsDoctor.duration(down)} — ${step.why}"
        when (step.action) {
            WifiPlan.Action.NONE -> Unit
            WifiPlan.Action.JOIN, WifiPlan.Action.RESTART_AND_JOIN -> {
                val t = best() ?: return
                if (!root) {
                    lastAction = "$t visible — waiting for Android to join (no root to force it)"
                    return
                }
                if (step.action == WifiPlan.Action.RESTART_AND_JOIN) {
                    changed()
                    Root.run("svc wifi disable", "sleep 2", "svc wifi enable", "sleep 4")
                }
                joinStatus = rootWifi("status", t)?.substringAfter(' ', "")?.takeIf { it.isNotBlank() }
                if (joinStatus?.contains("DISABLED") == true) lastAction += " (Android had blocked it: $joinStatus — clearing)"
                val r = rootWifi("enable", t)
                if (r?.startsWith("ok") != true) lastAction += " — ${r ?: "root refused"}"
                lastRestartAt = now()
            }
            WifiPlan.Action.BT_KICK -> {
                changed()
                lastAction += " → " + BtKick.kick()
            }
        }
        changed()
    }

    /** Which of ours to join: the one we were last on if it's there, else the strongest visible. */
    private fun best(): String? {
        val v = oursVisible
        return target?.takeIf { it in v } ?: v.maxByOrNull { it.value }?.key
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun readScan() {
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        // Any of ours (scan results only arrive with location on — Android 10).
        val mine = results.filter { it.SSID in ours }
            .groupBy { it.SSID }.mapValues { (_, l) -> l.maxOf { it.level } }
        val newest = results.filter { it.SSID in ours }.maxOfOrNull { it.timestamp / 1000 } ?: 0L
        oursVisible = mine
        if (newest > oursSeenAt) oursSeenAt = newest
        val t = target ?: return
        val hit = results.filter { it.SSID == t }.maxByOrNull { it.level } ?: return
        // ScanResult.timestamp is µs since boot.
        val seen = hit.timestamp / 1000
        if (seen > targetSeenAt) {
            targetSeenAt = seen
            targetSeenRssi = hit.level
            changed()
        }
    }

    private fun notifyLost() {
        if (notified || downSince == 0L || now() - downSince < 30_000 || now() - oursSeenAt < 15_000 || !enabled) return
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wi-Fi lost", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(app, 3, Intent(app, WifiDoctorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle("Wi-Fi lost — none of your networks visible")
            .setContentText(if (BtKick.phoneConnected() == true) "Kicking Bluetooth to switch the iPhone hotspot on. Or open Personal Hotspot." else "Open Personal Hotspot on the iPhone. Dock reconnects by itself.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
        notified = true
    }

    /** [RootWifi] as root: our own APK's code through app_process. */
    private fun rootWifi(op: String, ssid: String): String? {
        val hex = ssid.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val apk = app.applicationInfo.sourceDir
        return Root.output("CLASSPATH=$apk app_process /system/bin ${RootWifi::class.java.name} $op $hex")
            ?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
    }

    // --- Measurements -----------------------------------------------------------

    /** How round trips are measured: "ping", or "tcp" when this unit doesn't let apps ping. */
    @Volatile var rttMethod = "ping"
        private set

    /**
     * Round trip to the hotspot in ms, null = no answer. `ping` exits 1 for "no reply" but 2 (or says
     * "not permitted") when apps may not ping at all — then every ping looked lost and the link "bad".
     * Fallback: a TCP probe ([java.net.InetAddress.isReachable]); a refused port still answers.
     */
    private fun ping(host: String, waitSec: Int = 2): Int? {
        if (rttMethod == "ping") {
            val r = runCatching {
                val iface = gatewayIface
                val cmd = if (iface != null) listOf("ping6", "-c", "1", "-W", "$waitSec", "$host%$iface") else listOf("ping", "-c", "1", "-W", "$waitSec", host)
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor() to out
            }
            val (code, out) = r.getOrNull() ?: (-1 to (r.exceptionOrNull()?.message ?: ""))
            if (code == 0) return Regex("time=([\\d.]+)").find(out)?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 0
            if (code == 1 && !out.contains("permi", ignoreCase = true)) return null // a real "no reply"
            rttMethod = "tcp"
            DebugLog.log("WIFI", "ping not usable here (exit $code: ${out.trim().take(120)}) — measuring with a TCP probe instead")
        }
        return runCatching {
            val start = System.nanoTime()
            val ok = java.net.InetAddress.getByName(host).isReachable(waitSec * 1000)
            if (ok) ((System.nanoTime() - start) / 1_000_000).toInt() else null
        }.getOrNull()
    }

    /** Real internet through the Wi-Fi network itself (not mobile data): ~1 KB. */
    private fun probe(net: Network): Boolean = runCatching {
        val c = net.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.instanceFollowRedirects = false
        val code = c.responseCode
        c.disconnect()
        code == 204
    }.getOrDefault(false)

    // --- History ----------------------------------------------------------------

    class Event(val at: Long, val title: String, val why: String, val fault: Fault, val downMs: Long?, val after: String?)

    private fun record(title: String, why: String, fault: Fault, downMs: Long?, after: String? = null) {
        val list = load()
        list.put(JSONObject().put("at", System.currentTimeMillis()).put("title", title).put("why", why)
            .put("fault", fault.name).put("downMs", downMs ?: -1L).apply { if (after != null) put("after", after) })
        while (list.length() > KEEP) list.remove(0)
        sp().edit().putString("wifiEvents", list.toString()).apply()
        changed()
    }

    /** "back by itself after 40 s" on the newest drop. */
    private fun amendLast(after: String) {
        val list = load()
        val last = (list.length() - 1 downTo 0).map { list.getJSONObject(it) }.firstOrNull { it.optString("title").startsWith("Lost") } ?: return
        if (last.has("after")) return
        last.put("after", after)
        sp().edit().putString("wifiEvents", list.toString()).apply()
    }

    private fun load(): JSONArray = runCatching { JSONArray(sp().getString("wifiEvents", "[]")) }.getOrDefault(JSONArray())

    fun events(): List<Event> {
        if (!::app.isInitialized) return emptyList()
        val list = load()
        return (list.length() - 1 downTo 0).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            Event(o.optLong("at"), o.optString("title"), o.optString("why"),
                runCatching { Fault.valueOf(o.optString("fault")) }.getOrDefault(Fault.UNKNOWN),
                o.optLong("downMs", -1).takeIf { it >= 0 }, o.optString("after").takeIf { it.isNotEmpty() })
        }
    }

    fun clear() = sp().edit().remove("wifiEvents").apply().also { changed() }

    /** One sentence for the headline and the map chip. */
    fun status(): String = when {
        !enabled -> "Wi-Fi is switched off."
        !connected -> "Wi-Fi lost ${GpsDoctor.duration(now() - downSince)} ago — " +
            (best()?.takeIf { now() - oursSeenAt < VISIBLE_MS }?.let { "$it is visible (${oursVisible[it]} dBm)." } ?: "none of your networks visible.")
        internet == false && gatewayPingMs != null -> "Connected, but no internet behind the hotspot — iPhone has no mobile data (tunnel?)."
        internet == false -> "Connected, but the hotspot doesn't answer."
        internet == null -> "Connected to ${ssid ?: "Wi-Fi"}, checking internet…"
        else -> "Connected to ${ssid ?: "Wi-Fi"} — internet works."
    }
}
