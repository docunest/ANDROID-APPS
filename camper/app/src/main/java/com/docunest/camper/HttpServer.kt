package com.docunest.camper

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server: the bundled UI (assets/web) plus the JSON API the UI uses.
 *   GET  /api/state  -> controller snapshot
 *   POST /api/cmd    -> controller command
 * Serves the Z1's own kiosk (127.0.0.1) and phones on the camper Wi-Fi.
 */
class HttpServer(private val assets: AssetManager, private val controller: Controller, private val port: Int = 8080) {
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: continue
                pool.execute { handle(c) }
            }
        }, "camper-http").apply { isDaemon = true }.start()
        Log.i(Controller.TAG, "UI on port $port")
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(c: Socket) = c.use {
        runCatching {
            c.soTimeout = 15000
            val input = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(input) ?: return@runCatching
            val (method, rawPath) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
            var length = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }
            val path = rawPath.substringBefore("?")
            val out = c.getOutputStream()
            when {
                method == "GET" && path == "/api/state" -> send(out, 200, "application/json", controller.snapshot().toString().toByteArray())
                method == "GET" && path == "/api/saved" -> send(out, 200, "application/json", controller.saved().toByteArray())
                method == "POST" && path == "/api/cmd" -> {
                    // 127.0.0.1 = the wall screen itself, anything else = a phone on the camper Wi-Fi
                    Log.i(Controller.TAG, "command from ${c.inetAddress.hostAddress}")
                    val r = runCatching { controller.command(JSONObject(String(body, Charsets.UTF_8))) }
                        .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.toString()) }
                    send(out, 200, "application/json", r.toString().toByteArray())
                }
                method == "GET" -> {
                    val name = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
                    if (name.contains("..")) return@runCatching send(out, 404, "text/plain", "not found".toByteArray())
                    val bytes = runCatching { assets.open("web/$name").use { it.readBytes() } }.getOrNull()
                    if (bytes == null) send(out, 404, "text/plain", "not found".toByteArray())
                    else send(out, 200, mime(name), bytes)
                }
                else -> send(out, 405, "text/plain", "method not allowed".toByteArray())
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val x = input.read()
            if (x < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(x)
            if (b.size() > 8192) return null
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = mapOf(200 to "OK", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "Error"
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mime(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
