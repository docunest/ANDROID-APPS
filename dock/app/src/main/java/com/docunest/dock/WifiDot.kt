package com.docunest.dock

/**
 * The Wi-Fi dot on the floating button (bottom-right; GPS is bottom-left).
 * Pure (unit-tested); fed once a second by the dock service from [WifiDoctor].
 *
 *  green  – connected, internet works (or still being checked)
 *  orange – connected, but no internet behind it (iPhone has no mobile data — tunnel / no coverage)
 *  blue   – one of our networks is visible, joining it
 *  purple – none visible, Bluetooth kicked: waiting for the iPhone to switch its hotspot on
 *  red    – lost: none of our networks visible
 *  grey   – Wi-Fi switched off
 */
object WifiDot {
    enum class Level(val color: Int, val label: String) {
        ONLINE(0xFF16A34A.toInt(), "internet works"),
        NO_INTERNET(0xFFF97316.toInt(), "connected, no internet (iPhone has no data?)"),
        JOINING(0xFF2563EB.toInt(), "hotspot visible, joining"),
        KICKED(0xFFA855F7.toInt(), "Bluetooth kicked, waiting for the hotspot"),
        LOST(0xFFDC2626.toInt(), "lost, no hotspot visible"),
        OFF(0xFF9CA3AF.toInt(), "Wi-Fi off"),
    }

    /** [internet]: null = not checked yet. [kickPending]: a Bluetooth kick is waiting for its result. */
    fun level(enabled: Boolean, connected: Boolean, internet: Boolean?, oursVisible: Boolean, kickPending: Boolean): Level = when {
        !enabled -> Level.OFF
        connected -> if (internet == false) Level.NO_INTERNET else Level.ONLINE
        oursVisible -> Level.JOINING
        kickPending -> Level.KICKED
        else -> Level.LOST
    }
}
