package com.docunest.dock

import android.content.Context
import android.net.TrafficStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The network's recent history for the drop card and the dashboard (owner 2026-11-25: "when the
 * connection is lost show … the network status and issues for the last few minutes … a chart showing
 * what was failing … a full dash, Grafana style, not random logs").
 *
 * Every 2 s (Wi-Fi doctor's pinger): state, network, signal, the iPhone's ping (or lost), link speed,
 * traffic in/out, the van's speed and position. Every internet check (30 s): iPhone / DNS / web times.
 * Events: drops, back, Wi-Fi switched off, questions. Kept 2 h in memory; each drop also saves its last
 * 5 minutes (+ where it happened) to files/netdrops.json (40 kept), so past drops can be looked at.
 */
object NetHistory {
    /** state: 1 online, 3 connected but no internet, 0 down, 2 Wi-Fi off. ping: ms, -1 lost, -2 not measured. */
    class Sample(val t: Long, val state: Int, val ssid: String?, val rssi: Int, val pingMs: Int, val linkMbps: Int,
                 val rxKBs: Float, val txKBs: Float, val speedKmh: Float, val lat: Double, val lng: Double)
    /** null = no answer. */
    class Check(val t: Long, val hotMs: Int?, val dnsMs: Int?, val webMs: Int?, val webOk: Boolean, val level: String)
    class Event(val t: Long, val kind: String, val text: String)
    class Drop(val t: Long, val ssid: String?, val lat: Double, val lng: Double, var why: String,
               val samples: List<Sample>, val checks: List<Check>)

    private const val KEEP_MS = 2 * 60 * 60_000L
    private val samples = ArrayDeque<Sample>()
    private val checks = ArrayDeque<Check>()
    private val events = ArrayDeque<Event>()
    private var lastRx = -1L; private var lastTx = -1L; private var lastBytesAt = 0L
    private val lock = Any()

    fun samples(sinceMs: Long): List<Sample> = synchronized(lock) { samples.filter { it.t >= sinceMs } }
    fun checks(sinceMs: Long): List<Check> = synchronized(lock) { checks.filter { it.t >= sinceMs } }
    fun events(sinceMs: Long): List<Event> = synchronized(lock) { events.filter { it.t >= sinceMs } }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().t > KEEP_MS) samples.removeFirst()
        while (checks.isNotEmpty() && now - checks.first().t > KEEP_MS) checks.removeFirst()
        while (events.isNotEmpty() && now - events.first().t > KEEP_MS) events.removeFirst()
    }

    /** Every 2 s from the Wi-Fi doctor (any thread). */
    fun sample(ctx: Context, state: Int, ssid: String?, rssi: Int, pingMs: Int, linkMbps: Int) {
        val now = System.currentTimeMillis()
        // Wi-Fi traffic = all traffic minus mobile (the unit has no SIM in use): kB/s since the last sample.
        val rx = TrafficStats.getTotalRxBytes() - TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val tx = TrafficStats.getTotalTxBytes() - TrafficStats.getMobileTxBytes().coerceAtLeast(0)
        val secs = (now - lastBytesAt) / 1000f
        val rxK = if (lastRx >= 0 && secs > 0 && rx >= lastRx) (rx - lastRx) / 1024f / secs else 0f
        val txK = if (lastTx >= 0 && secs > 0 && tx >= lastTx) (tx - lastTx) / 1024f / secs else 0f
        lastRx = rx; lastTx = tx; lastBytesAt = now
        val here = runCatching { Trip.here(ctx) }.getOrNull()
        val s = Sample(now, state, ssid, if (rssi in -120..-1) rssi else 0, pingMs, linkMbps, rxK, txK,
            here?.takeIf { it.hasSpeed() }?.speed?.times(3.6f) ?: 0f, here?.latitude ?: 0.0, here?.longitude ?: 0.0)
        synchronized(lock) { samples.addLast(s); trim(now) }
    }

    fun check(hotMs: Int?, dnsMs: Int?, webMs: Int?, webCode: Int?, level: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) { checks.addLast(Check(now, hotMs, dnsMs, webMs, webCode == 204, level)); trim(now) }
    }

    fun event(kind: String, text: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) { events.addLast(Event(now, kind, text)); trim(now) }
    }

    // --- Past drops (persisted) ----------------------------------------------------------------------

    private fun file(ctx: Context) = File(ctx.filesDir, "netdrops.json")

    /** At a drop: the last 5 minutes and where. [why] is filled in a few seconds later ([explainDrop]). */
    fun saveDrop(ctx: Context, ssid: String?) {
        val now = System.currentTimeMillis()
        val s = samples(now - 5 * 60_000L)
        val c = checks(now - 5 * 60_000L)
        val at = s.lastOrNull { it.lat != 0.0 }
        val d = Drop(now, ssid, at?.lat ?: 0.0, at?.lng ?: 0.0, "", s, c)
        runCatching {
            val all = loadRaw(ctx)
            all.put(toJson(d))
            while (all.length() > 40) all.remove(0)
            file(ctx).writeText(all.toString())
        }.onFailure { DebugLog.log("NET", "couldn't save the drop: ${it.message}") }
    }

    /** The reason, once the Wi-Fi doctor has worked it out. */
    fun explainDrop(ctx: Context, why: String) = runCatching {
        val all = loadRaw(ctx)
        if (all.length() == 0) return@runCatching
        val last = all.getJSONObject(all.length() - 1)
        if (System.currentTimeMillis() - last.getLong("t") < 60_000) { last.put("why", why); file(ctx).writeText(all.toString()) }
    }

    fun drops(ctx: Context): List<Drop> = runCatching {
        val a = loadRaw(ctx)
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadRaw(ctx: Context): JSONArray = runCatching { JSONArray(file(ctx).readText()) }.getOrDefault(JSONArray())

    // Compact: one array per field.
    private fun toJson(d: Drop): JSONObject {
        fun arr(f: (Sample) -> Number) = JSONArray().apply { d.samples.forEach { put(f(it)) } }
        fun carr(f: (Check) -> Number?) = JSONArray().apply { d.checks.forEach { put(f(it) ?: -1) } }
        return JSONObject().put("t", d.t).put("ssid", d.ssid ?: "").put("lat", d.lat).put("lng", d.lng).put("why", d.why)
            .put("st", arr { it.t }).put("ss", arr { it.state }).put("sr", arr { it.rssi }).put("sp", arr { it.pingMs })
            .put("sl", arr { it.linkMbps }).put("srx", arr { it.rxKBs }).put("stx", arr { it.txKBs }).put("sv", arr { it.speedKmh })
            .put("ct", carr { it.t }).put("ch", carr { it.hotMs }).put("cd", carr { it.dnsMs }).put("cw", carr { it.webMs })
            .put("co", JSONArray().apply { d.checks.forEach { put(it.webOk) } })
    }

    private fun fromJson(o: JSONObject): Drop {
        val st = o.getJSONArray("st")
        val samples = (0 until st.length()).map { i ->
            Sample(st.getLong(i), o.getJSONArray("ss").getInt(i), o.optString("ssid"), o.getJSONArray("sr").getInt(i), o.getJSONArray("sp").getInt(i),
                o.getJSONArray("sl").getInt(i), o.getJSONArray("srx").getDouble(i).toFloat(), o.getJSONArray("stx").getDouble(i).toFloat(),
                o.getJSONArray("sv").getDouble(i).toFloat(), 0.0, 0.0)
        }
        val ct = o.getJSONArray("ct")
        fun n(a: String, i: Int) = o.getJSONArray(a).getInt(i).takeIf { it >= 0 }
        val checks = (0 until ct.length()).map { i -> Check(ct.getLong(i), n("ch", i), n("cd", i), n("cw", i), o.getJSONArray("co").getBoolean(i), "") }
        return Drop(o.getLong("t"), o.optString("ssid").ifEmpty { null }, o.getDouble("lat"), o.getDouble("lng"), o.optString("why"), samples, checks)
    }

    /**
     * One plain reading of the minute before a drop at [t], from the same data the charts show (so the card
     * never contradicts its own charts): signal (steady, weak, or collapsing in the last seconds), the iPhone's
     * ping, and the internet checks — what failed first.
     */
    fun diagnose(s: List<Sample>, c: List<Check>, t: Long): String {
        val before = s.filter { it.t in (t - 60_000)..t && it.state != 0 && it.state != 2 }
        if (before.isEmpty()) return "No data from the minute before."
        val sig = before.filter { it.rssi in -120..-1 }
        val last = sig.lastOrNull()?.rssi
        val typical = sig.filter { it.t < t - 10_000 }.map { it.rssi }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val pings = before.filter { it.pingMs != -2 }
        val loss = if (pings.isEmpty()) 0 else pings.count { it.pingMs == -1 } * 100 / pings.size
        val lastAnswer = before.lastOrNull { it.pingMs >= 0 }
        val silent = lastAnswer?.let { ((t - it.t) / 1000).toInt() }?.takeIf { it > 6 }
        val failed = c.filter { it.t in (t - 90_000)..t && !it.webOk }
        val collapsed = last != null && typical != null && last <= -78 && typical >= -68
        val weak = last != null && last <= -78 && !collapsed
        val facts = mutableListOf<String>()
        facts += when {
            last == null -> "no signal reading"
            collapsed -> "signal fell from $typical to $last dBm in the last seconds"
            weak -> "signal weak ($last dBm)"
            else -> "signal good ($last dBm)"
        }
        facts += when {
            silent != null -> "iPhone silent ${silent} s before"
            loss >= 30 -> "iPhone lost $loss % of pings"
            else -> "iPhone answering"
        }
        facts += if (failed.isNotEmpty()) "internet failing ${(t - failed.first().t) / 1000} s before" else "internet OK"
        val verdict = when {
            collapsed -> "the hotspot went away suddenly (iPhone switched it off or restarted it)"
            weak -> "reception: too far or blocked"
            failed.isNotEmpty() && silent == null && loss < 30 -> "the iPhone's mobile data failed first, then Wi-Fi"
            silent != null || loss >= 30 -> "the iPhone's hotspot stopped answering first"
            else -> "sudden, no warning in the data"
        }
        return facts.joinToString(" · ") + " → " + verdict
    }
}
