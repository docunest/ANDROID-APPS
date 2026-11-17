package com.docunest.dock

import android.os.Handler
import android.os.Looper

/**
 * Waze sometimes takes a link from the dock and does nothing (owner 2026-11-17: "I killed Waze, and
 * after restarting it picked it up"). On the unit, Waze's engine says "Url is not valid … Falling
 * back to init" for our ul?q= links, and a fresh Waze then runs the search once it has started.
 * A Waze that is already running can accept the link and never run it.
 *
 * So: after a link is sent, watch Waze's log (read by [Police] every 2 s). If Waze logged lines
 * but didn't start the search within [WAIT_MS], restart Waze and send the link again — once.
 * If no Waze log lines arrive at all (log not readable), do nothing: we can't tell, so we don't
 * kill a working Waze.
 */
object WazeLinkCheck {
    const val WAIT_MS = 12_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private class Pending(val uri: String, val retried: Boolean, val at: Long) {
        @Volatile var handled = false
        @Volatile var linesSeen = 0
    }
    @Volatile private var pending: Pending? = null

    /** Waze accepted [uri] from us (startActivity returned). */
    fun sent(uri: String, retried: Boolean = false) {
        val p = Pending(uri, retried, System.currentTimeMillis())
        pending = p
        main.postDelayed({ check(p) }, WAIT_MS)
    }

    /** Every line of Waze's log (from [Police]'s reader). */
    fun line(l: String) {
        val p = pending ?: return
        if (p.handled) return
        p.linesSeen++
        if (handledLine(l)) {
            p.handled = true
            DebugLog.log("NAV", "Waze ran the link after ${(System.currentTimeMillis() - p.at) / 1000} s${if (p.retried) " (after restart)" else ""}")
        } else if (l.contains("Url is not valid") || l.contains("Falling back to init")) {
            DebugLog.log("NAV", "Waze: " + l.substringAfter("] ").take(160))
        }
    }

    /** Waze starts the search / the drive for a link. */
    fun handledLine(l: String) =
        l.contains("Executing query:") || l.contains("Auto nav to address") ||
            (l.contains("startNavigationFlow") && l.contains("caller=DeepLink"))

    private fun check(p: Pending) {
        if (pending !== p || p.handled) return
        pending = null
        if (p.linesSeen == 0) {
            DebugLog.log("NAV", "no Waze log lines since the link — can't tell if it worked; leaving Waze alone")
            return
        }
        if (p.retried) {
            DebugLog.log("NAV", "Waze ignored the link even after a restart: ${p.uri}")
            return
        }
        DebugLog.log("NAV", "Waze ignored the link for ${WAIT_MS / 1000} s (${p.linesSeen} log lines) — restarting Waze and sending it again")
        Thread {
            val ok = Root.run("am force-stop com.waze", "sleep 1",
                "am start -a android.intent.action.VIEW -d '${p.uri.replace("'", "%27")}' com.waze")
            if (ok) sent(p.uri, retried = true)
            else DebugLog.log("NAV", "couldn't restart Waze (root)")
        }.start()
    }
}
