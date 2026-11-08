package com.docunest.dock

/**
 * The ring around the floating button: left half = GPS ([GpsDot]), right
 * half = Wi-Fi ([WifiDot]). White (the button's normal outline) while all is
 * fine; a half turns thicker and coloured only when something is off.
 */
object Ring {
    data class Arc(val color: Int, val dashed: Boolean = false, val fine: Boolean = false)

    val FINE = Arc(0xFFFFFFFF.toInt(), fine = true)
}
