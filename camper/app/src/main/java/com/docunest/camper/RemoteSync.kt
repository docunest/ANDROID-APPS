package com.docunest.camper

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote control through MapPal (/camper page): pushes the snapshot every ~20 s and right after commands,
 * and long-polls /api/camper/commands. Commands run through the same Controller.command() as the wall screen.
 * Configured by CAMPER_REMOTE_URL + CAMPER_REMOTE_TOKEN in camper.env; off if missing.
 */
class RemoteSync(private val controller: Controller, private val base: String, private val token: String) {
    @Volatile private var running = false
    private val results = JSONArray()
    @Volatile var lastOk = 0L
    @Volatile var lastError: String? = null

    fun start() {
        running = true
        Thread({ pushLoop() }, "camper-remote-push").apply { isDaemon = true }.start()
        Thread({ commandLoop() }, "camper-remote-cmd").apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    private val lock = Object()

    private fun pushLoop() {
        while (running) {
            push()
            synchronized(lock) { runCatching { lock.wait(20_000) } }
        }
    }

    // upload cursors: newest sample/event time MapPal has stored (kept in memory; after a restart MapPal's
    // reply to the first push tells us where it is, and duplicates are ignored there anyway)
    private var sentSample = -1L
    private var sentEvent = -1L

    private fun push() {
        val res = synchronized(results) { JSONArray(results.toString()).also { while (results.length() > 0) results.remove(0) } }
        val h = controller.history
        val samples = if (sentSample >= 0) h.samplesAfter(sentSample) else JSONArray()
        val events = if (sentEvent >= 0) h.eventsAfter(sentEvent) else JSONArray()
        try {
            val r = http("POST", "/api/camper/state", JSONObject().put("state", controller.snapshot()).put("results", res)
                .put("samples", samples).put("events", events), 20_000)
            // MapPal answers with the newest times it has; start from there (also covers its first reply)
            sentSample = r.optLong("lastSample", sentSample.coerceAtLeast(0))
            sentEvent = r.optLong("lastEvent", sentEvent.coerceAtLeast(0))
            lastOk = System.currentTimeMillis(); lastError = null
        } catch (e: Exception) {
            lastError = e.message
            synchronized(results) { for (i in 0 until res.length()) results.put(res.get(i)) } // retry next push
        }
    }

    private fun commandLoop() {
        var backoff = 5_000L
        while (running) {
            try {
                val r = http("GET", "/api/camper/commands?wait=1", null, 40_000)
                val cmds = r.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until cmds.length()) {
                    val c = cmds.getJSONObject(i)
                    Log.i(Controller.TAG, "remote command ${c.optString("id")}")
                    val out = runCatching { controller.command(c.getJSONObject("body"), "remote") }
                        .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                    synchronized(results) {
                        results.put(JSONObject().put("id", c.getString("id")).put("ok", out.optBoolean("ok")).put("error", out.optString("error", "")))
                    }
                }
                if (cmds.length() > 0) synchronized(lock) { lock.notifyAll() } // push the new state + results now
                backoff = 5_000L
            } catch (e: Exception) {
                lastError = e.message
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    private fun http(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        if (code !in 200..299) throw IllegalStateException("remote $path: HTTP $code")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
