package com.docunest.dock

import android.content.Context

/**
 * One-shot system scan into the debug log (tag DIAG): the GPS chip and its
 * config, the Bluetooth module, the CAN box (vendor apps, properties,
 * services, broadcasts mentioning speed) and sensors — what the laptop
 * diagnostics script collects, run on the unit itself through [Root]
 * (su or the unit's own adb). Without root only the parts an app may read.
 * Runs once per dock start (after root is ready) and from the Debug screen.
 */
object Diagnostics {
    @Volatile var running = false
        private set
    @Volatile var lastRun = "never"
        private set

    private val SECTIONS = listOf(
        "device" to "getprop ro.build.type; getprop ro.build.fingerprint; getprop ro.product.model; getprop ro.board.platform; getprop ro.hardware",
        "gnss files" to "ls -l /vendor/etc/gps* /system/etc/gps* /vendor/etc/*gnss* /vendor/etc/mnl* /system/vendor/etc/gps* 2>/dev/null",
        "gnss config" to "cat /vendor/etc/gps.conf /system/etc/gps.conf 2>/dev/null | grep -v '^#' | grep .",
        "gnss processes" to "ps -A | grep -iE 'gnss|gps|mnld|lbs|loc'",
        "gnss props" to "getprop | grep -iE 'gps|gnss|mnl'",
        "location service" to "dumpsys location | grep -iE 'gnss|gps|provider|capabil|hal|constellation' | head -60",
        "bluetooth" to "dumpsys bluetooth_manager | head -80",
        "bt / phone apps" to "pm list packages | grep -iE 'bt|blue|goc|phone|dial|syu|fyt|mtc'",
        "bt processes" to "ps -A | grep -iE 'bt|blue|goc'",
        "can / car apps" to "pm list packages | grep -iE 'can|mcu|car|vehicle|obd|syu|fyt|ts\\.'",
        "can props" to "getprop | grep -iE 'can|mcu|speed|car|vehicle'",
        "can services" to "service list | grep -iE 'can|mcu|car|vehicle'",
        "can devices" to "ls /dev | grep -iE 'tty|can|mcu'",
        "speed broadcasts" to "dumpsys activity broadcasts | grep -iE 'speed|canbus|can_|vehicle|mcu' | sort | uniq -c | sort -rn | head -40",
        "sensors" to "dumpsys sensorservice | head -40",
    )

    fun runAsync(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            try {
                run(app)
            } finally {
                running = false
            }
        }.start()
    }

    private fun run(context: Context) {
        val via = Root.via()
        DebugLog.log("DIAG", "── diagnostics start (root via $via) ──")
        var lines = 0
        for ((name, cmd) in SECTIONS) {
            // Root/adb first (dumpsys, vendor dirs); plain shell as the app otherwise.
            val out = Root.output(cmd) ?: runCatching {
                val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrNull()
            val body = out?.lines()?.map { it.trimEnd() }?.filter { it.isNotBlank() }.orEmpty()
            DebugLog.log("DIAG", "[$name] " + if (body.isEmpty()) "(nothing)" else "${body.size} lines")
            body.take(80).forEach { DebugLog.log("DIAG", "  $it"); lines++ }
        }
        BtKick.runCatching { DebugLog.log("DIAG", "[bluetooth as the app sees it] ${report()}") }
        DebugLog.log("DIAG", "[gps] per system: ${GpsDoctor.perSystem.ifEmpty { "—" }} · raw measurements: ${GpsDoctor.rawMeasurements}")
        DebugLog.log("DIAG", "── diagnostics done: $lines lines ──")
        lastRun = "${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())} via $via ($lines lines)"
    }
}
