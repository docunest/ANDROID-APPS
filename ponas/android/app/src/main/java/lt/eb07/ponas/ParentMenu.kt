package lt.eb07.ponas

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PIN-protected menu for the parent. Plain AlertDialogs: no extra screens, no extra RAM. */
object ParentMenu {

    private const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    fun askPin(home: HomeActivity) {
        if (home.isFinishing) return
        val input = pinField(home, "PIN")
        val dialog = AlertDialog.Builder(home, DIALOG_THEME)
            .setTitle("Parent PIN")
            .setView(padded(home, input))
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == Store.pin(home)) {
                    showMenu(home)
                } else {
                    Toast.makeText(home, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showMenu(home: HomeActivity) {
        val items = arrayOf(
            "Refresh games now",
            "Sync status and last error",
            "Open Android Settings",
            "Open normal launcher",
            "Change PIN",
            "Versions",
        )
        AlertDialog.Builder(home, DIALOG_THEME)
            .setTitle("Ponas – parent menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> refresh(home)
                    1 -> showText(home, "Sync status", statusText(home))
                    2 -> startSafely(home, Intent(Settings.ACTION_SETTINGS))
                    3 -> openLauncher3(home)
                    4 -> changePin(home)
                    5 -> showText(home, "Versions", versionsText(home))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun refresh(home: HomeActivity) {
        home.reportNextSyncResult = true
        if (SyncManager.maybeSync(home, force = true)) {
            Toast.makeText(home, "Refreshing…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(home, "A sync is already running", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmt(t: Long): String =
        if (t <= 0L) "never" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    private fun statusText(home: HomeActivity): String {
        val p = Store.prefs(home)
        val err = p.getString(Store.KEY_LAST_ERROR, null)
        return buildString {
            append("State: ").append(if (SyncManager.isRunning) "syncing…" else "idle").append('\n')
            append("Last attempt: ").append(fmt(p.getLong(Store.KEY_LAST_ATTEMPT, 0))).append('\n')
            append("Last success: ").append(fmt(p.getLong(Store.KEY_LAST_OK, 0))).append('\n')
            append("Games installed: ").append(Store.loadInstalled(home).size).append("\n\n")
            if (err == null) {
                append("Last error: none")
            } else {
                append("Last error (").append(fmt(p.getLong(Store.KEY_LAST_ERROR_AT, 0))).append("):\n")
                append(err)
            }
            append("\n\nSource: ").append(SyncManager.MANIFEST_URL)
        }
    }

    private fun versionsText(home: HomeActivity): String {
        val info = home.packageManager.getPackageInfo(home.packageName, 0)
        @Suppress("DEPRECATION")
        val code = info.versionCode
        val manifestVersion = Store.prefs(home).getLong(Store.KEY_MANIFEST_VERSION, 0)
        return buildString {
            append("App: ").append(info.versionName).append(" (").append(code).append(")\n")
            append("Package: ").append(home.packageName).append('\n')
            append("Manifest version: ").append(manifestVersion)
            if (manifestVersion > 0) append(" (").append(fmt(manifestVersion * 1000)).append(")")
            append("\n\nGames:\n")
            val games = Store.loadInstalled(home)
            if (games.isEmpty()) append("(none)")
            for (g in games) {
                append("• ").append(g.title).append(" [").append(g.id).append("] ")
                    .append(g.hash.take(10)).append('\n')
            }
        }
    }

    private fun changePin(home: HomeActivity) {
        val a = pinField(home, "New PIN (4 digits)")
        val b = pinField(home, "Repeat new PIN")
        val box = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            addView(a)
            addView(b)
        }
        AlertDialog.Builder(home, DIALOG_THEME)
            .setTitle("Change PIN")
            .setView(padded(home, box))
            .setPositiveButton("Save") { _, _ ->
                val p1 = a.text.toString()
                val p2 = b.text.toString()
                when {
                    !Regex("^[0-9]{4}$").matches(p1) -> toast(home, "PIN must be 4 digits")
                    p1 != p2 -> toast(home, "PINs don't match")
                    else -> {
                        Store.setPin(home, p1)
                        toast(home, "PIN changed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openLauncher3(home: HomeActivity) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage("com.android.launcher3")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startSafely(home, i)) {
            home.packageManager.getLaunchIntentForPackage("com.android.launcher3")?.let {
                startSafely(home, it)
            }
        }
    }

    private fun startSafely(home: HomeActivity, i: Intent): Boolean = try {
        home.startActivity(i)
        true
    } catch (e: Exception) {
        toast(home, "Can't open: ${e.message}")
        false
    }

    private fun showText(home: HomeActivity, title: String, text: String) {
        val tv = TextView(home).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 14f
        }
        val scroll = ScrollView(home).apply { addView(padded(home, tv)) }
        AlertDialog.Builder(home, DIALOG_THEME)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun pinField(home: HomeActivity, hint: String) = EditText(home).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        isSingleLine = true
    }

    private fun padded(home: HomeActivity, v: android.view.View): LinearLayout {
        val pad = (20 * home.resources.displayMetrics.density).toInt()
        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(v)
        }
    }

    private fun toast(home: HomeActivity, msg: String) =
        Toast.makeText(home, msg, Toast.LENGTH_SHORT).show()
}
