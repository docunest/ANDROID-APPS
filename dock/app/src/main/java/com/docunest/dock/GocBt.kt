package com.docunest.dock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream

/**
 * The KSW unit's phone Bluetooth: an external Goodocom module behind
 * `/dev/goc_serial` (Android's own Bluetooth stack is off on this unit).
 * Verified over ADB on 2026-11-13 (owner's handoff):
 *  - commands: write `AT#<CMD>\r\n` to /dev/goc_serial (world-writable, SELinux permissive) —
 *    CD disconnect all, CC<mac> connect (disconnects first, HFP ~7 s, A2DP ~14 s), CY / QB / QA queries;
 *  - state: never read /dev/goc_serial (the stock BT app reads it; a second reader steals replies) —
 *    follow its log instead (tags goc / GocsdkCallbackImp) via `logcat`, with READ_LOGS granted
 *    through [Root] (the unit's own adb as root).
 */
object GocBt {
    const val SERIAL = "/dev/goc_serial"

    val state = GocState()
    @Volatile var status = "not started"
        private set

    private lateinit var app: Context
    private var started = false

    /** The module is there (this head unit). */
    fun present(): Boolean = File(SERIAL).exists()

    fun start(context: Context) {
        if (started || !present()) return
        started = true
        app = context.applicationContext
        Thread({ follow() }, "goc-log").start()
    }

    /** Send one command (no reply here — it shows up in the log). */
    fun send(cmd: String): Boolean {
        val line = "AT#$cmd\r\n"
        val ok = runCatching { FileOutputStream(SERIAL).use { it.write(line.toByteArray()) } }.isSuccess ||
            Root.run("printf 'AT#$cmd\\r\\n' > $SERIAL")
        DebugLog.log("BT", "goc → AT#$cmd ${if (ok) "sent" else "FAILED"}")
        return ok
    }

    fun inCall(context: Context): Boolean =
        state.inCall || runCatching { Settings.System.getInt(context.contentResolver, "isCalling", 0) == 1 }.getOrDefault(false)

    /** The phone to reconnect: last seen in the log, else remembered. */
    fun phoneMac(): String? = state.mac?.also {
        app.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putString("gocMac", it).apply()
    } ?: app.getSharedPreferences("dock", Context.MODE_PRIVATE).getString("gocMac", null)

    /**
     * The Bluetooth kick through the module: drop the phone (fires the iPhone's "disconnected" automation),
     * then connect it again (fires "connected"). Blocking ~3 s — worker thread.
     */
    fun kick(): String {
        val mac = phoneMac() ?: run {
            send("QA") // ask for the current device's MAC; it lands in the log
            SystemClock.sleep(1500)
            phoneMac()
        } ?: return "no phone MAC known yet — nothing to reconnect"
        if (!send("CD")) return "couldn't write to $SERIAL"
        SystemClock.sleep(3000)
        send("CC$mac")
        return "phone dropped and reconnecting via the Bluetooth module ($mac)"
    }

    /**
     * Follow the module's log for ever: grant READ_LOGS once (adb root), then stream `logcat` for the two tags.
     * Without the grant (no root yet) poll through [Root] every 5 s instead.
     */
    private fun follow() {
        val pkg = app.packageName
        var granted = app.checkSelfPermission("android.permission.READ_LOGS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        while (true) {
            if (!granted) {
                granted = Root.run("pm grant $pkg android.permission.READ_LOGS") // takes effect after our process restarts
                if (granted) DebugLog.log("BT", "READ_LOGS granted (used after the dock restarts); polling through root meanwhile")
            }
            val streamed = app.checkSelfPermission("android.permission.READ_LOGS") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                runCatching { stream() }.isSuccess
            if (!streamed) {
                // Poll through root (adb), 5 s at a time.
                status = "polling the module's log through root"
                Root.output("logcat -d -v epoch -t 300 -s goc:V GocsdkCallbackImp:V")?.lines()?.forEach { feed(it) }
                SystemClock.sleep(5_000)
            } else SystemClock.sleep(2_000) // stream ended: restart it
        }
    }

    /** Newest log time fed (epoch seconds) — polling re-reads the buffer, so older lines are skipped. */
    private var lastEpoch = 0.0
    private val sameEpoch = mutableSetOf<String>()

    private fun stream() {
        status = "following the module's log"
        // Ask for the current state once; the replies arrive in the log we're reading.
        Thread { SystemClock.sleep(1500); send("CY"); send("QB"); send("QA") }.start()
        val p = ProcessBuilder("logcat", "-v", "epoch", "-T", "200", "-s", "goc:V", "GocsdkCallbackImp:V").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { feed(it) } }
    }

    private fun feed(line: String) {
        if (line.isBlank()) return
        // "-v epoch" lines start with "1731505440.123": feed each line once, in order.
        val epoch = line.trim().substringBefore(' ').toDoubleOrNull() ?: return
        if (epoch < lastEpoch) return
        if (epoch == lastEpoch && !sameEpoch.add(line)) return // same millisecond: once per distinct line
        if (epoch > lastEpoch) { lastEpoch = epoch; sameEpoch.clear(); sameEpoch.add(line) }
        val before = Triple(state.phoneConnected, state.inCall, state.a2dp)
        synchronized(state) { state.feed(SystemClock.elapsedRealtime(), line) }
        val after = Triple(state.phoneConnected, state.inCall, state.a2dp)
        if (after != before) {
            DebugLog.log("BT", "module: phone ${if (state.phoneConnected) "CONNECTED ${state.name ?: ""}" else "not connected"} · " +
                "call ${state.inCall} · audio ${state.a2dp} · hfp ${state.hfp} · mac ${state.mac ?: "?"}  [${line.takeLast(80).trim()}]")
            BtKick.refresh()
        }
    }
}
