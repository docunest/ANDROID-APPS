package com.docunest.dock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Result of a no-root self-update (see [Updater]). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Straight to Android's "Update?" screen when we may (overlay apps can);
                // the notification is there either way.
                runCatching { context.startActivity(confirm) }
                notify(context, "Dock update ready", "Tap to install the new version.", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // MY_PACKAGE_REPLACED restarts the dock
            else -> notify(
                context, "Dock update failed",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown error", null,
            )
        }
    }

    companion object {
        private const val CHANNEL = "updates"

        fun notify(context: Context, title: String, text: String, tap: Intent?) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_dock)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            if (tap != null) {
                n.setContentIntent(
                    PendingIntent.getActivity(context, 9, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            runCatching { nm.notify(9, n.build()) }
        }
    }
}
