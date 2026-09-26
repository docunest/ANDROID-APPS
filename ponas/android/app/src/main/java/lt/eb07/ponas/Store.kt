package lt.eb07.ponas

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One installed game, as recorded in filesDir/installed.json. */
data class Game(
    val id: String,
    val title: String,
    val color: String,
    val order: Int,
    val icon: String,
    val hash: String,
)

/**
 * Local state. installed.json is the "pointer" to the current version of each game:
 * it is only rewritten (atomically, via rename) after a new version is fully on disk.
 */
object Store {
    private const val TAG = "Ponas"
    private const val PREFS = "ponas"

    const val KEY_PIN = "pin"
    const val KEY_LAST_ATTEMPT = "sync_last_attempt"
    const val KEY_LAST_OK = "sync_last_ok"
    const val KEY_LAST_ERROR = "sync_last_error"
    const val KEY_LAST_ERROR_AT = "sync_last_error_at"
    const val KEY_MANIFEST_VERSION = "manifest_version"

    const val DEFAULT_PIN = "1234"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun gamesRoot(ctx: Context): File = File(ctx.filesDir, "games")

    fun gameDir(ctx: Context, id: String, hash: String): File =
        File(File(gamesRoot(ctx), id), hash)

    private fun installedFile(ctx: Context) = File(ctx.filesDir, "installed.json")

    /** Installed games whose files are actually present, sorted for the home screen. */
    @Synchronized
    fun loadInstalled(ctx: Context): List<Game> {
        val f = installedFile(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Game>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = Game(
                    id = o.getString("id"),
                    title = o.optString("title", o.getString("id")),
                    color = o.optString("color", "#4CAF50"),
                    order = o.optInt("order", 100),
                    icon = o.optString("icon", "icon.png"),
                    hash = o.getString("hash"),
                )
                if (File(gameDir(ctx, g.id, g.hash), "index.html").isFile) out.add(g)
            }
            out.sortedWith(compareBy<Game>({ it.order }, { it.title }))
        } catch (e: Exception) {
            Log.w(TAG, "installed.json unreadable", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveInstalled(ctx: Context, games: List<Game>) {
        val arr = JSONArray()
        for (g in games) {
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("title", g.title)
                    .put("color", g.color)
                    .put("order", g.order)
                    .put("icon", g.icon)
                    .put("hash", g.hash)
            )
        }
        val target = installedFile(ctx)
        val tmp = File(ctx.filesDir, "installed.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not write installed.json")
        }
    }

    fun pin(ctx: Context): String = prefs(ctx).getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY_PIN, pin).apply()
    }
}
