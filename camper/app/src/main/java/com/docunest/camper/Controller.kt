package com.docunest.camper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.docunest.camper.heater.HeaterBle
import com.docunest.camper.midea.AcState
import com.docunest.camper.midea.MideaAc
import com.docunest.camper.tuya.TuyaDevice
import com.docunest.camper.victron.Victron
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Device keys and location, pushed to the phone as camper.env (never in the repo or the APK). */
class Config(private val values: Map<String, String>) {
    operator fun get(k: String): String? = values[k]?.takeIf { it.isNotBlank() }

    /** VICTRON_MPPT=F0:93:..,<key> -> {"mppt": (mac, key)} */
    fun victronDevices(): Map<String, Pair<String, String>> = values.filterKeys { it.startsWith("VICTRON_") }.mapNotNull { (k, v) ->
        val parts = v.split(",").map { it.trim() }
        if (parts.size == 2 && parts[1].length == 32) k.removePrefix("VICTRON_").lowercase(Locale.US) to (parts[0] to parts[1]) else null
    }.toMap()

    companion object {
        fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), "camper.env")

        fun load(ctx: Context): Config {
            val f = file(ctx)
            val map = if (f.exists()) f.readLines().mapNotNull { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) null
                else l.substringBefore("=").trim() to l.substringAfter("=").trim()
            }.toMap() else emptyMap()
            return Config(map)
        }
    }
}

data class FloorState(
    val power: Boolean, val setpoint: Double, val temp: Double, val mode: String, val heating: Boolean,
    val childLock: Boolean, val fault: Int, val maxSetpoint: Int, val sensor: String,
) {
    fun json(): JSONObject = JSONObject().put("power", power).put("setpoint", setpoint).put("temp", temp).put("mode", mode)
        .put("heating", heating).put("child_lock", childLock).put("fault", fault).put("max_setpoint", maxSetpoint).put("sensor", sensor)

    companion object {
        fun of(d: JSONObject) = FloorState(
            power = d.optBoolean("1"),
            setpoint = if (d.optString("4") == "leave") d.optDouble("2") else d.optInt("2") / 10.0,
            temp = d.optInt("3") / 10.0,
            mode = d.optString("4"),
            heating = d.optString("5") == "1",
            childLock = d.optBoolean("9"),
            fault = d.optInt("11"),
            maxSetpoint = d.optInt("15", 28),
            sensor = when (d.optString("110")) { "1" -> "air"; "2" -> "floor"; "3" -> "both"; else -> d.optString("110") },
        )
    }
}

/**
 * The brain (same rules as the Python agent, docs/agent.md in the camper repo):
 * - Nothing is written until the user picked something (position == null mirrors the devices).
 * - Floor heating: the thermostat keeps regulating with its own over-reading sensor; we shift its setpoint by
 *   (thermostat - control temperature), so it stops at the real target and keeps working if this app dies.
 * - Only one local client per device; AC writes always start from a fresh state read.
 */
class Controller(private val ctx: Context) {
    companion object {
        const val TAG = "Camper"
        val HEAT = setOf("floor", "diesel", "max")
        val COOL = mapOf("cool" to Pair(24.0, AcState.FAN_LOW), "coolmax" to Pair(22.0, AcState.FAN_HIGH))
        private val DEFAULTS = """{"position":null,"floor_mode":"auto","on_target":24.0,
            "auto_targets":{"night":23.0,"cloudy":22.0,"sunny":20.0},"control_by":"calibrated","thermo_weight":0.5,
            "ac_fan":"auto","ac_quiet":true,"dry_until":0,"ac_managed":false,"dismissed":{},
            "diesel_phase":"","diesel_until":0,"diesel_started":0,"diesel_level":3,"diesel_low":3,"diesel_mid":5,"diesel_high":8}"""
        const val CLEAN_STOP_MIN = 10
        const val CLEANOUT_MIN = 20
    }

    private val cfg = Config.load(ctx)
    private val stateFile = File(ctx.filesDir, "state.json")
    private val s: JSONObject = JSONObject(DEFAULTS).also { d ->
        runCatching { JSONObject(stateFile.readText()) }.getOrNull()?.let { saved -> saved.keys().forEach { d.put(it, saved.get(it)) } }
    }
    private val io = Any()

    private val floor = cfg["TUYA_THERMOSTAT_ID"]?.let { id -> cfg["TUYA_THERMOSTAT_LOCAL_KEY"]?.let { TuyaDevice(id, it) } }
    private val ac = cfg["MIDEA_AC_ID"]?.let { id ->
        val t = cfg["MIDEA_AC_TOKEN"]; val k = cfg["MIDEA_AC_KEY"]
        if (t != null && k != null) MideaAc(id.toLong(), t, k) else null
    }
    val motion = Motion(ctx).also { it.start() }
    val heater = HeaterBle(ctx, cfg["HEATER_PASSKEY"]?.toIntOrNull() ?: 1234, cfg["HEATER_PREFIX"] ?: "BYD")
    val victron = Victron(ctx, cfg.victronDevices())
    val history = History(ctx.filesDir)
    private val location = cfg["CAMPER_LAT"]?.toDoubleOrNull()?.let { lat -> cfg["CAMPER_LON"]?.toDoubleOrNull()?.let { lat to it } }

    @Volatile private var floorState: FloorState? = null
    @Volatile private var acState: AcState? = null
    @Volatile private var weather: JSONObject? = null
    @Volatile private var battery: JSONObject? = null
    @Volatile private var lastPoll = 0L
    private var weatherAt = 0L
    private val errors = ConcurrentHashMap<String, String>()

    init {
        if (floor == null || ac == null) errors["config"] = "camper.env missing or incomplete"
        // last known IPs: connect straight away after a restart; discovery only if they changed
        s.optString("floor_ip").takeIf { it.isNotEmpty() }?.let { floor?.ip = it }
        s.optString("ac_ip").takeIf { it.isNotEmpty() }?.let { ac?.ip = it }
        s.optString("heater_addr").takeIf { it.isNotEmpty() }?.let { heater.address = it }
        heater.start()
        victron.start()
    }

    private fun rememberIps() {
        val f = floor?.ip; val a = ac?.ip; val hAddr = heater.address
        if ((f != null && f != s.optString("floor_ip")) || (a != null && a != s.optString("ac_ip")) ||
            (hAddr != null && hAddr != s.optString("heater_addr"))) {
            synchronized(s) { f?.let { s.put("floor_ip", it) }; a?.let { s.put("ac_ip", it) }; hAddr?.let { s.put("heater_addr", it) } }
            save()
        }
    }

    private fun save() = synchronized(s) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(s.toString(1))
        tmp.renameTo(stateFile)
    }

    private fun pos(): String? = synchronized(s) { if (s.isNull("position")) null else s.getString("position") }

    // ---------- readings ----------

    fun temps(): JSONObject {
        val t = floorState?.temp
        val a = acState?.indoor
        val cal = if (t != null && a != null) {
            val w = s.optDouble("thermo_weight", 0.5)
            ((w * t + (1 - w) * a) * 10).roundToInt() / 10.0
        } else t ?: a
        val use = when (s.optString("control_by")) { "thermostat" -> t; "ac" -> a; else -> cal } ?: cal
        return JSONObject().put("thermostat", t ?: JSONObject.NULL).put("ac", a ?: JSONObject.NULL)
            .put("calibrated", cal ?: JSONObject.NULL).put("control", use ?: JSONObject.NULL)
    }

    private fun autoReason(): String {
        val w = weather ?: return "night"
        return if (w.optBoolean("is_night", true)) "night" else if (w.optBoolean("sunny")) "sunny" else "cloudy"
    }

    fun floorTarget(): Double = synchronized(s) {
        if (s.optString("floor_mode") == "on") s.getDouble("on_target") else s.getJSONObject("auto_targets").getDouble(autoReason())
    }

    // ---------- device IO ----------

    fun poll() = synchronized(io) {
        floor?.let { f ->
            try { floorState = FloorState.of(f.status()); errors.remove("floor") } catch (e: Exception) {
                errors["floor"] = e.message ?: e.toString(); Log.w(TAG, "floor poll", e)
            }
        }
        ac?.let { a ->
            try { acState = a.status(); errors.remove("ac") } catch (e: Exception) {
                errors["ac"] = e.message ?: e.toString(); Log.w(TAG, "ac poll", e)
            }
        }
        val loc = location
        if (loc != null && System.currentTimeMillis() - weatherAt > 30 * 60_000) {
            try { weather = Weather.summarize(Weather.fetch(loc.first, loc.second)); weatherAt = System.currentTimeMillis(); errors.remove("weather") } catch (e: Exception) {
                errors["weather"] = e.message ?: e.toString(); Log.w(TAG, "weather", e)
            }
        }
        rememberIps()
        battery = readBattery()
        lastPoll = System.currentTimeMillis()
        logReading()
        recordHistory()
        try { enforce() } catch (e: Exception) { errors["control"] = e.message ?: e.toString(); Log.w(TAG, "enforce", e) }
    }

    private fun readBattery(): JSONObject? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return JSONObject().put("percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("temp", if (temp != Int.MIN_VALUE) temp / 10.0 else JSONObject.NULL)
    }

    private fun logReading() {
        runCatching {
            val f = File(ctx.filesDir, "readings-" + SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) + ".csv")
            if (!f.exists()) f.writeText("time,thermostat,ac,calibrated,outside,floor_on,floor_heating,floor_setpoint,ac_power,ac_mode,position\n")
            val t = temps(); val fl = floorState; val a = acState
            f.appendText(listOf(System.currentTimeMillis() / 1000, t.opt("thermostat"), t.opt("ac"), t.opt("calibrated"),
                weather?.opt("now_temp") ?: "", fl?.power ?: "", fl?.heating ?: "", fl?.setpoint ?: "", a?.power ?: "", a?.mode ?: "",
                pos() ?: "").joinToString(",") + "\n")
        }
    }

    // ---------- history (5-min samples + on/off events, uploaded by RemoteSync) ----------

    private var prev: Map<String, Any?>? = null

    /** Device states whose changes become events: every control, non-numeric readings, online. */
    private fun watched(devs: List<Device>): Map<String, Any?> = buildMap {
        for (d in devs) {
            put("${d.id}.online", d.online)
            if (!d.online) continue
            d.controls.forEach { put("${d.id}.${it.id}", it.value) }
            d.readings.filter { it.value !is Number }.forEach { put("${d.id}.${it.id}", it.value) }
        }
    }

    private fun recordHistory() = runCatching {
        val devs = devices()
        val now = watched(devs)
        prev?.let { old ->
            for ((k, v) in now) {
                val o = old[k]
                // a device dropping out is logged once as "<id>.online", not as every value going missing
                if (v != null && o != null && v != o) history.event(k.substringBefore("."), k.substringAfter("."), v)
            }
        }
        prev = now.mapValues { (k, v) -> v ?: prev?.get(k) }
        val nowS = System.currentTimeMillis() / 1000
        if (history.sampleDue(nowS)) history.sample(sampleRow(nowS, devs))
    }.onFailure { Log.w(TAG, "history", it) }

    /** "<device>.<id>": numeric readings with a unit, number controls, and on/off states (for the bands). */
    private fun sampleRow(t: Long, devs: List<Device>): JSONObject {
        val row = JSONObject().put("t", t)
        for (d in devs) {
            if (!d.online) continue
            d.readings.forEach { r -> if ((r.log && r.unit != null && r.value is Number) || r.value is Boolean) row.put("${d.id}.${r.id}", r.value) }
            d.controls.forEach { c -> if (c.type != Control.Type.CHOICE && c.value != null) row.put("${d.id}.${c.id}", c.value) }
        }
        return row
    }

    private fun modeName(m: Int) = when (m) { AcState.COOL -> "cool"; AcState.DRY -> "dry"; AcState.FAN_ONLY -> "fan"; else -> "other" }
    private fun fanName(f: Int) = when (f) { AcState.FAN_LOW -> "low"; AcState.FAN_HIGH -> "high"; AcState.FAN_AUTO -> "auto"; else -> "$f" }

    // ---------- the control step ----------

    private fun enforce() {
        // floor + cooling follow the dial (only once the user picked a position); the dehumidifier timer and
        // the diesel phases are independent of it, so drying never switches heating off
        val pos = pos()
        val now = System.currentTimeMillis()
        val dryUntil = s.optLong("dry_until")
        val dryActive = dryUntil > now
        if (dryUntil != 0L && !dryActive) { synchronized(s) { s.put("dry_until", 0) }; save() }

        val f = floorState
        val fl = floor
        if (pos != null && f != null && fl != null) {
            val wantOn = pos in HEAT && s.optString("floor_mode") != "off"
            if (f.power != wantOn) { Log.i(TAG, "floor <- power $wantOn"); fl.set(JSONObject().put("1", wantOn)) }
            if (wantOn) {
                if (f.mode != "home") { Log.i(TAG, "floor <- mode home"); fl.set(JSONObject().put("4", "home")) }
                val t = temps()
                val offset = if (!t.isNull("thermostat") && !t.isNull("control")) t.getDouble("thermostat") - t.getDouble("control") else 0.0
                val sp = ((floorTarget() + offset) * 2).roundToInt() / 2.0
                val clamped = sp.coerceIn(5.0, f.maxSetpoint.toDouble())
                if (abs(clamped - f.setpoint) >= 0.5) {
                    Log.i(TAG, "floor <- setpoint $clamped (target ${floorTarget()}, offset $offset)")
                    fl.set(JSONObject().put("2", (clamped * 10).roundToInt()))
                }
            }
        }

        val a = acState
        val acDev = ac
        if (a != null && acDev != null) {
            val cool = pos?.let { COOL[it] }
            val fanPref = fanPref()
            when {
                cool != null -> if (!a.power || a.mode != AcState.COOL || a.target != cool.first || a.fan != cool.second) {
                    Log.i(TAG, "ac <- $pos"); acDev.apply(a.copy(power = true, mode = AcState.COOL, target = cool.first, fan = cool.second))
                    synchronized(s) { s.put("ac_managed", true) }; save()
                }
                dryActive -> if (!a.power || a.mode != AcState.DRY) {
                    Log.i(TAG, "ac <- dry"); acDev.apply(a.copy(power = true, mode = AcState.DRY, fan = fanPref))
                }
                a.power && s.optBoolean("ac_managed") -> {
                    Log.i(TAG, "ac <- off"); acDev.apply(a.copy(power = false))
                    synchronized(s) { s.put("ac_managed", false) }; save()
                }
            }
        }
        enforceDiesel()
    }

    /**
     * Diesel heater: the app only acts inside a phase the user started (run with a timer, clean stop, clean-out).
     * Outside a phase the heater is left alone, so its own panel/remote keeps working.
     */
    private fun enforceDiesel() {
        val hs = heater.status ?: return
        if (!heater.connected) return
        val phase = s.optString("diesel_phase")
        if (phase.isEmpty()) return
        val now = System.currentTimeMillis()
        val until = s.optLong("diesel_until")
        when (phase) {
            "run" -> when {
                until > 0 && now > until -> startCleanStop("timer")
                // switched off at the heater itself (after we started it): respect it
                !hs.running && now - s.optLong("diesel_started") > 120_000 -> endDiesel("stopped at the heater")
                !hs.running -> { Log.i(TAG, "heater <- on"); heater.power(true) }
                hs.level != s.optInt("diesel_level") -> { Log.i(TAG, "heater <- level ${s.optInt("diesel_level")}"); heater.setLevel(s.optInt("diesel_level")) }
            }
            "clean_stop", "cleanout" -> when {
                !hs.running -> endDiesel("already off")
                now > until -> { Log.i(TAG, "heater <- off after $phase"); heater.power(false); endDiesel("done") }
                hs.level != 10 -> heater.setLevel(10)
            }
        }
    }

    private fun fanPref() = when (s.optString("ac_fan")) { "low" -> AcState.FAN_LOW; "high" -> AcState.FAN_HIGH; else -> AcState.FAN_AUTO }

    /**
     * Raw control ("Manual" on the remote page): the user drives one device directly, so the app's own rules let go
     * of it first, otherwise the next 30 s step would undo the change. Runs inside synchronized(s); returns an error.
     * - floor: dial position -> null (no rule writes the floor; the AC is left as it is too)
     * - ac: dry timer and cooling positions end; ac_managed off so the app never switches it off by itself
     * - diesel: any app phase (timer run, clean stop) ends
     */
    private fun rawRelease(device: String): String? = when (device) {
        "floor" -> if (floor == null) "Floor thermostat not configured" else {
            s.put("position", JSONObject.NULL); s.put("ac_managed", false); null
        }
        "ac" -> if (ac == null) "AC not configured" else {
            s.put("dry_until", 0); s.put("ac_managed", false)
            if (pos() in COOL) s.put("position", JSONObject.NULL)
            null
        }
        "diesel" -> if (!heater.connected) "Diesel heater not connected" else {
            s.put("diesel_phase", ""); s.put("diesel_until", 0)
            if (pos() == "diesel" || pos() == "max") s.put("position", JSONObject.NULL)
            null
        }
        else -> "unknown device"
    }

    /** Manual control write, validated against the device's own description (range, step, options). */
    private fun set(deviceId: String, controlId: String, value: Any?, by: String): JSONObject {
        fun fail(e: String) = JSONObject().put("ok", false).put("error", e)
        val d = devices().firstOrNull { it.id == deviceId } ?: return fail("unknown device $deviceId")
        val c = d.controls.firstOrNull { it.id == controlId } ?: return fail("${d.name}: unknown control $controlId")
        val v = try { c.coerce(value) } catch (e: IllegalArgumentException) { return fail(e.message ?: "bad value") }
        if (!d.online) return fail("${d.name} is not connected")
        if (c.raw) {
            synchronized(s) { rawRelease(d.id) }?.let { return fail(it) }
            save()
            history.event("command", "set", "${d.id}.${c.id}=$v", by)
        }
        return try {
            c.write(v)
            if (c.raw) {
                enforce()
                floor?.let { f -> runCatching { floorState = FloorState.of(f.status()) } }
                ac?.let { a -> runCatching { acState = a.status() } }
            }
            JSONObject().put("ok", true).put("state", snapshot())
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    /** One-tap action: expands to existing commands (with the chosen params checked against their options). */
    private fun act(deviceId: String, actionId: String, params: JSONObject, by: String): JSONObject {
        fun fail(e: String) = JSONObject().put("ok", false).put("error", e)
        val d = devices().firstOrNull { it.id == deviceId } ?: return fail("unknown device $deviceId")
        val a = d.actions.firstOrNull { it.id == actionId } ?: return fail("${d.name}: \"$actionId\" isn't available now")
        val p = JSONObject()
        for (c in a.params) {
            p.put(c.id, try { c.coerce(if (params.has(c.id)) params.get(c.id) else c.value) } catch (e: IllegalArgumentException) { return fail(e.message ?: "bad value") })
        }
        var last = JSONObject().put("ok", true)
        for (b in a.bodies(p)) {
            last = command(b, by)
            if (!last.optBoolean("ok")) return last
        }
        return last
    }

    private fun n(v: Double?) = v?.takeIf { !it.isNaN() }
    private fun onOff(v: Boolean?) = when (v) { true -> "on"; false -> "off"; else -> "–" }
    private fun d1(v: Double?) = v?.let { "%.1f°".format(Locale.US, it) } ?: "–"

    /** Everything the app can show and control, described for any UI (see Devices.kt). */
    fun devices(): List<Device> {
        val st = synchronized(s) { JSONObject(s.toString()) }
        val now = System.currentTimeMillis()
        val p = pos()
        val out = ArrayList<Device>()
        val t = temps()
        val dryLeft = st.optLong("dry_until").let { if (it > now) ((it - now) / 60_000).toInt() + 1 else 0 }

        // --- app rules: the dial, floor plan, targets ---
        val heatOn = p in HEAT; val inferred = inferredPosition()
        out += Device("climate", "Climate rules", "rules", true,
            status = when (p) { null -> "Manual (mirrors the devices) · now $inferred"; "floor", "diesel", "max" -> "Heat · ${st.optString("floor_mode")} ${floorTarget()}°"; "cool" -> "Cool 24°"; "coolmax" -> "Cool+ 22°"; else -> "Off" },
            readings = listOf(
                Reading("inside", "Inside", n(t.optDouble("calibrated")), "°C"),
                Reading("control", "Controlled by", n(t.optDouble("control")), "°C", log = false),
                Reading("outside", "Outside", weather?.optDouble("now_temp")?.let { n(it) }, "°C"),
                Reading("position", "Dial", p ?: "manual", log = false),
            ),
            controls = listOf(
                Control("floor_mode", "Floor plan", Control.Type.CHOICE, st.optString("floor_mode"),
                    options = listOf(Option("off", "Off"), Option("auto", "Auto (weather)"), Option("on", "On")), raw = false) { command(cmd("floor_mode", "value" to it)) },
                Control("on_target", "On target", Control.Type.NUMBER, st.optDouble("on_target"), 5.0, 28.0, 0.5, "°C", raw = false) { command(cmd("on_target", "value" to it)) },
            ) + listOf("night", "cloudy", "sunny").map { k ->
                Control("auto_$k", "Auto $k", Control.Type.NUMBER, st.getJSONObject("auto_targets").optDouble(k), 5.0, 28.0, 0.5, "°C", raw = false) { command(cmd("auto_target", "key" to k, "value" to it)) }
            } + Control("control_by", "Heat by sensor", Control.Type.CHOICE, st.optString("control_by"),
                options = listOf(Option("calibrated", "Average"), Option("thermostat", "Thermostat"), Option("ac", "AC")), raw = false) { command(cmd("control_by", "value" to it)) },
            actions = listOf(
                Action("heat", "Heat", active = heatOn) { listOf(cmd("position", "value" to "floor")) },
                Action("off", "Off", active = p == "off") { listOf(cmd("position", "value" to "off")) },
                Action("cool", "Cool", active = p == "cool") { listOf(cmd("position", "value" to "cool")) },
                Action("coolmax", "Cool+", active = p == "coolmax") { listOf(cmd("position", "value" to "coolmax")) },
            ))

        // --- floor thermostat (Tuya) ---
        floor?.let {
            val f = floorState
            out += Device("floor", "Floor heating", "thermostat", f != null && !errors.containsKey("floor"),
                status = if (f == null) "not responding" else if (!f.power) "Off" else "On · set ${f.setpoint}° · ${if (f.heating) "heating" else "idle"}",
                manual = p == null,
                readings = listOf(
                    Reading("temp", "Sensor", f?.temp, "°C"),
                    Reading("heating", "Relay", f?.heating),
                    Reading("fault", "Fault", f?.fault?.takeIf { it != 0 }?.let { "code $it" } ?: "none", log = false),
                ),
                controls = listOf(
                    Control("power", "Power", Control.Type.TOGGLE, f?.power) { v -> it.set(JSONObject().put("1", v as Boolean)) },
                    Control("setpoint", "Setpoint", Control.Type.NUMBER, f?.setpoint, 5.0, (f?.maxSetpoint ?: 28).toDouble(), 0.5, "°C") { v ->
                        val dps = JSONObject().put("2", ((v as Double) * 10).roundToInt())
                        if (floorState?.mode != "home") dps.put("4", "home") // a setpoint in auto/leave mode doesn't stick
                        it.set(dps)
                    },
                    Control("mode", "Mode", Control.Type.CHOICE, f?.mode, options = listOf(Option("home", "Manual"), Option("auto", "Schedule"))) { v ->
                        it.set(JSONObject().put("4", v as String))
                    },
                ))
        }

        // --- AC (Midea) ---
        ac?.let { dev ->
            val a = acState
            val managed = st.optBoolean("ac_managed") || dryLeft > 0 || COOL[p] != null
            out += Device("ac", "Air conditioner", "ac", a != null && !errors.containsKey("ac"),
                status = if (a == null) "not responding" else if (!a.power) "Off" else "${modeName(a.mode)} ${a.target.roundToInt()}° · fan ${fanName(a.fan)}",
                manual = !managed,
                readings = listOf(Reading("temp", "Sensor", a?.indoor, "°C"),
                    Reading("water", "Water tank", if (a?.errorCode != null && a.errorCode != 0) "error ${a.errorCode}" else "ok", log = false)),
                controls = listOf(
                    Control("power", "Power", Control.Type.TOGGLE, a?.power) { v -> dev.apply(dev.status().copy(power = v as Boolean)) },
                    Control("mode", "Mode", Control.Type.CHOICE, a?.let { modeName(it.mode) },
                        options = listOf(Option("cool", "Cool"), Option("dry", "Dry"), Option("fan", "Fan"))) { v ->
                        dev.apply(dev.status().copy(mode = when (v) { "dry" -> AcState.DRY; "fan" -> AcState.FAN_ONLY; else -> AcState.COOL }))
                    },
                    Control("target", "Target", Control.Type.NUMBER, a?.target, 17.0, 30.0, 1.0, "°C") { v -> dev.apply(dev.status().copy(target = v as Double)) },
                    Control("fan", "Fan", Control.Type.CHOICE, a?.let { fanName(it.fan) },
                        options = listOf(Option("low", "Low"), Option("high", "High"), Option("auto", "Auto"))) { v ->
                        dev.apply(dev.status().copy(fan = when (v) { "low" -> AcState.FAN_LOW; "high" -> AcState.FAN_HIGH; else -> AcState.FAN_AUTO }))
                    },
                ))

            // dehumidifier: the AC in dry mode on a timer, independent of the dial
            val fanOpts = listOf(Option("low", "Mini"), Option("auto", "Auto"), Option("high", "Max"))
            out += Device("dry", "Dehumidifier", "rules", a != null,
                status = if (dryLeft > 0) "Drying · ${dryLeft} min left · ${fanOpts.first { it.value == st.optString("ac_fan", "auto") }.label}" else "Off",
                readings = listOf(Reading("minutes_left", "Time left", dryLeft, "min", log = false)),
                controls = listOf(Control("fan", "Strength", Control.Type.CHOICE, st.optString("ac_fan", "auto"), options = fanOpts, raw = false) {
                    command(cmd("ac_fan", "value" to it))
                }),
                actions = if (dryLeft > 0) listOf(Action("stop", "Stop drying", active = true) { listOf(cmd("dry", "minutes" to 0)) })
                else listOf(Action("run", "Dry", params = listOf(
                    Control("fan", "Strength", Control.Type.CHOICE, st.optString("ac_fan", "auto"), options = fanOpts) {},
                    Control("minutes", "For", Control.Type.CHOICE, "60",
                        options = listOf(Option("60", "1 h"), Option("180", "3 h"), Option("480", "8 h"))) {},
                )) { pp -> listOf(cmd("ac_fan", "value" to pp.getString("fan")), cmd("dry", "minutes" to pp.getString("minutes").toInt())) }),
            )
        }

        // --- diesel heater (BLE) ---
        val hs = if (heater.connected) heater.status else null
        val phase = st.optString("diesel_phase")
        val left = st.optLong("diesel_until").let { if (it > now) ((it - now) / 60_000).toInt() + 1 else 0 }
        val lvl = { k: String -> st.optInt("diesel_$k") }
        out += Device("diesel", "Diesel heater", "heater", heater.connected,
            status = when {
                !heater.connected -> "Not connected" + (heater.lastError?.let { " · $it" } ?: "")
                hs == null -> "Connected, no status yet"
                hs.error != null -> hs.error
                !hs.running -> "Off"
                else -> "${if (hs.step == "running") "Level ${hs.level}" else hs.step}" + when (phase) {
                    "run" -> if (left == 0) " · until off" else " · $left min left"
                    "clean_stop" -> " · clean stop $left min"; "cleanout" -> " · clean-out $left min"; else -> "" }
            },
            manual = phase.isEmpty(),
            readings = listOf(
                Reading("room_temp", "Sensor", hs?.roomTemp, "°C"),
                Reading("case_temp", "Heat exchanger", hs?.caseTemp, "°C"),
                Reading("voltage", "Supply", hs?.voltage, "V"),
                Reading("step", "Stage", hs?.step, log = false),
            ),
            controls = listOf(
                Control("power", "Power", Control.Type.TOGGLE, hs?.running) { v -> heater.power(v as Boolean) },
                Control("level", "Level", Control.Type.NUMBER, hs?.level?.toDouble(), 1.0, 10.0, 1.0) { v -> heater.setLevel((v as Double).roundToInt()) },
            ),
            actions = if (hs?.running == true) listOf(
                Action("clean_stop", "Clean stop", active = phase == "clean_stop") { listOf(cmd("diesel_stop", "clean" to true)) },
                Action("stop_now", "Stop now") { listOf(cmd("diesel_stop", "clean" to false)) },
            ) else listOf(
                Action("run", "Run", params = listOf(
                    Control("level", "Power", Control.Type.CHOICE, "low",
                        options = listOf(Option("low", "Low · ${lvl("low")}"), Option("mid", "Mid · ${lvl("mid")}"), Option("high", "High · ${lvl("high")}"))) {},
                    Control("minutes", "For", Control.Type.CHOICE, "60",
                        options = listOf(Option("20", "20 min"), Option("60", "1 h"), Option("120", "2 h"), Option("0", "Until off"))) {},
                )) { pp -> listOf(cmd("diesel", "level" to pp.getString("level"), "minutes" to pp.getString("minutes").toInt())) },
                Action("cleanout", "Clean-out burn") { listOf(cmd("diesel_cleanout")) },
            ))

        // --- Victron (read-only broadcasts) ---
        val vj = victron.json()
        for (name in vj.keys()) {
            val v = vj.getJSONObject(name)
            val ok = v.has("age_s")
            val type = v.optString("type")
            fun r(k: String, label: String, unit: String?) = if (v.has(k)) Reading(k, label, v.opt(k), unit) else null
            out += Device(name, when (type) { "solar_charger" -> "Solar (MPPT)"; "ac_charger" -> "Charger"; "battery_monitor" -> "Battery monitor"; else -> name },
                "victron", ok,
                status = if (!ok) v.optString("error") else listOfNotNull(v.optString("state_name").ifEmpty { null },
                    v.opt("pv_power")?.let { "$it W" }, v.opt("battery_voltage")?.let { "$it V" }, v.opt("battery_current")?.let { "$it A" }).joinToString(" · "),
                readings = listOfNotNull(
                    r("pv_power", "Solar", "W"), r("battery_voltage", "Battery", "V"), r("battery_current", "Current", "A"),
                    r("yield_today_kwh", "Today", "kWh"), r("load_current", "Load", "A"), r("temperature", "Temperature", "°C"),
                    r("soc", "Charge", "%"), r("state_name", "State", null),
                    v.optInt("error").takeIf { ok && it != 0 }?.let { Reading("error", "Error", "code $it", log = false) },
                ))
        }

        // --- the Z1 itself ---
        val b = battery
        val m = motion.summary()
        out += Device("phone", "Wall screen (Z1)", "phone", true,
            status = "Battery ${b?.opt("percent") ?: "–"}%${if (b?.optBoolean("charging") == true) " · charging" else " · NOT charging"} · app ${BuildConfig.VERSION_NAME}",
            readings = listOf(
                Reading("battery", "Battery", b?.opt("percent") as? Number, "%"),
                Reading("battery_temp", "Battery temp", (b?.opt("temp") as? Number)?.toDouble(), "°C"),
                Reading("charging", "Charging", b?.optBoolean("charging")),
                Reading("motion", "Motion", m.optString("state").ifEmpty { null }, log = false),
            ))
        return out
    }

    private fun startCleanStop(why: String) {
        Log.i(TAG, "heater clean stop ($why)")
        synchronized(s) {
            s.put("diesel_phase", "clean_stop"); s.put("diesel_until", System.currentTimeMillis() + CLEAN_STOP_MIN * 60_000L)
            if (pos() == "diesel" || pos() == "max") s.put("position", "floor")
        }
        save()
        runCatching { heater.setLevel(10) }
    }

    private fun endDiesel(why: String) {
        Log.i(TAG, "heater phase ends: $why")
        synchronized(s) {
            s.put("diesel_phase", ""); s.put("diesel_until", 0)
            if (pos() == "diesel" || pos() == "max") s.put("position", "floor")
        }
        save()
    }

    // ---------- commands from the UI ----------

    fun command(cmd: JSONObject, by: String = "screen"): JSONObject = synchronized(io) {
        Log.i(TAG, "command from $by: $cmd")
        when (cmd.optString("action")) {
            "set" -> return set(cmd.optString("device"), cmd.optString("control"), cmd.opt("value"), by)
            "act" -> return act(cmd.optString("device"), cmd.optString("id"), cmd.optJSONObject("params") ?: JSONObject(), by)
            "raw" -> { // older remote pages: {device, power?, setpoint?, ...} -> one set per field
                var last = JSONObject().put("ok", true)
                for (k in cmd.keys().asSequence().filter { it != "action" && it != "device" }.toList()) {
                    last = set(cmd.optString("device"), k, cmd.get(k), by)
                    if (!last.optBoolean("ok")) return last
                }
                return last
            }
        }
        val err = synchronized(s) {
            when (cmd.optString("action")) {
                "diesel" -> {
                    if (!heater.connected) "Diesel heater not connected" else {
                        // preset low / mid / high (levels in state, default 3 / 5 / 8)
                        val preset = cmd.optString("level").takeIf { it in setOf("low", "mid", "high") } ?: "low"
                        val minutes = cmd.optInt("minutes")
                        s.put("position", if (preset == "high") "max" else "diesel")
                        s.put("diesel_phase", "run")
                        s.put("diesel_level", s.optInt("diesel_$preset"))
                        s.put("diesel_until", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0)
                        s.put("diesel_started", System.currentTimeMillis())
                        if (s.optString("floor_mode") == "off") s.put("floor_mode", "auto")
                        null
                    }
                }
                "diesel_stop" -> {
                    val next = cmd.optString("next").ifEmpty { "floor" }
                    if (cmd.optBoolean("clean")) {
                        s.put("diesel_phase", "clean_stop"); s.put("diesel_until", System.currentTimeMillis() + CLEAN_STOP_MIN * 60_000L)
                    } else {
                        s.put("diesel_phase", "stop_now")
                    }
                    s.put("position", next)
                    null
                }
                "diesel_level" -> {
                    val v = cmd.getInt("value").coerceIn(1, 10)
                    if (!heater.connected) "Diesel heater not connected" else {
                        if (s.optString("diesel_phase") == "run") s.put("diesel_level", v) else s.put("diesel_set_level", v)
                        null
                    }
                }
                "diesel_cleanout" -> {
                    if (!heater.connected) "Diesel heater not connected" else {
                        s.put("diesel_phase", "cleanout"); s.put("diesel_until", System.currentTimeMillis() + CLEANOUT_MIN * 60_000L)
                        s.put("diesel_started", System.currentTimeMillis()); s.put("diesel_last_cleanout", System.currentTimeMillis())
                        if (!(heater.status?.running ?: false)) runCatching { heater.power(true) }
                        null
                    }
                }
                "position" -> {
                    val p = cmd.getString("value")
                    when {
                        p == "diesel" || p == "max" -> "Pick a run time for the diesel heater"
                        p !in setOf("floor", "off", "cool", "coolmax") -> "unknown position"
                        else -> {
                            // leaving a running diesel by any route (e.g. a phone) = clean stop
                            if (s.optString("diesel_phase") == "run") {
                                s.put("diesel_phase", "clean_stop"); s.put("diesel_until", System.currentTimeMillis() + CLEAN_STOP_MIN * 60_000L)
                            }
                            s.put("position", p)
                            if (p == "floor" && s.optString("floor_mode") == "off") s.put("floor_mode", "auto")
                            if (p in COOL) { s.put("dry_until", 0); s.put("ac_managed", true) }
                            null
                        }
                    }
                }
                "floor_mode" -> {
                    s.put("floor_mode", cmd.getString("value"))
                    if (pos() == null || pos() !in HEAT) s.put("position", if (cmd.getString("value") != "off") "floor" else "off")
                    null
                }
                "on_target" -> { s.put("on_target", cmd.getDouble("value").coerceIn(5.0, 28.0)); null }
                "auto_target" -> { s.getJSONObject("auto_targets").put(cmd.getString("key"), cmd.getDouble("value").coerceIn(5.0, 28.0)); null }
                "control_by" -> { s.put("control_by", cmd.getString("value")); null }
                "dry" -> {
                    val minutes = cmd.optInt("minutes")
                    if (minutes > 0 && pos() in COOL) "AC is cooling; switch the dial off cooling first"
                    else {
                        s.put("dry_until", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0)
                        if (minutes > 0) s.put("ac_managed", true)
                        null
                    }
                }
                "ac_quiet" -> { s.put("ac_quiet", cmd.getBoolean("value")); null }
                "ac_fan" -> { s.put("ac_fan", cmd.getString("value")); null }
                "dismiss" -> { s.getJSONObject("dismissed").put(cmd.getString("id"), System.currentTimeMillis()); null }
                else -> "unknown action"
            }
        }
        if (cmd.optString("action") != "dismiss") {
            history.event("command", cmd.optString("action"), JSONObject(cmd.toString()).apply { remove("action") }.toString()
                .let { if (err != null) "$it · failed: $err" else it }, by)
        }
        if (err != null) return JSONObject().put("ok", false).put("error", err)
        save()
        try {
            if (cmd.optString("action") == "ac_fan" && s.optLong("dry_until") > System.currentTimeMillis()) {
                // a fan change during an active dry run applies now (enforce() only sets the fan when entering dry)
                val a = ac?.status()
                if (a != null && a.power && a.mode == AcState.DRY) ac.apply(a.copy(fan = fanPref()))
            }
            if (cmd.optString("action") == "ac_quiet") {
                val a = acState
                if (a != null && a.displayOn == cmd.getBoolean("value")) ac?.toggleDisplay()
            }
            if (s.optString("diesel_phase") == "stop_now") {
                runCatching { heater.power(false) }
                synchronized(s) { s.put("diesel_phase", ""); s.put("diesel_until", 0) }; save()
            }
            if (s.has("diesel_set_level")) {
                val v = synchronized(s) { s.remove("diesel_set_level") as Int }
                save()
                heater.setLevel(v)
            }
            enforce()
            floor?.let { f -> runCatching { floorState = FloorState.of(f.status()) } }
            ac?.let { d -> runCatching { acState = d.status() } }
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: e.toString()).put("state", snapshot())
        }
        JSONObject().put("ok", true).put("state", snapshot())
    }

    // ---------- what the UI renders ----------

    private fun alerts(): JSONArray {
        val out = JSONArray()
        val dismissed = synchronized(s) { JSONObject(s.getJSONObject("dismissed").toString()) }
        fun recent(k: String, days: Double) = System.currentTimeMillis() - dismissed.optLong(k) < days * 86_400_000
        fun add(id: String, text: String, sub: String, action: String?) =
            out.put(JSONObject().put("id", id).put("text", text).put("sub", sub).put("action", action ?: JSONObject.NULL))
        errors["config"]?.let { add("config", "Setup needed", it, null) }
        val a = acState
        if (a != null && a.filterAlert && !recent("ac_filter", 14.0)) add("ac_filter", "AC filter needs cleaning", "Rinse, then tap Done", "Done")
        if (a != null && a.errorCode != 0) add("ac_error", "AC error ${a.errorCode}", "Water tank full?", "OK")
        floorState?.let { if (it.fault != 0) add("floor_fault", "Floor thermostat fault", "Code ${it.fault}, check floor probe", "OK") }
        if (errors.containsKey("floor")) add("offline_floor", "Floor thermostat not responding", "Retrying every 30 s", null)
        if (errors.containsKey("ac")) add("offline_ac", "AC not responding", "Retrying every 30 s", null)
        heater.status?.let { hs ->
            if (heater.connected && hs.errorCode != 0) add("heater_error", "Diesel heater ${hs.error}", "Check the heater", null)
        }
        val lastClean = s.optLong("diesel_last_cleanout")
        if (heater.connected && lastClean > 0 && System.currentTimeMillis() - lastClean > 7 * 86_400_000L && !recent("cleanout", 2.0)) {
            add("cleanout", "Diesel clean-out due", "Last hot burn ${(System.currentTimeMillis() - lastClean) / 86_400_000} days ago", "Run")
        }
        val severe = motion.lastSevere()
        if (severe > 0 && System.currentTimeMillis() / 1000 - severe < 86_400 && !recent("impact_$severe", 30.0)) {
            add("impact_$severe", "Hard impact detected", "Crash-level shock at " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(severe * 1000)) + ", check the camper", "OK")
        }
        battery?.let { b ->
            val t = b.optDouble("temp", Double.NaN)
            if (!t.isNaN() && t >= 42) add("battery_hot", "Screen phone battery hot", "${t.roundToInt()}°C, check for swelling", null)
        }
        val w = weather
        val p = pos()
        if (w != null && w.optBoolean("evening") && w.optBoolean("line_cold") && (p == null || p == "off") && !recent("frost", 0.5)) {
            add("frost", "Cold night, heating is off", w.optString("line").substringAfter("·").trim(), "OK")
        }
        return out
    }

    private fun inferredPosition(): String {
        pos()?.let { return it }
        heater.status?.let { if (heater.connected && it.running && it.step != "cooldown") return if ((it.level ?: 0) >= s.optInt("diesel_high", 8)) "max" else "diesel" }
        val a = acState
        if (a != null && a.power && a.mode == AcState.COOL) return "cool"
        if (floorState?.power == true) return "floor"
        return "off"
    }

    /** Saved settings as on disk (debug: GET /api/saved). */
    fun saved(): String = runCatching { stateFile.readText() }.getOrElse { "error: ${it.message}" }

    fun snapshot(): JSONObject {
        val st = synchronized(s) { JSONObject(s.toString()) }
        val now = System.currentTimeMillis()
        val dryUntil = st.optLong("dry_until")
        val a = acState
        val acJson = a?.let {
            JSONObject().put("power", it.power)
                .put("mode", when (it.mode) { AcState.COOL -> "cool"; AcState.DRY -> "dry"; AcState.FAN_ONLY -> "fan"; else -> "other" })
                .put("target", it.target).put("temp", it.indoor ?: JSONObject.NULL)
                .put("fan", when (it.fan) { AcState.FAN_LOW -> "low"; AcState.FAN_HIGH -> "high"; AcState.FAN_AUTO -> "auto"; else -> "${it.fan}" })
                .put("display_on", it.displayOn).put("filter_alert", it.filterAlert).put("error_code", it.errorCode)
        }
        return JSONObject()
            .put("position", st.opt("position") ?: JSONObject.NULL)
            .put("inferred_position", inferredPosition())
            .put("floor_mode", st.optString("floor_mode"))
            .put("floor_target", floorTarget())
            .put("auto_reason", autoReason())
            .put("on_target", st.getDouble("on_target"))
            .put("auto_targets", st.getJSONObject("auto_targets"))
            .put("control_by", st.optString("control_by"))
            .put("temps", temps())
            .put("floor", floorState?.json() ?: JSONObject.NULL)
            .put("ac", acJson ?: JSONObject.NULL)
            .put("ac_quiet", st.optBoolean("ac_quiet"))
            .put("ac_fan", st.optString("ac_fan"))
            .put("dry_minutes_left", if (dryUntil > now) ((dryUntil - now) / 60_000).toInt() else 0)
            .put("diesel", JSONObject()
                .put("connected", heater.connected)
                .put("name", heater.deviceName ?: JSONObject.NULL)
                .put("note", heater.lastError ?: JSONObject.NULL)
                .put("status", if (heater.connected) heater.status?.json() ?: JSONObject.NULL else JSONObject.NULL)
                .put("age_s", if (heater.lastUpdate > 0) (now - heater.lastUpdate) / 1000 else JSONObject.NULL)
                .put("phase", st.optString("diesel_phase"))
                .put("minutes_left", st.optLong("diesel_until").let { if (it > now) ((it - now) / 60_000).toInt() + 1 else 0 })
                .put("until_off", st.optString("diesel_phase") == "run" && st.optLong("diesel_until") == 0L)
                .put("low", st.optInt("diesel_low")).put("mid", st.optInt("diesel_mid", 5)).put("high", st.optInt("diesel_high")))
            .put("weather", weather ?: JSONObject.NULL)
            .put("alerts", alerts())
            .put("errors", JSONObject(errors as Map<*, *>))
            .put("updated", lastPoll / 1000)
            .put("dry_run", false)
            .put("location", location?.let { JSONArray().put(((it.first * 10).roundToInt()) / 10.0).put(((it.second * 10).roundToInt()) / 10.0) } ?: JSONObject.NULL)
            .put("battery", battery ?: JSONObject.NULL)
            .put("motion", motion.summary())
            .put("victron", victron.json())
            .put("devices", JSONArray(devices().map { it.json() }))
            .put("app", BuildConfig.VERSION_NAME)
    }
}
