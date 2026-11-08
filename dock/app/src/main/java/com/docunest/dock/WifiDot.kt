package com.docunest.dock

/**
 * Wi-Fi state for the right half of the button's ring ([Ring]). Pure
 * (unit-tested); fed once a second by the dock service from [WifiDoctor].
 * White = fine — colour only when something is off:
 *
 *  white  – connected, internet works, link great/ok (or being checked)
 *  yellow – connected but the link is getting poor (ping / loss) — may drop soon
 *  orange – connected, but no internet behind it (iPhone has no mobile data), or the link is bad
 *  blue   – one of our networks is visible, joining it
 *  purple – none visible, Bluetooth kicked: waiting for the iPhone to switch its hotspot on
 *  red    – lost: none of our networks visible
 *  grey, dashed – Wi-Fi switched off
 */
object WifiDot {
    enum class Level(val color: Int, val label: String) {
        ONLINE(0xFF16A34A.toInt(), "internet works"),
        POOR(0xFFFACC15.toInt(), "link getting poor — may drop"),
        NO_INTERNET(0xFFF97316.toInt(), "connected, no internet (iPhone has no data?)"),
        JOINING(0xFF2563EB.toInt(), "hotspot visible, joining"),
        KICKED(0xFFA855F7.toInt(), "Bluetooth kicked, waiting for the hotspot"),
        LOST(0xFFDC2626.toInt(), "lost, no hotspot visible"),
        OFF(0xFF9CA3AF.toInt(), "Wi-Fi off"),
    }

    /** [internet]: null = not checked yet. [kickPending]: a Bluetooth kick is waiting for its result. [link]: ping meter. */
    fun level(
        enabled: Boolean,
        connected: Boolean,
        internet: Boolean?,
        oursVisible: Boolean,
        kickPending: Boolean,
        link: LinkQuality.Level = LinkQuality.Level.UNKNOWN,
    ): Level = when {
        !enabled -> Level.OFF
        connected -> when {
            internet == false || link == LinkQuality.Level.BAD -> Level.NO_INTERNET
            link == LinkQuality.Level.POOR -> Level.POOR
            else -> Level.ONLINE
        }
        oursVisible -> Level.JOINING
        kickPending -> Level.KICKED
        else -> Level.LOST
    }

    /** How the right half of the ring looks. */
    fun arc(l: Level): Ring.Arc = when (l) {
        Level.ONLINE -> Ring.FINE
        Level.OFF -> Ring.Arc(l.color, dashed = true)
        else -> Ring.Arc(l.color)
    }
}
