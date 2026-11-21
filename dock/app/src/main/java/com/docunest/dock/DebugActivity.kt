package com.docunest.dock

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 🐞 Debug log (from the GPS and Wi-Fi doctors): the current inputs behind the
 * status ring, then every logged change, newest first — made to be
 * screenshotted and sent back for development. Also uploaded to MapPal.
 */
class DebugActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var now: TextView
    private lateinit var logText: TextView
    private lateinit var upload: TextView
    private var tag: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        tag = intent.getStringExtra(EXTRA_TAG)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("🐞  Debug log", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bigButton("✕", Palette.CARD, 20f) { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        root.addView(head)

        val tags = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in listOf(null, "CRASH", "GPS", "AUTO", "PARK", "WIFI", "BT", "ROOT", "DIAG", "SPLIT", "KEY", "RING", "SNAP", "APP")) {
            tags.addView(bigButton(t ?: "ALL", Palette.CARD_HI, 13f) { tag = t; render() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tags) })

        now = label("", 13f).apply {
            background = rounded(Palette.CARD, dp(12).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        root.addView(now, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val up = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        @Suppress("UseSwitchCompatOrMaterialCode")
        up.addView(Switch(this).apply {
            text = "Send to MapPal"
            setTextColor(Palette.TEXT)
            isChecked = DebugLog.upload
            setOnCheckedChangeListener { _, v -> DebugLog.upload = v }
        })
        upload = label("", 12f, Palette.MUTED).apply { setPadding(dp(10), 0, dp(10), 0) }
        up.addView(upload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        up.addView(bigButton("Run diagnostics", Palette.CARD_HI, 13f) {
            Diagnostics.runAsync(this)
            tag = "DIAG"
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) })
        up.addView(bigButton("Send now", Palette.CARD_HI, 13f) {
            Thread { DebugLog.uploadNow(this); runOnUiThread { render() } }.start()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(up)

        logText = label("", 11f, Palette.TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logText) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        WifiDoctor.refreshNow()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        now.text = (listOf("Build ${BuildConfig.VERSION_CODE} · showing ${tag ?: "all"}") +
            DebugLog.status.entries.sortedBy { it.key }.map { "${it.key}: ${it.value}" }).joinToString("\n")
        upload.text = DebugLog.uploadStatus + " · diagnostics: " + (if (Diagnostics.running) "running…" else Diagnostics.lastRun)
        logText.text = DebugLog.lines(tag, 400).joinToString("\n") { it.text() }.ifEmpty { "Nothing logged yet." }
    }

    companion object {
        const val EXTRA_TAG = "tag"
    }
}

