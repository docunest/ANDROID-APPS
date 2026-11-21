package com.docunest.dock

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** A place to drive to: a link sent from a phone, or a bookmark made here. */
data class Item(
    val id: String,
    val kind: String, // "link" | "bookmark"
    val title: String,
    val lat: Double?,
    val lng: Double?,
    /** "link" exact, "search" found by name (approximate), "gps", "none" */
    val locatedBy: String,
    val address: String?,
    val sourceUrl: String?,
    val resolvedUrl: String?,
    val pinned: Boolean,
    val seenAt: Long?,
    val openedAt: Long?,
    val openCount: Int,
    val createdAt: Long,
    /** Bookmark saved here, not yet on the server (no signal). */
    val pending: Boolean = false,
    /** What the place is (MapPal lib/dock/category.ts): camp, grocery, fuel, parking, … — null until classified. */
    val category: String? = null,
    /** Inside a 100 k+ city (🏙 badge). */
    val bigCity: Boolean = false,
) {
    /** "⛺" / "🅿️ 🏙️" — what the place is, at a glance; "" when not classified yet. */
    val kindIcon: String get() = listOfNotNull(Kinds.ICON[category], if (bigCity) "🏙️" else null).joinToString(" ")

    val hasPoint get() = lat != null && lng != null
    val isNew get() = kind == "link" && seenAt == null
    val approximate get() = locatedBy == "search"
    /** Most recent of "sent" and "last driven to" — recency for sorting. */
    val recency get() = maxOf(createdAt, openedAt ?: 0)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title)
        .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
        .put("locatedBy", locatedBy).put("address", address ?: JSONObject.NULL)
        .put("sourceUrl", sourceUrl ?: JSONObject.NULL).put("resolvedUrl", resolvedUrl ?: JSONObject.NULL)
        .put("pinned", pinned)
        .put("seenAt", seenAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("openedAt", openedAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("openCount", openCount)
        .put("createdAt", Instant.ofEpochMilli(createdAt).toString())
        .put("pending", pending)
        .put("category", category ?: JSONObject.NULL)
        .put("bigCity", bigCity)

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
        private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
        private fun JSONObject.time(k: String): Long? = str(k)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun fromJson(o: JSONObject) = Item(
            id = o.getString("id"),
            kind = o.optString("kind", "link"),
            title = o.optString("title", "Place"),
            lat = o.dbl("lat"),
            lng = o.dbl("lng"),
            locatedBy = o.optString("locatedBy", "none"),
            address = o.str("address"),
            sourceUrl = o.str("sourceUrl"),
            resolvedUrl = o.str("resolvedUrl"),
            pinned = o.optBoolean("pinned"),
            seenAt = o.time("seenAt"),
            openedAt = o.time("openedAt"),
            openCount = o.optInt("openCount"),
            createdAt = o.time("createdAt") ?: System.currentTimeMillis(),
            pending = o.optBoolean("pending"),
            category = o.str("category"),
            bigCity = o.optBoolean("bigCity"),
        )
    }
}

/** Place categories, as MapPal classifies them (owner-approved 2026-11-15). */
object Kinds {
    val ORDER = listOf("camp", "grocery", "fuel", "parking", "shopping", "eat", "services", "sight", "town", "other")
    val ICON = mapOf(
        "camp" to "⛺", "grocery" to "🛒", "fuel" to "⛽", "parking" to "🅿️", "shopping" to "🛍️",
        "eat" to "🍴", "services" to "🔧", "sight" to "📍", "town" to "🏘️", "other" to "❔",
    )
    val LABEL = mapOf(
        "camp" to "Campsites", "grocery" to "Grocery", "fuel" to "Fuel", "parking" to "Parking", "shopping" to "Shopping",
        "eat" to "Eat", "services" to "Services", "sight" to "Sights", "town" to "Towns", "other" to "Other",
    )
    const val CITY = "city"
}

/**
 * Local copy of every place (works with no signal), kept in sync with
 * MapPal by [Sync]. Listeners are called on the main thread.
 */
object Store {
    private val items = LinkedHashMap<String, Item>()
    private var file: File? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    var lastSince: String? = null
        private set
    var lastFullSync: Long = 0
        private set
    var status: String = "Not synced yet"

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "items.json")
        file = f
        runCatching {
            val o = JSONObject(f.readText())
            lastSince = o.optString("since").takeIf { it.isNotEmpty() }
            lastFullSync = o.optLong("lastFullSync")
            val arr = o.getJSONArray("items")
            for (i in 0 until arr.length()) Item.fromJson(arr.getJSONObject(i)).let { items[it.id] = it }
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        items.values.forEach { arr.put(it.toJson()) }
        val o = JSONObject().put("items", arr).put("since", lastSince ?: "").put("lastFullSync", lastFullSync)
        runCatching {
            val f = file ?: return
            val tmp = File(f.parentFile, "items.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }

    @Synchronized
    fun all(): List<Item> = items.values.toList()

    @Synchronized
    fun get(id: String): Item? = items[id]

    /**
     * Pinned first, then links not yet looked at, then most recently sent or
     * driven to — history is kept forever but the top is what matters.
     */
    fun sorted(): List<Item> = all().sortedWith(
        compareByDescending<Item> { it.pinned }.thenByDescending { it.isNew }.thenByDescending { it.recency },
    )

    @Synchronized
    fun put(item: Item, notify: Boolean = true) {
        items[item.id] = item
        save()
        if (notify) changed()
    }

    @Synchronized
    fun remove(id: String) {
        items.remove(id)
        save()
        changed()
    }

    /** Merge a server page. A full sync also drops what was deleted on the server. */
    @Synchronized
    fun merge(server: List<Item>, now: String, full: Boolean) {
        val before = items.values.toList()
        if (full) {
            val keep = items.values.filter { it.pending }
            items.clear()
            keep.forEach { items[it.id] = it }
            lastFullSync = System.currentTimeMillis()
        }
        for (s in server) {
            val local = items[s.id]
            // Keep a local "seen/opened" we haven't managed to report yet.
            items[s.id] = s.copy(
                seenAt = s.seenAt ?: local?.seenAt,
                openedAt = listOfNotNull(s.openedAt, local?.openedAt).maxOrNull(),
                pending = false,
            )
        }
        lastSince = now
        save()
        // A quiet poll (nothing new) must not redraw the screen every 20 s.
        if (items.values.toList() != before) changed()
    }
}
