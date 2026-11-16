package com.docunest.dock

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

/** Settings → 🗺 Offline map data: choose countries, install over Starlink, see what's installed, test a lookup. */
class OfflineDataActivity : Activity() {
    private lateinit var col: LinearLayout
    private val refresh: () -> Unit = { render() }
    private var testResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (OfflinePacks.manifest == null) OfflinePacks.checkNow()
    }

    override fun onResume() { super.onResume(); OfflinePacks.addListener(refresh); render() }
    override fun onPause() { super.onPause(); OfflinePacks.removeListener(refresh) }

    private fun mb(b: Long) = if (b <= 0) "?" else if (b < 10_000_000) "%.1f MB".format(b / 1e6) else "${b / 1_000_000} MB"

    private fun render() {
        val scroll = (window.decorView.findViewById<View>(android.R.id.content) as? android.view.ViewGroup)
            ?.getChildAt(0) as? ScrollView
        val y = scroll?.scrollY ?: 0
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col); post { scrollTo(0, y) } })

        col.addView(label("🗺 Offline map data", 22f, bold = true))
        col.addView(label(
            "Fuel, rest areas, camper services, groceries, toilets, water, playgrounds and the cool stuff — " +
                "found with no signal at all. From OpenStreetMap, rebuilt every month; on Starlink the dock fetches newer data by itself. " +
                "Offline maps and routing come later.", 13f, Palette.MUTED))

        val ssid = OfflinePacks.ssid(this)
        val star = OfflinePacks.onStarlink(this)
        col.addView(label(
            "Network: ${ssid ?: "no Wi-Fi"} — " + if (star) "Starlink ✓ downloads go now" else "not Starlink: downloads wait for Starlink",
            15f, if (star) Palette.GMAPS else Palette.AMBER))

        section("Countries")
        val m = OfflinePacks.manifest
        val have = OfflinePacks.installed(this)
        val wanted = OfflinePacks.wanted(this)
        var toGet = 0L
        for ((cc, name) in OfflinePacks.COUNTRIES) {
            val p = m?.get(cc)
            val i = have[cc]
            val prog = OfflinePacks.progress?.takeIf { it.first == cc }
            val state = when {
                prog != null -> "downloading ${(prog.second * 100).toInt()}%"
                i != null && p != null && p.sha256 != i.sha256 -> "installed (data ${i.sourceDate}) · update ${mb(p.bytes)}"
                i != null -> "✓ installed · ${"%,d".format(i.count)} places · data ${i.sourceDate} · ${mb(i.bytes)}"
                p != null -> "not installed · download ${mb(p.bytes)}"
                m != null -> "not built yet"
                else -> "not installed"
            }
            if (cc in wanted && p != null && i?.sha256 != p.sha256) toGet += p.bytes
            col.addView(Switch(this).apply {
                text = "$name — $state"
                textSize = 16f
                setTextColor(Palette.TEXT)
                isChecked = cc in wanted
                minHeight = dp(52)
                setOnCheckedChangeListener { _, on -> OfflinePacks.setWanted(this@OfflineDataActivity, cc, on) }
            })
        }

        section("Install")
        col.addView(label(OfflinePacks.status + if (OfflinePacks.queued(this) && !OfflinePacks.busy) " (queued)" else "", 15f,
            if (OfflinePacks.busy) Palette.WAZE else Palette.TEXT))
        row(
            bigButton("⬇ Install over Starlink" + if (toGet > 0) " (${mb(toGet)})" else "", Palette.BLUE, 15f) {
                OfflinePacks.install(this); render()
            },
            bigButton("Check for updates", Palette.CARD_HI, 15f) { OfflinePacks.checkNow() },
        )
        row(
            bigButton("Download now on this network", Palette.CARD_HI, 14f) {
                AlertDialog.Builder(this)
                    .setTitle("Download without Starlink?")
                    .setMessage("Uses ${if (toGet > 0) mb(toGet) else "some"} of ${ssid ?: "this network"}'s data.")
                    .setPositiveButton("Download") { _, _ -> OfflinePacks.install(this, now = true); render() }
                    .setNegativeButton("Wait for Starlink", null)
                    .show()
            },
        )
        if (have.isNotEmpty()) row(*have.keys.map { cc ->
            bigButton("Remove ${OfflinePacks.COUNTRIES[cc]?.substringAfter(' ')}", Palette.CARD, 13f) { OfflinePacks.remove(this, cc) }
        }.toTypedArray())

        section("Test: nearest from here (offline)")
        row(bigButton("Look up now", Palette.GMAPS, 15f) { test() })
        testResult?.let { col.addView(label(it, 15f)) }
    }

    private fun test() {
        val loc = GpsDoctor.fix ?: DockActivity.lastLocation ?: Locator.lastKnownAny(this)
        if (loc == null) { testResult = "No position yet."; render(); return }
        if (!OfflinePois.available(this)) { testResult = "Nothing installed yet."; render(); return }
        testResult = "Looking up…"; render()
        Thread {
            val t0 = System.currentTimeMillis()
            val lines = listOf("fuel", "rest", "grocery", "aire", "dump", "water", "toilets", "playground", "viewpoint").mapNotNull { layer ->
                OfflinePois.nearest(this, loc.latitude, loc.longitude, layer, limit = 1).firstOrNull()?.let { p ->
                    "${p.icon} ${p.title}${p.info?.let { " · $it" } ?: ""} — ${km(p.distM.toFloat())}"
                } ?: "${OfflinePois.ICON[layer]} none within 30 km"
            }
            val ms = System.currentTimeMillis() - t0
            runOnUiThread { testResult = lines.joinToString("\n") + "\n($ms ms, straight-line distance)"; render() }
        }.start()
    }

    private fun section(title: String) {
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(18)) })
        col.addView(label(title, 20f, bold = true))
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)).apply { marginEnd = dp(8) }) }
        col.addView(r)
    }
}
