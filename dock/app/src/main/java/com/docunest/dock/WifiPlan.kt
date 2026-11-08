package com.docunest.dock

/**
 * What the Wi-Fi doctor does next while Wi-Fi is down — pure Kotlin, fed
 * every ~2 s, unit-tested. Aggressive on purpose (owner: battery doesn't
 * matter in the van; only the iPhone hotspots + Starlink are ever used).
 *
 *  - One of *our* networks (any saved one: the iPhone hotspots, Starlink)
 *    is visible → force a join 2 s after it shows up unless Android is
 *    already mid-handshake, then again after 6, 10, 20, 30, 30… s; every
 *    third try restarts Wi-Fi first. No give-up.
 *  - None visible but an iPhone is connected over Bluetooth → **Bluetooth
 *    kick**: switch Bluetooth off and on. The iPhone's automation turns its
 *    hotspot on whenever the car's Bluetooth connects *or* disconnects, so
 *    this switches the hotspot back on. Not during a call; not in the first
 *    [BT_SETTLE_MS] after Bluetooth connected by itself (that already fired
 *    the automation); then spaced 45 s, 60 s, 2 min, 5 min.
 */
class WifiPlan {
    enum class Action { NONE, JOIN, RESTART_AND_JOIN, BT_KICK }

    data class Step(val action: Action, val why: String)

    data class Input(
        /** ms, monotonic. */
        val t: Long,
        val connected: Boolean,
        /** wpa_supplicant is mid-join (associating / handshake / getting an address). */
        val joining: Boolean,
        /** One of our networks was in the last scan (seen in the last ~6 s). */
        val oursVisible: Boolean,
        /** An iPhone is connected to the car's Bluetooth; null = can't tell. */
        val btPhone: Boolean?,
        /** When that Bluetooth connection came up (ms), or -1. */
        val btSince: Long,
        val inCall: Boolean,
        val wifiOn: Boolean,
    )

    private var downSince = -1L
    private var visibleSince = -1L
    private var lastJoinAt = -1L
    var joinTries = 0
        private set
    private var lastKickAt = -1L
    var kicks = 0
        private set

    fun next(i: Input): Step {
        if (i.connected) {
            downSince = -1; visibleSince = -1; joinTries = 0; kicks = 0; lastJoinAt = -1
            return Step(Action.NONE, "connected")
        }
        if (downSince < 0) downSince = i.t
        if (!i.wifiOn) return Step(Action.NONE, "Wi-Fi is off")

        if (i.oursVisible) {
            if (visibleSince < 0) visibleSince = i.t
            if (i.joining && lastJoinAt >= 0 && i.t - lastJoinAt < JOINING_GRACE_MS) return Step(Action.NONE, "joining…")
            val wait = if (joinTries == 0) FIRST_JOIN_MS else JOIN_GAPS_MS[minOf(joinTries - 1, JOIN_GAPS_MS.size - 1)]
            val since = if (lastJoinAt < 0) i.t - visibleSince else i.t - lastJoinAt
            if (since < wait) return Step(Action.NONE, "visible — joining in ${(wait - since + 999) / 1000} s")
            joinTries++
            lastJoinAt = i.t
            return if (joinTries % 3 == 0) Step(Action.RESTART_AND_JOIN, "try $joinTries: Wi-Fi off/on, then join")
            else Step(Action.JOIN, "try $joinTries: join")
        }
        visibleSince = -1

        if (i.btPhone != true) return Step(Action.NONE, if (i.btPhone == null) "no hotspot visible" else "no hotspot visible, no iPhone on Bluetooth")
        if (i.inCall) return Step(Action.NONE, "no hotspot visible — in a call, not touching Bluetooth")
        if (i.btSince >= 0 && i.t - i.btSince < BT_SETTLE_MS) return Step(Action.NONE, "Bluetooth just connected — giving the iPhone time to switch its hotspot on")
        if (i.t - downSince < KICK_AFTER_MS) return Step(Action.NONE, "no hotspot visible")
        val gap = KICK_GAPS_MS[minOf(maxOf(kicks - 1, 0), KICK_GAPS_MS.size - 1)]
        if (lastKickAt >= 0 && i.t - lastKickAt < gap) return Step(Action.NONE, "Bluetooth kicked — next in ${(gap - (i.t - lastKickAt)) / 1000} s")
        kicks++
        lastKickAt = i.t
        return Step(Action.BT_KICK, "no hotspot visible, iPhone on Bluetooth — kick $kicks: Bluetooth off/on")
    }

    companion object {
        const val FIRST_JOIN_MS = 2_000L
        val JOIN_GAPS_MS = longArrayOf(6_000, 10_000, 20_000, 30_000)
        const val JOINING_GRACE_MS = 8_000L
        const val KICK_AFTER_MS = 10_000L
        const val BT_SETTLE_MS = 25_000L
        val KICK_GAPS_MS = longArrayOf(45_000, 60_000, 120_000, 300_000)
    }
}
