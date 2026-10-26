package com.vertin.toutouliao.chat

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vertin.toutouliao.BuildConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ChatActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // Plain View hierarchy: a WebView that explicitly MATCH_PARENTs the window.
        // Compose's AndroidView was leaving the WebView at its content height on some devices.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val view = createWebView(this, url)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            // System bars only. Do NOT shrink the WebView for IME here: a stale or
            // full-height IME inset is what made the chat page render as a short strip.
            // The web page tracks visualViewport height for the keyboard instead.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            },
        )
    }

    private fun createWebView(context: android.content.Context, url: String): WebView {
        val view = WebView(context)
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            // The server sends no-cache for assets, but an upgraded app can still
            // reuse an old WebView cache entry for /app.js or /style.css.
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (target.scheme == "toutouliao" && target.host == "exit") {
                    finish()
                    return true
                }
                if (target.scheme == "http" || target.scheme == "https") {
                    if (target.host == "127.0.0.1" || target.host == "localhost") return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, target))
                        true
                    }.getOrDefault(true)
                }
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@ChatActivity,
                        "聊天页加载失败：${error.description}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                webView = null
                view.destroy()
                Toast.makeText(this@ChatActivity, "聊天页崩溃，请重开", Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return runCatching {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                }.getOrElse {
                    pendingFileCallback = null
                    false
                }
            }
        }
        view.setDownloadListener(DownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
            handleDownload(downloadUrl, contentDisposition, mimeType)
        })
        webView = view
        view.clearCache(true)
        view.loadUrl(url)
        return view
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun handleDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val filename = guessFileName(url, contentDisposition)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
            }
            getSystemService(DownloadManager::class.java)?.enqueue(request)
            Toast.makeText(this, "开始下载 $filename", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessFileName(url: String, contentDisposition: String?): String {
        val fromHeader = contentDisposition?.let { header ->
            Regex("filename\\*=UTF-8''([^;]+)").find(header)?.groupValues?.getOrNull(1)
                ?.let { encoded -> runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString()) }.getOrNull() }
                ?: Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.getOrNull(1)
        }
        val fallback = Uri.parse(url).lastPathSegment
        val raw = fromHeader ?: fallback ?: "download"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .take(120)
            .ifBlank { "download" }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
