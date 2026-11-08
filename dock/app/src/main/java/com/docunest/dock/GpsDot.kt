package com.docunest.dock

/**
 * The GPS dot on the floating button: one colour for the whole GPS state,
 * readable at a glance while driving. Pure (unit-tested); fed once a second
 * by the dock service.
 *
 *  pink   – emulated: the parked position is shown (cold start in a garage)
 *  blue   – really good: fresh fix, ±10 m or better
 *  green  – OK: fresh fix, ±25 m or better
 *  orange – barely: worse than ±25 m, or fixes coming late (5–30 s old)
 *  black  – no sky: tunnel / car park / known no-GPS spot (auto doctor waits)
 *  red    – gone: no fix, location/GPS off
 *  grey   – nobody is asking the GPS (auto doctor off, no navigation app)
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
    const val GOOD_M = 25f
    const val FRESH_MS = 5_000L
    const val LATE_MS = 30_000L

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
}
