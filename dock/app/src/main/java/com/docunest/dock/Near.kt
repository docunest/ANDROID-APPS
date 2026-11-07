package com.docunest.dock

import android.content.Context
import android.location.Location

/** Distances from where the car is, and which places are worth showing up front. */
object Near {
    /** Up front: within this distance… */
    const val NEAR_M = 300_000f
    /** …or sent / driven to this recently (the next destination can be far ahead). */
    const val FRESH_MS = 3 * 24 * 60 * 60_000L

    /**
     * Where the car is: this session's fix, Android's last known, else the
     * position the GPS doctor saved before the last cold start (Android's own
     * is wiped by it — and "unknown" used to put every place on the map).
     */
    fun here(context: Context): Location? = DockActivity.lastLocation ?: Locator.lastKnownAny(context)
        ?: ParkedPosition.saved(context)?.let { s -> Location("saved").apply { latitude = s.lat; longitude = s.lng } }

    fun meters(here: Location?, item: Item): Float? {
        if (here == null || !item.hasPoint) return null
        val d = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, item.lat!!, item.lng!!, d)
        return d[0]
    }

    private fun bearing(here: Location?, item: Item): Float? {
        if (here == null || !item.hasPoint) return null
        val r = FloatArray(2)
        Location.distanceBetween(here.latitude, here.longitude, item.lat!!, item.lng!!, r)
        return r[1]
    }

    /** "↗ ~23 km" — the pins can be approximate and so is our fix. */
    fun label(here: Location?, item: Item): String? = meters(here, item)?.let { d ->
        val arrow = if (d >= 300f) bearing(here, item)?.let { arrow(it) + " " } ?: "" else ""
        arrow + "~" + km(d)
    }

    fun isMain(here: Location?, item: Item, now: Long = System.currentTimeMillis()): Boolean =
        keep(meters(here, item), now - item.recency, item.pinned || item.isNew || item.pending)

    /**
     * Up front: pinned / new / not synced always; otherwise within 300 km
     * *or* sent / driven to in the last 3 days. Unknown distance (no pin)
     * counts as far: only if fresh.
     */
    fun keep(distanceM: Float?, ageMs: Long, always: Boolean): Boolean =
        always || ageMs < FRESH_MS || (distanceM != null && distanceM <= NEAR_M)

    /** North-up arrow for a compass bearing (degrees, −180…360). */
    fun arrow(bearing: Float): String {
        val i = ((((bearing % 360f) + 360f) % 360f + 22.5f) / 45f).toInt() % 8
        return "↑↗→↘↓↙←↖"[i].toString()
    }

    /**
     * The town from a Google-style address, to tell places apart at a
     * glance: the part after the postcode ("39180 Noja" → Noja, "2450-065
     * Nazaré" → Nazaré), else the part before the country. Null when it's
     * already in the name or there's nothing usable.
     */
    fun town(address: String?, title: String = ""): String? {
        val parts = address?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        if (parts.size < 2) return null
        val postcode = Regex("^\\d{4,5}(-\\d{3})?\\s+(.+)$")
        val town = parts.firstNotNullOfOrNull { postcode.find(it)?.groupValues?.get(2) }
            ?: parts[parts.size - 2].takeIf { !it.any(Char::isDigit) }
            ?: return null
        return town.takeIf { it.length in 2..40 && !title.contains(it, ignoreCase = true) }
    }
}
