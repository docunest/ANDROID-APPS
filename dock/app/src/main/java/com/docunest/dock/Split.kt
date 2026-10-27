package com.docunest.dock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Google Maps next to Waze in split screen — Waze keeps the route, Maps is
 * for looking (a place the partner sent, a fuel stop, a toilet).
 *
 * Methods (⚙ → Split screen; "auto" tries them in order):
 *  - a11y:     Android's "toggle split screen" via [SplitService] docks the app
 *              in front (Waze), then Maps launches into the other half.
 *  - root:     `am start --windowingMode 3` (Waze → primary half) then
 *              `--windowingMode 4` (Maps → secondary half). Android 10 shell.
 *  - adjacent: just FLAG_ACTIVITY_LAUNCH_ADJACENT — works only when split
 *              screen is already on.
 * Must be called while Waze is in front (from an overlay, not an activity of ours).
 */
object Split {
    const val AUTO = "auto"
    const val A11Y = "a11y"
    const val ROOT = "root"
    const val ADJACENT = "adjacent"
    val METHODS = listOf(AUTO, A11Y, ROOT, ADJACENT)

    private val main = Handler(Looper.getMainLooper())

    /** Google Maps beside Waze: a place (preview), a search, or just the map. */
    fun openMaps(context: Context, uri: String? = null) {
        val app = context.applicationContext
        Thread { run(app, uri) }.start()
    }

    /** The place by name + address (Maps then shows its card, photos, reviews). */
    fun preview(context: Context, item: Item) {
        val q = Nav.wazeQuery(item.title, item.address, item.lat, item.lng) ?: return openMaps(context)
        openMaps(context, "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q))
    }

    /** "fuel near me" etc. — geo:0,0 means "around where I am". */
    fun nearby(context: Context, query: String) = openMaps(context, "geo:0,0?q=" + Uri.encode(query))

    private fun mapsIntent(context: Context, uri: String?): Intent? =
        (if (uri == null) context.packageManager.getLaunchIntentForPackage(Apps.GMAPS)
        else Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(Apps.GMAPS))

    private fun run(context: Context, uri: String?) {
        val intent = mapsIntent(context, uri) ?: run {
            toast(context, "Google Maps is not installed")
            return
        }
        val method = Prefs(context).splitMethod
        val ok = when (method) {
            A11Y -> viaA11y(context, intent)
            ROOT -> viaRoot(context, uri)
            ADJACENT -> adjacent(context, intent)
            else -> viaA11y(context, intent) || viaRoot(context, uri) || adjacent(context, intent)
        }
        if (!ok) {
            toast(context, "Split screen didn't start (method: $method) — opening Maps normally")
            main.post { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }

    private fun viaA11y(context: Context, intent: Intent): Boolean {
        var svc = SplitService.instance
        if (svc == null && Root.enableSplitService(context)) {
            Thread.sleep(1500)
            svc = SplitService.instance
        }
        if (svc == null) return false
        if (!svc.inSplit()) {
            var toggled = false
            onMain { toggled = svc.toggleSplit() }
            if (!toggled) return false
            Thread.sleep(900) // let Waze settle into its half
        }
        return adjacent(context, intent)
    }

    private fun viaRoot(context: Context, uri: String?): Boolean {
        val waze = context.packageManager.getLaunchIntentForPackage(Apps.WAZE)?.component?.flattenToShortString() ?: return false
        val maps = if (uri == null) {
            "-n " + (context.packageManager.getLaunchIntentForPackage(Apps.GMAPS)?.component?.flattenToShortString() ?: return false)
        } else {
            "-a android.intent.action.VIEW -d '$uri' ${Apps.GMAPS}"
        }
        return Root.run(
            "am start --windowingMode 3 -n $waze",
            "sleep 1",
            "am start --windowingMode 4 $maps",
        )
    }

    private fun adjacent(context: Context, intent: Intent): Boolean {
        var ok = false
        onMain {
            ok = runCatching {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
            }.isSuccess
        }
        return ok
    }

    /** Run on the main thread and wait for it (we're on a worker thread). */
    private fun onMain(block: () -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    /** Big, glanceable detour searches (Google searches these well in ES/PT). */
    val DETOURS = listOf(
        "⛽" to "Fuel" to "gas station",
        "🚻" to "Toilet" to "public toilet",
        "☕" to "Café" to "cafe",
        "🛒" to "Groceries" to "supermarket",
        "🅿️" to "Parking" to "parking",
        "💊" to "Pharmacy" to "pharmacy",
        "🚐" to "Camper area" to "área de autocaravanas",
    ).map { (a, q) -> Triple(a.first, a.second, q) }
}
