# Ponas uses no reflection except the WebView bridge (window.PonasApp) in GameActivity.
-keepclassmembers class lt.eb07.ponas.GameActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
