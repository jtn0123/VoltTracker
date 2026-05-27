# ProGuard/R8 rules for release builds.
# Release builds run R8. Keep every @JavascriptInterface method so the local
# dashboard bridge remains callable from WebView JavaScript after shrinking.

# The dashboard talks to the app through @JavascriptInterface methods. R8 would strip
# them (nothing calls them from Java), which would break the whole UI bridge.
-keepclassmembers class com.volttracker.obdpoc.VoltBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
