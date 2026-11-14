package com.docunest.dock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The iDrive straight from the car's controller board (KSW units). The KSW key service
 * (com.wits.pms) logs every controller message ("KswMcuListener … cmdType:A1 - data:17-06-01")
 * and only then injects an Android key — 150 ms later, and while the wheel turns at most one key
 * per ~300 ms (iDrive test 2026-11-14: 29 clicks → 8 keys). Following that log gives every click
 * at once.
 *
 * Codes (A1 17-XX-YY, YY 01 = pressed, 00 = released; the wheel sends only 01 per click):
 * 01 push up · 02 push down · 03 push left · 04 push right · 05 knob press · 06 wheel right ·
 * 07 wheel left · 0C BACK — and what the KSW service turns them into ([PMS_KEY]).
 *
 * Needs READ_LOGS (granted through root by [GocBt]); without it nothing is heard and the
 * injected keys keep working as before.
 */
object McuKeys {
    data class Ev(val code: Int, val pressed: Boolean, val lagMs: Long)

    /** KSW service's Android key for each controller code (seen in the iDrive test). */
    val PMS_KEY = mapOf(0x01 to 691, 0x02 to 692, 0x03 to 21, 0x04 to 22, 0x05 to 66, 0x06 to 20, 0x07 to 19, 0x0C to 4)

    /** Controller key events, on the main thread. */
    val listeners = CopyOnWriteArrayList<(Ev) -> Unit>()
    /** Every other interesting line (other controller messages, the KSW service's injected keys) — for the iDrive test. */
    val rawListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** uptime of the last controller key message, per KSW Android key it stands for. */
    private val lastFor = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    @Volatile var status = "not started"
        private set
    @Volatile private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val keyRx = Regex("cmdType:A1 - data:17-([0-9A-F]{2})-([0-9A-F]{2})")
    private val anyRx = Regex("cmdType:(\\w+) - data:([0-9A-F-]+)")
    private val pmsRx = Regex("handleKeyEvent keycode - (\\d+)")

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({ follow(app) }, "mcu-keys").start()
    }

    /** True if the controller reported the key that became Android key [keyCode] within [ms] — i.e. already handled. */
    fun justHad(keyCode: Int, ms: Long = 500): Boolean =
        (lastFor[keyCode] ?: 0L).let { it > 0 && SystemClock.uptimeMillis() - it < ms }

    private fun follow(app: Context) {
        while (true) {
            if (app.checkSelfPermission("android.permission.READ_LOGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                status = "no READ_LOGS yet (granted at the next dock restart)"
                SystemClock.sleep(60_000)
                continue
            }
            status = "following the controller"
            DebugLog.change("KEY", "controller", "following")
            runCatching {
                val since = System.currentTimeMillis() / 1000.0
                val p = ProcessBuilder("logcat", "-v", "epoch", "-T", "1", "-s", "KswMcuListener:I", "CenterControl:I")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { feed(it, since) } }
            }
            status = "log stream ended — retrying"
            DebugLog.change("KEY", "controller", "stream ended")
            SystemClock.sleep(5_000)
        }
    }

    private fun feed(line: String, since: Double) {
        val epoch = line.trim().substringBefore(' ').toDoubleOrNull() ?: return
        if (epoch < since - 1) return
        val lag = (System.currentTimeMillis() - epoch * 1000).toLong()
        keyRx.find(line)?.let { m ->
            val code = m.groupValues[1].toInt(16)
            val pressed = m.groupValues[2] != "00"
            PMS_KEY[code]?.let { lastFor[it] = SystemClock.uptimeMillis() }
            val ev = Ev(code, pressed, lag)
            main.post { listeners.forEach { l -> runCatching { l(ev) } } }
            if (rawListeners.isNotEmpty()) raw("controller 17-${m.groupValues[1]}-${m.groupValues[2]} (${lag} ms ago)")
            return
        }
        if (rawListeners.isEmpty()) return
        pmsRx.find(line)?.let { raw("KSW service injects key ${it.groupValues[1]}"); return }
        anyRx.find(line)?.let { m ->
            val t = m.groupValues[1]; val d = m.groupValues[2]
            // Skip the status chatter (speed, doors, climate, heartbeats).
            if (t == "68" || t == "1C" || (t == "A1" && (d.startsWith("19-") || d.startsWith("12-") || d.startsWith("10-")))) return
            raw("controller $t $d")
        }
    }

    private fun raw(s: String) = main.post { rawListeners.forEach { l -> runCatching { l(s) } } }
}
