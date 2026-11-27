# Keep native alarm, webview & compose classes
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
