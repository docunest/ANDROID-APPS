package com.docunest.dock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

/** Start turn-by-turn to a place — Waze by default, Google Maps on request. */
object Nav {
    fun go(context: Context, item: Item, app: String = Prefs(context).defaultNav) {
        val ok = start(context, app, item.lat, item.lng, item.title, item.resolvedUrl ?: item.sourceUrl)
        if (ok) Sync.patch(item.id, JSONObject().put("opened", true))
    }

    fun goTo(context: Context, lat: Double, lng: Double, app: String = Prefs(context).defaultNav) {
        start(context, app, lat, lng, null, null)
    }

    private fun start(context: Context, app: String, lat: Double?, lng: Double?, title: String?, url: String?): Boolean {
        val q = Uri.encode(title ?: "")
        val intents = mutableListOf<Intent>()
        if (app == Prefs.NAV_GMAPS) {
            if (lat != null && lng != null) intents += view("google.navigation:q=$lat,$lng", Apps.GMAPS)
            else if (url != null) intents += view(url, Apps.GMAPS)
            else if (q.isNotEmpty()) intents += view("google.navigation:q=$q", Apps.GMAPS)
        } else {
            if (lat != null && lng != null) {
                intents += view("waze://?ll=$lat,$lng&navigate=yes", Apps.WAZE)
                intents += view("https://waze.com/ul?ll=$lat,$lng&navigate=yes", Apps.WAZE)
            } else if (q.isNotEmpty()) {
                intents += view("waze://?q=$q&navigate=yes", Apps.WAZE)
                intents += view("https://waze.com/ul?q=$q&navigate=yes", Apps.WAZE)
            }
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

    private fun view(uri: String, pkg: String?) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        if (pkg != null) setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
