package com.docunest.dock

/**
 * GPS state for the left half of the button's ring ([Ring]). Pure
 * (unit-tested); fed once a second by the dock service. White = fine —
 * colour only when something is off:
 *
 *  white  – fix in the last 15 s, ±50 m or better (or GPS not in use)
 *  orange – barely: worse than ±50 m, or no fix for 15–60 s
 *  grey, dashed – no sky: tunnel / car park / known no-GPS spot (auto doctor waits)
 *  red    – gone: no fix for 60 s+, or location/GPS off
 *  pink   – emulated: the parked position is shown (cold start in a garage)
 *
 * The fix age is the freshest of three sources (background listener, the
 * GPS doctor's own listener, Android's last known) — reading only one made
 * the old dot go red while the GPS worked.
 */
object GpsDot {
    enum class Level(val color: Int, val label: String) {
        EMULATED(0xFFEC4899.toInt(), "emulated (parked position)"),
        GREAT(0xFF2563EB.toInt(), "really good"),
        GOOD(0xFF16A34A.toInt(), "OK"),
        WEAK(0xFFF97316.toInt(), "barely working"),
        NO_SKY(0xFF000000.toInt(), "no sky (tunnel / car park)"),
        GONE(0xFFDC2626.toInt(), "gone"),
        UNKNOWN(0xFF9CA3AF.toInt(), "not in use"),
    }

    const val GREAT_M = 10f
    const val GOOD_M = 50f
    /** Parked, some chips report only every 5–10 s: 15 s is still fine. */
    const val FRESH_MS = 15_000L
    const val LATE_MS = 60_000L

    /**
     * [fixAgeMs]/[accuracyM]: the last real (never mock) GPS fix; null = none.
     * [noSky]: the auto doctor judges tunnel / covered / dead zone.
     * [asked]: something keeps the GPS running (our doctor or another app).
     */
    fun level(settingsOk: Boolean, emulated: Boolean, fixAgeMs: Long?, accuracyM: Float?, noSky: Boolean, asked: Boolean): Level = when {
        emulated -> Level.EMULATED
        !settingsOk -> Level.GONE
        fixAgeMs != null && fixAgeMs <= FRESH_MS -> when {
            accuracyM == null -> Level.GOOD
            accuracyM <= GREAT_M -> Level.GREAT
            accuracyM <= GOOD_M -> Level.GOOD
            else -> Level.WEAK
        }
        fixAgeMs != null && fixAgeMs <= LATE_MS -> Level.WEAK
        noSky -> Level.NO_SKY
        !asked -> Level.UNKNOWN
        else -> Level.GONE
    }

    /** How the left half of the ring looks. */
    fun arc(l: Level): Ring.Arc = when (l) {
        Level.GREAT, Level.GOOD, Level.UNKNOWN -> Ring.FINE
        Level.WEAK -> Ring.Arc(0xFFF97316.toInt())
        Level.NO_SKY -> Ring.Arc(0xFF9CA3AF.toInt(), dashed = true)
        Level.GONE -> Ring.Arc(0xFFDC2626.toInt())
        Level.EMULATED -> Ring.Arc(0xFFEC4899.toInt())
    }
}
