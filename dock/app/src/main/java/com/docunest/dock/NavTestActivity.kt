package com.docunest.dock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

/**
 * Field test: every way we know to make Waze start a route, one numbered
 * button each (with the exact link shown), plus copy buttons as a fallback.
 * Report which numbers work and the dock uses that one.
 */
class NavTestActivity : Activity() {
    private lateinit var col: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Place"
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val original = intent.getStringExtra(EXTRA_URL)
        val hasPoint = !lat.isNaN() && !lng.isNaN()
        val c = if (hasPoint) "${f(lat)},${f(lng)}" else ""
        val q = Uri.encode(listOfNotNull(title, address).joinToString(", "))

        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) })
        col.addView(label("Waze test — $title", 20f, bold = true))
        col.addView(label(if (hasPoint) "Coordinates: $c" else "No coordinates — only name search methods", 14f, Palette.MUTED))
        col.addView(label("Tap a number. If Waze starts a route, tell Claude the number.", 14f, Palette.AMBER))

        section("Waze deep links")
        if (hasPoint) {
            method(1, "Waze web link, Waze app (current default)", "https://waze.com/ul?ll=$c&navigate=yes", WAZE)
            method(2, "Waze web link, any app that wants it", "https://waze.com/ul?ll=$c&navigate=yes", null)
            method(3, "www.waze.com + zoom", "https://www.waze.com/ul?ll=$c&navigate=yes&zoom=17", WAZE)
            method(4, "ul.waze.com host", "https://ul.waze.com/ul?ll=$c&navigate=yes", WAZE)
            method(5, "waze:// scheme, Waze app", "waze://?ll=$c&navigate=yes", WAZE)
            method(6, "waze:// scheme, no package", "waze://?ll=$c&navigate=yes", null)
            method(7, "waze:// with comma encoded", "waze://?ll=${f(lat)}%2C${f(lng)}&navigate=yes", WAZE)
            method(8, "live-map directions link", "https://www.waze.com/live-map/directions?to=ll.${f(lat)}%2C${f(lng)}&navigate=yes", WAZE)
        }
        method(9, "Waze search by name, navigate", "https://waze.com/ul?q=$q&navigate=yes", WAZE)
        method(10, "waze:// search by name, navigate", "waze://?q=$q&navigate=yes", WAZE)

        section("geo: links (Android standard)")
        if (hasPoint) {
            method(11, "geo: point, Waze app", "geo:$c", WAZE)
            method(12, "geo: point with label, Waze app", "geo:$c?q=$c(${Uri.encode(title)})", WAZE)
            method(13, "geo: point, let Android ask which app", "geo:0,0?q=$c(${Uri.encode(title)})", null, chooser = true)
        }

        section("Launch tricks (Waze already open / cold start)")
        if (hasPoint) {
            method(14, "Web link + CLEAR_TOP|SINGLE_TOP (Waze running)", "https://waze.com/ul?ll=$c&navigate=yes", WAZE,
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            method(15, "Web link + CLEAR_TASK (fresh Waze screen)", "https://waze.com/ul?ll=$c&navigate=yes", WAZE,
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            button(16, "Open Waze first, send link after 4 s (cold start)", "launch com.waze → wait 4 s → https://waze.com/ul?ll=$c&navigate=yes") {
                Apps.launch(this, WAZE)
                main.postDelayed({ start(view("https://waze.com/ul?ll=$c&navigate=yes", WAZE, Intent.FLAG_ACTIVITY_NEW_TASK)) }, 4000)
            }
            button(17, "Share coordinates to Waze (like from another app)", "ACTION_SEND text: https://maps.google.com/?q=$c → com.waze") {
                start(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(WAZE)
                    .putExtra(Intent.EXTRA_TEXT, "$title https://maps.google.com/?q=$c").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            method(18, "Google Maps link opened in Waze", "https://maps.google.com/?q=$c", WAZE)
            button(19, "Root: force-stop Waze, then waze:// via am start", "su: am force-stop com.waze; am start -a VIEW -d waze://?ll=$c&navigate=yes com.waze") {
                Thread {
                    val ok = Root.run("am force-stop $WAZE", "sleep 1",
                        "am start -a android.intent.action.VIEW -d 'waze://?ll=$c&navigate=yes' $WAZE")
                    runOnUiThread { toast(if (ok) "#19 sent (root)" else "#19 failed: no root?") }
                }.start()
            }
            button(20, "Root: am start web link (no force-stop)", "su: am start -a VIEW -d https://waze.com/ul?ll=$c&navigate=yes com.waze") {
                Thread {
                    val ok = Root.run("am start -a android.intent.action.VIEW -d 'https://waze.com/ul?ll=$c&navigate=yes' $WAZE")
                    runOnUiThread { toast(if (ok) "#20 sent (root)" else "#20 failed: no root?") }
                }.start()
            }
        }
        if (original != null) method(21, "Original shared link, Waze app", original, WAZE)

        section("Copy (paste into Waze search)")
        if (hasPoint) copy("Copy coordinates", c.replace(",", ", "))
        copy("Copy name + address", listOfNotNull(title, address).joinToString(", "))
        if (hasPoint) copy("Copy Waze link", "https://waze.com/ul?ll=$c&navigate=yes")
        if (original != null) copy("Copy original link", original)
    }

    private fun f(v: Double) = String.format(Locale.US, "%.6f", v)

    private fun section(t: String) {
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) })
        col.addView(label(t, 17f, bold = true))
    }

    private fun view(uri: String, pkg: String?, flags: Int) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        if (pkg != null) setPackage(pkg)
        addFlags(flags)
    }

    private fun method(n: Int, what: String, uri: String, pkg: String?, flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK, chooser: Boolean = false) =
        button(n, what, uri + (if (pkg != null) "  → $pkg" else "")) {
            val i = view(uri, pkg, flags)
            start(if (chooser) Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i, n)
        }

    private fun button(n: Int, what: String, detail: String, run: () -> Unit) {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.CARD, dp(12).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { run() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(6), 0, 0)
            }
        }
        b.addView(label("#$n  $what", 17f, bold = true))
        b.addView(label(detail, 12f, Palette.MUTED).apply { typeface = Typeface.MONOSPACE })
        col.addView(b)
    }

    private fun copy(text: String, value: String) {
        col.addView(bigButton(text, Palette.CARD_HI, 16f) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(text, value))
            toast("Copied: $value")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(6), 0, 0) }
        })
    }

    private fun start(i: Intent, n: Int? = null) {
        val r = runCatching { startActivity(i) }
        toast(if (r.isSuccess) "Sent${n?.let { " #$it" } ?: ""}" else "Failed${n?.let { " #$it" } ?: ""}: ${r.exceptionOrNull()?.javaClass?.simpleName}")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WAZE = Apps.WAZE
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_URL = "url"

        fun intent(activity: Activity, lat: Double?, lng: Double?, title: String, address: String?, url: String?) =
            Intent(activity, NavTestActivity::class.java).apply {
                if (lat != null && lng != null) { putExtra(EXTRA_LAT, lat); putExtra(EXTRA_LNG, lng) }
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_URL, url)
            }
    }
}
