package com.web2app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import android.widget.FrameLayout
import android.webkit.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.web2app.data.AppRepository
import com.web2app.handlers.PermissionsHandler
import com.web2app.models.parseAppConfig

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var noInternetView: View

    /** Holder for the configured page loader; null/false means use the default spinner. */
    private var pageLoaderHolder: View? = null
    private var useCustomPageLoader = false

    private lateinit var permissionsHandler: PermissionsHandler

    /** Pending WebView file-input callback; must be invoked exactly once per chooser. */
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserMultiple = false
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var selectedTabIndex = 0

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        private const val DEFAULT_THEME = 0xFF5B5BD6.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DEMO BUILD: one run only (see DemoGate). The builder's live preview passes an app id and is never gated.
        if (intent.getStringExtra(MainActivity.EXTRA_APP_ID) == null && DemoGate.block(this, savedInstanceState != null)) return

        // Preview mode: load the app being previewed from the builder's storage.
        // Falls back to the bundled assets/app_settings.json when launched standalone.
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val isPreview = appId != null
        appConfig = if (isPreview) {
            AppRepository(this).getApp(appId!!) ?: appConfig
        } else {
            parseAppConfig(readJson(this, "app_settings.json"))
        }

        // Setup permissions handler (must be created before setContentView for ActivityResult)
        permissionsHandler = PermissionsHandler(this)

        // System file/photo picker for <input type="file"> — needs no media permission.
        // Registered here (before STARTED) so it's ready when onShowFileChooser fires.
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            fileUploadCallback?.onReceiveValue(pickedUris(result.resultCode, result.data))
            fileUploadCallback = null
        }

        setContentView(R.layout.activity_main)
        applySystemBarInsets(R.id.mainRoot, R.id.statusBarScrim)
        applyBrandSurfaces()

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        bottomNav = findViewById(R.id.bottomNav)
        noInternetView = findViewById(R.id.noInternetView)

        // Show the preview close button (faint eye behind a clear X) only when
        // previewing an app from the builder; tapping it dismisses the preview.
        findViewById<View>(R.id.previewControls).apply {
            visibility = if (isPreview) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }

        setupPageLoader()
        setupWebView()
        setupBottomNav()
        setupConnectivity()
        requestConfiguredPermissions()

        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.isEnabled = appConfig.pullRefresh

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadCurrentUrl()
        }
    }

    // ─── Page loader ─────────────────────────────────────────────────────────

    /**
     * Builds the configured in-page loader into [R.id.pageLoaderHolder]. When the page
     * loader isn't enabled we fall back to the default centered [progressBar].
     */
    private fun setupPageLoader() {
        val holder = findViewById<FrameLayout>(R.id.pageLoaderHolder)
        pageLoaderHolder = holder
        val pl = appConfig.pageLoader
        if (!pl.enabled) {
            useCustomPageLoader = false
            return
        }
        val theme = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        val icon = Base64ImageUtil.base64ToBitmap(appConfig.appIcon)
        val opts = PageLoaderViews.Opts(
            card = pl.card,
            cardColor = safeParseColor(pl.cardColor, Color.WHITE),
            cardOpacity = pl.cardOpacity,
            cardRadius = pl.cardRadius,
            gap = pl.gap,
            loaderWidth = pl.loaderWidth,
            loaderThickness = pl.loaderThickness
        )
        holder.removeAllViews()
        holder.addView(PageLoaderViews.create(this, pl.style, icon, theme, opts))
        useCustomPageLoader = true
    }

    /** Shows/hides whichever loader is in use (configured page loader or the default). */
    private fun showLoading(show: Boolean) {
        val target = if (useCustomPageLoader) pageLoaderHolder else progressBar
        target?.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ─── WebView ───────────────────────────────────────────────────────────────

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Allow mixed content (http resources on https pages) – mirrors reference Webview.kt
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Custom user-agent matching the reference
            userAgentString =
                "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Enable cookies (including third-party) – mirrors reference Webview.kt
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                view.loadUrl(request.url.toString())
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                showLoading(false)
                swipeRefresh.isRefreshing = false
            }
        }

        // WebChromeClient with popup-window support – mirrors reference Webview.kt onCreateWindow
        webView.webChromeClient = object : WebChromeClient() {
            // Camera/mic (getUserMedia) and location requests from the page.
            override fun onPermissionRequest(request: PermissionRequest) =
                permissionsHandler.onWebPermissionRequest(request)

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) = permissionsHandler.onWebGeolocationRequest(origin, callback)

            // Handles <input type="file"> taps via the Android system picker (zero media
            // permissions). params.createIntent() honours the input's `accept` types and
            // `multiple` attribute; parseResult() pairs with it to return the picked URIs.
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Release any previous callback so an abandoned input never dead-locks.
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                fileChooserMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val ctx = view?.context ?: return false

                val popupWebView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    webViewClient = object : WebViewClient() {}
                    webChromeClient = permissionsHandler.webChromeClient()
                }

                android.app.Dialog(ctx).apply {
                    setContentView(popupWebView)
                    window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    show()
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    // ─── Bottom Navigation ─────────────────────────────────────────────────────

    private fun setupBottomNav() {
        if (!appConfig.showTabbar || appConfig.tabbars.isEmpty()) {
            bottomNav.visibility = View.GONE
            return
        }

        bottomNav.visibility = View.VISIBLE

        // targetSdk 35+ edge-to-edge: the root (mainRoot) already pads for the
        // navigation-bar inset, but Material's BottomNavigationView ALSO adds its own
        // bottom inset padding by default. That double-padding eats into our fixed bar
        // height and collapses the icon row to a blank white strip. Neutralise the
        // view's own inset handling so the icons + labels fill the full fixed height.
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
            insets
        }

        // Apply colours from tabSettings (mirrors BottomNavigationBar composable)
        val activeColor = safeParseColor(appConfig.tabSettings.tabActiveColor, Color.BLUE)
        val inactiveColor = safeParseColor(appConfig.tabSettings.tabInactiveColor, Color.GRAY)

        // Modern, flat tabbar (no line/elevation, matching Google Play).
        bottomNav.setBackgroundColor(brandBarColor())

        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(activeColor, inactiveColor)
        )
        bottomNav.itemIconTintList = colorStateList
        bottomNav.itemTextColor = colorStateList
        bottomNav.setItemTextAppearanceActive(R.style.TextAppearance_Web2App_Tab)
        bottomNav.setItemTextAppearanceInactive(R.style.TextAppearance_Web2App_Tab)

        // Match the classic (shorter) bottom bar: drop the Material3 active-indicator
        // pill, which otherwise makes the bar noticeably taller, and use a compact
        // fixed height (a touch shorter when titles are hidden).
        bottomNav.isItemActiveIndicatorEnabled = false
        val showTitles = appConfig.tabSettings.showTitles
        bottomNav.labelVisibilityMode = if (showTitles) {
            com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_LABELED
        } else {
            com.google.android.material.bottomnavigation.BottomNavigationView.LABEL_VISIBILITY_UNLABELED
        }
        val barHeightDp = if (showTitles) 60 else 54
        bottomNav.layoutParams = bottomNav.layoutParams.apply {
            height = (barHeightDp * resources.displayMetrics.density).toInt()
        }

        // Build menu items dynamically from JSON (Base64 icons → BitmapDrawable)
        val menu = bottomNav.menu
        menu.clear()

        // 3px gap between icon and title (only meaningful when titles are shown).
        val iconLabelGap = if (showTitles) 3 else 0
        for ((index, tab) in appConfig.tabbars.withIndex()) {
            val item = menu.add(0, index, index, tab.title)
            if (tab.icon.isNotBlank()) {
                val res = MaterialIcons.resFor(tab.icon)
                val icon: android.graphics.drawable.Drawable? = when {
                    LucideIcons.isLucide(tab.icon) -> {
                        val px = (24 * resources.displayMetrics.density).toInt()
                        LucideIcons.drawable(
                            this, LucideIcons.name(tab.icon), px, android.graphics.Color.BLACK
                        )
                    }
                    res != null -> androidx.core.content.ContextCompat.getDrawable(this, res)
                    else -> Base64ImageUtil.base64ToBitmapDrawable(this, tab.icon)
                }
                item.icon = icon?.let {
                    android.graphics.drawable.InsetDrawable(it, 0, 0, 0, iconLabelGap)
                }
            }
        }

        // Select first tab
        bottomNav.selectedItemId = 0

        bottomNav.setOnItemSelectedListener { item ->
            selectedTabIndex = item.itemId
            loadCurrentUrl()
            true
        }
    }

    private fun loadCurrentUrl() {
        val url = if (appConfig.showTabbar && appConfig.tabbars.isNotEmpty()) {
            appConfig.tabbars.getOrNull(selectedTabIndex)?.url ?: appConfig.websiteURL
        } else {
            appConfig.websiteURL
        }
        webView.loadUrl(url)
    }

    // ─── Network Connectivity (mirrors connectivityState / observeConnectivityAsFlow) ──

    private fun setupConnectivity() {
        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Show correct initial state
        if (isNetworkConnected()) showWebContent() else showNoInternet()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { showWebContent() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { showNoInternet() }
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = connectivityManager ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun showWebContent() {
        swipeRefresh.visibility = View.VISIBLE
        noInternetView.visibility = View.GONE
    }

    private fun showNoInternet() {
        swipeRefresh.visibility = View.GONE
        noInternetView.visibility = View.VISIBLE
    }

    // ─── Permissions (mirrors PermissionsWrapper composable in reference) ──────

    private fun requestConfiguredPermissions() {
        val perms = appConfig.permissions
        val needsLocation = perms.contains(0)
        val needsFileCamera = perms.contains(1)
        val needsAudioVideo = perms.contains(2)

        when {
            needsLocation -> permissionsHandler.requestLocationPermissions {
                if (needsFileCamera) {
                    permissionsHandler.requestFileAndCameraPermissions {
                        if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
                    }
                } else if (needsAudioVideo) {
                    permissionsHandler.requestAudioVideoPermissions()
                }
            }
            needsFileCamera -> permissionsHandler.requestFileAndCameraPermissions {
                if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
            }
            needsAudioVideo -> permissionsHandler.requestAudioVideoPermissions()
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Files picked for <input type="file">. Multi-select pickers (e.g. the Android
     * photo picker's "Add" mode) return them only in clipData, which
     * FileChooserParams.parseResult() ignores, so read both.
     */
    private fun pickedUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clip = data.clipData
        val uris = if (clip != null && clip.itemCount > 0) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        if (uris.isEmpty()) return null
        return if (fileChooserMultiple) uris.toTypedArray() else arrayOf(uris.first())
    }

    private fun safeParseColor(hex: String, default: Int): Int {
        return try {
            if (hex.isBlank()) default else Color.parseColor(hex)
        } catch (e: Exception) {
            default
        }
    }

    /** Blends [color] toward white, keeping [fraction] (0–1) of the original colour. */
    private fun lightTint(color: Int, fraction: Float): Int {
        fun mix(c: Int) = (c * fraction + 255 * (1 - fraction)).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color.red(color)), mix(Color.green(color)), mix(Color.blue(color)))
    }

    /**
     * The brand surface colour shared by the top status-bar scrim and the bottom tabbar
     * (Google-Play style). Defaults to a very light tint of the theme (active) colour;
     * a custom bar colour set in the builder overrides it.
     */
    private fun brandBarColor(): Int {
        val activeColor = safeParseColor(appConfig.tabSettings.tabActiveColor, Color.BLUE)
        val rawBarColor = safeParseColor(appConfig.tabSettings.tabBarColor, Color.WHITE)
        return if (rawBarColor == Color.WHITE) lightTint(activeColor, 0.08f) else rawBarColor
    }

    /**
     * Colours the two system-bar regions:
     *  - status bar (top)  → the plain theme colour from settings, with matching icons;
     *  - navigation bar (bottom) → the tabbar background tint, so it blends with the bar.
     * The scrim fills the status-bar strip; the root's bottom inset-padding shows behind
     * the nav bar, so colouring the root fills that strip.
     */
    private fun applyBrandSurfaces() {
        val statusColor = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        val navColor = brandBarColor()

        findViewById<View>(R.id.statusBarScrim).setBackgroundColor(statusColor)
        findViewById<View>(R.id.mainRoot).setBackgroundColor(navColor)

        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            // Dark primary status bar → light icons; light nav surface → dark icons.
            isAppearanceLightStatusBars =
                androidx.core.graphics.ColorUtils.calculateLuminance(statusColor) > 0.5
            isAppearanceLightNavigationBars =
                androidx.core.graphics.ColorUtils.calculateLuminance(navColor) > 0.5
        }
        // In 3-button nav mode the system draws a translucent contrast scrim behind the
        // buttons, which hides our colour. Opt out so the nav surface shows through.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            window.navigationBarColor = navColor
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
    }
}
