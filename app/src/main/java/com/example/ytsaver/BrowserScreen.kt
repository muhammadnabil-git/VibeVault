package com.example.ytsaver

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.Message
import android.webkit.JavascriptInterface
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.URLEncoder

private const val SEARCH_URL = "https://www.google.com/search?q="

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/** Whatever was typed in the address bar becomes a page: a full link, a bare domain, or a web search. */
internal fun addressToUrl(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
    val looksLikeSite = !t.contains(' ') && !t.endsWith('.') && (t.contains('.') || t.startsWith("localhost"))
    return if (looksLikeSite) "https://$t" else SEARCH_URL + URLEncoder.encode(t, "UTF-8")
}

/**
 * Which websites open in desktop mode by default: all of them, except YouTube, SoundCloud, Shazam, Dailymotion,
 * Mixcloud, Bandcamp and Rumble, which work best in their mobile version. Instagram, TikTok, Facebook and every website the user adds open as a desktop site.
 * (The ⋮ menu can still switch a page either way.)
 */
internal fun opensInDesktopMode(url: String): Boolean {
    val host = hostOf(url)
    return !(host.contains("youtube.") || host.contains("youtu.be") || host.contains("soundcloud.") ||
        host.contains("shazam.") || host.contains("dailymotion.") || host.contains("dai.ly") ||
        host.contains("mixcloud.") || host.contains("bandcamp.") || host.contains("rumble."))
}

private fun siteKey(url: String) = hostOf(url).removePrefix("www.").removePrefix("m.")

/** The mode a site opens in: the one the user last chose for it, otherwise the default rule above. */
private fun siteDefaultDesktop(ctx: Context, url: String): Boolean {
    val saved = Prefs.siteMode(ctx, siteKey(url))
    return if (saved >= 0) saved == 1 else opensInDesktopMode(url)
}

/** Sent with every page load, like a normal browser does. */
private fun pageHeaders(): Map<String, String> =
    mapOf("Accept-Language" to (java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8"))

/** Makes the page lay out like on a computer screen (a plain desktop identity alone often still gets a phone-width page). */
private const val DESKTOP_VIEWPORT_JS =
    "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "m.setAttribute('content','width=1024');})();"

private fun chromeVersion(ctx: Context): String =
    Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(ctx))?.groupValues?.get(1) ?: "124.0.0.0"

// A plain Chrome identity, without the "; wv" / "Version/x.x" marks that identify an embedded WebView.
// Many sign-in pages refuse (or break for) anything that looks like a WebView.
private fun mobileUa(v: String) =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Mobile Safari/537.36"

private fun desktopUa(v: String) =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(web: WebView, userAgent: String) {
    with(web.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true     // sign-in buttons open their form in a new window
        setSupportMultipleWindows(true)
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW        // an https page never pulls in insecure resources
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        allowFileAccess = false
        userAgentString = userAgent
        cacheMode = WebSettings.LOAD_DEFAULT              // pages and images are cached on the phone like in a normal browser
        databaseEnabled = true
        setGeolocationEnabled(false)
        safeBrowsingEnabled = true
        setSupportMultipleWindows(true)
    }
    // lets the phone's password manager (Google, Bitwarden…) offer to fill sign-in forms too
    web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    // sign-in needs cookies, including the ones set by the identity provider's own domain
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(web, true)
    }
    // don't announce "this request comes from an app" (X-Requested-With): some sign-in pages reject it
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
    }
}

/** @return true when the link was handled here and the WebView must not load it. */
private fun overrideUrl(ctx: Context, view: WebView?, uri: Uri?): Boolean {
    val scheme = uri?.scheme?.lowercase() ?: return true
    return when (scheme) {
        "http", "https", "about", "data", "blob", "javascript" -> false
        "intent" -> {
            // "open in the app" link: use the website version the page offers instead
            val fallback = runCatching {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            }.getOrNull()
            if (fallback != null) view?.loadUrl(fallback)
            true
        }
        "tel", "mailto", "sms", "geo" -> {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
            true
        }
        else -> true   // fb://, instagram://, snssdk…:// and the like: stay in the browser
    }
}

private fun startFileDownload(ctx: Context, url: String, userAgent: String?, disposition: String?, mime: String?) {
    if (url.startsWith("blob:") || url.startsWith("data:")) {
        toast(ctx, L.t("This file can't be downloaded from here.", "لا يمكن تنزيل هذا الملف من هنا."))
        return
    }
    try {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent ?: "")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        if (!mime.isNullOrBlank()) req.setMimeType(mime)
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        toast(ctx, L.t("Downloading $name", "جارٍ تنزيل $name"))
    } catch (e: Exception) {
        toast(ctx, L.t("Couldn't download this file.", "تعذّر تنزيل هذا الملف."))
    }
}

// ---------------------------------------------------------------------------------------------
// The browser
// ---------------------------------------------------------------------------------------------

/**
 * The in-app browser (opened from the Search tab). A complete browser: editable address bar, back / forward,
 * refresh / stop, desktop-site mode, sign-in popups, file upload, downloads, fullscreen video.
 * Find a video, then tap Download: the current page link goes to the Download tab.
 */
@Composable
fun BrowserScreen(vm: DownloaderViewModel, startUrl: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val chromeVer = remember { chromeVersion(ctx) }

    var desktop by remember { mutableStateOf(siteDefaultDesktop(ctx, startUrl)) }
    var current by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var addr by remember { mutableStateOf(TextFieldValue(startUrl)) }
    var addrFocused by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var pendingLogin by remember { mutableStateOf<SavedLogin?>(null) }       // "Save this password?"
    var passwordsOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var findCount by remember { mutableStateOf("") }
    var bookmarked by remember { mutableStateOf(false) }
    var songBusy by remember { mutableStateOf(false) }
    var songChoice by remember { mutableStateOf<SongPage?>(null) }

    val customCb = remember { arrayOfNulls<WebChromeClient.CustomViewCallback>(1) }
    val chooserCb = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooserCb[0]?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooserCb[0] = null
    }

    fun userAgent() = if (desktop) desktopUa(chromeVer) else mobileUa(chromeVer)

    // ---- saved passwords: the page script reports a sign-in, we offer to keep it ----
    val bridge = remember {
        BrowserBridge(currentUrl = { current }) { host, user, pass ->
            if (PasswordStore.enabled(ctx)) {
                val h = host.removePrefix("www.")
                val same = PasswordStore.forHost(ctx, h).firstOrNull { it.user == user }
                if (same?.pass != pass) pendingLogin = SavedLogin(h, user, pass)
            }
        }
    }

    // ---- the window opened by "Continue with…" / sign-in buttons ----
    val popupClient = remember {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                overrideUrl(ctx, view, request?.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
            }
        }
    }
    val popupChrome = remember {
        object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                if (window === popup) popup = null
            }
        }
    }

    // ---- the main page ----
    val client = remember {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                overrideUrl(ctx, view, request?.url)

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                loadError = null
                if (url != null) current = url
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (desktop) view?.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                if (desktop) view?.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                if (url != null) current = url
                canBack = view?.canGoBack() == true
                canForward = view?.canGoForward() == true
                CookieManager.getInstance().flush()
                if (url != null && url.startsWith("http") && view != null) {
                    BrowserData.addHistory(ctx, view.title.orEmpty(), url)
                    bookmarked = BrowserData.isBookmarked(ctx, url)
                    // watch sign-in forms, and fill a saved login (again a moment later for pages that build the form late)
                    if (PasswordStore.enabled(ctx) && url.startsWith("https://")) {
                        view.evaluateJavascript(CAPTURE_LOGIN_JS, null)
                        val saved = PasswordStore.forHost(ctx, hostOf(url).removePrefix("www.").substringBefore(':')).firstOrNull()
                        if (saved != null) {
                            val js = fillLoginJs(saved.user, saved.pass)
                            view.evaluateJavascript(js, null)
                            view.postDelayed({ view.evaluateJavascript(js, null) }, 1200)
                        }
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null) current = url
                canBack = view?.canGoBack() == true
                canForward = view?.canGoForward() == true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                val msg = error?.description?.toString().orEmpty()
                if (msg.contains("ERR_ABORTED")) return          // we stopped it ourselves
                loadError = msg.ifBlank { L.t("The page could not be loaded.", "تعذّر تحميل الصفحة.") }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                loadError = L.t("This site's security certificate is not trusted.", "شهادة الأمان لهذا الموقع غير موثوقة.")
            }
        }
    }
    val chromeClient = remember {
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title.orEmpty()
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val msg = resultMsg ?: return false
                val child = WebView(ctx)
                configureWebView(child, userAgent())
                child.webViewClient = popupClient
                child.webChromeClient = popupChrome
                (msg.obj as WebView.WebViewTransport).webView = child
                msg.sendToTarget()
                popup = child
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customCb[0]?.onCustomViewHidden()
                customView = view
                customCb[0] = callback
            }

            override fun onHideCustomView() {
                customView = null
                customCb[0]?.onCustomViewHidden()
                customCb[0] = null
            }

            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?,
            ): Boolean {
                chooserCb[0]?.onReceiveValue(null)
                chooserCb[0] = filePathCallback
                return try {
                    chooser.launch((fileChooserParams ?: return false).createIntent())
                    true
                } catch (_: Exception) {
                    chooserCb[0]?.onReceiveValue(null)
                    chooserCb[0] = null
                    false
                }
            }
        }
    }

    val web = remember {
        WebView(ctx).also { w ->
            configureWebView(w, userAgent())
            w.webViewClient = client
            w.webChromeClient = chromeClient
            w.addJavascriptInterface(bridge, "MSBridge")
            w.setFindListener { active, total, done ->
                if (done) findCount = if (total == 0) "0" else "${active + 1}/$total"
            }
            w.setDownloadListener { url, ua, disposition, mime, _ -> startFileDownload(ctx, url, ua, disposition, mime) }
            w.loadUrl(startUrl, pageHeaders())
        }
    }

    fun load(raw: String) {
        val url = addressToUrl(raw)
        if (url.isBlank()) return
        loadError = null
        web.loadUrl(url, pageHeaders())
    }

    // the address bar follows the page, unless the user is typing in it
    LaunchedEffect(current, addrFocused) { if (!addrFocused) addr = TextFieldValue(current) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { web.onPause(); CookieManager.getInstance().flush() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { web.onResume() }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()     // keeps the logins for next time
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
    BackHandler {
        when {
            customView != null -> { chromeClient.onHideCustomView() }
            popup != null -> { popup = null }
            web.canGoBack() -> { web.goBack() }
            else -> { onClose() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- top: close, address bar, refresh / stop ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = tr("Close browser", "إغلاق المتصفح"))
                }
                BasicTextField(
                    value = addr,
                    onValueChange = { addr = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { load(addr.text); focus.clearFocus() }),
                    modifier = Modifier.weight(1f).height(40.dp).onFocusChanged { st ->
                        addrFocused = st.isFocused
                        if (st.isFocused) addr = addr.copy(selection = TextRange(0, addr.text.length))   // select all
                    },
                    decorationBox = { inner ->
                        Row(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (current.startsWith("https://")) {
                                Sym(R.drawable.ic_lock, 14.dp, tint = Muted)
                                Spacer(Modifier.width(6.dp))
                            }
                            Box(Modifier.weight(1f)) { inner() }
                        }
                    },
                )
                IconButton(onClick = {
                    if (loading) { web.stopLoading(); loading = false } else web.reload()
                }) {
                    Sym(if (loading) R.drawable.ic_stop else R.drawable.ic_refresh, 22.dp)
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                if (loading && progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxSize())
                }
            }

            // ---- find in page ----
            if (findOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it; if (it.isBlank()) { web.clearMatches(); findCount = "" } else web.findAllAsync(it) },
                        singleLine = true, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        placeholder = { Text(tr("Find in page", "بحث في الصفحة")) },
                    )
                    Text(findCount, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    TextButton(onClick = { web.findNext(false) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▲") }
                    TextButton(onClick = { web.findNext(true) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▼") }
                    TextButton(onClick = { findOpen = false; findText = ""; findCount = ""; web.clearMatches() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕") }
                }
            }

            // ---- the page ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                loadError?.let { BrowserError(it) { loadError = null; web.reload() } }
            }

            // ---- bottom: back, forward, Download, menu ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { web.goBack() }, enabled = canBack) {
                    Sym(R.drawable.ic_arrow_back, 24.dp)
                }
                IconButton(onClick = { web.goForward() }, enabled = canForward) {
                    Sym(R.drawable.ic_arrow_forward, 24.dp)
                }
                val songPage = SongPages.isSongPage(current)
                Button(
                    onClick = {
                        if (songPage) {
                            // Spotify and Shazam don't give out their audio files: read the song's name and find it on the open web
                            songBusy = true
                            web.evaluateJavascript(SongPages.READ_JS) { res ->
                                songBusy = false
                                val sp = SongPages.parse(current, res)
                                if (sp == null) toast(ctx, L.t("Couldn't read this song's name. Wait for the page to finish loading.", "تعذّرت قراءة اسم الأغنية. انتظر اكتمال تحميل الصفحة."))
                                else if (sp.previewUrl != null && hostOf(current).contains("shazam.")) songChoice = sp
                                else { vm.sendSongToDownload(sp.query); onClose() }
                            }
                        } else {
                            val downloadUrl = LinkResolver.youtubeVideoUrlFromMusic(current) ?: current
                            vm.sendToDownload(downloadUrl)
                            onClose()
                        }
                    },
                    enabled = current.startsWith("http") && !songBusy,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Sym(R.drawable.ic_download, 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (songPage) tr("Download song", "تنزيل الأغنية") else tr("Download", "تنزيل"), fontSize = 14.sp, maxLines = 1)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Sym(R.drawable.ic_more, 24.dp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text((if (desktop) "✓  " else "") + tr("Desktop site", "نسخة سطح المكتب")) },
                            onClick = {
                                menuOpen = false
                                desktop = !desktop
                                Prefs.setSiteMode(ctx, siteKey(startUrl), desktop)   // remembered for this website
                                web.settings.userAgentString = userAgent()
                                web.reload()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Home page of this site", "الصفحة الرئيسية للموقع")) },
                            onClick = { menuOpen = false; web.loadUrl(startUrl, pageHeaders()) },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Copy link", "نسخ الرابط")) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(current))
                                toast(ctx, L.t("Link copied", "تم نسخ الرابط"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Open in another browser", "فتح في متصفح آخر")) },
                            onClick = {
                                menuOpen = false
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) {
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text((if (bookmarked) "★  " else "☆  ") + (if (bookmarked) tr("Remove bookmark", "إزالة الإشارة") else tr("Add bookmark", "إضافة إشارة مرجعية"))) },
                            onClick = {
                                menuOpen = false
                                bookmarked = BrowserData.toggleBookmark(ctx, pageTitle.ifBlank { web.title.orEmpty() }, current)
                                toast(ctx, if (bookmarked) L.t("Bookmark added", "تمت إضافة الإشارة") else L.t("Bookmark removed", "تمت إزالة الإشارة"))
                            },
                        )
                        DropdownMenuItem(text = { Text(tr("Bookmarks", "الإشارات المرجعية")) }, onClick = { menuOpen = false; bookmarksOpen = true })
                        DropdownMenuItem(text = { Text(tr("History", "السجل")) }, onClick = { menuOpen = false; historyOpen = true })
                        DropdownMenuItem(text = { Text(tr("Find in page", "بحث في الصفحة")) }, onClick = { menuOpen = false; findOpen = true })
                        DropdownMenuItem(
                            text = { Text(tr("Share page", "مشاركة الصفحة")) },
                            onClick = {
                                menuOpen = false
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current)
                                runCatching { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        )
                        DropdownMenuItem(text = { Text(tr("Saved passwords", "كلمات المرور المحفوظة")) }, onClick = { menuOpen = false; passwordsOpen = true })
                        DropdownMenuItem(text = { Text(tr("Clear browsing data", "مسح بيانات التصفح")) }, onClick = { menuOpen = false; clearOpen = true })
                    }
                }
            }
        }

        // a sign-in (or other "new window") page on top of the browser
        popup?.let { pv -> key(pv) { BrowserPopup(pv) { popup = null } } }
        // a video shown full screen
        customView?.let { cv -> key(cv) { FullscreenVideo(cv) } }
    }

    // ---- dialogs ----
    pendingLogin?.let { l ->
        AlertDialog(
            onDismissRequest = { pendingLogin = null },
            title = { Text(tr("Save password?", "حفظ كلمة المرور؟")) },
            text = {
                Text(
                    tr("${l.user.ifBlank { "(no user name)" }} on ${l.host}. It stays on this phone, encrypted, and is filled in next time.",
                        "${l.user.ifBlank { "(بدون اسم مستخدم)" }} على ${l.host}. تبقى على هذا الهاتف مشفرة وتُملأ تلقائيًا في المرة القادمة."),
                )
            },
            confirmButton = { TextButton(onClick = { PasswordStore.save(ctx, l.host, l.user, l.pass); pendingLogin = null }) { Text(tr("Save", "حفظ")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingLogin = null }) { Text(tr("Not now", "ليس الآن")) }
                    TextButton(onClick = { PasswordStore.setEnabled(ctx, false); pendingLogin = null }) { Text(tr("Never", "أبدًا"), color = Muted) }
                }
            },
        )
    }
    if (passwordsOpen) SavedPasswordsDialog(ctx) { passwordsOpen = false }
    if (bookmarksOpen) BrowserListDialog(ctx, history = false, onOpen = { load(it) }) { bookmarksOpen = false }
    if (historyOpen) BrowserListDialog(ctx, history = true, onOpen = { load(it) }) { historyOpen = false }
    if (clearOpen) ClearBrowsingDataDialog(
        onClear = { cookies, cache, hist, pw ->
            if (cookies) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
            if (cache) web.clearCache(true)
            if (hist) { BrowserData.clearHistory(ctx); web.clearHistory() }
            if (pw) PasswordStore.clear(ctx)
            toast(ctx, L.t("Cleared", "تم المسح"))
            if (cookies || cache) web.reload()
        },
        onDismiss = { clearOpen = false },
    )
    songChoice?.let { sp ->
        AlertDialog(
            onDismissRequest = { songChoice = null },
            title = { Text(sp.title, maxLines = 2) },
            text = {
                Text(
                    tr("Shazam's own file is only a 30-second preview. The full song can be found on the open web (matched by name) and downloaded as MP3.",
                        "ملف Shazam نفسه مقطع معاينة مدته 30 ثانية فقط. يمكن إيجاد الأغنية كاملة على الويب المفتوح (بالاسم) وتنزيلها بصيغة MP3."),
                )
            },
            confirmButton = {
                TextButton(onClick = { songChoice = null; vm.sendSongToDownload(sp.query); onClose() }) { Text(tr("Full song", "الأغنية كاملة")) }
            },
            dismissButton = {
                TextButton(onClick = { val u = sp.previewUrl; songChoice = null; if (u != null) { vm.sendToDownload(u, asMp3 = true); onClose() } }) {
                    Text(tr("30-second preview", "معاينة 30 ثانية"))
                }
            },
        )
    }
}

@Composable
private fun BrowserError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(tr("This page can't be opened", "تعذّر فتح هذه الصفحة"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(10.dp)) { Text(tr("Try again", "حاول مرة أخرى")) }
    }
}

@Composable
private fun BrowserPopup(web: WebView, onClose: () -> Unit) {
    DisposableEffect(web) {
        onDispose {
            CookieManager.getInstance().flush()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = tr("Close window", "إغلاق النافذة"))
            }
            Text(tr("Sign in / new window", "تسجيل الدخول / نافذة جديدة"), color = Muted, fontSize = 13.sp)
        }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun FullscreenVideo(view: View) {
    val holder = remember(view) { arrayOfNulls<FrameLayout>(1) }
    DisposableEffect(view) {
        onDispose { holder[0]?.removeAllViews() }
    }
    AndroidView(
        factory = { c ->
            FrameLayout(c).also { f ->
                f.setBackgroundColor(android.graphics.Color.BLACK)
                (view.parent as? ViewGroup)?.removeView(view)
                f.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                holder[0] = f
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}
