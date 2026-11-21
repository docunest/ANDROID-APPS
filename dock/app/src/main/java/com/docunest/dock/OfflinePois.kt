package com.docunest.dock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Nearest POIs from the installed offline packs ([OfflinePacks]) — no network needed.
 * Layers are the Road ahead ones (MapPal lib/dock/road.ts ROAD_LAYERS).
 */
object OfflinePois {
    data class Poi(val osm: String, val layer: String, val lat: Double, val lng: Double,
                   val name: String?, val brand: String?, val info: String?, val distM: Double) {
        val icon get() = ICON[layer] ?: "📍"
        val title get() = name ?: brand ?: LABEL[layer] ?: layer
    }

    val ICON = linkedMapOf(
        "fuel" to "⛽", "rest" to "☕", "toilets" to "🚻", "water" to "💧", "grocery" to "🛒",
        "dump" to "🚐", "aire" to "⛺", "limit" to "⚠️",
        "playground" to "🛝", "picnic" to "🧺", "beach" to "🏖️",
        "works" to "🏭", "power" to "⚡", "quarry" to "⛏️", "dam" to "🌊", "bridge" to "🌉",
        "airport" to "✈️", "castle" to "🏰", "viewpoint" to "⛰️", "lighthouse" to "🗼", "parking" to "🅿️",
    )
    val LABEL = mapOf(
        "fuel" to "Diesel", "rest" to "Rest area / services", "toilets" to "Toilets", "water" to "Drinking water", "grocery" to "Grocery",
        "dump" to "Camper service", "aire" to "Aire / campsite", "limit" to "Height / weight limit",
        "playground" to "Playground", "picnic" to "Picnic site", "beach" to "Beach",
        "works" to "Factory", "power" to "Power plant", "quarry" to "Quarry", "dam" to "Dam", "bridge" to "Bridge",
        "airport" to "Airport", "castle" to "Castle", "viewpoint" to "Viewpoint", "lighthouse" to "Lighthouse", "parking" to "Parking",
    )

    /**
     * The small context icon drawn on a place's map icon (owner 2026-11-17 "add some context icons"):
     * for parking the thing that matters most to a camper first (height), then campers OK / no, free / paid;
     * any kind: 🌙 open 24/7. From the pack's info text (build.py info_of).
     */
    fun badge(p: Poi): String? {
        val i = p.info ?: return null
        if (p.layer == "parking") {
            when {
                "underground" in i -> return "⬇️"
                "multi storey" in i || "rooftop" in i -> return "🏢"
                "no campers" in i -> return "🚫"
                "campers OK" in i -> return "🚐"
                "🔒" in i -> return "🔒"
                "free" in i -> return "🆓"
                "paid" in i -> return "💶"
            }
        }
        if (p.layer == "fuel" && "diesel ✓" in i && "24/7" !in i) return null
        return if ("24/7" in i) "🌙" else null
    }
    fun streetSide(p: Poi) = p.layer == "parking" && p.info?.contains("street side") == true

    /** How far off the road each kind still counts as "on the way" (MapPal ROAD_LAYERS radius). */
    val RADIUS = mapOf(
        "fuel" to 1000, "rest" to 1000, "toilets" to 300, "water" to 300, "grocery" to 1500,
        "dump" to 3000, "aire" to 3000, "limit" to 20, "playground" to 600, "picnic" to 1000, "beach" to 3000,
        "works" to 3000, "power" to 5000, "quarry" to 3000, "dam" to 3000, "bridge" to 60,
        "airport" to 8000, "castle" to 3000, "viewpoint" to 2000, "lighthouse" to 3000, "parking" to 300,
    )

    /** The on/off buttons above the map: one per group (owner 2026-11-16: all on by default; parking optional = off). */
    data class Group(val id: String, val icon: String, val label: String, val layers: List<String>, val onByDefault: Boolean = true)
    val GROUPS = listOf(
        Group("fuel", "⛽", "Diesel", listOf("fuel")),
        Group("rest", "☕", "Rest areas & services", listOf("rest")),
        Group("food", "🛒", "Shops", listOf("grocery")),
        Group("wc", "🚻", "WC & water", listOf("toilets", "water")),
        Group("camper", "⛺", "Camper", listOf("aire", "dump")),
        Group("parking", "🅿️", "Parking", listOf("parking"), onByDefault = false),
        Group("limit", "⚠️", "Limits", listOf("limit")),
        Group("kids", "🛝", "Kids", listOf("playground", "picnic", "beach")),
        Group("cool", "🏰", "Cool stuff", listOf("castle", "viewpoint", "lighthouse", "dam", "bridge", "works", "power", "quarry", "airport")),
    )
    /** Drawn first / kept when the map is crowded. */
    val PRIORITY = listOf("fuel", "rest", "grocery", "aire", "dump", "limit", "toilets", "water", "parking", "playground", "picnic",
        "castle", "viewpoint", "beach", "lighthouse", "dam", "works", "power", "quarry", "airport", "bridge")

    private val dbs = HashMap<String, SQLiteDatabase>()

    @Synchronized fun close(cc: String) { dbs.remove(cc)?.close() }

    @Synchronized private fun db(ctx: Context, cc: String): SQLiteDatabase? {
        dbs[cc]?.let { return it }
        val f = OfflinePacks.dbFile(ctx, cc)
        if (!f.exists()) return null
        return runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()?.also { dbs[cc] = it }
    }

    fun available(ctx: Context) = OfflinePacks.COUNTRIES.keys.any { OfflinePacks.dbFile(ctx, it).exists() }

    /** Up to [limit] POIs of [layer] within [radiusM] of the point, nearest first (straight line). */
    fun nearest(ctx: Context, lat: Double, lng: Double, layer: String, radiusM: Double = 30_000.0, limit: Int = 3): List<Poi> {
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val out = ArrayList<Poi>()
        for (cc in OfflinePacks.COUNTRIES.keys) {
            val db = synchronized(this) { db(ctx, cc) } ?: continue
            runCatching {
                db.rawQuery(
                    "SELECT osm, lat, lng, name, brand, info FROM poi WHERE layer = ? AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?",
                    arrayOf(layer, (lat - dLat).toString(), (lat + dLat).toString(), (lng - dLng).toString(), (lng + dLng).toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val pl = c.getDouble(1); val pg = c.getDouble(2)
                        val d = distM(lat, lng, pl, pg)
                        if (d <= radiusM) out += Poi(c.getString(0), layer, pl, pg, c.getString(3), c.getString(4), c.getString(5), d)
                    }
                }
            }
        }
        // Border areas: the same object can be in two countries' extracts.
        return out.distinctBy { it.osm }.sortedBy { it.distM }.take(limit)
    }

    /** Places of [layers] inside a box (the visible map), at most [perLayer] of each. */
    fun inBox(ctx: Context, s: Double, n: Double, w: Double, e: Double, layers: Collection<String>, perLayer: Int = 200): List<Poi> {
        val out = ArrayList<Poi>()
        for (cc in OfflinePacks.COUNTRIES.keys) {
            val db = synchronized(this) { db(ctx, cc) } ?: continue
            for (layer in layers) runCatching {
                db.rawQuery(
                    // Parking is dense (tens of thousands a country): a bigger cap, or only one strip of the box would show.
                    "SELECT osm, lat, lng, name, brand, info FROM poi WHERE layer = ? AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? LIMIT ${if (layer == "parking") perLayer * 4 else perLayer}",
                    arrayOf(layer, s.toString(), n.toString(), w.toString(), e.toString()),
                ).use { c ->
                    while (c.moveToNext()) out += Poi(c.getString(0), layer, c.getDouble(1), c.getDouble(2), c.getString(3), c.getString(4), c.getString(5), 0.0)
                }
            }
        }
        return out.distinctBy { it.osm }
    }

    /** A place along the route: [km] from the start where it is level with the road, metres off it, side. */
    data class RoutePoi(val poi: Poi, val km: Double, val offM: Int, val left: Boolean)

    /** Every place of [layers] within its kind's distance of the route line, in driving order. */
    fun corridor(ctx: Context, lat: DoubleArray, lng: DoubleArray, cum: DoubleArray, layers: Collection<String>): List<RoutePoi> {
        if (lat.size < 2) return emptyList()
        val maxR = layers.maxOf { RADIUS[it] ?: 1000 } * 1.2 + 50
        val seen = HashSet<String>()
        val out = ArrayList<RoutePoi>()
        var i = 0
        while (i < lat.size - 1) {
            // Pieces of ≤ 40 vertices / ~8 km: small boxes, few rows each.
            var j = i + 1
            while (j < lat.size - 1 && j - i < 40 && cum[j] - cum[i] < 8000) j++
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            for (k in i..j) { s = minOf(s, lat[k]); n = maxOf(n, lat[k]); w = minOf(w, lng[k]); e = maxOf(e, lng[k]) }
            val dLat = maxR / 111_320.0
            val dLng = maxR / (111_320.0 * cos(Math.toRadians((s + n) / 2)).coerceAtLeast(0.2))
            for (p in inBox(ctx, s - dLat, n + dLat, w - dLng, e + dLng, layers, perLayer = 2000)) {
                if (p.osm in seen) continue
                val pr = RouteGeo.project(lat, lng, cum, p.lat, p.lng, from = maxOf(1, i - 3), to = minOf(lat.size, j + 4))
                if (pr.off > (RADIUS[p.layer] ?: 1000) * 1.2 + 50) continue
                seen += p.osm
                out += RoutePoi(p.copy(distM = pr.off), pr.along / 1000.0, pr.off.toInt(), pr.left)
            }
            i = j
        }
        return out.sortedBy { it.km }
    }

    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return hypot(x, y) * 6_371_000.0
    }
}
