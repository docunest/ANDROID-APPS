package lt.eb07.ponas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
 * Runs exactly one game, in its own process (":game", see AndroidManifest.xml). Closing the game
 * destroys the WebView and then ends the process, so every game starts in a completely fresh
 * browser and nothing (memory, timers, audio, connections) leaks from one game to the next.
 *
 * Each game gets its own origin, https://<id>.appassets.androidplatform.net/games/<id>/, so its
 * localStorage is private to that game and survives updates. Files come from
 * filesDir/games/<id>/<hash>/. The only network access is to hosts the game lists in meta.json
 * "network" AND that are in [ALLOWED_HOSTS]; a Content-Security-Policy enforces this too.
 */
class GameActivity : Activity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_NETWORK = "network"
        private const val TWO_FINGER_EXIT_MS = 1500L

        /** Hosts any game may reach. Must match ALLOWED_HOSTS in tools/build-site.mjs. */
        val ALLOWED_HOSTS = setOf("ntfy.sh")

        fun intent(ctx: Context, g: Game): Intent = Intent(ctx, GameActivity::class.java)
            .putExtra(EXTRA_ID, g.id)
            .putExtra(EXTRA_HASH, g.hash)
            .putExtra(EXTRA_NETWORK, g.network.filter { it in ALLOWED_HOSTS }.toTypedArray())
    }

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val exitRunnable = Runnable { finish() }

    /** Exposed to the game as window.PonasApp (used by ponas.js). */
    inner class Bridge {
        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val hash = intent.getStringExtra(EXTRA_HASH) ?: ""
        val network = (intent.getStringArrayExtra(EXTRA_NETWORK) ?: emptyArray())
            .filter { it in ALLOWED_HOSTS }.toSet()
        val dir = File(File(File(filesDir, "games"), id), hash)
        if (!Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(id) || hash.isEmpty() || !File(dir, "index.html").isFile) {
            finish()
            return
        }
        Immersive.hide(window)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val host = "$id.${WebViewAssetLoader.DEFAULT_DOMAIN}"
        val prefix = "/games/$id/"
        val csp = buildString {
            append("default-src 'self' data: blob:; ")
            append("script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; ")
            append("img-src 'self' data: blob:; media-src 'self' data: blob:; font-src 'self' data:; ")
            append("connect-src 'self' data: blob:")
            for (h in network) append(" https://").append(h).append(" wss://").append(h)
            append("; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'self'")
        }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(host)
            .addPathHandler(prefix, WebViewAssetLoader.InternalStoragePathHandler(this, dir))
            .build()

        val wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        wv.isHapticFeedbackEnabled = false
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER

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
            textZoom = 100
            setGeolocationEnabled(false)
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                val h = url.host ?: ""
                if (h == host) {
                    val resp = loader.shouldInterceptRequest(url)
                    if (resp != null) {
                        val headers = HashMap(resp.responseHeaders ?: emptyMap())
                        headers["Content-Security-Policy"] = csp
                        headers["Cache-Control"] = "no-store"
                        resp.responseHeaders = headers
                        return resp
                    }
                } else if (url.scheme == "https" && h in network) {
                    return null // allowed multiplayer host: let WebView load it
                }
                return WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not Found", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "https" && url.host == host && (url.path ?: "").startsWith(prefix)) return false
                // Anything else (e.g. the browser fallback "../../index.html") means "leave the game".
                if (request.isForMainFrame) finish()
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
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
        wv.setDownloadListener { _, _, _, _, _ -> }
        wv.addJavascriptInterface(Bridge(), "PonasApp")

        webView = wv
        setContentView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.loadUrl("https://$host${prefix}index.html")
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
        val finishing = isFinishing
        super.onDestroy()
        // End the whole ":game" process: the next game starts in a brand-new one.
        if (finishing) Process.killProcess(Process.myPid())
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.removeJavascriptInterface("PonasApp")
        wv.destroy()
    }
}
