package com.docunest.dock

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug log for the GPS / Wi-Fi doctors and the status ring: what each
 * measurement said and every state change, with the raw inputs — so a
 * screenshot of the Debug screen (or the copy uploaded to MapPal) shows why
 * the ring said what it said. Changes are logged, not every tick ([change]);
 * a snapshot of everything once a minute.
 *
 * Kept in memory (last [MEM] lines) and in files (2 × 2 MB). Uploaded to
 * MapPal `/api/dock/logs` every 2 min when switched on (server keeps ≤ 50 MB).
 */
object DebugLog {
    private const val MEM = 5000
    private const val FILE_MAX = 2_000_000L
    private const val UPLOAD_BATCH = 500

    data class Line(val seq: Long, val at: Long, val tag: String, val msg: String) {
        fun text(): String = "${SimpleDateFormat("dd HH:mm:ss", Locale.US).format(Date(at))} $tag  $msg"
    }

    private val mem = ArrayDeque<Line>()
    private val last = HashMap<String, String>()
    private var seq = 0L
    private var app: Context? = null
    @Volatile var uploadStatus = "not sent yet"
        private set
    /** Current values for the top of the Debug screen ("GPS inputs" → "watch 1s/±21 · …"). */
    val status = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        seq = sp()?.getLong("debugSeq", 0L) ?: 0L
        log("APP", "start · build ${BuildConfig.VERSION_CODE} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        // The previous run's crash (saved by the handler below as the process died) → into this run's uploaded log.
        sp()?.getString("lastCrash", null)?.let { c ->
            c.lines().take(60).forEach { log("CRASH", it) }
            sp()?.edit()?.remove("lastCrash")?.apply()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = "dock crashed in thread ${t.name} (build ${BuildConfig.VERSION_CODE}):\n" + android.util.Log.getStackTraceString(e)
                sp()?.edit()?.putString("lastCrash", trace.take(8000))?.commit() // commit: the process is about to die
                log("CRASH", trace.lines().first())
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun sp() = app?.getSharedPreferences("dock", Context.MODE_PRIVATE)

    var upload: Boolean
        get() = sp()?.getBoolean("debugUpload", true) ?: true
        set(v) { sp()?.edit()?.putBoolean("debugUpload", v)?.apply() }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = Line(++seq, System.currentTimeMillis(), tag, msg)
        mem.addLast(line)
        while (mem.size > MEM) mem.removeFirst()
        if (seq % 50 == 0L) sp()?.edit()?.putLong("debugSeq", seq)?.apply()
        val ctx = app ?: return
        runCatching {
            val dir = File(ctx.filesDir, "debug").apply { mkdirs() }
            val f = File(dir, "log.txt")
            if (f.length() > FILE_MAX) f.renameTo(File(dir, "log.old.txt"))
            f.appendText(line.text() + "\n")
        }
    }

    /** Log only when [value] differs from the last one logged under [key]. Returns true if it changed. */
    @Synchronized
    fun change(tag: String, key: String, value: String, detail: String = ""): Boolean {
        val k = "$tag/$key"
        val before = last[k]
        if (before == value) return false
        last[k] = value
        log(tag, "$key: ${before ?: "—"} → $value" + if (detail.isNotEmpty()) "  [$detail]" else "")
        return true
    }

    /** Newest first; [tag] null = all. */
    @Synchronized
    fun lines(tag: String? = null, n: Int = 400): List<Line> =
        mem.asReversed().asSequence().filter { tag == null || it.tag == tag }.take(n).toList()

    /** POST the lines not sent yet. Worker thread. */
    fun uploadNow(context: Context) {
        if (!upload) return
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
        val from = sp.getLong("debugUploaded", 0L)
        val batch = synchronized(this) { mem.filter { it.seq > from }.take(UPLOAD_BATCH) }
        if (batch.isEmpty()) return
        val body = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · build ${BuildConfig.VERSION_CODE}")
            .put("lines", JSONArray().apply {
                batch.forEach { put(JSONObject().put("seq", it.seq).put("at", it.at).put("tag", it.tag).put("msg", it.msg)) }
            })
        val c = URL(prefs.serverUrl.trimEnd('/') + "/api/dock/logs").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${prefs.token}")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code in 200..299) {
                sp.edit().putLong("debugUploaded", batch.last().seq).apply()
                uploadStatus = "sent ${batch.size} lines at ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
            } else {
                uploadStatus = "server said $code (MapPal not updated yet?)"
            }
        } catch (e: Exception) {
            uploadStatus = "not sent: ${e.javaClass.simpleName}"
        } finally {
            c.disconnect()
        }
    }
}
