package lt.eb07.ponas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The child's home screen (and the device launcher). Big picture tiles for the installed games,
 * plus Camera. Works in both orientations.
 *  - small ⬇ button (bottom-right): PIN, then the install screen;
 *  - hold the top-right corner for 3 s: PIN, then the parent menu.
 */
class HomeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var emptyPanel: LinearLayout
    private lateinit var emptyImage: ImageView
    private lateinit var emptyText: TextView
    private lateinit var emptyProgress: ProgressBar

    private var renderedSignature: String? = null
    private var lastLaunchAt = 0L

    /** Set by the parent menu's "Refresh" so we can report the result with a toast. */
    var reportNextSyncResult = false

    private val openParentMenu = Runnable { ParentMenu.askPin(this) { ParentMenu.showMenu(this) } }
    private val syncListener = Runnable { onSyncStateChanged() }

    /** While nothing has ever been downloaded, keep retrying so the catalog arrives soon after Wi‑Fi. */
    private val emptyRetry = object : Runnable {
        override fun run() {
            if (Store.loadCatalog(this@HomeActivity).isEmpty()) {
                SyncManager.maybeSync(this@HomeActivity)
                handler.postDelayed(this, 31_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immersive.hide(window)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        Immersive.hide(window)
        SyncManager.addListener(syncListener)
        render()
        SyncManager.maybeSync(this)
        handler.removeCallbacks(emptyRetry)
        handler.postDelayed(emptyRetry, 31_000)
    }

    override fun onPause() {
        SyncManager.removeListener(syncListener)
        handler.removeCallbacks(openParentMenu)
        handler.removeCallbacks(emptyRetry)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderedSignature = null
        grid.post { render() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.hide(window)
    }

    @Deprecated("Back does nothing on the home screen")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Intentionally empty: back must not leave the home screen.
    }

    private fun onSyncStateChanged() {
        render()
        if (reportNextSyncResult && !SyncManager.catalogRunning) {
            reportNextSyncResult = false
            val err = Store.prefs(this).getString(Store.KEY_LAST_ERROR, null)
            val msg = if (err == null) "Games are up to date" else "Sync problem:\n$err"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---- UI -------------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = FrameLayout(this)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        grid = GridLayout(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        content.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        emptyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(16))
            visibility = View.GONE
        }
        emptyImage = ImageView(this)
        emptyProgress = ProgressBar(this).apply { isIndeterminate = true }
        emptyText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#5D4037"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        emptyPanel.addView(emptyImage, LinearLayout.LayoutParams(dp(110), dp(110)))
        emptyPanel.addView(emptyProgress, LinearLayout.LayoutParams(dp(64), dp(64)))
        emptyPanel.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(emptyPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Small, quiet install button for the parent (PIN protected).
        val install = ImageView(this).apply {
            setImageResource(R.drawable.ic_install)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke(dp(2), 0x338D6E63)
            }
            alpha = 0.55f
            contentDescription = "Install games"
            setOnClickListener {
                ParentMenu.askPin(this@HomeActivity) {
                    startActivity(Intent(this@HomeActivity, InstallActivity::class.java))
                }
            }
        }
        root.addView(install, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(10), dp(10))
        })

        // Invisible hot corner for the parent menu: hold 3 seconds.
        val corner = View(this)
        corner.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(openParentMenu, 3000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(openParentMenu)
            }
            true
        }
        root.addView(corner, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END))
        return root
    }

    /** Columns and tile size for the current orientation: ≥ 140 dp tiles, 2+ columns. */
    private fun layoutFor(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels - dp(16)
        val margin = dp(8)
        var cols = maxOf(2, w / (dp(170) + 2 * margin))
        var size = w / cols - 2 * margin
        if (size > dp(230)) { cols += 1; size = w / cols - 2 * margin }
        return cols to size.coerceAtLeast(dp(140))
    }

    fun render() {
        val games = Store.loadInstalled(this)
        val catalogEmpty = Store.loadCatalog(this).isEmpty()
        val running = SyncManager.catalogRunning
        val (cols, size) = layoutFor()
        val sig = games.joinToString("|") { "${it.id}:${it.hash}:${it.title}:${it.color}:${it.order}" } +
            "#$cols#$size#${games.isEmpty()}#$catalogEmpty#$running"
        if (sig == renderedSignature) return
        renderedSignature = sig

        grid.removeAllViews()
        grid.columnCount = cols
        for (g in games) {
            val bmp = loadIcon(File(Store.gameDir(this, g.id, g.hash), g.icon), size)
            grid.addView(makeTile(g.title, parseColor(g.color), bmp, null, size) { openGame(g) })
        }
        grid.addView(makeTile("Kamera", Color.parseColor("#7E57C2"), null, R.drawable.ic_camera, size) {
            openCamera()
        })

        if (games.isEmpty()) {
            emptyPanel.visibility = View.VISIBLE
            when {
                catalogEmpty && running -> {
                    emptyImage.visibility = View.GONE
                    emptyProgress.visibility = View.VISIBLE
                    emptyText.text = "Kraunama…"
                }
                catalogEmpty -> {
                    emptyImage.visibility = View.VISIBLE
                    emptyImage.setImageResource(R.drawable.ic_wifi)
                    emptyProgress.visibility = View.GONE
                    emptyText.text = "Prijunkite Wi‑Fi\nConnect Wi‑Fi"
                }
                else -> {
                    emptyImage.visibility = View.VISIBLE
                    emptyImage.setImageResource(R.drawable.ic_install)
                    emptyProgress.visibility = View.GONE
                    emptyText.text = "Tėvams: ⬇ apačioje dešinėje\nParent: tap ⬇ bottom-right to install games"
                }
            }
        } else {
            emptyPanel.visibility = View.GONE
        }
    }

    private fun parseColor(s: String): Int =
        try { Color.parseColor(s) } catch (_: Exception) { Color.parseColor("#4CAF50") }

    private fun loadIcon(f: File, targetPx: Int): Bitmap? {
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
    }

    private fun makeTile(
        label: String,
        color: Int,
        bitmap: Bitmap?,
        iconRes: Int?,
        size: Int,
        onTap: () -> Unit,
    ): View {
        val dark = isLight(color)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dpf(28f)
                setColor(color)
            }
            elevation = dpf(3f)
            contentDescription = label
            isHapticFeedbackEnabled = false
        }

        val iconLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        when {
            bitmap != null -> tile.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, iconLp)
            iconRes != null -> tile.addView(ImageView(this).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, iconLp)
            else -> tile.addView(TextView(this).apply {
                text = label.take(1).uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.4f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (dark) Color.parseColor("#3E2723") else Color.WHITE)
            }, iconLp)
        }

        tile.addView(TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (dark) Color.parseColor("#3E2723") else Color.WHITE)
            if (!dark) setShadowLayer(dpf(2f), 0f, dpf(1f), 0x66000000)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tile.setOnClickListener { v ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunchAt < 1500) return@setOnClickListener
            lastLaunchAt = now
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
                v.scaleX = 1f
                v.scaleY = 1f
                onTap()
            }.start()
        }

        val m = dp(8)
        tile.layoutParams = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(m, m, m, m)
        }
        return tile
    }

    private fun isLight(color: Int): Boolean {
        val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return lum > 186
    }

    // ---- Actions --------------------------------------------------------------------------

    private fun openGame(g: Game) {
        startActivity(GameActivity.intent(this, g))
    }

    private fun openCamera() {
        val explicit = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.softwinner.camera2", "com.softwinner.camera2.CameraLauncher"))
        try {
            startActivity(explicit)
        } catch (_: Exception) {
            try {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            } catch (_: Exception) {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
