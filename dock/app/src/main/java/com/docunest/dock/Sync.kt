package com.docunest.dock

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.util.UUID

/**
 * Keeps [Store] in step with MapPal while the dock runs. Long-poll: the
 * server holds each request until something changes (~1 s from phone to
 * car) and sets the rhythm; asked again at once on wake / network back, so
 * links sent while the car was off arrive as soon as it's online.
 */
object Sync {
    private const val FULL_SYNC_MS = 30 * 60_000L
    /** Used only if the server doesn't say (older server) — it normally sets the rhythm. */
    private const val FALLBACK_DELAY_MS = 20_000L

    // Writes (bookmarks, seen/opened) never wait behind a long-poll.
    private val thread = HandlerThread("dock-sync").apply { start() }
    private val worker = Handler(thread.looper)
    private val pollThread = HandlerThread("dock-poll").apply { start() }
    private val poller = Handler(pollThread.looper)
    @Volatile private var running = false
    @Volatile private var liveConn: java.net.HttpURLConnection? = null
    @Volatile private var interrupted = false
    @Volatile private var uploading = false
    private var failures = 0
    private lateinit var app: Context

    /** Called with links nobody has looked at yet (pop-up). */
    var onNewLinks: ((List<Item>) -> Unit)? = null
    private val announced = mutableSetOf<String>()

    fun init(context: Context) {
        app = context.applicationContext
        Store.init(app)
    }

    /**
     * Long-poll loop: the server holds each request until something changes
     * (or ~25 s pass) and says when to ask again, so a link sent from the
     * phone reaches the car in about a second.
     */
    private val loop = object : Runnable {
        override fun run() {
            if (Store.all().any { it.pending }) uploadSoon()
            val delay = pollOnce()
            poller.removeCallbacks(this)
            if (running) poller.postDelayed(this, delay)
        }
    }

    fun start(context: Context) {
        init(context)
        if (running) return
        running = true
        poller.post(loop)
    }

    fun stop() {
        running = false
        poller.removeCallbacks(loop)
        liveConn?.disconnect()
    }

    /** Ask now (woke up, signal back, panel opened): cut a waiting long-poll short. */
    fun pollSoon() {
        if (!::app.isInitialized) return
        val c = liveConn
        if (c != null) {
            interrupted = true
            Thread { runCatching { c.disconnect() } }.start()
        } else {
            poller.removeCallbacks(loop)
            poller.post(if (running) loop else Runnable { pollOnce() })
        }
    }

    /** One request; returns how long to wait before the next. */
    private fun pollOnce(): Long {
        val api = Api(app)
        try {
            val full = Store.lastSince == null || System.currentTimeMillis() - Store.lastFullSync > FULL_SYNC_MS
            val q = if (full) "" else "?wait=1&since=" + java.net.URLEncoder.encode(Store.lastSince, "UTF-8")
            val res = api.call("GET", "/api/dock/items$q", readTimeoutMs = 75_000) { liveConn = it }
            liveConn = null
            val arr = res.getJSONArray("items")
            val items = (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
            val wasOffline = Store.status.startsWith("Offline") || Store.status.startsWith("Not synced")
            Store.merge(items, res.getString("now"), full)
            Store.status = "Live ✓ ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}"
            if (wasOffline) Store.changed()
            announceNew()
            failures = 0
            return res.optJSONObject("next")?.optLong("delayMs", 0L) ?: FALLBACK_DELAY_MS
        } catch (e: Exception) {
            liveConn = null
            if (interrupted) {
                interrupted = false
                return 0L
            }
            val status = "Offline: ${e.message ?: e.javaClass.simpleName}"
            if (status != Store.status) {
                Store.status = status
                Store.changed()
            }
            // Back off while there's no signal; network-back wakes us early anyway.
            failures++
            return minOf(60_000L, 5_000L * failures)
        }
    }

    private fun announceNew() {
        val fresh = Store.sorted().filter { it.isNew && it.id !in announced }
        if (fresh.isEmpty()) return
        announced += fresh.map { it.id }
        onNewLinks?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(fresh) } }
    }

    private fun uploadSoon() {
        if (uploading) return
        uploading = true
        worker.post {
            try {
                uploadPending(Api(app))
            } catch (_: Exception) {
                // No signal: the next loop round retries.
            } finally {
                uploading = false
            }
        }
    }

    private fun uploadPending(api: Api) {
        for (item in Store.all().filter { it.pending }) {
            val body = JSONObject().put("lat", item.lat).put("lng", item.lng).put("clientId", item.id)
            try {
                val res = api.call("POST", "/api/dock/items", body)
                Store.put(Item.fromJson(res.getJSONObject("item")))
            } catch (e: Api.HttpError) {
                // Server refuses this one for good (4xx): keep it on the car only,
                // don't let it block every later sync. Token errors still stop the poll.
                if (e.code == 401 || e.code >= 500) throw e
                Store.put(item.copy(pending = false, locatedBy = "gps"))
            }
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
        uploadSoon()
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
