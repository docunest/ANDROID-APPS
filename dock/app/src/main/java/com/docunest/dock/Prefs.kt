package com.docunest.dock

import android.content.Context

/** Everything the dock remembers, editable on the settings screen. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    /** MapPal address, e.g. https://mappal.vercel.app */
    var serverUrl: String
        get() = sp.getString("serverUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL
        set(value) = sp.edit().putString("serverUrl", value.trim().trimEnd('/')).apply()

    /** Same value as MapPal's DOCK_INBOX_TOKEN (or its committed default). */
    var token: String
        get() = sp.getString("token", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN
        set(value) = sp.edit().putString("token", value.trim()).apply()

    /** "waze" (95% of drives) or "gmaps". Never asked per drive. */
    var defaultNav: String
        get() = sp.getString("defaultNav", NAV_WAZE) ?: NAV_WAZE
        set(value) = sp.edit().putString("defaultNav", value).apply()

    /** Apps on the dock screen grid, in order. */
    var gridApps: List<String>
        get() = list("gridApps", listOf(Apps.WAZE, Apps.GMAPS, Apps.YOUTUBE, Apps.SPOTIFY))
        set(value) = putList("gridApps", value)

    /** Apps in the press-and-slide menu, in order. */
    var fanApps: List<String>
        get() = list("fanApps", listOf(Apps.YOUTUBE, Apps.GMAPS))
        set(value) = putList("fanApps", value)

    /** Dashcam app — set later in settings; shown on grid and fan once set. */
    var dvrPackage: String
        get() = sp.getString("dvrPackage", "") ?: ""
        set(value) = sp.edit().putString("dvrPackage", value).apply()

    /** How many recent places appear in the press-and-slide menu. */
    var fanLinks: Int
        get() = sp.getInt("fanLinks", 4)
        set(value) = sp.edit().putInt("fanLinks", value).apply()

    var fanBookmarkHere: Boolean
        get() = sp.getBoolean("fanBookmarkHere", true)
        set(value) = sp.edit().putBoolean("fanBookmarkHere", value).apply()

    /** How Google Maps is put next to Waze — see [Split]. */
    var splitMethod: String
        get() = sp.getString("splitMethod", Split.AUTO) ?: Split.AUTO
        set(value) = sp.edit().putString("splitMethod", value).apply()

    var fanNearby: Boolean
        get() = sp.getBoolean("fanNearby", true)
        set(value) = sp.edit().putBoolean("fanNearby", value).apply()

    var bubbleSizeDp: Int
        get() = sp.getInt("bubbleSizeDp", 50)
        set(value) = sp.edit().putInt("bubbleSizeDp", value).apply()

    /** Idle opacity, percent. Fully opaque while touched. */
    var bubbleAlpha: Int
        get() = sp.getInt("bubbleAlpha", 45)
        set(value) = sp.edit().putInt("bubbleAlpha", value).apply()

    var bubbleX: Int
        get() = sp.getInt("bubbleX", 0)
        set(value) = sp.edit().putInt("bubbleX", value).apply()

    var bubbleY: Int
        get() = sp.getInt("bubbleY", 300)
        set(value) = sp.edit().putInt("bubbleY", value).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(value) = sp.edit().putBoolean("startOnBoot", value).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(value) = sp.edit().putBoolean("autoUpdate", value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(value) = sp.edit().putLong("lastUpdateCheck", value).apply()

    var rootGranted: Boolean
        get() = sp.getBoolean("rootGranted", false)
        set(value) = sp.edit().putBoolean("rootGranted", value).apply()

    private fun list(key: String, default: List<String>): List<String> =
        sp.getString(key, null)?.split(',')?.filter { it.isNotBlank() } ?: default

    private fun putList(key: String, value: List<String>) = sp.edit().putString(key, value.joinToString(",")).apply()

    companion object {
        const val NAV_WAZE = "waze"
        const val NAV_GMAPS = "gmaps"
        // MapPal lib/dock/auth.ts DEFAULT_DOCK_TOKEN — keep in sync.
        const val DEFAULT_TOKEN = "b76a9d1d8c4afa58012625e1920abbca"
    }
}
