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
 * Never restores once the real GPS had a fix, while driving, or after
 * [RESTORE_WINDOW_MS] — a tunnel later in the drive is the auto doctor's.
 */
class ParkedLogic(private val hasSaved: Boolean) {
    enum class State { WATCHING, RESTORED, PROBING, DONE }

    var state = State.WATCHING
        private set
    var why = "Waiting for the GPS…"
        private set

    private var startedAt = -1L
    private var enteredAt = -1L
    private var drivingSince = -1L
    private var blockedSince = -1L

    /** [realFix] = a fresh fix from the real chip (never our own mock). */
    fun tick(t: Long, realFix: Boolean, strongSats: Int, driving: Boolean?): State {
        if (startedAt < 0) startedAt = t
        if (driving == true) { if (drivingSince < 0) drivingSince = t } else drivingSince = -1
        val drove = drivingSince >= 0 && t - drivingSince >= DRIVING_MS
        val open = strongSats >= SkyJudge.OPEN_SATS
        if (open) blockedSince = -1 else if (blockedSince < 0) blockedSince = t

        when (state) {
            State.WATCHING -> when {
                realFix -> done("Real GPS fix")
                !hasSaved -> if (t - startedAt > RESTORE_WINDOW_MS) done("No saved position") else why = "No saved position yet"
                t - startedAt > RESTORE_WINDOW_MS -> done("No GPS yet, but past the first ${RESTORE_WINDOW_MS / 60_000} min — real GPS only")
                driving == true -> why = "Van is moving — real GPS only"
                t - startedAt >= LOOK_MS && blockedSince >= 0 && t - blockedSince >= LOOK_MS ->
                    enter(State.RESTORED, t, "No sky (garage?) — showing where the van was parked")
                open -> why = "Open sky — the real GPS should fix soon"
                else -> why = "Looking at the sky…"
            }
            State.RESTORED -> when {
                drove -> done("Van is moving — handed back to the real GPS")
                t - enteredAt >= PROBE_EVERY_MS -> enter(State.PROBING, t, "Checking the real GPS for ${PROBE_MS / 1000} s…")
                else -> why = "Showing the parked position — real GPS check in ${(PROBE_EVERY_MS - (t - enteredAt)) / 1000} s or when the van moves"
            }
            State.PROBING -> when {
                realFix -> done("Real GPS fix")
                open -> done("Sky is back — the real GPS takes over")
                drove -> done("Van is moving — handed back to the real GPS")
                t - enteredAt >= PROBE_MS -> enter(State.RESTORED, t, "Still no sky — showing the parked position again")
            }
            State.DONE -> Unit
        }
        return state
    }

    /** The person asked for it ("use parked position now"). */
    fun force(t: Long) = enter(State.RESTORED, t, "Showing the parked position (asked for)")

    fun stop(reason: String) = done(reason)

    private fun enter(s: State, t: Long, reason: String) {
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
        const val DRIVING_MS = 8_000L
        const val PROBE_EVERY_MS = 4 * 60_000L
        const val PROBE_MS = 30_000L
        const val RESTORE_WINDOW_MS = 15 * 60_000L
    }
}
