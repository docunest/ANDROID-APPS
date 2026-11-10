package com.docunest.dock

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The GPS doctor's fix report: which fixes help, how sure that is, how long
 * they take, and what the calibration does next. Pure (unit-tested); shown
 * by [FixReportActivity].
 */
object FixReport {
    data class Fix(val id: String, val title: String)

    fun text(fixes: List<Fix>, attempts: List<FixStats.Attempt>, runs: List<FixStats.Run>, symptomLabel: (String) -> String): String {
        val out = StringBuilder()
        val min = FixStats.MIN_TRIES
        val stats = FixStats.summarize(attempts)
        val counted = attempts.count { it.outcome != FixStats.Outcome.VOID }
        val judged = runs.filter { it.result != "stopped" && it.result != "paused" }
        val fixed = runs.filter { it.result == "fixed" }
        out.appendLine("GPS FIX REPORT")
        out.appendLine("Runs: ${runs.size} (${runs.count { it.auto }} auto, ${runs.count { !it.auto }} manual) · GPS back in ${fixed.size} of ${judged.size}" +
            (FixStats.percentile(fixed.map { it.secs }, 0.5)?.let { " · typically after ${secs(it)}" } ?: ""))
        out.appendLine("Tries counted: $counted · spoiled (tunnel / Stop, not counted): ${attempts.size - counted}")
        out.appendLine()

        if (counted == 0) {
            out.appendLine("Nothing measured yet. Every Fix GPS run (yours or the auto doctor's) adds tries; each fix needs $min before its numbers count.")
        } else {
            out.appendLine("FIX                     WORKED     95% RANGE   TIME TO FIX  AS 1ST   VS WAITING")
            val base = stats["listen"]?.takeIf { it.tries > 0 }
            val order = fixes.sortedWith(compareBy({ (stats[it.id]?.tries ?: 0) < min }, { -(stats[it.id]?.rate ?: -1.0) }))
            for (f in order) {
                val s = stats[f.id]
                if (s == null || s.tries == 0) {
                    out.appendLine("${f.title.padEnd(23).take(23)} not tried yet" + (s?.voids?.takeIf { it > 0 }?.let { " ($it spoiled)" } ?: ""))
                    continue
                }
                val (lo, hi) = s.interval()
                val worked = "${s.wins}/${s.tries} ${pct(s.rate)}"
                val range = "${(lo * 100).toInt()}–${(hi * 100).toInt()}%"
                val time = s.medianFixSecs?.let { "~" + secs(it) } ?: "—"
                val first = if (s.firstTries > 0) "${s.firstWins}/${s.firstTries}" else "—"
                val lift = if (base != null && f.id != "listen" && !s.learning && !base.learning) "%+d pts".format(((s.rate - base.rate) * 100).toInt()) else if (f.id == "listen") "baseline" else "—"
                out.appendLine("${f.title.padEnd(23).take(23)} ${worked.padEnd(10)} ${range.padEnd(11)} ${time.padEnd(12)} ${first.padEnd(8)} $lift")
            }
            out.appendLine()
            out.appendLine("WHAT HELPS")
            val measured = fixes.mapNotNull { f -> stats[f.id]?.takeIf { !it.learning }?.let { f to it } }
            if (measured.isEmpty()) out.appendLine("No fix has $min tries yet — no verdict.")
            measured.sortedByDescending { it.second.rate }.forEach { (f, s) ->
                val (lo, _) = s.interval()
                val verdict = when {
                    base != null && f.id != "listen" && !base.learning && lo > base.rate -> "helps (better than just waiting, even at its worst case)"
                    s.rate >= 0.5 -> "often works"
                    s.wins == 0 -> "never worked so far"
                    else -> "rarely works"
                }
                out.appendLine("· ${f.title}: $verdict")
            }
            val bySymptom = attempts.filter { it.outcome != FixStats.Outcome.VOID }.groupBy { it.symptom }
            if (bySymptom.isNotEmpty()) {
                out.appendLine()
                out.appendLine("BY KIND OF TROUBLE")
                for ((sym, list) in bySymptom.entries.sortedByDescending { it.value.size }) {
                    val st = FixStats.summarize(list).values.sortedByDescending { it.rate }
                    out.appendLine("· ${symptomLabel(sym)} (${list.size} tries): " +
                        st.take(3).joinToString(", ") { s -> "${fixes.firstOrNull { it.id == s.step }?.title ?: s.step} ${s.wins}/${s.tries}" })
                }
            }
        }

        out.appendLine()
        out.appendLine("WHAT'S NEXT (calibration)")
        val missing = fixes.sumOf { maxOf(0, min - (stats[it.id]?.tries ?: 0)) }
        val ready = fixes.count { (stats[it.id]?.tries ?: 0) >= min }
        out.appendLine("Measured enough: $ready of ${fixes.size} fixes ($min+ tries each); $missing tries still to go.")
        val triesPerRun = if (runs.isNotEmpty()) counted.toDouble() / runs.size else 2.5
        if (missing > 0) out.appendLine("At ~%.1f tries per GPS loss that's roughly %d more losses.".format(triesPerRun, Math.ceil(missing / maxOf(1.0, triesPerRun)).toInt()))
        val next = Planner.order(fixes.map { it.id to 120 }, attempts, "nofix", Random(7))
        out.appendLine("Next run tries, in order: " + next.joinToString(" → ") { id -> fixes.firstOrNull { it.id == id }?.title ?: id })
        out.appendLine(if (ready < fixes.size) "(least-tried first, to measure every fix)" else "(fastest expected first; the runner-up still gets tried now and then)")

        if (runs.isNotEmpty()) {
            out.appendLine()
            out.appendLine("LAST RUNS")
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.US)
            runs.takeLast(20).reversed().forEach { r ->
                out.appendLine("${fmt.format(Date(r.id))}  ${if (r.auto) "auto  " else "manual"} ${symptomLabel(r.symptom).padEnd(18).take(18)} ${r.result.padEnd(7)} " +
                    "${r.fixedBy?.let { id -> fixes.firstOrNull { it.id == id }?.title } ?: ""} ${secs(r.secs)}, ${r.tried} tried")
            }
        }
        return out.toString().trimEnd()
    }

    private fun pct(r: Double) = "${(r * 100).toInt()}%"
    private fun secs(s: Int) = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
