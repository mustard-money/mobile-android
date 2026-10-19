package com.mustard.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

/**
 * The whole app: a full-screen web view showing the PWA, with the native pieces a browser tab
 * would otherwise provide (splash, offline message, dialogs, file picking, somewhere for outside
 * links to open) and the ones it can't (document scanning, through [NativeBridge]).
 */
class MainActivity : ComponentActivity() {
    private lateinit var config: AppConfig
    private lateinit var bridge: NativeBridge
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusBarBackground: View
    private lateinit var navigationBarBackground: View
    private lateinit var errorView: View

    private var keepSplash = true
    private var isShowingLoadError = false
    private var hasDocumentStartScript = false
    private val handler = Handler(Looper.getMainLooper())

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var captureUri: Uri? = null
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val clip = result.data?.clipData
        val uris = if (clip != null) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        }
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        fileCallback?.onReceiveValue(captureUri?.takeIf { taken }?.let { arrayOf(it) })
        fileCallback = null
        captureUri = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Load as soon as a connection comes back, without waiting for "Try Again".
        override fun onAvailable(network: Network) {
            handler.post { if (isShowingLoadError) load() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { keepSplash }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        config = AppConfig.from(intent)
        bridge = NativeBridge(this, ::applyPageColors)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = false
            // Appended to Chrome's user agent, so the site and server can tell this app apart.
            settings.userAgentString = "${settings.userAgentString} MustardAndroid/${BuildConfig.VERSION_NAME}"
            // No white flash before the page paints in dark mode.
            setBackgroundColor(ContextCompat.getColor(context, R.color.pageBackground))
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = Client()
            webChromeClient = ChromeClient()
            setDownloadListener { url, _, _, _, _ -> openExternally(Uri.parse(url)) }
        }
        if (bridge.attach(webView, config.origin) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridge.script(), setOf(config.origin))
            hasDocumentStartScript = true
        }

        statusBarBackground = View(this)
        navigationBarBackground = View(this)
        errorView = makeErrorView().apply { visibility = View.GONE }
        root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pageBackground))
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(statusBarBackground, FrameLayout.LayoutParams(MATCH_PARENT, 0, Gravity.TOP))
            addView(navigationBarBackground, FrameLayout.LayoutParams(MATCH_PARENT, 0, Gravity.BOTTOM))
            addView(errorView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        // Edge to edge, the web view sits between the system bars (and above the keyboard),
        // with the page's own colors painted behind the bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            webView.updateLayoutParams<FrameLayout.LayoutParams> { setMargins(bars.left, bars.top, bars.right, bottom) }
            errorView.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            statusBarBackground.updateLayoutParams { height = bars.top }
            navigationBarBackground.updateLayoutParams { height = bottom }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isShowingLoadError && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        // Never leave the splash up if the page is slow to say it has loaded.
        handler.postDelayed({ keepSplash = false }, 8_000)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (isShowingLoadError) {
            load()
        } else {
            // A browser looks for a new service worker when you open the site, but this page can
            // stay open for days. Checking on every return picks up new deployments; the page
            // reloads itself once one takes over.
            webView.evaluateJavascript("navigator.serviceWorker?.getRegistration().then(r => r?.update()).catch(() => {}); null", null)
        }
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    // MARK: Loading

    private fun load() {
        if (isShowingLoadError) {
            isShowingLoadError = false
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
        val current = webView.url?.let(Uri::parse)
        if (current != null && config.isInScope(current)) webView.reload() else webView.loadUrl(config.startUrl.toString())
    }

    private fun showLoadError() {
        keepSplash = false
        isShowingLoadError = true
        webView.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
        applyPageColors(null, null)
    }

    private fun makeErrorView(): View {
        fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(context, R.color.pageBackground))
            addView(TextView(context).apply {
                setText(R.string.load_error_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                setText(R.string.load_error_message)
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(8), dp(32), dp(24))
            })
            addView(
                Button(context).apply {
                    setText(R.string.try_again)
                    isAllCaps = false
                    // White isn't readable on mustard yellow.
                    setTextColor(ContextCompat.getColor(context, R.color.onAccent))
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                    setOnClickListener { load() }
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
    }

    // MARK: System bars

    /** Paints the system bars in the page's colors (or the app's, with null), keeping their icons readable. */
    private fun applyPageColors(top: String?, bottom: String?) {
        val fallback = ContextCompat.getColor(this, R.color.pageBackground)
        val topColor = top?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback
        val bottomColor = bottom?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: topColor
        statusBarBackground.setBackgroundColor(topColor)
        navigationBarBackground.setBackgroundColor(bottomColor)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !isDark(topColor)
            isAppearanceLightNavigationBars = !isDark(bottomColor)
        }
    }

    private fun isDark(color: Int) =
        0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color) < 128

    // MARK: Links that leave the app

    private fun openExternally(url: Uri) {
        try {
            if (url.scheme == "http" || url.scheme == "https") {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url)
            } else {
                // mailto:, tel: and the like.
                startActivity(Intent(Intent.ACTION_VIEW, url))
            }
        } catch (_: ActivityNotFoundException) {
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            if (config.isInScope(url) || url.scheme in setOf("about", "blob", "data")) return false
            openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Older WebViews without document-start scripts get the bridge as early as they can.
            if (!hasDocumentStartScript && config.isInScope(Uri.parse(url))) view.evaluateJavascript(bridge.script(), null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            keepSplash = false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showLoadError()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // Android can stop the page's process to reclaim memory; start over with a new web view.
            recreate()
            return true
        }
    }

    // MARK: Dialogs and file picking

    private inner class ChromeClient : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            dialog(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            dialog(message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue) }
            dialog(message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        /** `<input type="file">`, and with `capture`, the camera. */
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            try {
                if (params.isCaptureEnabled && params.acceptTypes.any { it.startsWith("image/") }) {
                    val file = File(cacheDir, "captures").apply { mkdirs() }.let { File(it, "photo-${System.currentTimeMillis()}.jpg") }
                    captureUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    takePicture.launch(captureUri!!)
                } else {
                    val intent = params.createIntent()
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    pickFiles.launch(intent)
                }
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                return false
            }
            return true
        }
    }

    /** The PWA asks with `confirm("Title\n\nDetails")`; show that as a titled dialog. */
    private fun dialog(message: String): AlertDialog.Builder {
        val builder = AlertDialog.Builder(this)
        val gap = message.indexOf("\n\n")
        return if (gap >= 0) {
            builder.setTitle(message.substring(0, gap)).setMessage(message.substring(gap + 2))
        } else {
            builder.setMessage(message)
        }
    }
}
