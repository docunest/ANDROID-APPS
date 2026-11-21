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
            // Straight from the log service, one entry at a time. `logcat` into a pipe holds its output
            // until ~4 KB pile up — 5–15 s late at this rate (seen live) — so it's only the fallback.
            val direct = runCatching { readLogd() }
            if (direct.isFailure) {
                DebugLog.change("KEY", "controller", "direct read failed (${direct.exceptionOrNull()?.javaClass?.simpleName}) — logcat")
                runCatching {
                    status = "following the controller (logcat, may be late)"
                    val since = System.currentTimeMillis() / 1000.0
                    val p = ProcessBuilder("logcat", "-v", "epoch", "-T", "1", "-s", "KswMcuListener:I", "CenterControl:I")
                        .redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().useLines { lines -> lines.forEach { feed(it, since) } }
                }
            }
            status = "log stream ended — retrying"
            DebugLog.change("KEY", "controller", "stream ended")
            SystemClock.sleep(5_000)
        }
    }

    /**
     * Read the main log buffer from logd's reader socket (needs READ_LOGS → the "log" group). Each
     * packet is one entry: header (len, hdr_size, pid, tid, sec, nsec, …), then priority, tag\0, message\0.
     */
    private fun readLogd() {
        val sock = android.net.LocalSocket(android.net.LocalSocket.SOCKET_SEQPACKET)
        sock.connect(android.net.LocalSocketAddress("logdr", android.net.LocalSocketAddress.Namespace.RESERVED))
        // The controller chatters ~1×/s while the car is on: 30 s of nothing from it means logd isn't
        // giving us other apps' entries (no "log" group yet) → the logcat fallback.
        sock.soTimeout = 30_000
        var seen = false
        sock.use { s ->
            s.outputStream.write("stream lids=0 tail=1".toByteArray())
            val input = s.inputStream
            val buf = ByteArray(5 * 1024 + 128)
            val since = System.currentTimeMillis() / 1000.0
            status = "following the controller (direct)"
            DebugLog.change("KEY", "controller", "following (direct)")
            while (true) {
                val n = try { input.read(buf) } catch (e: java.io.IOException) {
                    if (seen) return else throw e
                }
                if (n <= 0) return
                if (n < 20) continue
                val bb = java.nio.ByteBuffer.wrap(buf, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val hdr = (bb.getShort(2).toInt() and 0xffff).let { if (it in 20..64) it else 20 }
                val sec = bb.getInt(12).toLong() and 0xffffffffL
                val nsec = bb.getInt(16).toLong() and 0xffffffffL
                if (n <= hdr + 2) continue
                var i = hdr + 1 // skip priority
                val tagEnd = (i until n).firstOrNull { buf[it] == 0.toByte() } ?: continue
                val tag = String(buf, i, tagEnd - i)
                if (tag != "KswMcuListener" && tag != "CenterControl") continue
                seen = true
                i = tagEnd + 1
                var msgEnd = n
                while (msgEnd > i && (buf[msgEnd - 1] == 0.toByte() || buf[msgEnd - 1] == '\n'.code.toByte())) msgEnd--
                val msg = String(buf, i, msgEnd - i)
                feed("$sec.${"%09d".format(nsec)} $tag: $msg", since)
            }
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
