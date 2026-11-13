package com.docunest.dock

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wi-Fi doctor (from the 📶 chip on the dock map): is the hotspot connected,
 * is there internet behind it, what the watchdog is doing about it, and a
 * history of every drop with who was at fault.
 */
class WifiDoctorActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var headline: TextView
    private lateinit var live: TextView
    private lateinit var action: TextView
    private lateinit var tally: TextView
    private lateinit var history: LinearLayout
    private var shownEvents = -1L
    private val listener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WifiDoctor.start(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        WifiDoctor.refreshNow()
        WifiDoctor.addListener(listener)
        main.post(ticker)
    }

    override fun onPause() {
        WifiDoctor.removeListener(listener)
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
    }

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("📶  Wi-Fi doctor", 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(bigButton("✕", Palette.CARD, 20f) { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        left.addView(head)
        headline = label("", 19f, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        left.addView(headline)
        live = label("", 15f).apply {
            background = rounded(Palette.CARD, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        left.addView(live)
        action = label("", 14f, Palette.AMBER).apply { setPadding(0, dp(8), 0, dp(4)) }
        left.addView(action)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(bigButton("Reconnect now", Palette.BLUE, 16f) { WifiDoctor.reconnectNow() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        buttons.addView(space(8))
        buttons.addView(bigButton("Kick Bluetooth", Palette.CARD_HI, 15f) { WifiDoctor.kickNow() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        buttons.addView(space(8))
        buttons.addView(bigButton("Wi-Fi settings", Palette.CARD_HI, 15f) {
            val panel = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            runCatching { startActivity(Intent(panel)) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
        left.addView(buttons)
        left.addView(space(8))
        left.addView(bigButton("🐞 Debug log", Palette.CARD_HI, 14f) {
            startActivity(Intent(this, DebugActivity::class.java).putExtra(DebugActivity.EXTRA_TAG, "WIFI"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        left.addView(space(6))
        left.addView(toggle("Reconnect by itself (root: forces a rejoin, clears Android's no-internet block, restarts Wi-Fi if needed)", WifiDoctor.autoFix) { WifiDoctor.autoFix = it })
        left.addView(toggle("Bluetooth kick: no hotspot visible but the iPhone is on the car's Bluetooth → Bluetooth off/on, so the iPhone's automation switches its hotspot on (never during a call)", WifiDoctor.btKick) { WifiDoctor.btKick = it })
        left.addView(toggle("Keep-alive ping to the hotspot every 15 s (may stop the iPhone dropping an idle hotspot)", WifiDoctor.keepAlive) { WifiDoctor.keepAlive = it })
        left.addView(label("Internet is checked once a minute through Wi-Fi (~1 KB of iPhone data).", 12f, Palette.MUTED))

        val rhead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rhead.addView(label("What happened", 18f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rhead.addView(bigButton("Clear", Palette.CARD, 13f) { WifiDoctor.clear(); shownEvents = -1L; render() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        right.addView(rhead)
        tally = label("", 13f, Palette.MUTED)
        right.addView(tally)
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(history)
        shownEvents = -1L

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        if (landscape) {
            root.addView(scroll(left), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(space(14))
            root.addView(scroll(right), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            setContentView(root)
        } else {
            root.addView(left)
            root.addView(space(14))
            root.addView(right)
            setContentView(scroll(root).apply { setBackgroundColor(Palette.BG) })
        }
        render()
    }

    private fun toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Palette.TEXT)
        isChecked = on
        minHeight = dp(52)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }
    private fun space(d: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(d), dp(d)) }

    private fun render() {
        if (!::headline.isInitialized) return
        val w = WifiDoctor
        val now = SystemClock.elapsedRealtime()
        headline.text = w.status()
        headline.setTextColor(
            when {
                w.connected && w.internet == true -> Palette.GMAPS
                w.connected -> Palette.AMBER
                else -> Palette.RED
            },
        )
        val signal = if (w.connected) "${w.rssi} dBm · ${quality(w.rssi)} · ${band(w.freqMhz)} · ${w.linkMbps} Mbit/s" else "—"
        val seen = if (w.oursSeenAt > 0) w.oursVisible.entries.joinToString { "${it.key} ${it.value} dBm" }.ifEmpty { "—" } + ", ${GpsDoctor.duration(now - w.oursSeenAt)} ago" else "not yet"
        val bt = when (BtKick.phoneConnected()) {
            true -> "${BtKick.phone} connected" + (if (BtKick.since >= 0) " ${GpsDoctor.duration(now - BtKick.since)}" else "") + if (BtKick.inCall(this)) " · in a call" else ""
            false -> "no phone connected"
            null -> "Android can't see the car's Bluetooth (off, or a separate Bluetooth module) — kick not possible"
        }
        live.text = listOf(
            "Wi-Fi ${if (w.enabled) "on ✓" else "off ✗"} · Root ${Root.via()}",
            "Network: ${if (w.connected) (w.ssid ?: "connected") else "not connected"}" + (w.target?.let { "   (keeping: $it)" } ?: ""),
            "Signal: $signal",
            "Link quality (ping to hotspot, last ~30 s): " + if (w.connected) w.link.text() else "—",
            "Before the last drop: " + (w.beforeLastDrop ?: "—"),
            "Hotspot answers: " + when {
                !w.connected -> "—"
                w.gatewayPingMs != null -> "yes, ${w.gatewayPingMs} ms (${w.gateway})"
                w.gateway == null -> "unknown"
                else -> "no (${w.gateway})"
            },
            "Internet: " + when (w.internet) { true -> "works ✓"; false -> "none ✗ for ${GpsDoctor.duration(now - w.noInternetSince)}"; null -> if (w.connected) "checking…" else "—" },
            "Your networks: ${w.ours.joinToString().ifEmpty { "learning…" }}",
            "Seen in scan: $seen",
            "Bluetooth: $bt",
            "Bluetooth kick: " + (BtKick.summary(this) ?: "not used yet") + if (BtKick.pendingAt > 0) " · waiting for a hotspot…" else "",
            if (w.connected) "Up for: ${if (w.upSince > 0) GpsDoctor.duration(now - w.upSince) else "—"}" else "Down for: ${GpsDoctor.duration(now - w.downSince)} · rejoin tries ${w.restarts}",
            "Android auto-join: " + (w.joinStatus?.let { if (it.contains("DISABLED")) "blocked ✗ ($it)" else "allowed ✓" } ?: if (w.hasRoot == true) "checked on a drop" else "unknown (needs root)"),
        ).joinToString("\n")
        action.text = if (w.connected) "" else w.lastAction
        action.visibility = if (action.text.isEmpty()) View.GONE else View.VISIBLE

        val events = w.events()
        val sig = events.size * 31L + (events.firstOrNull()?.let { it.at + (it.after?.length ?: 0) } ?: 0L)
        if (sig == shownEvents) return
        shownEvents = sig
        val drops = events.filter { it.title.startsWith("Lost") }
        tally.text = if (events.isEmpty()) "Nothing yet — every drop and no-internet spell is listed here with the cause."
        else "${drops.size} drops: " + drops.groupingBy { it.fault }.eachCount().entries.joinToString(" · ") { "${it.key.label} ${it.value}" } +
            "  ·  ${events.size - drops.size} no-internet spells"
        history.removeAllViews()
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.US)
        for (e in events.take(40)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Palette.CARD, dp(12).toFloat())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
            }
            val color = when (e.fault) {
                WifiDoctor.Fault.ANDROID -> Palette.RED
                WifiDoctor.Fault.IPHONE -> Palette.AMBER
                WifiDoctor.Fault.NO_DATA -> Palette.WAZE
                else -> Palette.MUTED
            }
            card.addView(label("${fmt.format(Date(e.at))}  ${e.title}", 15f, bold = true))
            card.addView(label(e.why, 13f))
            card.addView(label("Fault: ${e.fault.label}" + (e.after?.let { " · $it" } ?: ""), 12f, color))
            history.addView(card)
        }
    }

    private fun quality(rssi: Int) = when {
        rssi >= -60 -> "strong"
        rssi >= -70 -> "good"
        rssi >= -80 -> "weak"
        else -> "very weak"
    }

    private fun band(mhz: Int) = when {
        mhz in 2400..2500 -> "2.4 GHz"
        mhz in 4900..5900 -> "5 GHz"
        mhz > 5900 -> "6 GHz"
        else -> "?"
    }
}
