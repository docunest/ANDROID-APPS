package lt.eb07.ponas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Result of an update install (see [Updater]). Success needs nothing: the new build starts on the next Home press. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                Log.i("PonasUpdate", "install waits for a tap")
                Updater.installResult(context, "Update waits for a tap")
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("PonasUpdate", "install ok")
            else -> {
                Log.w("PonasUpdate", "install failed: $message")
                Updater.installResult(context, "Install failed: $message")
            }
        }
    }
}
