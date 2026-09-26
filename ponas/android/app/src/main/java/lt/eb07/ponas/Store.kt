package lt.eb07.ponas

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One game as listed in the catalog or installed on the tablet. */
data class Game(
    val id: String,
    val title: String,
    val color: String,
    val order: Int,
    val icon: String,
    val hash: String,
    val version: String = "1",
    val updated: Long = 0L,
    val size: Long = 0L,
    val enabled: Boolean = true,
    val network: List<String> = emptyList(),
    /** Previous installed version, kept one round so a running game never loses its files. */
    val prev: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("color", color).put("order", order)
        .put("icon", icon).put("hash", hash).put("version", version).put("updated", updated)
        .put("size", size).put("enabled", enabled).put("network", JSONArray(network))
        .apply { if (prev != null) put("prev", prev) }

    companion object {
        fun fromJson(o: JSONObject): Game {
            val id = o.getString("id")
            val net = o.optJSONArray("network")
            return Game(
                id = id,
                title = o.optString("title", id),
                color = o.optString("color", "#4CAF50"),
                order = o.optInt("order", 100),
                icon = o.optString("icon", "icon.png"),
                hash = o.getString("hash"),
                version = o.optString("version", "1"),
                updated = o.optLong("updated", 0L),
                size = o.optLong("size", 0L),
                enabled = o.optBoolean("enabled", true),
                network = if (net == null) emptyList() else (0 until net.length()).map { net.optString(it) },
                prev = if (o.has("prev")) o.optString("prev") else null,
            )
        }
    }
}

/**
 * Local state (main process only).
 *  - installed.json: games the parent chose, with the current version of each (the "pointer";
 *    rewritten atomically only after a new version is completely on disk).
 *  - catalog.json:   last good manifest.json from GitHub Pages, for the install screen when offline.
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
    private fun catalogFile(ctx: Context) = File(ctx.filesDir, "catalog.json")

    /** Installed games whose files are present, sorted for the home screen. */
    @Synchronized
    fun loadInstalled(ctx: Context): List<Game> {
        val f = installedFile(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length())
                .map { Game.fromJson(arr.getJSONObject(it)) }
                .filter { File(gameDir(ctx, it.id, it.hash), "index.html").isFile }
                .sortedWith(compareBy<Game>({ it.order }, { it.title }))
        } catch (e: Exception) {
            Log.w(TAG, "installed.json unreadable", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveInstalled(ctx: Context, games: List<Game>) {
        val arr = JSONArray()
        for (g in games) arr.put(g.toJson())
        atomicWrite(ctx, installedFile(ctx), arr.toString())
    }

    /** Catalog entries (enabled or not) with their raw JSON, which holds the file list. */
    @Synchronized
    fun loadCatalog(ctx: Context): List<Pair<Game, JSONObject>> {
        val f = catalogFile(ctx)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("games") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                try { Game.fromJson(o) to o } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog.json unreadable", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveCatalog(ctx: Context, manifestText: String) {
        atomicWrite(ctx, catalogFile(ctx), manifestText)
    }

    private fun atomicWrite(ctx: Context, target: File, text: String) {
        val tmp = File(ctx.filesDir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not write ${target.name}")
        }
    }

    fun pin(ctx: Context): String = prefs(ctx).getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY_PIN, pin).apply()
    }
}
