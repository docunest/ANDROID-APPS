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
) {
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
        )
    }
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
        changed()
    }
}
