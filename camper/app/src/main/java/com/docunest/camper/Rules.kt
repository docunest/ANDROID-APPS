package com.docunest.camper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * User rules, stored as data (rules.json) and edited from any UI. A rule:
 * {
 *   "id", "name", "enabled": true,
 *   "when": [ {"device":"mppt","field":"battery_voltage","op":"<","value":12.4},
 *             {"time":{"from":"22:00","to":"07:00"}} ],      // all must hold
 *   "for_min": 5,          // ... continuously for this long
 *   "then": [ {"device":"ac","control":"power","value":false},           // = command "set"
 *             {"device":"diesel","action":"run","params":{"minutes":"60"}} ],  // = command "act"
 *   "cooldown_min": 30     // don't fire again sooner
 * }
 * Fields are any reading or control id from the device descriptions (Devices.kt) plus "online".
 * A rule fires once when its conditions become true (after for_min) and re-arms when they turn false, so
 * "if battery < 12.4 V then AC off" doesn't fight you if you switch the AC back on; a time window acts as a
 * schedule (fires at "from"). Actions go through the same validated set/act commands as manual control.
 */
class Rules(dir: File) {
    companion object {
        val OPS = setOf("<", "<=", ">", ">=", "==", "!=")
        private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

        /** Checks the structure; returns a normalised copy or throws IllegalArgumentException. */
        fun validate(r: JSONObject): JSONObject {
            val out = JSONObject()
            out.put("id", r.optString("id").ifEmpty { UUID.randomUUID().toString().take(8) })
            out.put("name", r.optString("name").trim().ifEmpty { throw IllegalArgumentException("Give the rule a name") }.take(60))
            out.put("enabled", r.optBoolean("enabled", true))
            val whenIn = r.optJSONArray("when") ?: JSONArray()
            if (whenIn.length() == 0) throw IllegalArgumentException("Add at least one condition")
            val whenOut = JSONArray()
            for (i in 0 until whenIn.length()) {
                val c = whenIn.getJSONObject(i)
                val t = c.optJSONObject("time")
                if (t != null) {
                    val from = t.optString("from"); val to = t.optString("to")
                    if (!HHMM.matches(from) || !HHMM.matches(to)) throw IllegalArgumentException("Time window: use HH:MM")
                    whenOut.put(JSONObject().put("time", JSONObject().put("from", from).put("to", to)))
                } else {
                    val op = c.optString("op")
                    if (c.optString("device").isEmpty() || c.optString("field").isEmpty()) throw IllegalArgumentException("Condition ${i + 1}: pick a device and a value")
                    if (op !in OPS) throw IllegalArgumentException("Condition ${i + 1}: operator must be one of ${OPS.joinToString(" ")}")
                    if (!c.has("value")) throw IllegalArgumentException("Condition ${i + 1}: missing value")
                    whenOut.put(JSONObject().put("device", c.getString("device")).put("field", c.getString("field")).put("op", op).put("value", c.get("value")))
                }
            }
            out.put("when", whenOut)
            val thenIn = r.optJSONArray("then") ?: JSONArray()
            if (thenIn.length() == 0) throw IllegalArgumentException("Add at least one action")
            val thenOut = JSONArray()
            for (i in 0 until thenIn.length()) {
                val a = thenIn.getJSONObject(i)
                if (a.optString("device").isEmpty()) throw IllegalArgumentException("Action ${i + 1}: pick a device")
                when {
                    a.has("control") -> thenOut.put(JSONObject().put("device", a.getString("device")).put("control", a.getString("control")).put("value", a.opt("value") ?: JSONObject.NULL))
                    a.has("action") -> thenOut.put(JSONObject().put("device", a.getString("device")).put("action", a.getString("action")).put("params", a.optJSONObject("params") ?: JSONObject()))
                    else -> throw IllegalArgumentException("Action ${i + 1}: pick a control or an action")
                }
            }
            out.put("then", thenOut)
            out.put("for_min", r.optDouble("for_min", 0.0).coerceIn(0.0, 24 * 60.0))
            out.put("cooldown_min", r.optDouble("cooldown_min", 0.0).coerceIn(0.0, 7 * 24 * 60.0))
            return out
        }

        fun compare(actual: Any?, op: String, want: Any?): Boolean {
            if (actual == null || actual == JSONObject.NULL) return false
            val a = (actual as? Number)?.toDouble() ?: (actual as? String)?.toDoubleOrNull()
            val w = (want as? Number)?.toDouble() ?: (want as? String)?.toDoubleOrNull()
            if (actual !is Boolean && a != null && w != null) return when (op) {
                "<" -> a < w; "<=" -> a <= w; ">" -> a > w; ">=" -> a >= w; "==" -> a == w; else -> a != w
            }
            val eq = when {
                actual is Boolean -> actual == (want == true || want == "true" || want == "on")
                else -> actual.toString() == want.toString()
            }
            return when (op) { "==" -> eq; "!=" -> !eq; else -> false }
        }

        /** minutes since midnight in [from, to), wrapping past midnight */
        fun inWindow(nowMin: Int, from: String, to: String): Boolean {
            fun m(s: String) = s.substringBefore(":").toInt() * 60 + s.substringAfter(":").toInt()
            val f = m(from); val t = m(to)
            return if (f <= t) nowMin in f until t else nowMin >= f || nowMin < t
        }
    }

    private val file = File(dir, "rules.json")
    private val rules = ArrayList<JSONObject>(runCatching {
        JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }.getOrDefault(emptyList()))

    private class St { var since = -1L; var fired = false; var lastFired = 0L; var lastResult: String? = null; var holds = false }
    private val st = HashMap<String, St>()

    private fun save() = synchronized(this) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(rules).toString(1))
        tmp.renameTo(file)
    }

    fun put(rule: JSONObject): JSONObject = synchronized(this) {
        val r = validate(rule)
        val i = rules.indexOfFirst { it.optString("id") == r.getString("id") }
        if (i >= 0) rules[i] = r else rules.add(r)
        st.remove(r.getString("id")) // re-arm after an edit
        save(); r
    }

    fun delete(id: String): Boolean = synchronized(this) { rules.removeAll { it.optString("id") == id }.also { if (it) { st.remove(id); save() } } }

    fun get(id: String): JSONObject? = synchronized(this) { rules.firstOrNull { it.optString("id") == id } }

    /**
     * One evaluation pass (every poll). [value] looks up device.field; [run] executes one action and returns an
     * error or null. Returns the names of rules that fired.
     */
    fun evaluate(nowMs: Long, value: (String, String) -> Any?, run: (JSONObject, String) -> String?): List<String> {
        val list = synchronized(this) { rules.map { JSONObject(it.toString()) } }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val fired = ArrayList<String>()
        for (r in list) {
            val id = r.getString("id")
            val s = synchronized(this) { st.getOrPut(id) { St() } }
            if (!r.optBoolean("enabled", true)) { s.since = -1; s.fired = false; s.holds = false; continue }
            val w = r.getJSONArray("when")
            val holds = (0 until w.length()).all { i ->
                val c = w.getJSONObject(i)
                c.optJSONObject("time")?.let { t -> inWindow(nowMin, t.getString("from"), t.getString("to")) }
                    ?: compare(value(c.getString("device"), c.getString("field")), c.getString("op"), c.opt("value"))
            }
            s.holds = holds
            if (!holds) { s.since = -1; s.fired = false; continue }
            if (s.since < 0) s.since = nowMs
            val sustained = nowMs - s.since >= (r.optDouble("for_min") * 60_000).toLong()
            val cooled = s.lastFired == 0L || nowMs - s.lastFired >= (r.optDouble("cooldown_min") * 60_000).toLong()
            if (sustained && !s.fired && cooled) {
                s.fired = true; s.lastFired = nowMs
                s.lastResult = runActions(r, run)
                fired += r.getString("name")
            }
        }
        return fired
    }

    /** Runs a rule's actions now ("Run now" in the editor, or when it fires); returns null or the errors. */
    fun runActions(r: JSONObject, run: (JSONObject, String) -> String?): String? {
        val t = r.getJSONArray("then")
        val errs = (0 until t.length()).mapNotNull { run(t.getJSONObject(it), r.getString("name")) }
        return if (errs.isEmpty()) null else errs.joinToString("; ")
    }

    fun recordManualRun(id: String, result: String?) {
        synchronized(this) { st.getOrPut(id) { St() }.apply { lastFired = System.currentTimeMillis(); lastResult = result } }
    }

    /** Rules plus their live state for the UI. */
    fun json(nowMs: Long): JSONArray = synchronized(this) {
        JSONArray(rules.map { r ->
            val s = st[r.getString("id")]
            JSONObject(r.toString())
                .put("holds", s?.holds ?: false)
                .put("waiting_min", if (s != null && s.since >= 0 && !s.fired) ((nowMs - s.since) / 60_000.0).let { Math.round(it * 10) / 10.0 } else JSONObject.NULL)
                .put("fired", s?.fired ?: false)
                .put("last_fired", if (s != null && s.lastFired > 0) s.lastFired / 1000 else JSONObject.NULL)
                .put("last_result", s?.lastResult ?: if (s != null && s.lastFired > 0) "ok" else JSONObject.NULL)
        })
    }
}
