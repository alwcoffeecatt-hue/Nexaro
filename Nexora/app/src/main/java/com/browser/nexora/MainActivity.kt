package com.browser.nexora

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * Nexora for Android.
 *
 * Layout (bottom to top):
 *   pages  - one real WebView per tab, sized to the page area the HTML UI reports
 *   ui     - a transparent WebView showing assets/nexora.html (toolbar, menus, settings, tabs...)
 *
 * The UI WebView lets touches fall through to the page below whenever the touch is inside the
 * page area and no menu is open, so pages stay fully interactive.
 */
class MainActivity : Activity() {

    private class PageTab(val id: Int, val web: WebView) {
        var last = ""          // last URL we sent to / received from this WebView
        var pending = false    // a load we started is still settling (redirects count as "replace")
        var shown = true
        var profile: String? = null   // set for incognito tabs: their own cookie/cache/storage profile
    }

    private class UiWebView(c: Context) : WebView(c) {
        var passRect: Rect? = null
        private var pass = false
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val r = passRect
                pass = r != null && r.contains(e.x.toInt(), e.y.toInt())
            }
            return if (pass) false else super.dispatchTouchEvent(e)
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var ui: UiWebView

    private val main = Handler(Looper.getMainLooper())
    private val tabs = HashMap<Int, PageTab>()
    private val downloads = LinkedHashMap<Long, String>()
    private var curId = -1
    private var geom = IntArray(4)
    private var defaultUa = ""
    private var uiReady = false
    private var pendingUrl: String? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var lastState: JSONObject? = null
    private var lastBg = ""

    // incognito isolation
    private var incUsed = false

    // background tab loading: one tab at a time, so the visible page keeps the bandwidth
    private var bgOn = true
    private var preloading: PageTab? = null
    private val preloadTimeout = Runnable { finishPreload() }

    // secure DNS: WebView is pointed at DohProxy, which looks hostnames up over DNS-over-HTTPS
    private val proxy = DohProxy()
    private var dnsApplied: String? = null      // provider the proxy override is currently set up for
    private var dnsCfgKey = ""
    private var dnsBusy = false                 // proxy override change in flight; pages wait for it
    private var dnsAuto = true
    private var lastDns = ""
    private var countQueued = false
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val mainExec = Executor { main.post(it) }
    private val pushCount = Runnable {
        countQueued = false
        runJs("window.nxDnsCount&&nxDnsCount(${proxy.lookups})")
    }

    // fullscreen video
    private var fsView: View? = null
    private var fsCallback: WebChromeClient.CustomViewCallback? = null
    private var fsTab = -1

    // camera / microphone / location prompts
    private val prefs by lazy { getSharedPreferences("site_permissions", MODE_PRIVATE) }
    private var permReq: PermissionRequest? = null
    private var permDialog: AlertDialog? = null
    private var geoOrigin: String? = null
    private var geoCb: GeolocationPermissions.Callback? = null
    private var geoDialog: AlertDialog? = null

    private companion object {
        const val INCOGNITO = "nexora_incognito"
        const val MAX_PRELOAD = 8
        const val REQ_MEDIA = 78
        const val REQ_GEO = 79

        // Chrome-style "current provider" upgrade: if the network's DNS servers belong to one of
        // these, the same provider's DoH address is used. IPv6 uses Java's expanded text form.
        val AUTO_DOH = mapOf(
            "8.8.8.8" to "https://dns.google/dns-query",
            "8.8.4.4" to "https://dns.google/dns-query",
            "2001:4860:4860:0:0:0:0:8888" to "https://dns.google/dns-query",
            "2001:4860:4860:0:0:0:0:8844" to "https://dns.google/dns-query",
            "1.1.1.1" to "https://chrome.cloudflare-dns.com/dns-query",
            "1.0.0.1" to "https://chrome.cloudflare-dns.com/dns-query",
            "2606:4700:4700:0:0:0:0:1111" to "https://chrome.cloudflare-dns.com/dns-query",
            "2606:4700:4700:0:0:0:0:1001" to "https://chrome.cloudflare-dns.com/dns-query",
            "9.9.9.9" to "https://dns.quad9.net/dns-query",
            "149.112.112.112" to "https://dns.quad9.net/dns-query",
            "208.67.222.222" to "https://doh.opendns.com/dns-query",
            "208.67.220.220" to "https://doh.opendns.com/dns-query",
            "185.228.168.168" to "https://doh.cleanbrowsing.org/doh/family-filter/",
            "185.228.169.168" to "https://doh.cleanbrowsing.org/doh/family-filter/"
        )
    }

    private var jsOn = true
    private var cookiesOn = true
    private var popupsOn = false
    private var desktopOn = false
    private var langs = "en"
    @Volatile private var adOn = true
    @Volatile private var trkOn = true

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "pubmatic.com",
        "rubiconproject.com", "amazon-adsystem.com", "moatads.com", "adsrvr.org", "popads.net",
        "propellerads.com"
    )
    private val trackHosts = listOf(
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "hotjar.com",
        "connect.facebook.net", "mixpanel.com", "segment.io", "fullstory.com", "quantserve.com"
    )

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#18191b"))
        pages = FrameLayout(this)
        pages.visibility = View.GONE
        ui = UiWebView(this)
        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.overScrollMode = View.OVER_SCROLL_NEVER
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(pages, FrameLayout.LayoutParams(full, full))
        root.addView(ui, FrameLayout.LayoutParams(full, full))
        setContentView(root)
        applyInsets()

        // Look like Chrome, not like an embedded WebView (many sites treat "; wv" differently)
        defaultUa = ui.settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

        ui.settings.javaScriptEnabled = true
        ui.settings.domStorageEnabled = true
        noDark(ui.settings)
        ui.addJavascriptInterface(Bridge(), "NX")
        ui.webChromeClient = BaseChrome()
        ui.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.toString()?.startsWith("file:///android_asset/") != true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                uiReady = true
                runJs("window.nxCaps&&nxCaps({iso:${incognitoIsolated()},dns:${dnsSupported()}})")
                pendingUrl?.let { openTab(it) }
                pendingUrl = null
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        proxy.onLookup = {
            main.post {
                if (!countQueued) {
                    countQueued = true
                    main.postDelayed(pushCount, 1000)
                }
            }
        }
        watchNetwork()
        // an incognito profile left behind by a crash must not survive into this session
        if (incognitoIsolated()) try { ProfileStore.getInstance().deleteProfile(INCOGNITO) } catch (e: Exception) {}
        ui.loadUrl("file:///android_asset/nexora.html")
        ui.requestFocus()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { goBack() }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_VIEW) return
        val u = i.dataString ?: return
        if (uiReady) openTab(u) else pendingUrl = u
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) goBack() else super.onBackPressed()
    }

    // The HTML decides what "back" means (close menu, leave settings, go back a page...).
    // It answers "true" when it handled the key; otherwise we leave the app.
    private fun goBack() {
        if (fsView != null) {
            hideFullscreen()
            return
        }
        ui.evaluateJavascript("window.nxBackKey?nxBackKey():false") { r ->
            if (r != "true") finish()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        main.removeCallbacks(poller)
        main.removeCallbacks(preloadTimeout)
        main.removeCallbacks(pushCount)
        unwatchNetwork()
        proxy.stop()
        hideFullscreen()
        permDialog?.dismiss()
        geoDialog?.dismiss()
        for (t in tabs.values) destroyTab(t)
        tabs.clear()
        dropIncognitoProfile()
        ui.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooser = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBg(hex: String) {
        val c = try { Color.parseColor(hex) } catch (e: Exception) { return }
        lastBg = hex
        root.setBackgroundColor(c)
        val light = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 160
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = c
            window.navigationBarColor = c
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    @Suppress("DEPRECATION")
    private fun noDark(s: WebSettings) {
        if (Build.VERSION.SDK_INT >= 29) s.forceDark = WebSettings.FORCE_DARK_OFF
    }

    // ---------------------------------------------------------------- JS -> app

    inner class Bridge {
        @JavascriptInterface
        fun sync(json: String) {
            runOnUiThread { applyState(JSONObject(json)) }
        }

        @JavascriptInterface
        fun reload(id: Int) {
            runOnUiThread { tabs[id]?.web?.reload() }
        }

        @JavascriptInterface
        fun find(q: String) {
            runOnUiThread { tabs[curId]?.web?.findAllAsync(q) }
        }

        @JavascriptInterface
        fun findNext() {
            runOnUiThread { tabs[curId]?.web?.findNext(true) }
        }

        @JavascriptInterface
        fun findDone() {
            runOnUiThread { tabs[curId]?.web?.clearMatches() }
        }

        @JavascriptInterface
        fun download(url: String) {
            runOnUiThread { startDownload(url, defaultUa, null, null) }
        }

        @JavascriptInterface
        fun dlRemove(id: String) {
            runOnUiThread { removeDownload(id) }
        }

        @JavascriptInterface
        fun clear(kind: String) {
            runOnUiThread { clearData(kind) }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nexora", text))
            }
        }

        @JavascriptInterface
        fun dnsSettings() {
            runOnUiThread {
                // Private DNS has no public intent; try the settings screen directly, then the network page
                val tries = listOf("android.settings.PRIVATE_DNS_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
                for (a in tries) {
                    try {
                        startActivity(Intent(a))
                        return@runOnUiThread
                    } catch (e: Exception) {
                    }
                }
                toast("Open Settings > Network & internet > Private DNS")
            }
        }

        @JavascriptInterface
        fun share(url: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                startActivity(Intent.createChooser(i, null))
            }
        }

        @JavascriptInterface
        fun openWith(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null))
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
            }
        }
    }

    private fun clearData(kind: String) {
        if (kind == "perms") {
            prefs.edit().clear().apply()
            GeolocationPermissions.getInstance().clearAll()
            return
        }
        if (kind == "cookies") {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            if (incUsed && incognitoIsolated()) try {
                val p = ProfileStore.getInstance().getProfile(INCOGNITO)
                p?.cookieManager?.removeAllCookies(null)
                p?.webStorage?.deleteAllData()
            } catch (e: Exception) {
            }
            for (t in tabs.values) t.web.reload()
        } else if (kind == "cache") {
            for (t in tabs.values) {
                t.web.clearCache(true)
                t.web.reload()
            }
        }
    }

    // ---------------------------------------------------------------- state from the HTML UI

    private fun applyState(s: JSONObject) {
        lastState = s
        if (dnsBusy) return   // re-run by the proxy callback once the override is in place
        bgOn = s.optBoolean("bgl", true)
        jsOn = s.optBoolean("js", true)
        cookiesOn = s.optBoolean("ck", true)
        popupsOn = s.optBoolean("pop", false)
        desktopOn = s.optBoolean("desk", false)
        adOn = s.optBoolean("ad", true)
        trkOn = s.optBoolean("tk", true)
        langs = s.optString("lang", "en").ifEmpty { "en" }
        CookieManager.getInstance().setAcceptCookie(cookiesOn)
        val bg = s.optString("bg", "")
        if (bg.isNotEmpty()) applyBg(bg)

        val show = s.optBoolean("show")
        val overlay = s.optBoolean("ov")
        curId = s.optInt("cur", -1)

        // secure DNS must be in place before any page loads; true means a change is still being applied
        if (syncDns(s.optJSONObject("sd"))) return

        // create / navigate the active tab, remember which tabs still exist
        val live = HashSet<Int>()
        val arr = s.getJSONArray("tabs")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getInt("id")
            live.add(id)
            val url = o.optString("u")
            if (url.isEmpty()) continue
            if (id != curId) {
                // background tab: load it quietly, one at a time, up to MAX_PRELOAD open WebViews
                if (bgOn && tabs[id] == null && preloading == null && tabs.size < MAX_PRELOAD &&
                    (url.startsWith("http://") || url.startsWith("https://"))
                ) {
                    val bt = newTab(id, o.optBoolean("inc"))
                    preloading = bt
                    applySettings(bt)
                    navigate(bt, url)
                    main.postDelayed(preloadTimeout, 20000)
                }
                continue
            }
            val t = tabs[id] ?: newTab(id, o.optBoolean("inc"))
            applySettings(t)
            if (url != t.last) navigate(t, url)
        }
        for (t in tabs.values) applySettings(t)

        // drop WebViews of closed tabs
        val iter = tabs.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in live) {
                destroyTab(e.value)
                iter.remove()
            }
        }
        dropIncognitoProfile()

        // place the page area under the transparent UI
        val r = s.optJSONArray("r")
        val visible = show && r != null && tabs[curId] != null
        pages.visibility = if (visible) View.VISIBLE else View.GONE
        for ((id, t) in tabs) {
            t.web.visibility = if (id == curId) View.VISIBLE else View.GONE
            val want = visible && id == curId
            if (t === preloading && !want) continue   // let a background load finish before pausing it
            if (want != t.shown) {
                t.shown = want
                if (want) t.web.onResume() else t.web.onPause()
            }
        }
        if (visible && r != null) {
            val dpr = s.optDouble("dpr", resources.displayMetrics.density.toDouble())
            val l = (r.getDouble(0) * dpr).toInt()
            val tp = (r.getDouble(1) * dpr).toInt()
            val rt = (r.getDouble(2) * dpr).toInt()
            val bt = (r.getDouble(3) * dpr).toInt()
            val g = intArrayOf(l, tp, rt, bt)
            if (!g.contentEquals(geom)) {
                geom = g
                val lp = FrameLayout.LayoutParams(maxOf(0, rt - l), maxOf(0, bt - tp))
                lp.leftMargin = l
                lp.topMargin = tp
                pages.layoutParams = lp
            }
            // touches inside the page area go to the page, unless a menu is open
            ui.passRect = if (overlay) null else Rect(l, tp, rt, bt)
        } else {
            ui.passRect = null
        }
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(id: Int, incognito: Boolean): PageTab {
        val w = WebView(this)
        val t = PageTab(id, w)
        if (incognito && incognitoIsolated()) {
            // own cookie jar, cache and site storage; must be set before the WebView loads anything
            try {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO)
                WebViewCompat.setProfile(w, INCOGNITO)
                t.profile = INCOGNITO
                incUsed = true
            } catch (e: Exception) {
            }
        }
        w.setBackgroundColor(Color.WHITE)
        val st = w.settings
        st.domStorageEnabled = true
        st.useWideViewPort = true
        st.loadWithOverviewMode = true
        st.setSupportZoom(true)
        st.builtInZoomControls = true
        st.displayZoomControls = false
        st.allowFileAccess = false
        st.allowContentAccess = false
        st.mediaPlaybackRequiresUserGesture = true
        st.userAgentString = defaultUa
        if (incognito) st.cacheMode = WebSettings.LOAD_NO_CACHE
        noDark(st)
        w.webViewClient = pageClient(t)
        w.webChromeClient = pageChrome(t)
        w.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime, cookieFor(t, url)) }
        pages.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs[id] = t
        return t
    }

    private fun destroyTab(t: PageTab) {
        if (fsTab == t.id) hideFullscreen()
        if (preloading === t) {
            preloading = null
            main.removeCallbacks(preloadTimeout)
        }
        pages.removeView(t.web)
        t.web.stopLoading()
        t.web.destroy()
    }

    private fun applySettings(t: PageTab) {
        val st = t.web.settings
        if (st.javaScriptEnabled != jsOn) st.javaScriptEnabled = jsOn
        CookieManager.getInstance().setAcceptThirdPartyCookies(t.web, cookiesOn)
        t.profile?.let { n ->
            try { ProfileStore.getInstance().getProfile(n)?.cookieManager?.setAcceptCookie(cookiesOn) } catch (e: Exception) {}
        }
        st.setSupportMultipleWindows(popupsOn)
        st.javaScriptCanOpenWindowsAutomatically = popupsOn
        val ua = if (desktopOn) desktopUa() else defaultUa
        if (st.userAgentString != ua) {
            st.userAgentString = ua
            if (t.last.isNotEmpty()) t.web.reload()
        }
    }

    private fun desktopUa(): String {
        val v = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "120.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    // The HTML keeps the back/forward list; when it asks for the previous or next entry we use the
    // WebView's own history (keeps scroll position and form state), otherwise we load the URL.
    private fun navigate(t: PageTab, url: String) {
        val list = t.web.copyBackForwardList()
        val i = list.currentIndex
        t.last = url
        t.pending = true
        if (i > 0 && list.getItemAtIndex(i - 1).url == url && t.web.canGoBack()) {
            t.web.goBack()
            return
        }
        if (i >= 0 && i < list.size - 1 && list.getItemAtIndex(i + 1).url == url && t.web.canGoForward()) {
            t.web.goForward()
            return
        }
        t.web.loadUrl(url, mapOf("Accept-Language" to langs))
    }

    private fun pageClient(t: PageTab) = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (t.id == curId) runJs("window.nxLoad&&nxLoad(true)")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            t.pending = false
            if (t.id == curId) runJs("window.nxLoad&&nxLoad(false)")
            if (preloading === t) finishPreload()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url == null || url == t.last || url.startsWith("about:")) return
            val replace = t.pending
            t.pending = false
            t.last = url
            runJs("window.nxPage&&nxPage(${t.id},${JSONObject.quote(url)},$replace)")
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return handleUrl(request?.url)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val host = request?.url?.host ?: return null
            if (!adOn) return null
            val hit = adHosts.any { host == it || host.endsWith(".$it") } ||
                (trkOn && trackHosts.any { host == it || host.endsWith(".$it") })
            return if (hit) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
        }
    }

    // http(s) stays in the browser; mailto:, tel:, intent:, market: ... go to other apps
    private fun handleUrl(u: Uri?): Boolean {
        val s = u?.scheme ?: return false
        if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob" || s == "javascript") return false
        if (s == "file" || s == "content") return true
        try {
            val i = if (s == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, u)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            startActivity(i)
        } catch (e: Exception) {
            toast("No app can open this link")
        }
        return true
    }

    // ---------------------------------------------------------------- dialogs, uploads, popups

    private open inner class BaseChrome : WebChromeClient() {
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
        ): Boolean {
            val input = EditText(this@MainActivity)
            input.setText(defaultValue)
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            chooser?.onReceiveValue(null)
            chooser = filePathCallback
            return try {
                startActivityForResult(fileChooserParams!!.createIntent(), 77)
                true
            } catch (e: Exception) {
                chooser = null
                filePathCallback?.onReceiveValue(null)
                false
            }
        }
    }

    private fun pageChrome(t: PageTab) = object : BaseChrome() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress >= 100) t.pending = false
        }

        // Only reached when the Pop-ups setting is on: open the popup as a new Nexora tab
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            val msg = resultMsg ?: return false
            val tmp = WebView(this@MainActivity)
            var done = false
            fun take(u: String?) {
                if (done || u == null || u == "about:blank") return
                done = true
                openTab(u)
                main.post { tmp.destroy() }
            }
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    take(r?.url?.toString())
                    return true
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    take(url)
                }
            }
            (msg.obj as WebView.WebViewTransport).webView = tmp
            msg.sendToTarget()
            return true
        }

        // ---- titles and favicons, sent to the HTML UI for the tab list and history
        override fun onReceivedTitle(view: WebView?, title: String?) {
            val u = view?.url ?: return
            if (title.isNullOrBlank() || u.startsWith("about:")) return
            runJs("window.nxMeta&&nxMeta(${t.id},${JSONObject.quote(u)},${JSONObject.quote(title)},null)")
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            val u = view?.url ?: return
            val data = icon?.let { iconData(it) } ?: return
            runJs("window.nxMeta&&nxMeta(${t.id},${JSONObject.quote(u)},null,${JSONObject.quote(data)})")
        }

        // ---- fullscreen video
        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null || callback == null) return
            showFullscreen(t.id, view, callback)
        }

        override fun onHideCustomView() {
            hideFullscreen()
        }

        // stops some devices from crashing on the default grey video poster
        override fun getDefaultVideoPoster(): Bitmap? =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // ---- camera, microphone, location
        override fun onPermissionRequest(request: PermissionRequest?) {
            val r = request ?: return
            runOnUiThread { askMedia(r) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
            if (request != null && request === permReq) {
                permDialog?.dismiss()
                permReq = null
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            if (origin == null || callback == null) return
            askGeo(origin, callback)
        }

        override fun onGeolocationPermissionsHidePrompt() {
            geoDialog?.dismiss()
            geoCb = null
        }
    }

    // ---------------------------------------------------------------- secure DNS

    private fun dnsSupported(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    } catch (e: Exception) {
        false
    }

    // Looks at the network's DNS servers. Returns a status plus the DoH address when it can be upgraded.
    private fun detectSystemDns(): Pair<String, String?> {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork ?: return "none" to null) ?: return "none" to null
            if (Build.VERSION.SDK_INT >= 28 && lp.isPrivateDnsActive) return "private" to null
            for (a in lp.dnsServers) {
                val ip = a.hostAddress?.substringBefore('%') ?: continue
                AUTO_DOH[ip]?.let { return "active" to it }
            }
        } catch (e: Exception) {
        }
        return "none" to null
    }

    /**
     * Brings the proxy in line with the Secure DNS settings. Returns true when an asynchronous
     * change was started; the state is then re-applied from its callback.
     */
    private fun syncDns(sd: JSONObject?): Boolean {
        val on = sd?.optBoolean("on", false) ?: false
        dnsAuto = sd?.optBoolean("auto", true) ?: true
        val custom = sd?.optString("url", "") ?: ""

        var status = "off"
        var target: String? = null
        if (on) {
            if (!dnsSupported()) {
                status = "unsupported"
            } else if (!dnsAuto) {
                if (custom.startsWith("https://") && Uri.parse(custom).host != null) {
                    target = custom
                    status = "active"
                } else {
                    status = "badurl"
                }
            } else {
                val (st, url) = detectSystemDns()
                status = st
                target = url
            }
        }
        val tgt: String? = target
        val name = tgt?.let { Uri.parse(it).host } ?: ""
        reportDns(status, name)

        // provider changed while the override is already active: only the proxy needs to know
        if (tgt != null && tgt == dnsApplied) {
            val key = "$tgt|$dnsAuto"
            if (key != dnsCfgKey) configureProxy(tgt, key)
            return false
        }
        if (tgt != null && dnsApplied != null) {
            val key = "$tgt|$dnsAuto"
            if (configureProxy(tgt, key)) dnsApplied = tgt
            return false
        }
        if (tgt == null && dnsApplied == null) return false

        if (tgt != null) {
            // turn on: start the proxy, then point every WebView at it
            val port = try { proxy.start() } catch (e: Exception) {
                reportDns("error", "")
                return false
            }
            if (!configureProxy(tgt, "$tgt|$dnsAuto")) {
                reportDns("badurl", "")
                return false
            }
            try {
                val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
                dnsBusy = true
                ProxyController.getInstance().setProxyOverride(cfg, mainExec) {
                    dnsBusy = false
                    dnsApplied = tgt
                    lastState?.let { applyState(it) }
                }
            } catch (e: Exception) {
                dnsBusy = false
                reportDns("unsupported", "")
                return false
            }
            return true
        }

        // turn off
        return try {
            dnsBusy = true
            ProxyController.getInstance().clearProxyOverride(mainExec) {
                dnsBusy = false
                dnsApplied = null
                dnsCfgKey = ""
                proxy.stop()
                lastState?.let { applyState(it) }
            }
            true
        } catch (e: Exception) {
            dnsBusy = false
            false
        }
    }

    private fun configureProxy(url: String, key: String): Boolean = try {
        proxy.configure(url, dnsAuto)
        dnsCfgKey = key
        true
    } catch (e: Exception) {
        false
    }

    private fun reportDns(status: String, name: String) {
        val v = "$status|$name"
        if (v == lastDns) return
        lastDns = v
        runJs("window.nxDns&&nxDns({s:${JSONObject.quote(status)},n:${JSONObject.quote(name)}})")
    }

    // "Current provider" mode follows the network: Wi-Fi to mobile data can change the DNS servers
    private fun watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                main.post { if (dnsAuto && !dnsBusy) lastState?.let { applyState(it) } }
            }

            override fun onLost(network: Network) {
                main.post { if (dnsAuto && !dnsBusy) lastState?.let { applyState(it) } }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) {
        }
    }

    private fun unwatchNetwork() {
        val cb = netCb ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
        netCb = null
    }

    // ---------------------------------------------------------------- incognito isolation

    private fun incognitoIsolated(): Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    } catch (e: Exception) {
        false
    }

    // Incognito tabs use their own WebView profile; it is deleted once the last one closes.
    private fun dropIncognitoProfile() {
        if (!incUsed || tabs.values.any { it.profile != null }) return
        try {
            ProfileStore.getInstance().deleteProfile(INCOGNITO)
            incUsed = false
        } catch (e: Exception) {
            // still in use: tried again on the next state update
        }
    }

    private fun cookieFor(t: PageTab, url: String): String? {
        val n = t.profile
        if (n != null) {
            try {
                return ProfileStore.getInstance().getProfile(n)?.cookieManager?.getCookie(url)
            } catch (e: Exception) {
            }
        }
        return CookieManager.getInstance().getCookie(url)
    }

    // ---------------------------------------------------------------- background tab loading

    private fun finishPreload() {
        main.removeCallbacks(preloadTimeout)
        if (preloading == null) return
        preloading = null
        // applying the last state pauses the finished tab and starts the next background tab
        lastState?.let { applyState(it) }
    }

    // ---------------------------------------------------------------- favicons

    private fun iconData(b: Bitmap): String? = try {
        val sm = Bitmap.createScaledBitmap(b, 48, 48, true)
        val out = ByteArrayOutputStream()
        sm.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- fullscreen video

    private fun showFullscreen(tabId: Int, v: View, cb: WebChromeClient.CustomViewCallback) {
        if (fsView != null) {
            cb.onCustomViewHidden()
            return
        }
        fsView = v
        fsCallback = cb
        fsTab = tabId
        v.setBackgroundColor(Color.BLACK)
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars(true)
    }

    private fun hideFullscreen() {
        val v = fsView ?: return
        val cb = fsCallback
        fsView = null
        fsCallback = null
        fsTab = -1
        root.removeView(v)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars(false)
        cb?.onCustomViewHidden()
    }

    @Suppress("DEPRECATION")
    private fun hideBars(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (hide) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (hide) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else 0
            if (!hide && lastBg.isNotEmpty()) applyBg(lastBg)
        }
    }

    // ---------------------------------------------------------------- camera, microphone, location

    private fun originKey(origin: String): String = origin.trimEnd('/')

    private fun hostOf(origin: String): String = Uri.parse(origin).host ?: origin

    private fun askMedia(r: PermissionRequest) {
        val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE in r.resources
        val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in r.resources
        if (!cam && !mic) {
            // protected media (DRM video) needs no prompt; anything else is refused
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in r.resources)
                r.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            else r.deny()
            return
        }
        permDialog?.dismiss()
        permReq?.deny()
        permReq = r
        val key = originKey(r.origin.toString())
        val saved = listOfNotNull(
            if (cam) prefs.getString("cam|$key", null) else null,
            if (mic) prefs.getString("mic|$key", null) else null
        )
        if (saved.contains("deny")) {
            permReq = null
            r.deny()
            return
        }
        if (saved.size == (if (cam) 1 else 0) + (if (mic) 1 else 0)) {
            useMedia(r)
            return
        }
        val what = if (cam && mic) "camera and microphone" else if (cam) "camera" else "microphone"
        permDialog = AlertDialog.Builder(this)
            .setTitle("Use your $what?")
            .setMessage("${hostOf(key)} wants to use your $what.")
            .setPositiveButton("Allow") { _, _ ->
                prefs.edit().apply {
                    if (cam) putString("cam|$key", "allow")
                    if (mic) putString("mic|$key", "allow")
                }.apply()
                useMedia(r)
            }
            .setNegativeButton("Block") { _, _ ->
                prefs.edit().apply {
                    if (cam) putString("cam|$key", "deny")
                    if (mic) putString("mic|$key", "deny")
                }.apply()
                permReq = null
                r.deny()
            }
            .setNeutralButton("Not now") { _, _ ->
                permReq = null
                r.deny()
            }
            .setOnCancelListener {
                permReq = null
                r.deny()
            }
            .show()
    }

    // the site is allowed; Android still needs its own runtime permission
    private fun useMedia(r: PermissionRequest) {
        val need = ArrayList<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in r.resources &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.CAMERA)
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in r.resources &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isEmpty()) finishMedia(r) else requestPermissions(need.toTypedArray(), REQ_MEDIA)
    }

    // grants whichever of the requested resources Android now allows
    private fun finishMedia(r: PermissionRequest) {
        permReq = null
        val ok = ArrayList<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in r.resources &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) ok.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in r.resources &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) ok.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in r.resources) ok.add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        if (ok.isEmpty()) r.deny() else r.grant(ok.toTypedArray())
    }

    private fun askGeo(origin: String, cb: GeolocationPermissions.Callback) {
        geoDialog?.dismiss()
        geoCb?.invoke(geoOrigin, false, false)
        geoCb = null
        val key = originKey(origin)
        when (prefs.getString("geo|$key", null)) {
            "deny" -> {
                cb.invoke(origin, false, false)
                return
            }
            "allow" -> {
                useGeo(origin, cb)
                return
            }
        }
        geoOrigin = origin
        geoCb = cb
        geoDialog = AlertDialog.Builder(this)
            .setTitle("Use your location?")
            .setMessage("${hostOf(key)} wants to know where you are.")
            .setPositiveButton("Allow") { _, _ ->
                prefs.edit().putString("geo|$key", "allow").apply()
                useGeo(origin, cb)
            }
            .setNegativeButton("Block") { _, _ ->
                prefs.edit().putString("geo|$key", "deny").apply()
                geoCb = null
                cb.invoke(origin, false, false)
            }
            .setNeutralButton("Not now") { _, _ ->
                geoCb = null
                cb.invoke(origin, false, false)
            }
            .setOnCancelListener {
                geoCb = null
                cb.invoke(origin, false, false)
            }
            .show()
    }

    private fun useGeo(origin: String, cb: GeolocationPermissions.Callback) {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            geoCb = null
            cb.invoke(origin, true, false)
            return
        }
        geoOrigin = origin
        geoCb = cb
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_GEO
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MEDIA -> permReq?.let { finishMedia(it) }
            REQ_GEO -> {
                val ok = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                geoCb?.invoke(geoOrigin, ok, false)
                geoCb = null
            }
        }
    }

    // ---------------------------------------------------------------- downloads

    private fun startDownload(url: String, ua: String?, cd: String?, mime: String?, cookie: String? = CookieManager.getInstance().getCookie(url)) {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 5)
            toast("Allow storage access, then try the download again")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle(name)
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            cookie?.let { req.addRequestHeader("Cookie", it) }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloads[dm.enqueue(req)] = name
            toast("Downloading $name")
            main.removeCallbacks(poller)
            main.post(poller)
        } catch (e: Exception) {
            toast("Can't download this link")
        }
    }

    private fun removeDownload(id: String) {
        val l = id.toLongOrNull() ?: return
        if (downloads.remove(l) == null) return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query().setFilterById(l))
        var finished = false
        if (c != null) {
            if (c.moveToFirst()) {
                finished = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL
            }
            c.close()
        }
        // remove() also deletes the file, so only cancel downloads that haven't finished
        if (!finished) dm.remove(l)
    }

    private val poller: Runnable = object : Runnable {
        override fun run() {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val out = JSONArray()
            val drop = ArrayList<Long>()
            var active = false
            for ((id, name) in downloads) {
                var p = 0.0
                var gone = true
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c != null) {
                    if (c.moveToFirst()) {
                        gone = false
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        p = when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> 1.0
                            DownloadManager.STATUS_FAILED -> -1.0
                            else -> if (tot > 0) minOf(0.99, got.toDouble() / tot) else 0.0
                        }
                    }
                    c.close()
                }
                if (gone || p < 0) {
                    drop.add(id)
                    if (!gone) toast("Download failed: $name")
                    continue
                }
                if (p < 1.0) active = true
                out.put(JSONObject().put("id", id.toString()).put("n", name).put("p", p))
            }
            for (id in drop) downloads.remove(id)
            runJs("window.nxDl&&nxDl($out)")
            if (active) main.postDelayed(this, 600)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun runJs(code: String) {
        ui.evaluateJavascript(code, null)
    }

    private fun toast(msg: String) {
        runJs("window.toast&&toast(${JSONObject.quote(msg)})")
    }

    private fun openTab(url: String) {
        runJs("window.nxOpenTab&&nxOpenTab(${JSONObject.quote(url)})")
    }
}
