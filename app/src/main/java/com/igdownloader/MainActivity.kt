package com.igdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IGDownloader"
        private const val INSTAGRAM_URL = "https://www.instagram.com/"

        // User-Agent matching a real Chrome on Android — critical to avoid IG's "browser not supported" wall
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private var injectedJs: String = ""

    // ─── Permission launcher (Android 13+ uses READ_MEDIA_* instead of WRITE_EXTERNAL_STORAGE) ──
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            if (!granted) {
                Toast.makeText(this, "Storage permission needed to save downloads", Toast.LENGTH_LONG).show()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)

        requestStoragePermission()
        loadInjectScript()
        setupWebView()
        enableThirdPartyCookies()

        webView.loadUrl(INSTAGRAM_URL)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────────

    /** Read inject.js from assets once at startup */
    private fun loadInjectScript() {
        try {
            injectedJs = assets.open("inject.js").bufferedReader().use { it.readText() }
            Log.d(TAG, "inject.js loaded (${injectedJs.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load inject.js: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowContentAccess = true
            allowFileAccess = false          // Not needed; reduces attack surface
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = USER_AGENT

            // Cache strategy: use cache when available, refresh over network
            cacheMode = WebSettings.LOAD_DEFAULT

            // Allow mixed content (Instagram CDN URLs are always https, but just in case)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }

        // Register JS Bridge — accessible as window.Android in JS
        webView.addJavascriptInterface(InstagramBridge(), "Android")

        webView.webViewClient = InstagramWebViewClient()
        webView.webChromeClient = InstagramChromeClient()
    }

    private fun enableThirdPartyCookies() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    private fun requestStoragePermission() {
        val permsNeeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val notGranted = permsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            storagePermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WebViewClient — JS injection on page load
    // ─────────────────────────────────────────────────────────────────────────────

    private inner class InstagramWebViewClient : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.d(TAG, "Page finished: $url")

            // Persist cookies immediately after every page load
            CookieManager.getInstance().flush()

            // Inject our script into the page
            if (injectedJs.isNotEmpty()) {
                // evaluateJavascript is the modern, non-deprecated approach (API 19+)
                view.evaluateJavascript(injectedJs, null)
            }
        }

        // Intercept navigation — keep everything inside the WebView
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return handleUrlOverride(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleUrlOverride(request.url.toString())
        }

        private fun handleUrlOverride(url: String): Boolean {
            // Let Instagram domains load normally inside WebView
            return when {
                url.startsWith("https://www.instagram.com") -> false
                url.startsWith("https://l.instagram.com") -> false // IG's link redirector
                url.startsWith("https://accounts.instagram.com") -> false
                url.startsWith("https://graph.facebook.com") -> false
                else -> {
                    Log.d(TAG, "Blocking external navigation to: $url")
                    true // Block anything outside IG
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            Log.e(TAG, "WebView error: ${error.errorCode} — ${error.description} for ${request.url}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // WebChromeClient — handle JS dialogs, console logs
    // ─────────────────────────────────────────────────────────────────────────────

    private inner class InstagramChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "[JS] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JavaScript Bridge
    // ─────────────────────────────────────────────────────────────────────────────

    private inner class InstagramBridge {

        /**
         * Called from JS for single media items.
         * @param url   Direct CDN URL of the video/image
         * @param type  "video" or "image"
         */
        @JavascriptInterface
        fun downloadMedia(url: String, type: String) {
            Log.d(TAG, "downloadMedia() called — type=$type url=$url")
            if (url.isBlank()) {
                runOnUiThread { Toast.makeText(applicationContext, "Invalid media URL", Toast.LENGTH_SHORT).show() }
                return
            }
            val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com")
            enqueueDownload(url, type, cookies)
        }

        /**
         * Called from JS for carousel posts with multiple media.
         * @param jsonPayload  JSON array: [{ url, type, label }, ...]
         */
        @JavascriptInterface
        fun downloadMultiple(jsonPayload: String) {
            Log.d(TAG, "downloadMultiple() called — payload=$jsonPayload")
            try {
                val arr = JSONArray(jsonPayload)
                val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com")
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val url = item.optString("url")
                    val type = item.optString("type", "image")
                    if (url.isNotBlank()) {
                        enqueueDownload(url, type, cookies)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadMultiple JSON parse failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Failed to parse media list", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DownloadManager — the core download logic with cookie injection
    // ─────────────────────────────────────────────────────────────────────────────

    private fun enqueueDownload(rawUrl: String, type: String, cookies: String?) {
        try {
            // Instagram CDN URLs can be encoded; decode first
            val url = URLDecoder.decode(rawUrl, "UTF-8")
            val uri = Uri.parse(url)

            // Build filename: igdl_<timestamp>.<ext>
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val extension = when {
                type == "video" -> "mp4"
                url.contains(".jpg", ignoreCase = true) -> "jpg"
                url.contains(".webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val filename = "IGDL_${timestamp}.$extension"

            // Sub-directory inside Downloads
            val subDir = "IGDownloader"

            val request = DownloadManager.Request(uri).apply {
                // ── CRITICAL: Pass session cookies so private content doesn't 403 ──
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                    Log.d(TAG, "Cookies attached: ${cookies.take(80)}…")
                }

                // Spoof headers to match what IG's CDN expects from a real browser
                addRequestHeader("User-Agent", USER_AGENT)
                addRequestHeader("Referer", "https://www.instagram.com/")
                addRequestHeader("Accept", "video/mp4,video/*;q=0.9,image/webp,image/*,*/*;q=0.8")
                addRequestHeader("Accept-Language", "en-US,en;q=0.5")
                addRequestHeader("DNT", "1")

                setTitle(filename)
                setDescription("Downloading from Instagram")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType(if (type == "video") "video/mp4" else "image/jpeg")

                // Save to public Downloads directory in our sub-folder
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$subDir${File.separator}$filename"
                )

                // Scan file so it appears in gallery
                allowScanningByMediaScanner()
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            Log.d(TAG, "Download enqueued — id=$downloadId filename=$filename")

            runOnUiThread {
                Toast.makeText(
                    this,
                    "⬇ Downloading $filename",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "enqueueDownload failed: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
