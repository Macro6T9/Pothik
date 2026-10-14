package com.pothik.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private class Site(val view: WebView) {
        var rev: Int = Int.MIN_VALUE
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout   // সাইটের WebView-গুলো এখানে থাকে (নিচের স্তর)
    private lateinit var ui: PassThroughWebView // পথিকের UI (উপরের স্বচ্ছ স্তর)
    private lateinit var fsHost: FrameLayout    // ফুলস্ক্রিন ভিডিও

    private val sites = LinkedHashMap<String, Site>()
    private var privateMode = false
    private var uiReady = false
    private var pendingUrl: String? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var permAction: ((Map<String, Boolean>) -> Unit)? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCb
            fileCb = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            val a = permAction
            permAction = null
            a?.invoke(r)
        }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        content = FrameLayout(this)
        ui = PassThroughWebView(this)
        fsHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(match, match))
        root.addView(ui, FrameLayout.LayoutParams(match, match))
        root.addView(fsHost, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        applyTheme(false)
        configureUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBack()
            }
        })

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        sites.values.forEach { it.view.onResume() }
    }

    override fun onPause() {
        sites.values.forEach { it.view.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        sites.values.forEach { destroySite(it) }
        sites.clear()
        ui.destroy()
        super.onDestroy()
    }

    private fun onBack() {
        if (customView != null) {
            hideCustom()
            return
        }
        ui.evaluateJavascript("window.nativeBack?window.nativeBack():false") { r ->
            if (r != "true") finish()
        }
    }

    // ───────────────────────── UI WebView ─────────────────────────

    private fun configureUi() {
        val s = ui.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.textZoom = 100
        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.overScrollMode = View.OVER_SCROLL_NEVER

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        ui.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            // UI পেজ কখনো অন্য ঠিকানায় যাবে না
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onPageFinished(view: WebView, url: String?) {
                uiReady = true
                flushPending()
            }
        }
        ui.addJavascriptInterface(Bridge(), "AndroidBridge")
        ui.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private fun js(call: String) {
        ui.post { ui.evaluateJavascript("window.__nat&&window.__nat.$call", null) }
    }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW && (d.scheme == "http" || d.scheme == "https")) {
            pendingUrl = d.toString()
            flushPending()
        }
    }

    private fun flushPending() {
        val u = pendingUrl ?: return
        if (!uiReady) return
        pendingUrl = null
        js("openUrl(${q(u)})")
    }

    // ───────────────────────── JS ব্রিজ ─────────────────────────

    inner class Bridge {
        @JavascriptInterface
        fun sync(json: String) {
            runOnUiThread { applySync(json) }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { openExternally(url) }
        }

        @JavascriptInterface
        fun setTheme(dark: Boolean) {
            runOnUiThread { applyTheme(dark) }
        }

        @JavascriptInterface
        fun setPrivate(on: Boolean) {
            runOnUiThread {
                privateMode = on
                sites.values.forEach { applySiteSettings(it.view) }
            }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }

    private fun applyTheme(dark: Boolean) {
        val c = if (dark) 0xFF3A372F.toInt() else 0xFFCFC9B8.toInt()
        content.setBackgroundColor(c)
        root.setBackgroundColor(c)
        @Suppress("DEPRECATION")
        window.statusBarColor = if (dark) 0xFF0B4C52.toInt() else 0xFF0D5C63.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = c
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = !dark
    }

    /** JS থেকে আসা অবস্থা অনুযায়ী সাইট WebView-গুলোর তৈরি, অবস্থান, লোড ও মোছা। */
    private fun applySync(json: String) {
        val o = JSONObject(json)
        ui.overlayOpen = o.getBoolean("o")
        val arr = o.getJSONArray("v")
        val d = resources.displayMetrics.density
        val seen = HashSet<String>()
        val holes = ArrayList<RectF>()

        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val key = v.getString("k")
            seen.add(key)
            val st = sites.getOrPut(key) { createSite(key) }
            val wv = st.view

            val z = v.getInt("z")
            if (wv.settings.textZoom != z) wv.settings.textZoom = z

            var visible = false
            if (v.optBoolean("vis", false)) {
                val l = (v.getDouble("l") * d).roundToInt()
                val t = (v.getDouble("t") * d).roundToInt()
                val w = (v.getDouble("w") * d).roundToInt()
                val h = (v.getDouble("h") * d).roundToInt()
                if (w > 0 && h > 0) {
                    val lp = wv.layoutParams as FrameLayout.LayoutParams
                    if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
                        lp.leftMargin = l
                        lp.topMargin = t
                        lp.width = w
                        lp.height = h
                        wv.layoutParams = lp
                    }
                    visible = true
                    holes.add(RectF(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat()))
                }
            }
            wv.visibility = if (visible) View.VISIBLE else View.GONE

            val rev = v.getInt("r")
            if (st.rev != rev) {
                st.rev = rev
                wv.loadUrl(v.getString("u"))
            }
        }

        val it = sites.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen) {
                destroySite(e.value)
                it.remove()
            }
        }
        ui.holes = holes
    }

    private fun destroySite(st: Site) {
        content.removeView(st.view)
        st.view.stopLoading()
        st.view.destroy()
    }

    // ───────────────────────── সাইট WebView ─────────────────────────

    private fun cleanUa(ua: String): String =
        ua.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")

    private fun applySiteSettings(wv: WebView) {
        wv.settings.cacheMode =
            if (privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    private fun createSite(key: String): Site {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(true)
        s.userAgentString = cleanUa(s.userAgentString)
        applySiteSettings(wv)

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = siteClient(key)
        wv.webChromeClient = siteChrome(key)
        wv.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) }

        wv.visibility = View.GONE
        content.addView(wv, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.START))
        return Site(wv)
    }

    private fun siteClient(key: String) = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: ""
            return when (scheme) {
                "http", "https", "about", "data", "blob", "javascript", "ws", "wss" -> false
                else -> {
                    handleExternal(request.url.toString())
                    true
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                js("nav(${q(key)},${q(url)})")
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            js("done(${q(key)})")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            if (error.errorCode == ERROR_UNKNOWN) return
            showError(view, request.url.toString(), error.description?.toString() ?: "")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError(view, error.url ?: "", "SSL certificate error")
        }
    }

    private fun siteChrome(key: String) = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            js("progress(${q(key)},$newProgress)")
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            js("title(${q(key)},${q(title ?: "")})")
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCb = callback
            fsHost.addView(view, FrameLayout.LayoutParams(match, match))
            fsHost.visibility = View.VISIBLE
            val ctl = WindowCompat.getInsetsController(window, window.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }

        override fun onHideCustomView() {
            hideCustom()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = filePathCallback
            return try {
                fileLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                fileCb = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val need = ArrayList<String>()
                for (r in request.resources) {
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> need.add(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> need.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                ensure(need) { ok ->
                    if (ok) request.grant(request.resources) else request.deny()
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { ok ->
                callback.invoke(origin, ok, false)
            }
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        fsHost.removeView(v)
        fsHost.visibility = View.GONE
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun ensure(perms: List<String>, cb: (Boolean) -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            cb(true)
            return
        }
        permAction = { r -> cb(missing.all { r[it] == true }) }
        permLauncher.launch(missing.toTypedArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun showError(view: WebView, url: String, desc: String) {
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;text-align:center;padding:12vh 24px;color:#1f2a37;background:#f7f2e4}
h2{color:#0d5c63}button{padding:10px 22px;font-size:16px;border:0;border-radius:18px;background:#0d5c63;color:#fff}</style></head>
<body><h2>পেজ খোলা যায়নি</h2><p>Couldn't load this page</p>
<p style="word-break:break-all;opacity:.7">${esc(url)}</p><p style="opacity:.7">${esc(desc)}</p>
<button onclick="location.replace(location.href)">আবার চেষ্টা করুন / Retry</button></body></html>"""
        view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
    }

    // ───────────────────────── ডাউনলোড ও বাইরের অ্যাপ ─────────────────────────

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("এই ধরনের ডাউনলোড সমর্থিত নয়")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { ok ->
                if (ok) startDownload(url, ua, cd, mime)
            }
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("ডাউনলোড শুরু: $name")
        } catch (e: Exception) {
            toast("ডাউনলোড করা গেল না")
        }
    }

    private fun handleExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            startActivity(intent)
        } catch (e: Exception) {
            toast("এই লিংক খোলার মতো অ্যাপ নেই")
        }
    }

    private fun openExternally(url: String) {
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val chooser = Intent.createChooser(view, null)
            chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this, MainActivity::class.java))
            )
            startActivity(chooser)
        } catch (e: Exception) {
            toast("খোলা গেল না")
        }
    }
}
