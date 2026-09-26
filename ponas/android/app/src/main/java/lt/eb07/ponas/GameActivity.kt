package lt.eb07.ponas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Runs one game in one WebView. The WebView is created here and destroyed in onDestroy.
 * Files are served from filesDir/games/<id>/<hash>/ at
 * https://appassets.androidplatform.net/games/<id>/ — nothing else can be loaded.
 */
class GameActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        private const val HOST = WebViewAssetLoader.DEFAULT_DOMAIN // appassets.androidplatform.net
        private const val TWO_FINGER_EXIT_MS = 1500L
    }

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { finish() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val game = Store.loadInstalled(this).firstOrNull { it.id == id }
        val dir = game?.let { Store.gameDir(this, it.id, it.hash) }
        if (game == null || dir == null || !File(dir, "index.html").isFile) {
            finish()
            return
        }
        SyncManager.gameOpen = true
        Immersive.hide(window)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val prefix = "/games/${game.id}/"
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler(prefix, WebViewAssetLoader.InternalStoragePathHandler(this, dir))
            .build()

        val wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        wv.isHapticFeedbackEnabled = false
        wv.isLongClickable = false
        wv.setOnLongClickListener { true } // no text-selection / context menus
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = android.view.View.OVER_SCROLL_NEVER

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100 // ignore the system font size so layouts stay 640×375
            setGeolocationEnabled(false)
            safeBrowsingEnabled = false // needs Google services; games are local anyway
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse {
                val url = request.url
                if (url.host == HOST) {
                    loader.shouldInterceptRequest(url)?.let { return it }
                }
                // Anything else (other origins, missing files) is refused: games must be offline.
                return WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val allowed = url.scheme == "https" && url.host == HOST &&
                    (url.path ?: "").startsWith(prefix)
                return !allowed
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Usually the low-memory killer. Don't crash the app; go back home.
                destroyWebView()
                finish()
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        wv.setDownloadListener { _, _, _, _, _ -> /* downloads are not allowed */ }

        webView = wv
        setContentView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl("https://$HOST${prefix}index.html")
    }

    override fun onResume() {
        super.onResume()
        Immersive.hide(window)
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.hide(window)
    }

    @Deprecated("Back returns to the home screen")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }

    /** Two fingers held down for 1.5 s returns to the home screen. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(exitRunnable)
                if (ev.pointerCount == 2) handler.postDelayed(exitRunnable, TWO_FINGER_EXIT_MS)
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(exitRunnable)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        destroyWebView()
        SyncManager.gameOpen = false
        super.onDestroy()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.resumeTimers() // timers are process-global; don't leave them paused
        wv.destroy()
    }
}
