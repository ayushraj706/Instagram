# Keep the JavascriptInterface bridge — if proguard renames these,
# the JS calls (window.Android.downloadMedia) will silently fail at runtime.
-keepclassmembers class com.igdownloader.MainActivity$InstagramBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep all public methods annotated with @JavascriptInterface anywhere
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve WebView-related classes
-keep class android.webkit.** { *; }

# Preserve DownloadManager request builder chain
-keep class android.app.DownloadManager { *; }
-keep class android.app.DownloadManager$Request { *; }
