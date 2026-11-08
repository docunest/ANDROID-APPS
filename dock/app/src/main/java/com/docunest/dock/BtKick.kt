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

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = refresh()
            }, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            })
        }
        refresh()
    }

    /** true / false, or null when Android's Bluetooth can't tell (no adapter, no permission, separate BT module). */
    fun phoneConnected(): Boolean? = if (!usable) null else phone != null

    @SuppressLint("MissingPermission")
    fun refresh() {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            usable = false; phone = null; since = -1; return
        }
        val found = runCatching {
            if (!adapter.isEnabled) return@runCatching null
            adapter.bondedDevices.orEmpty().firstOrNull { d -> isConnected(d) && isPhone(d) }
        }
        usable = found.isSuccess
        val name = found.getOrNull()?.let { runCatching { it.name }.getOrNull() ?: it.address }
        if (name != null && phone == null) since = SystemClock.elapsedRealtime()
        if (name == null) since = -1
        phone = name
    }

    /** Hidden but long-standing BluetoothDevice.isConnected() (ACL link up). */
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
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE || am.isBluetoothScoOn
    }

    /** Bluetooth off, 2 s, on (root `svc bluetooth`, else the adapter API). Blocking — worker thread. */
    @SuppressLint("MissingPermission")
    fun kick(): String {
        pendingAt = SystemClock.elapsedRealtime()
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
