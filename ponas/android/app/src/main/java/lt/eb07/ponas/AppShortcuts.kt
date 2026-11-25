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

    /** The photo viewer is on the home screen until the parent changes the list (owner 2026-11-25). */
    private val DEFAULTS = listOf("org.fossify.gallery")

    data class App(val pkg: String, val label: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chosen(c: Context): List<String> {
        val raw = prefs(c).getString(KEY, null) ?: return DEFAULTS
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(DEFAULTS)
    }

    fun save(c: Context, pkgs: List<String>) = prefs(c).edit().putString(KEY, JSONArray(pkgs).toString()).apply()

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
