package com.docunest.dock

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * KSW unit only (phone on the Goodocom module, [GocBt]): keeps Android's OWN Bluetooth stack off.
 *
 * Seen 2026-11-14: Google Play services switched it on ("Enabled due to APPLICATION_REQUEST by
 * com.google.android.gms", state BLE_TURNING_ON — Bluetooth scanning for location). The unit's
 * Qualcomm Bluetooth isn't usable (the module owns the phone link), so the stack crashed every
 * ~3.5 s — 641 times in 38 min — until Android's core process died: System UI not responding,
 * Waze on "approximate location", full restarts at 12:07, 13:04, ~13:16 UTC.
 *
 * Every minute: if the stack isn't OFF, or Bluetooth scanning / the saved "Bluetooth on" is set,
 * switch them off (root). Logged as BT. Never touches the Goodocom module.
 */
object BtStackGuard {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bt-guard") }
    @Volatile private var started = false

    fun start() {
        if (started || !GocBt.present()) return
        started = true
        exec.scheduleWithFixedDelay({ runCatching { check() } }, 15, 60, TimeUnit.SECONDS)
    }

    private fun check() {
        val out = Root.output(
            "echo on=\$(settings get global bluetooth_on)",
            "echo scan=\$(settings get global ble_scan_always_enabled)",
            "dumpsys bluetooth_manager 2>/dev/null | head -12 | grep -E '^ +(state|enabled):' | tr -d ' ' | tr '\\n' ' '; echo",
            "dumpsys bluetooth_manager 2>/dev/null | grep -m1 'Bluetooth crashed'; true",
        ) ?: return
        val on = Regex("on=(\\S+)").find(out)?.groupValues?.get(1)
        val scan = Regex("scan=(\\S+)").find(out)?.groupValues?.get(1)
        val state = Regex("state:(\\S+)").find(out)?.groupValues?.get(1) ?: "?"
        val crashes = Regex("crashed (\\d+) times").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val bad = state != "OFF" || on == "1" || scan == "1"
        DebugLog.change("BT", "android stack", if (bad) "ON ($state)" else "off",
            "saved on $on · Bluetooth scanning $scan · crashed $crashes times since start · phone uses the Goodocom module")
        if (!bad) return
        val ok = Root.run(
            "settings put global ble_scan_always_enabled 0",
            "settings put global bluetooth_on 0",
            "svc bluetooth disable",
        )
        DebugLog.log("BT", "Android's own Bluetooth was $state (crashed $crashes times, scanning $scan) → " +
            if (ok) "switched off + Bluetooth scanning off" else "couldn't switch off (no root)")
    }
}
