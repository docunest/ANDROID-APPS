package com.docunest.dock

/**
 * The Wi-Fi doctor's three parallel checks and what they mean together
 * (owner's handoff 2026-11-13: "test both the link to the iPhone and real
 * internet, in parallel"):
 *  - hotspot: does the iPhone (172.20.10.1) answer a ping;
 *  - DNS: a fresh lookup of a random, never-cached name, straight to the
 *    hotspot's DNS server — the iPhone can only answer it (even "no such
 *    name") through its mobile data, never from its cache;
 *  - HTTP: generate_204 over Wi-Fi, expecting 204 (≤ 3 s = working, slower = slow).
 * Pure, unit-tested.
 */
object NetVerdict {
    enum class Level { WORKS, SLOW, NO_DATA, DNS, BLOCKED, STALLED, UNKNOWN }

    /** null = that check couldn't run (no gateway / DNS server known). Times in ms; HTTP [httpCode] 204 = good. */
    data class Checks(val hotspotMs: Int?, val hotspotOk: Boolean?, val dnsMs: Int?, val dnsOk: Boolean?, val httpMs: Int?, val httpCode: Int?)

    data class Verdict(val level: Level, val text: String) {
        val internet: Boolean? get() = when (level) {
            Level.WORKS, Level.SLOW, Level.DNS -> true
            Level.NO_DATA, Level.BLOCKED, Level.STALLED -> false
            Level.UNKNOWN -> null
        }
    }

    const val SLOW_MS = 3_000

    fun classify(c: Checks): Verdict {
        val http = c.httpCode == 204
        return when {
            c.hotspotOk == false && !http ->
                Verdict(Level.STALLED, "Hotspot stalled: the iPhone isn't answering on Wi-Fi (no ping, no internet)")
            http && (c.httpMs ?: 0) > SLOW_MS ->
                Verdict(Level.SLOW, "Internet works but slow: ${(c.httpMs!! + 50) / 100 / 10.0} s for a tiny request")
            http && c.dnsOk == false ->
                Verdict(Level.DNS, "Internet works, but the hotspot's DNS didn't answer (apps may stall on new names)")
            http -> Verdict(Level.WORKS, "Internet works (${c.httpMs} ms)" + (c.hotspotMs?.let { " · iPhone answers in $it ms" } ?: ""))
            c.dnsOk == false && c.hotspotOk != false ->
                Verdict(Level.NO_DATA, "iPhone answers, but it has no mobile data (tunnel / no coverage): DNS and web both fail")
            c.dnsOk == true ->
                Verdict(Level.BLOCKED, "iPhone answers and DNS works, but the web request failed (code ${c.httpCode ?: "none"}) — very slow or blocked")
            c.hotspotOk == null && c.dnsOk == null && c.httpCode == null -> Verdict(Level.UNKNOWN, "Not checked yet")
            else -> Verdict(Level.NO_DATA, "No internet behind the hotspot")
        }
    }

    // --- A raw DNS query (so the answer can't come from Android's cache) ------------------

    /** Query for an A record of [name] with [id]. */
    fun dnsQuery(id: Int, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8 and 0xff); out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // standard query, recursion desired
        out.write(0); out.write(1) // 1 question
        repeat(6) { out.write(0) } // no answers / authority / additional
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1) // type A
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    /**
     * Was this a real answer to our query? Any response code except SERVFAIL (2) / REFUSED (5) counts —
     * "no such name" (3) for a random name proves the query went out to the internet and back.
     */
    fun dnsAnswered(id: Int, reply: ByteArray, len: Int = reply.size): Boolean {
        if (len < 12) return false
        val rid = (reply[0].toInt() and 0xff shl 8) or (reply[1].toInt() and 0xff)
        val isResponse = reply[2].toInt() and 0x80 != 0
        val rcode = reply[3].toInt() and 0x0f
        return rid == id && isResponse && rcode != 2 && rcode != 5
    }
}
