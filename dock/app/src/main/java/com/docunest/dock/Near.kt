package com.docunest.dock

import android.content.Context
import android.location.Location

/** Distances from where the car is, and which places are worth showing up front. */
object Near {
    private const val FAR_M = 150_000f
    private const val OLD_MS = 2 * 24 * 60 * 60_000L

    fun here(context: Context): Location? = DockActivity.lastLocation ?: Locator.lastKnownAny(context)

    fun meters(here: Location?, item: Item): Float? {
        if (here == null || !item.hasPoint) return null
        val d = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, item.lat!!, item.lng!!, d)
        return d[0]
    }

    /** "~23 km" — the pins can be approximate and so is our fix. */
    fun label(here: Location?, item: Item): String? = meters(here, item)?.let { "~" + km(it) }

    /**
     * Up front unless it's both far (> 150 km) and old (sent / last driven
     * to > 2 days ago). New and pinned places always stay; so does anything
     * whose distance we can't tell.
     */
    fun isMain(here: Location?, item: Item, now: Long = System.currentTimeMillis()): Boolean {
        if (item.pinned || item.isNew || item.pending) return true
        val d = meters(here, item) ?: return true
        return !(d > FAR_M && now - item.recency > OLD_MS)
    }
}
