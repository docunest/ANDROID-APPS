package com.docunest.dock

/**
 * State of the KSW head unit's external Goodocom Bluetooth module (the phone,
 * calls, audio), read from its log lines — tags `goc` / `GocsdkCallbackImp`.
 * Android's own Bluetooth stack is off on this unit, so this is the only way
 * to know whether the iPhone is connected. Pure, unit-tested with the lines
 * seen live on 2026-11-13 (owner's ADB handoff).
 *
 *   onHfpStatus: 1|3|4|5|6, MG1|MG3… — HFP: 1 idle (no phone), 3 connected, 4–6 call (out / in / talking)
 *   MU1 / MU2                         — A2DP audio link down / up
 *   IV<mac> connecting · IB<mac> HFP connected · IA<mac><reason> disconnected
 *   SA<name> connected device name    · MX<0|1><mac><name> paired-list entry
 */
class GocState {
    /** HFP status as the module reports it (0 = unknown yet). */
    var hfp = 0
        private set
    var a2dp: Boolean? = null
        private set
    var mac: String? = null
        private set
    var name: String? = null
        private set
    /** Time (ms, the caller's clock) the phone last connected, -1 = not connected. */
    var connectedAt = -1L
        private set
    var lastLineAt = -1L
        private set

    val phoneConnected: Boolean get() = hfp >= 3
    val inCall: Boolean get() = hfp in 4..6

    fun feed(t: Long, line: String) {
        lastLineAt = t
        val before = phoneConnected
        Regex("onHfpStatus:\\s*(\\d)").find(line)?.let { hfp = it.groupValues[1].toInt() }
        Regex("\\bMG(\\d)\\b").find(line)?.let { hfp = it.groupValues[1].toInt() }
        Regex("\\bMU([12])\\b").find(line)?.let { a2dp = it.groupValues[1] == "2" }
        Regex("\\bI([BV])([0-9A-Fa-f]{12})").find(line)?.let {
            mac = it.groupValues[2].uppercase()
            if (it.groupValues[1] == "B" && hfp < 3) hfp = 3 // HFP connected
        }
        Regex("\\bIA([0-9A-Fa-f]{12})").find(line)?.let { hfp = 1; a2dp = false }
        if (line.contains("onHfpConnected")) hfp = maxOf(hfp, 3)
        if (line.contains("onHfpDisconnected")) hfp = 1
        // The live log wraps replies as "send_ind: [SALukas’s iPhone] serial": take what's inside the brackets.
        Regex("\\bSA([^\\]]+)").find(line)?.let { name = it.groupValues[1].trim().takeIf { n -> n.isNotEmpty() } ?: name }
        Regex("\\bMX[01]([0-9A-Fa-f]{12})([^\\]]*)").find(line)?.let {
            if (mac == null) mac = it.groupValues[1].uppercase()
            if (name == null) name = it.groupValues[2].trim().takeIf { n -> n.isNotEmpty() }
        }
        if (phoneConnected && !before) connectedAt = t
        if (!phoneConnected) connectedAt = -1
    }

    companion object {
        /** "84D328D8B613" from "84:D3:28:D8:B6:13" (the module's format). */
        fun moduleMac(mac: String) = mac.replace(":", "").uppercase()
    }
}

/**
 * Which Wi-Fi to be on. Owner's rule (changed 2026-11-15 — "Starlink signal wins, it's unmetered"):
 * a working Starlink with a decent signal beats the iPhone hotspot; the iPhone ("Lukas’s iPhone",
 * curly apostrophe U+2019) is for when Starlink isn't there (driving), is weak, or has no internet.
 * Hysteresis so it doesn't ping-pong: go to Starlink at ≥ [UNMETERED_JOIN] dBm, leave it only below
 * [UNMETERED_LEAVE] or without internet. Pure, unit-tested.
 */
object WifiPrefer {
    const val PRIMARY = "Lukas’s iPhone"
    /** A hotspot this weak couldn't be joined anyway — don't drop a working network for it. */
    const val MIN_RSSI = -85
    /** Starlink at least this strong: switch to it. */
    const val UNMETERED_JOIN = -72
    /** On Starlink: leave only when it's weaker than this (or has no internet). */
    const val UNMETERED_LEAVE = -80

    private fun norm(s: String) = s.replace('’', '\'').replace('‘', '\'').trim().lowercase()

    /** Unmetered: the van's Starlink. */
    fun unmetered(ssid: String?): Boolean = ssid?.contains("starlink", ignoreCase = true) == true

    /** Among the metered ones: 3 = the owner's iPhone, 2 = another iPhone hotspot, 1 = anything else, 0 = nothing. */
    fun rank(ssid: String?): Int = when {
        ssid.isNullOrBlank() -> 0
        norm(ssid) == norm(PRIMARY) -> 3
        ssid.contains("iphone", ignoreCase = true) -> 2
        else -> 1
    }

    /**
     * The network to switch to from [current], given what's visible (SSID → dBm); null = stay.
     * [currentDbm]: the signal of the network we're on; [currentWorks]: its last internet check
     * (null = unknown); [avoid]: networks that just failed (Starlink with no internet).
     */
    fun switchTo(
        current: String?,
        visible: Map<String, Int>,
        currentDbm: Int? = current?.let { visible[it] },
        currentWorks: Boolean? = null,
        avoid: Set<String> = emptySet(),
    ): String? {
        fun bestMetered(min: Int) = visible.filter { (s, d) -> !unmetered(s) && s !in avoid && rank(s) >= min && d >= MIN_RSSI }
            .maxWithOrNull(compareBy({ rank(it.key) }, { it.value }))?.key
        if (unmetered(current)) {
            val weak = currentDbm != null && currentDbm < UNMETERED_LEAVE
            if (currentWorks != false && !weak) return null // a working Starlink: stay
            return bestMetered(2) // weak or no internet: the iPhone, if it's there
        }
        visible.filter { (s, d) -> unmetered(s) && s !in avoid && d >= UNMETERED_JOIN }.maxByOrNull { it.value }?.let { return it.key }
        return bestMetered(rank(current) + 1)
    }
}
