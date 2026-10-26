package com.docunest.dock

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** MapPal /api/dock calls. Blocking — call off the main thread. */
class Api(context: Context) {
    private val prefs = Prefs(context)

    class HttpError(val code: Int, message: String) : IOException(message)

    fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val base = prefs.serverUrl.trimEnd('/')
        if (base.isBlank()) throw IOException("Server address not set (Settings)")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 40_000
            conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).opt("error")?.toString() }.getOrNull() ?: text.take(200)
                throw HttpError(code, if (code == 401) "Token rejected — check Settings" else "Server $code: $msg")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
