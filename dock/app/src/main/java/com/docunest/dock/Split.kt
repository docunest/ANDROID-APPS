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
 * Google Maps never opens full screen over Waze: every Google Maps launch
 * in the dock goes through here, and when split screen can't be made it
 * says so instead of opening Maps normally. From one of our own screens,
 * that screen closes first so Waze (behind it) gets the other half.
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
        if (context is android.app.Activity) {
            // Our panel must not be the half next to Maps: close it, Waze is behind it.
            context.finish()
            main.postDelayed({ Thread { run(app, uri) }.start() }, 600)
        } else {
            Thread { run(app, uri) }.start()
        }
    }

    /** Turn-by-turn in Google Maps, beside Waze. */
    fun navigate(context: Context, uri: String) = openMaps(context, uri)

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
        // Each method is checked against the real window state afterwards: "it ran" isn't "it split".
        val tries: List<Pair<String, () -> Boolean>> = when (method) {
            A11Y -> listOf("a11y" to { viaA11y(context, intent) })
            ROOT -> listOf("root-task" to { viaRootTask(context, intent) }, "root-am" to { viaRoot(context, uri) })
            ADJACENT -> listOf("adjacent" to { splitNow(context) == true && adjacent(context, intent) })
            else -> listOf(
                "already split" to { splitNow(context) == true && adjacent(context, intent) },
                "root-task" to { viaRootTask(context, intent) },
                "root-am" to { viaRoot(context, uri) },
                "a11y" to { viaA11y(context, intent) },
            )
        }
        for ((name, attempt) in tries) {
            val did = runCatching { attempt() }.getOrElse { DebugLog.log("SPLIT", "$name threw ${it.javaClass.simpleName}: ${it.message}"); false }
            if (!did) {
                DebugLog.log("SPLIT", "$name: didn't run")
                continue
            }
            Thread.sleep(1500)
            val state = windowModes()
            val ok = state?.let { SplitState.mapsBesideWaze(it) } ?: SplitService.instance?.inSplit()
            DebugLog.log("SPLIT", "$name: ${if (ok == true) "✓ Maps beside Waze" else "✗ not split"} — ${state?.let { SplitState.describe(it) } ?: "no root to check (divider ${SplitService.instance?.inSplit()})"}")
            if (ok == true) return
            if (ok == null) return // can't check (no root, no accessibility): trust it
        }
        // Never leave Google Maps full screen over Waze.
        val state = windowModes()
        if (state?.entries?.firstOrNull { it.key.startsWith(Apps.GMAPS) }?.value == "fullscreen") {
            context.packageManager.getLaunchIntentForPackage(Apps.WAZE)?.let { w ->
                main.post { runCatching { context.startActivity(w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
        }
        DebugLog.log("SPLIT", "all methods failed ($method) — Waze brought back")
        toast(context, "Split screen didn't start (method: $method), so Google Maps wasn't left over Waze. " +
            "Put Waze in split screen by hand (recent apps → Waze icon → Split screen), then try again.")
    }

    /** Waze / Maps windowing modes via root dumpsys (null without root). */
    private fun windowModes(): Map<String, String>? =
        Root.output("dumpsys activity activities | grep -E 'Stack #|TaskRecord'")?.let { SplitState.parse(it) }

    /** Split screen is on with Waze in it (null = can't tell). */
    private fun splitNow(context: Context): Boolean? =
        windowModes()?.entries?.firstOrNull { it.key.startsWith(Apps.WAZE) }?.value?.startsWith("split-screen")
            ?: SplitService.instance?.inSplit()

    /**
     * Root: put Waze's own task into the split primary half (what System UI does by hand — [RootSplit] via
     * app_process through the unit's adb), then open Maps adjacent, i.e. in the other half.
     */
    private fun viaRootTask(context: Context, intent: Intent): Boolean {
        val apk = context.applicationInfo.sourceDir
        var r = Root.output("CLASSPATH=$apk app_process /system/bin ${RootSplit::class.java.name} primary ${Apps.WAZE} ${if (Prefs(context).wazeOnRight) "right" else "left"}")?.trim()?.lines()?.lastOrNull()
        if (r?.startsWith("missing") == true) {
            // Waze isn't running: start it, then split it.
            context.packageManager.getLaunchIntentForPackage(Apps.WAZE)?.let { w -> onMain { runCatching { context.startActivity(w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
            Thread.sleep(2500)
            r = Root.output("CLASSPATH=$apk app_process /system/bin ${RootSplit::class.java.name} primary ${Apps.WAZE} ${if (Prefs(context).wazeOnRight) "right" else "left"}")?.trim()?.lines()?.lastOrNull()
        }
        DebugLog.log("SPLIT", "root-task: Waze → ${if (Prefs(context).wazeOnRight) "right" else "left"} half: ${r ?: "root refused"}")
        if (r?.startsWith("ok") != true) return false
        Thread.sleep(800)
        return adjacent(context, intent)
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
            Thread.sleep(1200) // let Waze settle into its half
            // Some firmwares accept the toggle and do nothing: opening "adjacent" then means full screen.
            if (!svc.inSplit()) {
                DebugLog.log("SPLIT", "a11y: toggle pressed but no split divider appeared")
                return false
            }
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
