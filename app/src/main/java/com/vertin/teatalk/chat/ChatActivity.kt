package com.vertin.teatalk.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vertin.teatalk.BuildConfig
import com.vertin.teatalk.ui.TeaSettings
import com.vertin.teatalk.ui.TeaTalkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** Streams downloads itself instead of handing them to the system download service. */
    private lateinit var downloader: InAppDownloader

    /** Compose overlay stacked above the WebView; sized by [applyOverlayLayout]. */
    private var overlayView: ComposeView? = null

    /** Expanded state of the download sheet; set when a download starts. */
    private val sheetExpanded = MutableStateFlow(false)

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
        // Process-wide, like HostRuntime: leaving the chat page must not abort a transfer
        // that is already streaming.
        downloader = InAppDownloader.get(this)

        var url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // The chat page follows the app appearance preference, so the WebView ground,
        // the system bar icons and the download sheet all agree with it.
        val dark = TeaSettings.isDark(this)
        val ground = if (dark) PAPER_DARK else PAPER_LIGHT
        // A pinned theme is forced onto the page once, before the first load; the
        // system case keeps the page's own prefers-color-scheme handling.
        TeaSettings.webThemeParam(this)?.let { theme ->
            url += (if (url.contains('?')) "&" else "?") + "theme=" + theme
        }

        // Plain View hierarchy: a WebView that explicitly MATCH_PARENTs the window.
        // Compose's AndroidView was leaving the WebView at its content height on some devices.
        val root = FrameLayout(this).apply { setBackgroundColor(ground) }
        val view = createWebView(this, url, ground)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            // Apply the IME inset only while the keyboard is actually visible. An
            // unconditional stale IME inset is what previously collapsed the WebView
            // to a short strip; this keeps the composer above the keyboard.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val bottomInset = if (imeVisible) maxOf(bars.bottom, ime.bottom) else bars.bottom
            target.setPadding(bars.left, bars.top, bars.right, bottomInset)
            insets
        }
        // The download overlay floats over the untouched WebView. The root padding
        // above already keeps it clear of the system bars, so the Compose side adds none.
        // Its size follows applyOverlayLayout(): gone while idle, a pill-sized
        // WRAP_CONTENT box while collapsed (so the page keeps its touches), full width
        // for the sheet.
        val overlay = createDownloadOverlay(dark)
        overlayView = overlay
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        setContentView(root)
        lifecycleScope.launch { downloader.tasks.collect { applyOverlayLayout() } }
        lifecycleScope.launch { sheetExpanded.collect { applyOverlayLayout() } }
        applyOverlayLayout()

        // Keep the status bar time/battery readable against the page ground.
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
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

    private fun createWebView(context: android.content.Context, url: String, ground: Int): WebView {
        val view = WebView(context)
        view.setBackgroundColor(ground)
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
                if (target.scheme == "teatalk" && target.host == "exit") {
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
            // Deliberately not the system download service: the engine streams the file
            // into the app's own Download directory and the sheet shows the progress.
            downloader.enqueue(
                url = downloadUrl,
                name = guessDownloadFileName(downloadUrl, contentDisposition),
                mime = mimeType,
            )
            sheetExpanded.value = true
        })
        webView = view
        view.clearCache(true)
        view.loadUrl(url)
        return view
    }

    /**
     * Compose layer stacked on the WebView: only the download sheet or its collapsed
     * pill occupies space, so the page stays touchable everywhere above it.
     */
    private fun createDownloadOverlay(dark: Boolean): ComposeView =
        ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TeaTalkTheme(darkTheme = dark) {
                    val downloads by downloader.tasks.collectAsState()
                    val expanded by sheetExpanded.collectAsState()
                    DownloadOverlay(
                        downloads = downloads,
                        expanded = expanded,
                        onExpand = { sheetExpanded.value = true },
                        onCollapse = { sheetExpanded.value = false },
                        onCancel = { downloader.cancel(it) },
                        onOpen = { openDownloadedFile(it) },
                        onDismissTask = { downloader.dismiss(it) },
                    )
                }
            }
        }

    /** Opens a finished (API 29+) download through the MediaStore uri the engine kept. */
    private fun openDownloadedFile(task: DownloadTask) {
        val uri = task.contentUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, task.mime?.takeIf { it.isNotBlank() } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "没有可以打开这个文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Keeps the overlay from stealing touches: hidden while there is nothing to show,
     * pill-sized while collapsed (the WebView keeps everything around it), full width for
     * the sheet. The pill's clearance lives in the layout margin rather than in Compose
     * padding so the transparent ComposeView stops above the page's composer.
     */
    private fun applyOverlayLayout() {
        val view = overlayView ?: return
        val hasTasks = downloader.tasks.value.isNotEmpty()
        view.visibility = if (hasTasks) View.VISIBLE else View.GONE
        if (!hasTasks) return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val expanded = sheetExpanded.value
        params.width = if (expanded) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.bottomMargin = if (expanded) 0 else pillClearancePx()
        view.layoutParams = params
    }

    private fun pillClearancePx(): Int = (PILL_CLEARANCE_DP * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // The downloader is process-wide: an in-flight transfer survives leaving this page.
        overlayView = null
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        /** Must match --paper in web/style.css and @color/paper in colors.xml. */
        private const val PAPER_LIGHT = 0xFFFAF8F3.toInt()

        /** Must match the dark --paper token in web/style.css. */
        private const val PAPER_DARK = 0xFF121110.toInt()

        /** Clearance above the page composer; the web composer is ~64–88dp tall. */
        private const val PILL_CLEARANCE_DP = 78f
    }
}
