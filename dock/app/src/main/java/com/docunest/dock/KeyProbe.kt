package com.docunest.dock

import android.content.Context
import android.os.SystemClock

/**
 * Finds where the iDrive really comes from. The car's software injects keys late
 * (left/right only on release, wheel clicks at most ~1 per 150 ms, push down not
 * at all), so: for [SECONDS] s after the Dock keyboard opens, record the raw input
 * devices (getevent) and the system log lines about keys, through root, and put
 * them in the debug log (KEYRAW). At most once per 10 minutes.
 */
object KeyProbe {
    private const val SECONDS = 20
    @Volatile private var lastAt = 0L

    fun maybeStart(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < 10 * 60_000L) return
        lastAt = now
        Thread({ runCatching { run() }.onFailure { DebugLog.log("KEYRAW", "failed: $it") } }, "key-probe").start()
    }

    private fun run() {
        val devices = Root.output("getevent -pl 2>&1 | grep -E 'add device|name:|KEY_|REL_|ABS_' | head -120; true")
        DebugLog.log("KEYRAW", "input devices:")
        devices?.lines()?.filter { it.isNotBlank() }?.forEach { DebugLog.log("KEYRAW", "  ${it.trim().take(200)}") }
        DebugLog.log("KEYRAW", "recording $SECONDS s — turn the wheel fast, push up / down / left / right, hold one")
        val out = AdbShell.output(
            "D=/data/local/tmp/dockprobe; mkdir -p \$D",
            "(timeout $SECONDS getevent -lt > \$D/ge.txt 2>&1 &)",
            "(timeout $SECONDS logcat -v time -T 1 > \$D/lc.txt 2>&1 &)",
            "sleep ${SECONDS + 1}",
            "echo '== getevent'; grep -v -E 'SYN_REPORT|ABS_MT|BTN_TOUCH|ABS_X|ABS_Y' \$D/ge.txt | head -250",
            "echo '== logcat'; grep -i -E 'key|idrive|knob|rotat|encoder|inject|wheel|canbus|mcu' \$D/lc.txt | grep -v -i -E 'docunest|KEYRAW' | head -250",
            "rm -rf \$D; true",
            timeoutMs = (SECONDS + 25) * 1000,
        )
        if (out == null) { DebugLog.log("KEYRAW", "no root answer"); return }
        out.lines().filter { it.isNotBlank() }.forEach { DebugLog.log("KEYRAW", it.trim().take(220)) }
        DebugLog.log("KEYRAW", "done")
    }
}
