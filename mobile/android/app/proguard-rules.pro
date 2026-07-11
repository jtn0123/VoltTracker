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

# Strip debug/verbose/info logging from release builds. The app has unguarded Log.d/v/i
# calls (poll loops, bridge dispatch, restore staging) whose messages can carry telemetry
# values and device details; release users pay the logcat cost and the format strings ship
# in the APK for no benefit. -assumenosideeffects lets R8 remove the calls and dead-strip
# the message-building expressions feeding them. Log.w/Log.e are deliberately KEPT: they
# mark real faults (storage failures, decrypt failures, lifecycle races) and are the only
# signal available in field bug reports.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
