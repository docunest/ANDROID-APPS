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
        "fuel" to "⛽", "rest" to "🅿️", "toilets" to "🚻", "water" to "💧", "grocery" to "🛒",
        "dump" to "🚐", "aire" to "⛺", "limit" to "⚠️",
        "playground" to "🛝", "picnic" to "🧺", "beach" to "🏖️",
        "works" to "🏭", "power" to "⚡", "quarry" to "⛏️", "dam" to "🌊", "bridge" to "🌉",
        "airport" to "✈️", "castle" to "🏰", "viewpoint" to "⛰️", "lighthouse" to "🗼",
    )
    val LABEL = mapOf(
        "fuel" to "Diesel", "rest" to "Rest area", "toilets" to "Toilets", "water" to "Drinking water", "grocery" to "Grocery",
        "dump" to "Camper service", "aire" to "Aire / campsite", "limit" to "Height / weight limit",
        "playground" to "Playground", "picnic" to "Picnic site", "beach" to "Beach",
        "works" to "Factory", "power" to "Power plant", "quarry" to "Quarry", "dam" to "Dam", "bridge" to "Bridge",
        "airport" to "Airport", "castle" to "Castle", "viewpoint" to "Viewpoint", "lighthouse" to "Lighthouse",
    )

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

    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return hypot(x, y) * 6_371_000.0
    }
}
