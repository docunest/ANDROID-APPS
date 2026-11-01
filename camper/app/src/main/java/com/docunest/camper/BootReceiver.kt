package com.docunest.camper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts control after boot, after an app update and when power comes back. The kiosk is the HOME app, so Android opens it itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CamperService.start(context)
    }
}
