package com.docunest.dock

import kotlin.math.max

/**
 * The auto doctor's brain: is the GPS broken (fix it now), or is the sky
 * just blocked for a while (tunnel, underground car park) and fixing would
 * only waste the chip's time? Pure Kotlin, fed once a second, unit-tested.
 *
 * What it uses:
 *  - Sky: how many satellites are heard *strongly* (≥ 25 dB-Hz). Four or
 *    more = the antenna sees open sky, so a missing fix is the chip's fault.
 *  - How the fix was lost: at speed (≥ 20 km/h) = tunnel / gallery; slowly
 *    = car park, ferry deck, garage.
 *  - Movement from the accelerometer: driving for minutes with no sky is
 *    not a car park (you'd be out by then).
 *  - Dead zones: places where the GPS was lost before and came back by
 *    itself (home garage, the regular underground car park).
 *
 * Fix now when: location/GPS switched off; the chip stopped talking (no
 * tunnel silences the driver); sky is clear for 45 s (75 s at start-up)
 * but no fix; "tunnel" lasts over [TUNNEL_MAX_MS]; "car park" while
 * driving for over [DRIVING_MAX_MS]; no sky at all for [UNKNOWN_MAX_MS]
 * with nothing to explain it.
 */
class SkyJudge(private val deadZones: () -> List<Spot> = { emptyList() }) {
    data class Spot(val lat: Double, val lng: Double)

    /** A good, fresh GPS fix. [speed] m/s. */
    data class Fix(val lat: Double, val lng: Double, val speed: Float?)

    data class Reading(
        /** ms, monotonic. */
        val t: Long,
        /** Permission + location + GPS provider all on. */
        val settingsOk: Boolean,
        /** NMEA or satellite status in the last ~10 s. */
        val chipTalks: Boolean,
        /** This second's good fresh fix, or null. */
        val fix: Fix?,
        /** Satellites heard at ≥ 25 dB-Hz. */
        val strongSats: Int,
        /** Accelerometer says the van is moving; null = unit has none. */
        val driving: Boolean?,
        /** A fix run (manual or auto) is going on now. */
        val fixing: Boolean = false,
    )

    enum class Mode { STARTING, OK, WAITING, TUNNEL, COVERED, DEAD_ZONE, BROKEN }

    data class Call(val mode: Mode, val why: String, val fixNow: Boolean = false, val symptom: String = "")

    enum class Cause { NONE, TUNNEL, COVERED, DEAD_ZONE, UNKNOWN }

    private var startedAt = -1L
    private var lastT = -1L
    /** Last good fix (seeded with the system's last known position at start). */
    var lastGood: Fix? = null
        private set
    private var lastGoodAt = -1L
    private var hadFixThisSession = false
    private var outageAt = -1L
    var cause = Cause.NONE
        private set
    private var openSince = -1L
    private var blockedSince = -1L
    private var silentSince = -1L
    private var offSince = -1L
    private var drivingMs = 0L
    private var fixedDuringOutage = false

    /** Set when an outage under cover ended by itself: remember as a dead zone. Read and clear. */
    var selfRecoveredAt: Spot? = null

    /** Seed with where the van was last (system last-known GPS), e.g. still in the garage. */
    fun seed(last: Fix?) {
        if (lastGood == null) lastGood = last
    }

    fun judge(r: Reading): Call {
        if (startedAt < 0) startedAt = r.t
        // A long gap (head unit asleep with the ignition off, nobody listening): start afresh,
        // with the last fix as where we are parked — an outage doesn't span a night.
        if (lastT >= 0 && r.t - lastT > GAP_RESET_MS) restart()
        val dt = if (lastT < 0) 0L else (r.t - lastT).coerceIn(0, 5_000)
        lastT = r.t

        // Sky with a little hysteresis: a 1–4 s dip doesn't reset "open".
        if (r.strongSats >= OPEN_SATS) {
            blockedSince = -1
            if (openSince < 0) openSince = r.t
        } else {
            if (blockedSince < 0) blockedSince = r.t
            if (r.t - blockedSince >= 5_000) openSince = -1
        }
        if (r.fixing) fixedDuringOutage = true

        if (r.fix != null) {
            if (outageAt >= 0 && (cause == Cause.COVERED || cause == Cause.DEAD_ZONE || cause == Cause.UNKNOWN) &&
                !fixedDuringOutage && r.t - outageAt >= DEAD_ZONE_MIN_MS
            ) {
                lastGood?.let { selfRecoveredAt = Spot(it.lat, it.lng) }
            }
            lastGood = r.fix
            lastGoodAt = r.t
            hadFixThisSession = true
            outageAt = -1
            cause = Cause.NONE
            drivingMs = 0
            fixedDuringOutage = false
            silentSince = -1
            offSince = -1
            return Call(Mode.OK, "GPS fine")
        }

        if (outageAt < 0) {
            outageAt = if (lastGoodAt >= 0) lastGoodAt else r.t
            cause = classify()
        }
        val out = r.t - outageAt

        if (!r.settingsOk) {
            if (offSince < 0) offSince = r.t
            return if (r.t - offSince >= 5_000) Call(Mode.BROKEN, "Location / GPS is switched off", true, "off")
            else Call(Mode.WAITING, "Location / GPS just switched off")
        }
        offSince = -1

        if (!r.chipTalks) {
            if (silentSince < 0) silentSince = r.t
            if (r.t - silentSince >= SILENT_MAX_MS) return Call(Mode.BROKEN, "The GPS chip has gone silent (driver)", true, "silent")
        } else silentSince = -1

        if (openSince >= 0) {
            val limit = if (hadFixThisSession) OPEN_NO_FIX_MS else STARTUP_OPEN_NO_FIX_MS
            // Counted from the loss at the earliest: the sky was clear while the fix was fine too.
            val open = r.t - max(openSince, outageAt)
            return if (open >= limit) Call(Mode.BROKEN, "Sky is clear (${r.strongSats} strong satellites) for ${secs(open)} but no fix", true, "nofix")
            else Call(Mode.WAITING, "Sky clear — waiting for the fix (${secs(open)})")
        }

        if (r.driving == true) drivingMs += dt
        return when (cause) {
            Cause.TUNNEL ->
                if (out > TUNNEL_MAX_MS) Call(Mode.BROKEN, "No sky for ${secs(out)} — longer than any tunnel", true, "nosky")
                else Call(Mode.TUNNEL, "Tunnel? Lost at ${kmh(lastGood?.speed)} — waiting (${secs(out)})")
            Cause.COVERED, Cause.DEAD_ZONE -> {
                val label = if (cause == Cause.DEAD_ZONE) "Known no-GPS spot" else "Under cover (car park?)"
                val mode = if (cause == Cause.DEAD_ZONE) Mode.DEAD_ZONE else Mode.COVERED
                when {
                    drivingMs > DRIVING_MAX_MS ->
                        Call(Mode.BROKEN, "Driving ${secs(drivingMs)} with no sky — not a car park", true, "nosky")
                    r.driving == null && out > NO_SENSOR_COVER_MAX_MS ->
                        Call(Mode.BROKEN, "No sky for ${secs(out)}", true, "nosky")
                    else -> Call(mode, "$label — waiting (${secs(out)})")
                }
            }
            Cause.UNKNOWN, Cause.NONE ->
                if (out > UNKNOWN_MAX_MS || drivingMs > DRIVING_MAX_MS) Call(Mode.BROKEN, "No satellites for ${secs(out)}", true, "nosky")
                else Call(if (hadFixThisSession) Mode.WAITING else Mode.STARTING, "Looking for satellites (${secs(out)})")
        }
    }

    private fun restart() {
        lastGood = lastGood?.copy(speed = 0f)
        lastGoodAt = -1
        hadFixThisSession = false
        outageAt = -1
        cause = Cause.NONE
        openSince = -1
        blockedSince = -1
        silentSince = -1
        offSince = -1
        drivingMs = 0
        fixedDuringOutage = false
    }

    private fun classify(): Cause {
        val g = lastGood ?: return Cause.UNKNOWN
        if (deadZones().any { GlitchDetector.meters(fixOf(it), fixOf(Spot(g.lat, g.lng))) <= DEAD_ZONE_RADIUS_M }) return Cause.DEAD_ZONE
        // Lost the fix while driving (this session): tunnel, gallery, deep cutting.
        if (hadFixThisSession && (g.speed ?: 0f) >= TUNNEL_SPEED) return Cause.TUNNEL
        return Cause.COVERED
    }

    private fun fixOf(s: Spot) = GlitchDetector.Fix(0.0, s.lat, s.lng, null, null)

    companion object {
        const val OPEN_SATS = 4
        const val GAP_RESET_MS = 30_000L
        /** m/s — 20 km/h. */
        const val TUNNEL_SPEED = 5.5f
        /** Somport (8.6 km) at ~50 km/h ≈ 10 min; Vielha 5.2 km, Marão 5.7 km. */
        const val TUNNEL_MAX_MS = 12 * 60_000L
        const val DRIVING_MAX_MS = 4 * 60_000L
        const val NO_SENSOR_COVER_MAX_MS = 30 * 60_000L
        const val UNKNOWN_MAX_MS = 5 * 60_000L
        const val OPEN_NO_FIX_MS = 45_000L
        const val STARTUP_OPEN_NO_FIX_MS = 75_000L
        const val SILENT_MAX_MS = 30_000L
        /** Shorter stops (traffic light under a bridge) aren't worth remembering. */
        const val DEAD_ZONE_MIN_MS = 2 * 60_000L
        const val DEAD_ZONE_RADIUS_M = 250f

        fun secs(ms: Long): String {
            val s = max(0L, ms / 1000)
            return if (s < 60) "$s s" else if (s < 3600) "${s / 60} min ${s % 60} s" else "${s / 3600} h ${(s % 3600) / 60} min"
        }

        private fun kmh(mps: Float?) = if (mps == null) "speed" else "${(mps * 3.6f).toInt()} km/h"
    }
}
