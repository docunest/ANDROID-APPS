package com.docunest.dock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

/**
 * Start turn-by-turn to a place — Waze by default, Google Maps on request.
 *
 * Waze: a name search with navigate=yes ("https://waze.com/ul?q=<name, address>").
 * Field-tested 2026-10-27 on the owner's devices (Waze test #9): the only
 * form that starts a route *and* shows the place's name; every ll= / waze://
 * / geo: variant opened Waze without a usable destination.
 */
object Nav {
    fun go(context: Context, item: Item, app: String = Prefs(context).defaultNav) {
        val ok = start(context, app, item.lat, item.lng, item.title, item.address, item.resolvedUrl ?: item.sourceUrl)
        if (ok) Sync.patch(item.id, JSONObject().put("opened", true))
    }

    /** First intent that would navigate there with the usual app (for a notification button). */
    fun intentFor(context: Context, item: Item): Intent? {
        val pkg = if (Prefs(context).defaultNav == Prefs.NAV_GMAPS) Apps.GMAPS else Apps.WAZE
        val uri = when {
            pkg == Apps.WAZE -> wazeLink(item.title, item.address, item.lat, item.lng) ?: return null
            item.lat != null && item.lng != null -> "google.navigation:q=${coord(item.lat)},${coord(item.lng)}"
            else -> "google.navigation:q=${Uri.encode(item.title)}"
        }
        val i = view(uri, pkg)
        return if (i.resolveActivity(context.packageManager) != null) i else view(item.resolvedUrl ?: item.sourceUrl ?: return null, null)
    }

    fun goTo(context: Context, lat: Double, lng: Double, app: String = Prefs(context).defaultNav) {
        start(context, app, lat, lng, null, null, null)
    }

    /** "Name, address" for Waze to search; plain coordinates when there's no name. */
    fun wazeQuery(title: String?, address: String?, lat: Double?, lng: Double?): String? {
        // A title that is just "41.38, 2.17" adds nothing to the address.
        val name = title?.trim()?.takeIf { it.isNotEmpty() && !Regex("""^-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?$""").matches(it) }
        val text = listOfNotNull(name, address?.trim()?.takeIf { it.isNotEmpty() && it != name }).joinToString(", ")
        return text.ifEmpty { if (lat != null && lng != null) "${coord(lat)}, ${coord(lng)}" else null }
    }

    private fun wazeLink(title: String?, address: String?, lat: Double?, lng: Double?): String? =
        wazeQuery(title, address, lat, lng)?.let { "https://waze.com/ul?q=${Uri.encode(it)}&navigate=yes" }

    private fun start(context: Context, app: String, lat: Double?, lng: Double?, title: String?, address: String?, url: String?): Boolean {
        val q = Uri.encode(title ?: "")
        val intents = mutableListOf<Intent>()
        if (app == Prefs.NAV_GMAPS) {
            if (lat != null && lng != null) intents += view("google.navigation:q=${coord(lat)},${coord(lng)}", Apps.GMAPS)
            else if (url != null) intents += view(url, Apps.GMAPS)
            else if (q.isNotEmpty()) intents += view("google.navigation:q=$q", Apps.GMAPS)
        } else {
            wazeLink(title, address, lat, lng)?.let { intents += view(it, Apps.WAZE) }
        }
        // App missing or no coordinates: any map app, then the original link.
        if (lat != null && lng != null) intents += view("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(title ?: "")})", null)
        if (url != null) intents += view(url, null)
        for (i in intents) {
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        Toast.makeText(context, "No navigation app could open this place", Toast.LENGTH_LONG).show()
        return false
    }

    // Plain NEW_TASK: RESET_TASK_IF_NEEDED (a launcher flag) can make Android just
    // bring a running Waze to the front without handing it the destination.
    private fun view(uri: String, pkg: String?) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        if (pkg != null) setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Plain decimal, never scientific notation, dot separator whatever the locale. */
    private fun coord(v: Double) = String.format(java.util.Locale.US, "%.6f", v)
}
