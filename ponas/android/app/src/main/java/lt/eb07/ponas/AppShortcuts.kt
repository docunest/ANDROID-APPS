package lt.eb07.ponas

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray

/**
 * Other Android apps the parent put on the home screen (parent menu → Apps on home screen).
 * Ponas is the tablet's home app, so this is how the child (and the parent) reach them.
 * Stored as package names, in the order chosen; an app that was uninstalled simply drops out.
 */
object AppShortcuts {
    private const val PREFS = "ponas-apps"
    private const val KEY = "packages"

    private const val KEY_DEFAULTS_ROUND = "defaultsRound"

    /** On the home screen until the parent changes the list (owner 2026-11-25): photo viewer, Waze, Google Maps. */
    private val DEFAULTS = listOf("org.fossify.gallery", "com.waze", "com.google.android.apps.maps")

    /** Apps added to an already saved list once, by round, so a later untick sticks. */
    private val ADDED_IN_ROUND = mapOf(1 to listOf("com.waze", "com.google.android.apps.maps"))

    data class App(val pkg: String, val label: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chosen(c: Context): List<String> {
        val p = prefs(c)
        val raw = p.getString(KEY, null) ?: return DEFAULTS
        var list = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(DEFAULTS)
        val done = p.getInt(KEY_DEFAULTS_ROUND, 0)
        val pending = ADDED_IN_ROUND.filterKeys { it > done }
        if (pending.isNotEmpty()) {
            list = list + pending.values.flatten().filter { it !in list }
            p.edit().putString(KEY, JSONArray(list).toString()).putInt(KEY_DEFAULTS_ROUND, pending.keys.max()).apply()
        }
        return list
    }

    fun save(c: Context, pkgs: List<String>) = prefs(c).edit()
        .putString(KEY, JSONArray(pkgs).toString())
        .putInt(KEY_DEFAULTS_ROUND, ADDED_IN_ROUND.keys.max())
        .apply()

    /** Every app with a launcher icon, except Ponas itself, sorted by name. */
    fun launchable(c: Context): List<App> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != c.packageName }
            .distinctBy { it.first }
            .map { App(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    /** The chosen apps that are still installed, in the chosen order. */
    fun onHome(c: Context): List<App> {
        val all = launchable(c).associateBy { it.pkg }
        return chosen(c).mapNotNull { all[it] }
    }

    fun launch(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun icon(c: Context, pkg: String, size: Int): Bitmap? = runCatching {
        val d = c.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        bmp
    }.getOrNull()
}
