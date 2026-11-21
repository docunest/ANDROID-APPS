package com.docunest.dock

import android.app.Activity
import android.os.Bundle

/**
 * "Mini-map" in the head unit's app list (owner 2026-11-17: after Hide, bring it back from the
 * launcher next to YouTube / Google Maps, without the bubble taking screen space meanwhile).
 * Switches the mini-map on and closes at once.
 */
class MiniMapLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).miniMap = true
        DockService.start(this, DockService.ACTION_MINIMAP)
        finish()
    }
}
