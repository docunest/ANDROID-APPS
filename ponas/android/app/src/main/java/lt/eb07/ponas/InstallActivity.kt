package lt.eb07.ponas

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Parent-only screen (reached after the PIN): every game in the catalog plus everything already
 * installed, each with Install / Update / Remove. Installed games also auto-update on sync.
 */
class InstallActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val iconLoader = Executors.newSingleThreadExecutor()
    private val iconCache = HashMap<String, android.graphics.Bitmap?>()
    private val listener = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immersive.hide(window)
        setContentView(buildUi())
        SyncManager.maybeSync(this, force = true)
    }

    override fun onResume() {
        super.onResume()
        Immersive.hide(window)
        SyncManager.addListener(listener)
        render()
    }

    override fun onPause() {
        SyncManager.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        iconLoader.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.hide(window)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF6E0"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        bar.addView(iconButton(R.drawable.ic_back, "Back") { finish() })
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(TextView(this@InstallActivity).apply {
                text = "Games"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#5D4037"))
            })
            status = TextView(this@InstallActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#8D6E63"))
                maxLines = 2
            }
            addView(status)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(R.drawable.ic_refresh, "Refresh") { SyncManager.maybeSync(this, force = true) })
        root.addView(bar)

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun iconButton(res: Int, desc: String, onTap: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { onTap() }
    }

    private fun render() {
        val p = Store.prefs(this)
        val err = p.getString(Store.KEY_LAST_ERROR, null)
        val lastOk = p.getLong(Store.KEY_LAST_OK, 0)
        status.text = when {
            SyncManager.catalogRunning -> "Checking for games…"
            err != null -> "⚠ $err"
            lastOk > 0 -> "Catalog checked ${fmt(lastOk)} · installed games update automatically"
            else -> "Not synced yet. Connect Wi‑Fi."
        }

        val installed = Store.loadInstalled(this).associateBy { it.id }
        val catalog = Store.loadCatalog(this).map { it.first }
        val rows = LinkedHashMap<String, Pair<Game?, Game?>>() // id -> (catalog, installed)
        for (c in catalog) if (c.enabled || installed.containsKey(c.id)) rows[c.id] = c to installed[c.id]
        for (i in installed.values) if (!rows.containsKey(i.id)) rows[i.id] = null to i
        val sorted = rows.values.sortedWith(compareBy({ (it.first ?: it.second)!!.order }, { (it.first ?: it.second)!!.title }))

        list.removeAllViews()
        if (sorted.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No games yet. Check Wi‑Fi, then tap refresh."
                setPadding(dp(8), dp(24), dp(8), dp(8))
                setTextColor(Color.parseColor("#5D4037"))
            })
        }
        for ((cat, inst) in sorted) list.addView(row(cat, inst))
    }

    private fun row(cat: Game?, inst: Game?): View {
        val g = cat ?: inst!!
        val busy = SyncManager.busy.contains(g.id)
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(4), 0, dp(4))
        r.layoutParams = lp

        // icon on the game's colour
        val icon = ImageView(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(try { Color.parseColor(g.color) } catch (_: Exception) { Color.GRAY })
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        r.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
        loadIconInto(icon, g, inst)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(TextView(this).apply {
            text = g.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3E2723"))
        })
        val details = buildString {
            append("v").append(g.version)
            if (g.updated > 0) append(" · ").append(day(g.updated))
            if (g.size > 0) append(" · ").append(g.size / 1024).append(" KB")
            if (g.network.isNotEmpty()) append(" · online: ").append(g.network.joinToString())
        }
        texts.addView(TextView(this).apply {
            text = details
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#8D6E63"))
        })
        val (state, stateColor) = when {
            busy -> "Installing…" to "#1E88E5"
            inst == null -> "Not installed" to "#9E9E9E"
            cat == null -> "Installed · no longer in catalog" to "#8D6E63"
            cat.hash != inst.hash -> "Update available (installed v${inst.version})" to "#FB8C00"
            !cat.enabled -> "Installed · disabled in catalog" to "#8D6E63"
            else -> "Installed" to "#43A047"
        }
        texts.addView(TextView(this).apply {
            text = state
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(stateColor))
        })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!busy) {
            if (inst == null && cat != null) {
                actions.addView(button("Install", "#43A047") { SyncManager.install(this, g.id) })
            }
            if (inst != null && cat != null && cat.hash != inst.hash) {
                actions.addView(button("Update", "#FB8C00") { SyncManager.install(this, g.id) })
            }
            if (inst != null) {
                actions.addView(button("Remove", "#E53935") { confirmRemove(g) })
            }
        }
        r.addView(actions)
        return r
    }

    private fun button(label: String, color: String, onTap: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(color)) }
        minWidth = dp(88)
        minHeight = dp(44)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            setMargins(dp(6), 0, 0, 0)
        }
        setOnClickListener { onTap() }
    }

    private fun confirmRemove(g: Game) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Remove ${g.title}?")
            .setMessage("The tile disappears from the home screen. Saved progress stays, and you can install it again later.")
            .setPositiveButton("Remove") { _, _ -> SyncManager.uninstall(this, g.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadIconInto(view: ImageView, g: Game, inst: Game?) {
        val key = "${g.id}-${g.hash}"
        if (iconCache.containsKey(key)) {
            iconCache[key]?.let { view.setImageBitmap(it) }
            return
        }
        iconLoader.execute {
            val local = inst?.let { File(Store.gameDir(this, it.id, it.hash), it.icon) }
            val f = if (local != null && local.isFile) local else SyncManager.fetchIcon(this, g)
            val bmp = f?.let {
                try { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Throwable) { null }
            }
            runOnUiThread {
                iconCache[key] = bmp
                if (!isDestroyed && bmp != null) view.setImageBitmap(bmp)
            }
        }
    }

    private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))
    private fun day(unixSeconds: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(unixSeconds * 1000))
}
