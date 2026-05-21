# ProGuard/R8 rules for release builds.
# Currently release uses minifyEnabled false, so these are inert until minify is turned
# on — they are kept ready so enabling R8 does not silently break the WebView bridge.

# The dashboard talks to the app through @JavascriptInterface methods. R8 would strip
# them (nothing calls them from Java), which would break the whole UI bridge.
-keepclassmembers class com.volttracker.obdpoc.MainActivity$VoltBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
