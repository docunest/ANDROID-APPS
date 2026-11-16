package com.docunest.dock


/**
 * Drive recording of Waze's DEBUG log (owner 2026-11-17: "Build logging"). [Police] already reads
 * every new line; the useful ones go to the dock's debug log (tag WAZE, uploaded to MapPal like the
 * rest) so the next steps can be built on what Waze really writes while driving:
 *  - road type (`NVG_LOG … current_road_type=`) on every change + a sample every 30 s → Auto zoom by
 *    road; distance to the next turn in the sample;
 *  - whether Waze is matched to a road (`confused=TRUE/FALSE`, line_id) on change;
 *  - the route's turn list (`[LANES] ------ Route map`) when it's (re)calculated;
 *  - route lifecycle: calculating, route changed (reroute), near destination, no progress;
 *  - every community report (police, accident, hazard, jam…) as one line;
 *  - discovery: lines mentioning ETA / remaining / street / speed limit / camera (each kind once per
 *    10 min), and every 10 min 30 raw lines that aren't known noise — to find ETA, street names, more.
 * Bounded: roughly a few hundred lines per hour of driving.
 */
object WazeTap {
    private const val TAG = "WAZE"
    private val noise = Regex("GetPrimaryMainCanvas|map-location-tracker|geo_config|config_values|encouragement|mood|RealtimeNetRecPb")
    private val prefix = Regex("""^\[\d\d:\d\d:\d\d\.\d+ \d+ [DIWE]\]\s*""")
    private val lifecycle = listOf(
        "calc" to Regex("Calculating route"),
        "changed" to Regex("route changed new_generation|Route changed"),
        "near" to Regex("navigate_near_destination"),
        "stuck" to Regex("No navigation progress"),
        "instr" to Regex("RouteInstructionsPlayer"),
        "ranges" to Regex("found [0-9]+ ranges"),
    )
    private val discover = listOf(
        "eta" to Regex("(?i)\\beta\\b|arriv|time_to_dest|remaining"),
        "street" to Regex("(?i)street|street_name|current_street"),
        "limit" to Regex("(?i)speed_?limit|max_?speed"),
        "camera" to Regex("(?i)camera|speedcam"),
        "dest" to Regex("(?i)distance_to_dest|dist_to_dest"),
    )
    private val lastAt = HashMap<String, Long>()
    private var lanes: StringBuilder? = null
    private var lanesCount = 0
    private var lastLanes = ""
    private var rawLeft = 0
    private var raw = StringBuilder()
    private var lastRawAt = 0L
    private var otherAlerts = 0
    private var otherHour = 0L

    @Volatile var enabled = true

    private fun every(key: String, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        if (lastAt.size > 2000) lastAt.clear()
        val t = lastAt[key]
        if (t != null && now - t < ms) return false
        lastAt[key] = now
        return true
    }

    fun line(full: String) {
        if (!enabled) return
        val l = full.replace(prefix, "").trim()
        if (l.isEmpty()) return
        // Route turn list: collect the [LANES] lines after the header.
        lanes?.let { b ->
            if (l.startsWith("[LANES]") && lanesCount < 120) { b.append(l.removePrefix("[LANES]").trim()).append(" | "); lanesCount++; return }
            val text = b.toString()
            if (text != lastLanes) { lastLanes = text; DebugLog.log(TAG, "route turns ($lanesCount): " + text.take(4000)) }
            lanes = null
        }
        if (l.contains("------ Route map")) { lanes = StringBuilder(); lanesCount = 0; return }
        if (l.startsWith("NVG_LOG:")) {
            Regex("current_road_type=(-?\\d+)").find(l)?.groupValues?.get(1)?.let { DebugLog.change(TAG, "road type", it) }
            if (every("nvg", 30_000)) DebugLog.log(TAG, "sample: $l")
            return
        }
        if (l.contains("get match time")) {
            val conf = Regex("confused=(\\w+)").find(l)?.groupValues?.get(1)
            if (conf != null) DebugLog.change(TAG, "road matched", if (conf == "FALSE") "yes" else "no")
            if (every("match", 60_000)) DebugLog.log(TAG, "match: $l")
            return
        }
        for ((k, rx) in lifecycle) if (rx.containsMatchIn(l)) { if (every("lc-$k", 5_000)) DebugLog.log(TAG, "route: $l"); return }
        if (noise.containsMatchIn(l)) return
        for ((k, rx) in discover) if (rx.containsMatchIn(l)) {
            val key = "d-$k-" + l.take(40)
            if (every(key, 10 * 60_000L)) DebugLog.log(TAG, "found ($k): ${l.take(400)}")
            break
        }
        // A raw sample every 10 min: 30 lines of whatever Waze writes (minus noise).
        if (rawLeft == 0 && System.currentTimeMillis() - lastRawAt > 10 * 60_000L) { rawLeft = 30; raw = StringBuilder() }
        if (rawLeft > 0) {
            raw.append(l.take(300)).append(" ⏎ ")
            if (--rawLeft == 0) { lastRawAt = System.currentTimeMillis(); DebugLog.log(TAG, "raw sample: $raw") }
        }
    }

    /** Every community report Waze receives, as one line (police all; others ≤ 40 an hour). */
    fun alert(type: String, block: String) {
        if (!enabled) return
        if (!type.startsWith("POLICE")) {
            val hour = System.currentTimeMillis() / 3_600_000
            if (hour != otherHour) { otherHour = hour; otherAlerts = 0 }
            if (++otherAlerts > 40) return
        }
        fun f(k: String) = Regex("""\b$k:\s*("[^"]*"|-?[\w.]+)""").find(block)?.groupValues?.get(1)
        val lat = f("lat_times_1000000")?.toLongOrNull()?.let { it / 1e6 }
        val lng = f("lon_times_1000000")?.toLongOrNull()?.let { it / 1e6 }
        DebugLog.log(TAG, "report $type sub=${f("sub_type") ?: "-"} at ${lat ?: "?"},${lng ?: "?"} on_route=${f("on_route") ?: "?"} " +
            "street=${f("street") ?: "-"} city=${f("city") ?: "-"} thumbs=${f("thumbs_up_count") ?: "-"} id=${f("id") ?: "-"}")
    }
}
