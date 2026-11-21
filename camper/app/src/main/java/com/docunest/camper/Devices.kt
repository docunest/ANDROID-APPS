package com.docunest.camper

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Self-describing device model. Every device the app knows is published as a [Device] in the snapshot
 * ("devices") and at GET /api/devices, so remote UIs render it without knowing the device:
 * - readings: values to show and chart (numbers with a unit are logged every 5 min)
 * - controls: manual inputs with their own range/step/options; the app validates every write against them
 * - actions: one-tap automation (e.g. "Run diesel 1 h"), optionally with choice parameters
 * To add a device: build one more Device in Controller.devices(). Nothing in the remote page changes.
 *
 * Commands: {"action":"set","device":id,"control":id,"value":v} and {"action":"act","device":id,"id":id,"params":{..}}.
 */
class Option(val value: String, val label: String = value) {
    fun json(): JSONObject = JSONObject().put("value", value).put("label", label)
}

class Reading(
    val id: String, val label: String, val value: Any?, val unit: String? = null,
    /** numeric readings: also logged in the 5-min history; others: changes become events */
    val log: Boolean = true,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("value", value ?: JSONObject.NULL)
        .put("unit", unit ?: JSONObject.NULL).put("log", log)
}

class Control(
    val id: String, val label: String, val type: Type, val value: Any?,
    val min: Double? = null, val max: Double? = null, val step: Double? = null, val unit: String? = null,
    val options: List<Option> = emptyList(),
    /** true: a direct device write, so the app's rules let go of this device first ("manual") */
    val raw: Boolean = true,
    val write: (Any) -> Unit,
) {
    enum class Type { TOGGLE, NUMBER, CHOICE }

    /** The value as this control accepts it; IllegalArgumentException with a readable message otherwise. */
    fun coerce(v: Any?): Any = when (type) {
        Type.TOGGLE -> when (v) {
            is Boolean -> v
            "true", "on", "1", 1 -> true
            "false", "off", "0", 0 -> false
            else -> throw IllegalArgumentException("$label: on or off expected")
        }
        Type.NUMBER -> {
            val d = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
                ?: throw IllegalArgumentException("$label: a number expected")
            val lo = min; val hi = max
            if ((lo != null && d < lo - 1e-9) || (hi != null && d > hi + 1e-9)) {
                throw IllegalArgumentException("$label must be ${fmt(lo)}–${fmt(hi)}${unit ?: ""}")
            }
            val st = step
            if (st != null && st > 0) {
                val base = lo ?: 0.0
                val snapped = base + ((d - base) / st).roundToLong() * st
                if (abs(snapped - d) > 1e-6) throw IllegalArgumentException("$label: steps of ${fmt(st)}")
                (snapped * 1000).roundToLong() / 1000.0
            } else d
        }
        Type.CHOICE -> {
            val s = v?.toString() ?: ""
            if (options.none { it.value == s }) throw IllegalArgumentException("$label: one of ${options.joinToString { it.value }}")
            s
        }
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("type", type.name.lowercase())
        .put("value", value ?: JSONObject.NULL).put("raw", raw)
        .also { o ->
            min?.let { o.put("min", it) }; max?.let { o.put("max", it) }; step?.let { o.put("step", it) }; unit?.let { o.put("unit", it) }
            if (options.isNotEmpty()) o.put("options", JSONArray(options.map { it.json() }))
        }

    private fun fmt(d: Double?) = when { d == null -> "…"; d == Math.floor(d) -> d.toLong().toString(); else -> d.toString() }
}

class Action(
    val id: String, val label: String,
    /** choice parameters shown next to the button, e.g. run time; value = default */
    val params: List<Control> = emptyList(),
    val active: Boolean = false,
    /** the existing command bodies this action expands to, given the chosen params */
    val bodies: (JSONObject) -> List<JSONObject>,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("label", label).put("active", active)
        .put("params", JSONArray(params.map { it.json() }))
}

class Device(
    val id: String, val name: String, val kind: String,
    val online: Boolean, val status: String,
    /** null: the device has no rules (read-only, or always manual); true/false: manual vs. app rules */
    val manual: Boolean? = null,
    val readings: List<Reading> = emptyList(),
    val controls: List<Control> = emptyList(),
    val actions: List<Action> = emptyList(),
    val note: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("online", online)
        .put("status", status).put("manual", manual ?: JSONObject.NULL).put("note", note ?: JSONObject.NULL)
        .put("readings", JSONArray(readings.map { it.json() }))
        .put("controls", JSONArray(controls.map { it.json() }))
        .put("actions", JSONArray(actions.map { it.json() }))
}

fun cmd(action: String, vararg kv: Pair<String, Any?>): JSONObject =
    JSONObject().put("action", action).also { o -> kv.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
