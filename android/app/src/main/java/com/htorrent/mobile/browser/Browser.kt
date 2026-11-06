package com.htorrent.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.htorrent.mobile.core.FileNames
import com.htorrent.mobile.core.Links
import com.htorrent.mobile.core.Media
import com.htorrent.mobile.data.Downloads
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.net.URLEncoder

/** A file or video found on the page: offered in the "Found on this page" panel. */
data class Found(val url: String, val name: String, val kind: String, val label: String? = null)

/** What the browser hands to the app when a download starts or a link is long-pressed. */
data class Offer(
    val url: String,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = -1,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val quality: String? = null,
)

/**
 * The built-in browser. It does what the desktop browser extension does: downloads go to HTorrent, and files and
 * videos on the page are listed so they can be saved. Streams (.m3u8) and copy-protected video are not offered.
 */
class Browser(context: Context, private val onOffer: (Offer) -> Unit, private val onLongPress: (Offer, isImage: Boolean) -> Unit) {
    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(0)
    val canBack = MutableStateFlow(false)
    val canForward = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, !Downloads.settings.blockThirdPartyCookies)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                // Only web pages load here; other links (mail, phone, app links) are ignored for safety.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                this@Browser.url.value = u
                found.value = emptyList()
                updateNav()
            }

            override fun onPageFinished(view: WebView, u: String) {
                this@Browser.url.value = u
                this@Browser.title.value = view.title.orEmpty()
                updateNav()
                scan()
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                this@Browser.url.value = u
                updateNav()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Only looks at addresses; nothing is changed or blocked.
                val u = request.url.toString()
                if (Media.isMedia(u, null, -1) || (Media.looksDownloadable(u) && !request.isForMainFrame)) {
                    main.post { addFound(Found(u, FileNames.fromUrl(u), kindOf(u))) }
                }
                return null
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@Browser.progress.value = newProgress }
            override fun onReceivedTitle(view: WebView, t: String?) { this@Browser.title.value = t.orEmpty() }
        }
        setDownloadListener { u, userAgent, contentDisposition, mimetype, contentLength ->
            if (!Links.isWebLink(u)) return@setDownloadListener // blob: and data: stay in the page
            onOffer(
                Offer(
                    url = u,
                    fileName = URLUtil.guessFileName(u, contentDisposition, mimetype),
                    mime = mimetype,
                    size = contentLength,
                    referer = this.url,
                    userAgent = userAgent,
                    cookies = CookieManager.getInstance().getCookie(u),
                )
            )
        }
        setOnLongClickListener {
            val hit = hitTestResult
            val target = hit.extra
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE -> {
                    if (target != null && Links.isWebLink(target)) {
                        onLongPress(offerFor(target), hit.type == WebView.HitTestResult.IMAGE_TYPE)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    fun offerFor(link: String, quality: String? = null): Offer {
        val u = Links.unwrap(link)
        return Offer(
            url = u, fileName = FileNames.fromUrl(u), referer = view.url,
            userAgent = view.settings.userAgentString, cookies = CookieManager.getInstance().getCookie(u), quality = quality,
        )
    }

    private fun kindOf(u: String): String {
        val ext = FileNames.extensionOf(u)
        return when {
            ext in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac") -> "Audio"
            ext in Media.MEDIA_TYPES -> "Video"
            else -> "File"
        }
    }

    private fun addFound(f: Found) {
        if (Media.isStream(f.url)) return
        val list = found.value
        if (list.any { it.url == f.url } || list.size >= 200) return
        found.value = list + f
    }

    /** Finds video, audio and file links in the page (like the extension's content script). */
    private fun scan() {
        val js = """
            (function(){
              var out=[];
              function add(u,kind,label){ try{ u=new URL(u,location.href).href; }catch(e){ return; } out.push([u,kind,label||'']); }
              document.querySelectorAll('video,audio').forEach(function(m){
                var k=m.tagName==='VIDEO'?'Video':'Audio';
                if(m.currentSrc&&!m.currentSrc.startsWith('blob:')) add(m.currentSrc,k,'');
                m.querySelectorAll('source[src]').forEach(function(s){ add(s.src,k,s.getAttribute('label')||s.getAttribute('size')||s.getAttribute('res')||s.getAttribute('title')||''); });
              });
              document.querySelectorAll('a[href]').forEach(function(a){ add(a.href,'Link',(a.textContent||'').trim().slice(0,60)); });
              return JSON.stringify(out.slice(0,800));
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { result ->
            runCatching {
                val arr = JSONArray(JSONArray("[$result]").getString(0))
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    val u = e.getString(0)
                    val kind = e.getString(1)
                    val label = e.getString(2).ifEmpty { null }
                    val ok = if (kind == "Link") Media.looksDownloadable(u) else Links.isWebLink(u) && !Media.isStream(u)
                    if (ok) addFound(Found(u, FileNames.fromUrl(u), if (kind == "Link") kindOf(u) else kind, if (kind == "Link") null else label))
                }
            }
        }
    }

    private fun updateNav() {
        canBack.value = view.canGoBack()
        canForward.value = view.canGoForward()
    }

    /** Loads an address, or searches for the words typed. */
    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val target = when {
            Links.isWebLink(text) -> text
            !text.contains(' ') && text.contains('.') && !text.contains("://") -> "https://$text"
            else -> com.htorrent.mobile.core.SearchEngines.url(Downloads.settings.searchEngine, text)
        }
        started = true
        view.loadUrl(target)
    }

    fun startIfNeeded() {
        if (!started) { started = true; view.loadUrl(Downloads.settings.homePage) }
    }

    fun back() { if (view.canGoBack()) view.goBack() }
    fun forward() { if (view.canGoForward()) view.goForward() }
    fun reload() = view.reload()
    fun stop() = view.stopLoading()

    fun applyPrivacy() {
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, !Downloads.settings.blockThirdPartyCookies)
    }

    /** Clears cookies, site data, cache and history. */
    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
        found.value = emptyList()
        updateNav()
    }

    fun host(): String = runCatching { Uri.parse(url.value).host.orEmpty() }.getOrDefault("")
}
