package com.docunest.dock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock

/**
 * "Bluetooth kick": the iPhone's Shortcuts automation switches Personal
 * Hotspot on whenever the car's Bluetooth connects *or* disconnects. So when
 * no hotspot is visible but an iPhone is on the car's Bluetooth, switching
 * Bluetooth off and on brings the hotspot back ([WifiPlan] decides when).
 *
 * Works only if the head unit's Bluetooth is Android's own (some units run
 * it on a separate module Android can't see — then [phoneConnected] is null
 * and the kick is never tried). Never during a call. Each kick is recorded
 * with whether one of our Wi-Fi networks showed up within [JUDGE_MS] and how
 * fast, so the Wi-Fi doctor screen shows if it actually helps.
 */
object BtKick {
    const val JUDGE_MS = 60_000L
    private const val KEEP = 40

    /** Name of the connected iPhone (or phone), null = none / unknown. */
    @Volatile var phone: String? = null
        private set
    /** elapsedRealtime the phone connected, -1 = not connected. */
    @Volatile var since = -1L
        private set
    /** Android's Bluetooth can be read at all. */
    @Volatile var usable = false
        private set
    /** A kick waiting for its outcome: elapsedRealtime it happened, 0 = none. */
    @Volatile var pendingAt = 0L
        private set

    private lateinit var app: Context
    private var started = false

    /** Addresses with a link up, from ACL broadcasts. */
    private val aclUp = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Profile proxies (public BluetoothProfile API): hands-free client / audio sink = the car side of a phone. */
    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, android.bluetooth.BluetoothProfile>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            runCatching { refresh() }
            main.postDelayed(this, 10_000)
        }
    }

    /** Profiles to look at: the car-side ones first (16 hands-free client, 11 audio sink), then phone-side ones. */
    private val PROFILES = linkedMapOf(16 to "hands-free (car)", 11 to "audio sink (car)", 1 to "headset", 2 to "A2DP", 17 to "phonebook client", 18 to "messages client")

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        GocBt.start(app) // KSW unit: the phone is on the external Goodocom module, not Android's Bluetooth
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    when (i.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> d?.address?.let { aclUp += it }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> d?.address?.let { aclUp -= it }
                    }
                    DebugLog.log("BT", "event ${i.action?.substringAfterLast('.')} ${d?.let { runCatching { it.name }.getOrNull() ?: it.address } ?: ""}")
                    refresh()
                }
            }, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            })
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null) for (id in PROFILES.keys) runCatching {
            adapter.getProfileProxy(app, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) { proxies[profile] = proxy; refresh() }
                override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
            }, id)
        }
        main.post(loop)
        main.postDelayed({ DebugLog.log("BT", report()) }, 5_000)
    }

    /** true / false, or null when Android's Bluetooth can't tell (no adapter, no permission, separate BT module). */
    fun phoneConnected(): Boolean? = if (!usable) null else phone != null

    /** Who's connected, as Android sees it — any of: profile proxies, profile states, ACL events, the hidden isConnected(). */
    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!::app.isInitialized) return
        if (GocBt.present()) {
            // The module's own state (from its log) is the truth on this unit.
            usable = true
            val st = GocBt.state
            phone = if (st.phoneConnected) (st.name ?: "phone") else null
            since = if (st.phoneConnected) st.connectedAt else -1
            DebugLog.change("BT", "phone", if (st.hfp == 0) "module: state not read yet (${GocBt.status})" else phone ?: "none (module idle)")
            return
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            usable = false; phone = null; since = -1
            DebugLog.change("BT", "phone", "no Bluetooth adapter in Android")
            return
        }
        val found = runCatching {
            if (!adapter.isEnabled) return@runCatching null
            val bonded = adapter.bondedDevices.orEmpty()
            val viaProxy = PROFILES.keys.flatMap { id -> runCatching { proxies[id]?.connectedDevices.orEmpty() }.getOrDefault(emptyList()) }
            val candidates = viaProxy + bonded.filter { it.address in aclUp || isConnected(it) }
            candidates.firstOrNull { isPhone(it) } ?: candidates.firstOrNull()
                ?: PROFILES.entries.firstOrNull { (id, _) -> runCatching { adapter.getProfileConnectionState(id) }.getOrDefault(0) == 2 }
                    ?.let { (_, label) -> PhoneByProfile(label) }
        }
        usable = found.isSuccess
        val name = when (val f = found.getOrNull()) {
            null -> null
            is BluetoothDevice -> runCatching { f.name }.getOrNull() ?: f.address
            is PhoneByProfile -> "a phone (${f.label} connected)"
            else -> null
        }
        if (name != null && phone == null) since = SystemClock.elapsedRealtime()
        if (name == null) since = -1
        phone = name
        DebugLog.change("BT", "phone", when {
            !usable -> "Android can't read Bluetooth (${found.exceptionOrNull()?.javaClass?.simpleName})"
            !adapter.isEnabled -> "Bluetooth off in Android"
            else -> name ?: "none"
        })
    }

    private class PhoneByProfile(val label: String)

    /** Everything Android's Bluetooth shows, plus vendor Bluetooth apps (a separate module) — for the debug log. */
    @SuppressLint("MissingPermission")
    fun report(): String {
        if (GocBt.present()) {
            val st = GocBt.state
            return "Bluetooth module (Goodocom, ${GocBt.SERIAL}): ${GocBt.status} · phone ${if (st.phoneConnected) "connected" else "not connected"} " +
                "${st.name ?: ""} ${st.mac ?: ""} · hfp ${st.hfp} · audio ${st.a2dp} · call ${st.inCall} · Android's own Bluetooth not used on this unit"
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "Android Bluetooth: no adapter at all — the car's Bluetooth is a separate module. Vendor apps: ${vendorApps()}"
        val lines = mutableListOf("Android Bluetooth: ${if (runCatching { adapter.isEnabled }.getOrDefault(false)) "on" else "OFF"} · name ${runCatching { adapter.name }.getOrNull()}")
        runCatching { adapter.bondedDevices.orEmpty() }.onSuccess { b ->
            lines += "paired: " + b.joinToString { d ->
                "${runCatching { d.name }.getOrNull() ?: d.address} (class ${runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()?.let { "0x%x".format(it) }}, link ${if (d.address in aclUp || isConnected(d)) "up" else "down"})"
            }.ifEmpty { "none" }
        }.onFailure { lines += "paired: can't read (${it.javaClass.simpleName}: ${it.message})" }
        lines += "profiles: " + PROFILES.entries.joinToString { (id, label) ->
            val st = runCatching { adapter.getProfileConnectionState(id) }.getOrNull()
            "$label ${when (st) { 2 -> "CONNECTED"; 1 -> "connecting"; 0 -> "-"; else -> "?" }}" + (proxies[id]?.let { p -> runCatching { p.connectedDevices.size }.getOrNull()?.takeIf { it > 0 }?.let { " ($it dev)" } } ?: "")
        }
        lines += "vendor Bluetooth apps: ${vendorApps()}"
        return lines.joinToString(" · ")
    }

    private fun vendorApps(): String = runCatching {
        app.packageManager.getInstalledPackages(0).map { it.packageName }
            .filter { Regex("bt|blue|goc|phone|dial|carlink|syu|fyt|mtc", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .filter { !it.startsWith("com.android.") && !it.startsWith("com.google.") }
            .joinToString().ifEmpty { "none" }
    }.getOrDefault("?")

    /** Hidden but long-standing BluetoothDevice.isConnected() (ACL link up); Android 10 may refuse it to apps. */
    private fun isConnected(d: BluetoothDevice): Boolean =
        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun isPhone(d: BluetoothDevice): Boolean {
        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        val name = runCatching { d.name }.getOrNull().orEmpty()
        return major == BluetoothClass.Device.Major.PHONE || name.contains("iphone", ignoreCase = true)
    }

    /** A call on the car's speakerphone: don't touch Bluetooth. */
    fun inCall(context: Context): Boolean {
        if (GocBt.present() && GocBt.inCall(context)) return true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE || am.isBluetoothScoOn
    }

    /** Bluetooth off, 2 s, on (root `svc bluetooth`, else the adapter API). Blocking — worker thread. */
    @SuppressLint("MissingPermission")
    fun kick(): String = doKick().also { DebugLog.log("BT", "kick: $it (phone ${phone ?: "?"})") }

    @SuppressLint("MissingPermission")
    private fun doKick(): String {
        pendingAt = SystemClock.elapsedRealtime()
        if (GocBt.present()) return GocBt.kick()
        if (Root.run("svc bluetooth disable", "sleep 2", "svc bluetooth enable")) return "Bluetooth off/on (root)"
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return "no Bluetooth"
        @Suppress("DEPRECATION")
        val ok = runCatching { adapter.disable().also { SystemClock.sleep(2500) } && adapter.enable() }.getOrDefault(false)
        return if (ok) "Bluetooth off/on" else "Bluetooth couldn't be switched (no root)"
    }

    /** Called by the Wi-Fi doctor every check: did the last kick bring a hotspot? */
    fun judge(oursVisibleAt: Long, connectedNow: Boolean) {
        val at = pendingAt
        if (at == 0L) return
        val now = SystemClock.elapsedRealtime()
        when {
            oursVisibleAt > at || connectedNow -> { record(true, ((maxOf(oursVisibleAt, at) - at) / 1000).toInt().coerceAtLeast(0)); pendingAt = 0L }
            now - at > JUDGE_MS -> { record(false, (JUDGE_MS / 1000).toInt()); pendingAt = 0L }
        }
    }

    private fun record(worked: Boolean, secs: Int) {
        DebugLog.log("BT", if (worked) "kick worked: hotspot after $secs s" else "kick didn't bring a hotspot within ${secs} s")
        val sp = app.getSharedPreferences("dock", Context.MODE_PRIVATE)
        val lines = sp.getString("btKicks", "")!!.split('\n').filter { it.isNotBlank() } + "${System.currentTimeMillis()}|${if (worked) 1 else 0}|$secs"
        sp.edit().putString("btKicks", lines.takeLast(KEEP).joinToString("\n")).apply()
    }

    /** "3 of 4 kicks brought a hotspot, after ~9 s" — or null before the first. */
    fun summary(context: Context): String? {
        val rows = context.getSharedPreferences("dock", Context.MODE_PRIVATE).getString("btKicks", "")!!
            .split('\n').mapNotNull { l -> l.split('|').takeIf { it.size == 3 } }
        if (rows.isEmpty()) return null
        val wins = rows.filter { it[1] == "1" }
        val med = FixStats.percentile(wins.mapNotNull { it[2].toIntOrNull() }, 0.5)
        return "${wins.size} of ${rows.size} kicks brought a hotspot back" + (med?.let { ", typically after ~$it s" } ?: "")
    }
}
