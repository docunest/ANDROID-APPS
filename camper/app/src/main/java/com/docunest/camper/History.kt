package com.docunest.camper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * History for the remote page: a sample every 5 min (temperatures, device states, Victron) and an event for every
 * on/off change and every command. Kept on the phone (7 days of samples, last 2000 events) so nothing is lost while
 * the internet is down; RemoteSync uploads whatever MapPal hasn't acknowledged yet.
 * Rows are JSON with "t" = epoch seconds; one JSON object per line in samples.jsonl / events.jsonl.
 */
class History(dir: File) {
    companion object {
        const val SAMPLE_S = 300L
        private const val MAX_SAMPLES = 7 * 24 * 12
        private const val MAX_EVENTS = 2000
    }

    private val sampleFile = File(dir, "samples.jsonl")
    private val eventFile = File(dir, "events.jsonl")
    private val samples = load(sampleFile)
    private val events = load(eventFile)

    private fun load(f: File): ArrayList<JSONObject> =
        ArrayList(runCatching { f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() } }.getOrDefault(emptyList()))

    private fun append(list: ArrayList<JSONObject>, f: File, row: JSONObject, max: Int) = synchronized(this) {
        list.add(row)
        if (list.size > max + max / 10) {
            // trim in batches and rewrite the file
            val keep = list.takeLast(max)
            list.clear(); list.addAll(keep)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(list.joinToString("") { "$it\n" })
            tmp.renameTo(f)
        } else runCatching { f.appendText("$row\n") }
    }

    /** True when the 5-min slot of [nowS] has no sample yet. */
    fun sampleDue(nowS: Long): Boolean = synchronized(this) {
        val last = samples.lastOrNull()?.optLong("t") ?: 0L
        nowS / SAMPLE_S != last / SAMPLE_S
    }

    fun sample(row: JSONObject) = append(samples, sampleFile, row, MAX_SAMPLES)

    fun event(device: String, what: String, value: Any?, by: String? = null) = append(events, eventFile,
        JSONObject().put("t", System.currentTimeMillis() / 1000).put("device", device).put("what", what)
            .put("value", value ?: JSONObject.NULL).put("by", by ?: JSONObject.NULL), MAX_EVENTS)

    /** Rows newer than [afterT] (oldest first, at most [limit]) for upload. */
    fun samplesAfter(afterT: Long, limit: Int = 300) = after(samples, afterT, limit)
    fun eventsAfter(afterT: Long, limit: Int = 300) = after(events, afterT, limit)

    private fun after(list: List<JSONObject>, afterT: Long, limit: Int): JSONArray = synchronized(this) {
        JSONArray().also { out -> list.asSequence().filter { it.optLong("t") > afterT }.take(limit).forEach { out.put(it) } }
    }
}
