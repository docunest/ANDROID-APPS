package com.docunest.dock

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.UUID

/**
 * Keeps [Store] in step with MapPal. Polls every [POLL_MS] while the dock
 * runs, and right away when the head unit wakes or gets signal — links sent
 * while the car was off arrive within seconds of switching it on.
 * (Vercel can't hold a socket open; a short poll is the same experience.)
 */
object Sync {
    const val POLL_MS = 20_000L
    private const val FULL_SYNC_MS = 30 * 60_000L

    private val thread = HandlerThread("dock-sync").apply { start() }
    private val worker = Handler(thread.looper)
    private var running = false
    private lateinit var app: Context

    /** Called with links nobody has looked at yet (pop-up). */
    var onNewLinks: ((List<Item>) -> Unit)? = null
    private val announced = mutableSetOf<String>()

    fun init(context: Context) {
        app = context.applicationContext
        Store.init(app)
    }

    private val loop = object : Runnable {
        override fun run() {
            pollOnce()
            if (running) worker.postDelayed(this, POLL_MS)
        }
    }

    fun start(context: Context) {
        init(context)
        if (running) return
        running = true
        worker.post(loop)
    }

    fun stop() {
        running = false
        worker.removeCallbacks(loop)
    }

    /** Poll now (woke up, signal back, panel opened). */
    fun pollSoon() {
        if (!::app.isInitialized) return
        worker.removeCallbacks(loop)
        worker.post(loop.takeIf { running } ?: Runnable { pollOnce() })
    }

    private fun pollOnce() {
        val api = Api(app)
        try {
            uploadPending(api)
            val full = Store.lastSince == null || System.currentTimeMillis() - Store.lastFullSync > FULL_SYNC_MS
            val q = if (full) "" else "?since=" + java.net.URLEncoder.encode(Store.lastSince, "UTF-8")
            val res = api.call("GET", "/api/dock/items$q")
            val arr = res.getJSONArray("items")
            val items = (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
            Store.merge(items, res.getString("now"), full)
            Store.status = "Synced ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}"
            announceNew()
        } catch (e: Exception) {
            Store.status = "Offline: ${e.message ?: e.javaClass.simpleName}"
            Store.changed()
        }
    }

    private fun announceNew() {
        val fresh = Store.sorted().filter { it.isNew && it.id !in announced }
        if (fresh.isEmpty()) return
        announced += fresh.map { it.id }
        onNewLinks?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(fresh) } }
    }

    private fun uploadPending(api: Api) {
        for (item in Store.all().filter { it.pending }) {
            val body = JSONObject().put("lat", item.lat).put("lng", item.lng).put("clientId", item.id)
            val res = api.call("POST", "/api/dock/items", body)
            Store.put(Item.fromJson(res.getJSONObject("item")))
        }
    }

    /** "I like this place": saved locally at once, named by the server when there's signal. */
    fun bookmark(context: Context, loc: Location): Item {
        init(context)
        val now = System.currentTimeMillis()
        val item = Item(
            id = UUID.randomUUID().toString(),
            kind = "bookmark",
            title = "Saved spot " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(now)),
            lat = loc.latitude,
            lng = loc.longitude,
            locatedBy = "gps",
            address = null,
            sourceUrl = null,
            resolvedUrl = null,
            pinned = false,
            seenAt = now,
            openedAt = null,
            openCount = 0,
            createdAt = now,
            pending = true,
        )
        Store.put(item)
        pollSoon()
        return item
    }

    /** Report seen / opened / pinned; local state changes first, server best-effort. */
    fun patch(id: String, body: JSONObject) {
        val item = Store.get(id) ?: return
        val now = System.currentTimeMillis()
        Store.put(
            item.copy(
                seenAt = item.seenAt ?: if (body.has("seen") || body.has("opened")) now else null,
                openedAt = if (body.has("opened")) now else item.openedAt,
                openCount = item.openCount + if (body.has("opened")) 1 else 0,
                pinned = if (body.has("pinned")) body.getBoolean("pinned") else item.pinned,
            ),
        )
        if (item.pending) return
        worker.post { runCatching { Api(app).call("POST", "/api/dock/items/$id", body) } }
    }

    fun delete(id: String) {
        val item = Store.get(id) ?: return
        Store.remove(id)
        if (!item.pending) worker.post { runCatching { Api(app).call("DELETE", "/api/dock/items/$id") } }
    }

    fun post(r: Runnable) = worker.post(r)
}
