package com.docunest.dock

import android.content.Context

/**
 * Where [FixStats] data lives: plain text lines in the dock's shared
 * preferences (survives updates; reset from the GPS doctor screen).
 * Also the dead zones the auto doctor learned.
 */
object FixHistory {
    private const val KEEP_ATTEMPTS = 1500
    private const val KEEP_RUNS = 300
    private const val KEEP_ZONES = 40

    private fun sp(context: Context) = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    @Synchronized
    fun attempts(context: Context): List<FixStats.Attempt> =
        lines(context, "gpsFixAttempts").mapNotNull { FixStats.Attempt.decode(it) }

    @Synchronized
    fun runs(context: Context): List<FixStats.Run> =
        lines(context, "gpsFixRuns").mapNotNull { FixStats.Run.decode(it) }

    @Synchronized
    fun add(context: Context, a: FixStats.Attempt) = append(context, "gpsFixAttempts", a.encode(), KEEP_ATTEMPTS)

    @Synchronized
    fun add(context: Context, r: FixStats.Run) = append(context, "gpsFixRuns", r.encode(), KEEP_RUNS)

    @Synchronized
    fun clear(context: Context) = sp(context).edit().remove("gpsFixAttempts").remove("gpsFixRuns").apply()

    @Synchronized
    fun deadZones(context: Context): List<SkyJudge.Spot> = lines(context, "gpsDeadZones").mapNotNull { l ->
        val p = l.split(',')
        if (p.size == 2) runCatching { SkyJudge.Spot(p[0].toDouble(), p[1].toDouble()) }.getOrNull() else null
    }

    @Synchronized
    fun addDeadZone(context: Context, s: SkyJudge.Spot) {
        val near = deadZones(context).any {
            GlitchDetector.meters(GlitchDetector.Fix(0.0, it.lat, it.lng, null, null), GlitchDetector.Fix(0.0, s.lat, s.lng, null, null)) < 100f
        }
        if (!near) append(context, "gpsDeadZones", "%.5f,%.5f".format(java.util.Locale.US, s.lat, s.lng), KEEP_ZONES)
    }

    @Synchronized
    fun clearDeadZones(context: Context) = sp(context).edit().remove("gpsDeadZones").apply()

    private fun lines(context: Context, key: String): List<String> =
        sp(context).getString(key, "")!!.split('\n').filter { it.isNotBlank() }

    private fun append(context: Context, key: String, line: String, keep: Int) {
        val all = lines(context, key) + line
        sp(context).edit().putString(key, all.takeLast(keep).joinToString("\n")).apply()
    }
}
