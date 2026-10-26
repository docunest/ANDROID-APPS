package com.docunest.dock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ignition on (boot / quick-boot / power) or just self-updated: bring the dock back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || prefs.startOnBoot) DockService.start(context)
    }
}
