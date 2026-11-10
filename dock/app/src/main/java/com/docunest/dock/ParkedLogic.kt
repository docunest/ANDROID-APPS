package com.docunest.dock

/**
 * When to show the saved parked position as the GPS position (see
 * [ParkedPosition]) — pure Kotlin, fed once a second, unit-tested.
 *
 * The head unit cold-starts its GPS at every ignition. In a garage that
 * means no position at all, and Waze won't plan a route. So:
 *  - WATCHING (just started): the real GPS gets [LOOK_MS] to show it sees
 *    the sky. Open sky → leave it alone (a mock would pause the chip and
 *    delay its own fix). No sky, van not moving → RESTORED.
 *  - RESTORED: the saved position is given to every app as the GPS
 *    position. The real chip is paused by Android meanwhile, so hand back
 *    as soon as possible: the van has been moving for [DRIVING_MS] →
 *    DONE; every [PROBE_EVERY_MS] → PROBING.
 *  - PROBING: the real GPS gets [PROBE_MS]. A fix or open sky → DONE;
 *    otherwise back to RESTORED.
 *  - DONE: real GPS only, until the next start (or "use it now").
 * Only right after the head unit booted, never mid-travel (a stale position
 * in a tunnel is worse than none):
 *  - the dock must start within [START_MAX_MS] of boot — a dock restart
 *    later (self-update while driving, crash) never restores;
 *  - only within [RESTORE_WINDOW_MS] of boot;
 *  - never once the van has moved or the real GPS had a fix since boot;
 *  - shown at most [MAX_SHOWN_MS]; units without an accelerometer (can't
 *    tell driving) check the real GPS every [PROBE_EVERY_NO_SENSOR_MS].
 * [bootAt] and the tick times are on the same clock (elapsedRealtime: boot = 0).
 */
class ParkedLogic(private val hasSaved: Boolean, private val bootAt: Long = 0L) {
    enum class State { WATCHING, RESTORED, PROBING, DONE }

    var state = State.WATCHING
        private set
    var why = "Waiting for the GPS…"
        private set

    private var startedAt = -1L
    private var enteredAt = -1L
    private var drivingSince = -1L
    private var blockedSince = -1L
    private var shownSince = -1L

    /**
     * [realFix] = a fresh fix from the real chip (never our own mock). [skyKnown] false = the unit reports no
     * satellites at all: then "no sky" can't be seen — wait [LOOK_UNKNOWN_MS] (an open-sky cold start usually
     * fixes by then) and check the real GPS every [PROBE_EVERY_UNKNOWN_MS] for [PROBE_UNKNOWN_MS].
     */
    fun tick(t: Long, realFix: Boolean, strongSats: Int, driving: Boolean?, skyKnown: Boolean = true): State {
        if (startedAt < 0) startedAt = t
        if (driving == true) { if (drivingSince < 0) drivingSince = t } else drivingSince = -1
        val drove = drivingSince >= 0 && t - drivingSince >= DRIVING_MS
        val open = skyKnown && strongSats >= SkyJudge.OPEN_SATS
        val look = if (skyKnown) LOOK_MS else LOOK_UNKNOWN_MS
        val probeEvery = when {
            !skyKnown -> PROBE_EVERY_UNKNOWN_MS
            driving == null -> PROBE_EVERY_NO_SENSOR_MS
            else -> PROBE_EVERY_MS
        }
        val probeFor = if (skyKnown) PROBE_MS else PROBE_UNKNOWN_MS
        if (open) blockedSince = -1 else if (blockedSince < 0) blockedSince = t

        val sinceBoot = t - bootAt
        when (state) {
            State.WATCHING -> when {
                startedAt - bootAt > START_MAX_MS -> done("Dock started ${startedAt / 60_000} min after boot (update / restart), not a cold start — real GPS only")
                realFix -> done("Real GPS fix")
                driving == true -> done("Van moved — real GPS only for this drive")
                sinceBoot > RESTORE_WINDOW_MS -> done("No GPS in the first ${RESTORE_WINDOW_MS / 60_000} min after boot — real GPS only")
                !hasSaved -> why = "No saved position yet"
                t - startedAt >= look && blockedSince >= 0 && t - blockedSince >= look ->
                    enter(State.RESTORED, t, if (skyKnown) "No sky (garage?) — showing where the van was parked" else "No GPS fix after ${look / 1000} s — showing where the van was parked")
                open -> why = "Open sky — the real GPS should fix soon"
                else -> why = "Looking at the sky…"
            }
            State.RESTORED -> when {
                drove -> done("Van is moving — handed back to the real GPS")
                t - shownSince > MAX_SHOWN_MS -> done("Parked position shown ${MAX_SHOWN_MS / 60_000} min — handed back to the real GPS")
                t - enteredAt >= probeEvery -> enter(State.PROBING, t, "Checking the real GPS for ${probeFor / 1000} s…")
                else -> why = "Showing the parked position — real GPS check in ${(probeEvery - (t - enteredAt)) / 1000} s or when the van moves"
            }
            State.PROBING -> when {
                realFix -> done("Real GPS fix")
                open -> done("Sky is back — the real GPS takes over")
                drove -> done("Van is moving — handed back to the real GPS")
                t - shownSince > MAX_SHOWN_MS -> done("Parked position shown ${MAX_SHOWN_MS / 60_000} min — handed back to the real GPS")
                t - enteredAt >= probeFor -> enter(State.RESTORED, t, "Still no sky — showing the parked position again")
            }
            State.DONE -> Unit
        }
        return state
    }

    /** The person asked for it ("use parked position now" — refused while driving by the caller). */
    fun force(t: Long) {
        shownSince = t
        enter(State.RESTORED, t, "Showing the parked position (asked for)")
    }

    fun stop(reason: String) = done(reason)

    private fun enter(s: State, t: Long, reason: String) {
        if (s == State.RESTORED && shownSince < 0) shownSince = t
        state = s
        enteredAt = t
        why = reason
    }

    private fun done(reason: String) {
        state = State.DONE
        why = reason
    }

    companion object {
        const val LOOK_MS = 12_000L
        const val LOOK_UNKNOWN_MS = 45_000L
        const val PROBE_EVERY_UNKNOWN_MS = 2 * 60_000L
        const val PROBE_UNKNOWN_MS = 45_000L
        const val DRIVING_MS = 8_000L
        const val PROBE_EVERY_MS = 4 * 60_000L
        const val PROBE_EVERY_NO_SENSOR_MS = 60_000L
        const val PROBE_MS = 30_000L
        /** Head units start the dock 20–60 s after boot; later = a restart, not a cold start. */
        const val START_MAX_MS = 3 * 60_000L
        const val RESTORE_WINDOW_MS = 5 * 60_000L
        const val MAX_SHOWN_MS = 20 * 60_000L
    }
}
