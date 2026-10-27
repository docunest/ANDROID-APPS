package com.docunest.dock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** One-time setup on the head unit (done parked — nothing here is needed while driving). */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) })

        section("Permissions")
        val overlay = Settings.canDrawOverlays(this)
        val location = Locator.hasPermission(this)
        col.addView(label("Display over other apps: ${if (overlay) "✓" else "✗"}    Location: ${if (location) "✓" else "✗"}", 15f))
        row(
            bigButton("Grant all with root", Palette.BLUE, 15f) {
                Thread {
                    val ok = Root.grantAll(this)
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Granted" else "Root refused — use the buttons instead", Toast.LENGTH_LONG).show()
                        if (ok) DockService.start(this)
                        render()
                    }
                }.start()
            },
            bigButton("Overlay…", Palette.CARD_HI, 15f) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            bigButton("Location…", Palette.CARD_HI, 15f) {
                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(perms.toTypedArray(), 1)
            },
        )
        row(
            bigButton("Show button", Palette.GMAPS, 15f) { DockService.start(this) },
            bigButton("Hide button", Palette.CARD_HI, 15f) { DockService.stop(this) },
        )
        switch("Start by itself when the car starts", prefs.startOnBoot) { prefs.startOnBoot = it }

        section("Server (MapPal)")
        val url = field(prefs.serverUrl, "https://your-mappal.vercel.app")
        val token = field(prefs.token, "token")
        row(
            bigButton("Save + test", Palette.BLUE, 15f) {
                prefs.serverUrl = url.text.toString()
                prefs.token = token.text.toString()
                Thread {
                    val msg = runCatching { Api(this).call("GET", "/api/dock/items?since=2100-01-01T00:00:00Z"); "Connected ✓" }
                        .getOrElse { it.message ?: "Failed" }
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    Sync.pollSoon()
                }.start()
            },
        )
        col.addView(label(Store.status, 13f, Palette.MUTED))

        section("Navigation app")
        col.addView(label("Places open in this app without asking. The small button next to each place opens the other one.", 13f, Palette.MUTED))
        row(
            bigButton((if (prefs.defaultNav == Prefs.NAV_WAZE) "● " else "") + "Waze", Palette.WAZE, 16f) { prefs.defaultNav = Prefs.NAV_WAZE; render() },
            bigButton((if (prefs.defaultNav == Prefs.NAV_GMAPS) "● " else "") + "Google Maps", Palette.GMAPS, 16f) { prefs.defaultNav = Prefs.NAV_GMAPS; render() },
        )

        section("Apps")
        col.addView(label("Press-and-slide: " + names(prefs.fanApps), 14f))
        col.addView(label("DVR: " + (prefs.dvrPackage.takeIf { it.isNotBlank() }?.let { names(listOf(it)) } ?: "not set"), 14f))
        row(
            bigButton("Slide apps…", Palette.CARD_HI, 15f) { pickApps(prefs.fanApps) { prefs.fanApps = it } },
            bigButton("DVR app…", Palette.CARD_HI, 15f) { pickOne() },
        )
        switch("\"★ Save spot\" in press-and-slide", prefs.fanBookmarkHere) { prefs.fanBookmarkHere = it }
        slider("Places in press-and-slide", prefs.fanLinks, 0, 8, "") { prefs.fanLinks = it }

        section("Floating button")
        slider("Size", prefs.bubbleSizeDp, 32, 96, " dp") { prefs.bubbleSizeDp = it; DockService.start(this, DockService.ACTION_RELAYOUT) }
        slider("Opacity when idle", prefs.bubbleAlpha, 15, 100, " %") { prefs.bubbleAlpha = it; DockService.start(this, DockService.ACTION_RELAYOUT) }

        section("Updates")
        col.addView(label("Build ${BuildConfig.VERSION_CODE}. New builds from GitHub install by themselves (root), checked on start and every 3 h.", 13f, Palette.MUTED))
        switch("Install updates automatically", prefs.autoUpdate) { prefs.autoUpdate = it }
        row(bigButton("Check now", Palette.CARD_HI, 15f) {
            Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = Updater.check(this, force = true)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        })
    }

    private fun names(pkgs: List<String>) =
        pkgs.joinToString(", ") { Apps.entry(this, it)?.label ?: "$it (not installed)" }.ifBlank { "none" }

    private fun section(title: String) {
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(18)) })
        col.addView(label(title, 20f, bold = true))
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginEnd = dp(8) }) }
        col.addView(r)
    }

    private fun field(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
    }.also { col.addView(it) }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(Switch(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Palette.TEXT)
            isChecked = value
            minHeight = dp(48)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun slider(title: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
        val l: TextView = label("$title: $value$unit", 15f)
        col.addView(l)
        col.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { l.text = "$title: ${p + min}$unit" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { onChange((s?.progress ?: 0) + min) }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
    }

    /** Multi-select; chosen apps keep their order, new ones go last. */
    private fun pickApps(current: List<String>, save: (List<String>) -> Unit) {
        val apps = Apps.launchable(this)
        val checked = BooleanArray(apps.size) { apps[it].pkg in current }
        AlertDialog.Builder(this)
            .setTitle("Choose apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Save") { _, _ ->
                val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg }
                save(current.filter { it in chosen } + chosen.filter { it !in current })
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickOne() {
        val apps = Apps.launchable(this)
        AlertDialog.Builder(this)
            .setTitle("DVR app")
            .setItems((listOf("None") + apps.map { it.label }).toTypedArray()) { _, i ->
                prefs.dvrPackage = if (i == 0) "" else apps[i - 1].pkg
                render()
            }
            .show()
    }
}
