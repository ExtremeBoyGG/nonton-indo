package com.indo

import android.app.Dialog
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * gfymultimedx.online.
 *
 * The catalog (home shelves, genres, search) is open. Film and episode pages answer with a redirect
 * to the home page unless the request carries the site as Referer.
 *
 * Playback goes through the site's own player and cannot be automated: each start asks for an
 * interactive Cloudflare Turnstile check ("Verify you are human"), then plays video ads, and only
 * then does the player get its source: a signed MP4 under /_media/local/, an HLS playlist, or (for
 * some titles) a Pixeldrain viewer, pixeldrain.com/d/<id>?embed, in an iframe. So loadLinks shows
 * the film page in a WebView on screen, presses the site's play button, and leaves the check to the
 * viewer. Once the player has its source, the window closes and the same URL is played in
 * CloudStream with the WebView's cookies; a Pixeldrain share is resolved to its video file through
 * Pixeldrain's public filesystem API.
 *
 * That WebView loads only the site, Cloudflare's check, hls.js, fonts and Pixeldrain; ad networks
 * and the site's VAST tag get an empty answer, and the page cannot navigate elsewhere. Without the
 * ad SDK the site's player skips the video ads itself and goes on to the film.
 */
class GfyMultimedx : MainAPI() {
    override var mainUrl = "https://gfymultimedx.online"
    override var name = "GfyMultimedx"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // /film, /serial and /anime now redirect to the home page, which holds the shelves instead.
    override val mainPage = mainPageOf(
        HOME to "Beranda",
        "$mainUrl/genre/aksi" to "Aksi",
        "$mainUrl/genre/kengerian" to "Horor",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/komedi" to "Komedi",
        "$mainUrl/genre/cerita-seru" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == HOME) {
            if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)
            val doc = app.get("$mainUrl/", headers = headers).document
            // Each shelf: section.cinema-shelf with an h2 title and a.web-media-link cards.
            val shelves = doc.select("section.cinema-shelf").mapNotNull { shelf ->
                val title = shelf.selectFirst("h2")?.text()?.trim().orEmpty()
                val items = shelf.select("a.web-media-link").mapNotNull { toSearchResponse(it) }.distinctBy { it.url }
                if (title.isBlank() || items.isEmpty()) null else HomePageList(title, items)
            }
            return newHomePageResponse(shelves, false)
        }
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }.distinctBy { it.url }
        val hasNext = doc.selectFirst("a[href*='page=${page + 1}']") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/cari?q=$encoded"
        val doc = app.get(url, headers = headers).document
        return doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }.distinctBy { it.url }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""^Poster\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^[▶★\s0-9.]+"""), "")
            .replace(Regex("""(?:Film|Serial|Anime)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val rawHref = element.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#")) return null
        if (!rawHref.contains("/film/")) return null

        val href = fixUrlNull(rawHref) ?: return null

        val rawTitle = element.attr("data-web-title").ifBlank { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: element.text().trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = element.attr("data-web-backdrop").ifBlank { null }
            ?: element.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        val meta = element.attr("data-web-meta")
        val score = Regex("""★\s*([0-9.]+)""").find(meta)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(meta)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tvType = when {
            meta.contains("Serial", ignoreCase = true) -> TvType.TvSeries
            meta.contains("Anime", ignoreCase = true) -> TvType.Anime
            else -> TvType.Movie
        }

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    /** Film and episode pages redirect to the home page without the site as Referer. */
    private suspend fun getPage(url: String): Document {
        val response = app.get(url, headers = headers)
        val finalUrl = response.url.trimEnd('/')
        if (finalUrl == mainUrl && url.trimEnd('/') != mainUrl) {
            throw ErrorLoadingException("GfyMultimedx mengalihkan halaman ini ke beranda.")
        }
        return response.document
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url)

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "GfyMultimedx"
        val title = cleanTitle(rawTitle.removeSuffix(" - gfymultimedx"))

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("video[poster]")?.attr("poster")
            ?: doc.selectFirst(".web-media-poster img, img")?.attr("src")

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst(".synopsis, p")?.text()?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val epLinks = doc.select("a[href*='/season-'][href*='/episode-']")
        val isSeries = epLinks.isNotEmpty()

        return if (isSeries) {
            val episodes = epLinks.mapIndexedNotNull { index, a ->
                val epUrl = fixUrlNull(a.attr("href")) ?: return@mapIndexedNotNull null
                val epText = a.text().trim().replace(Regex("""\s+"""), " ")

                val sMatch = Regex("""season-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                val eMatch = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)
                    ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(epText)

                val sNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val eNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
                val epName = epText.ifBlank { "S${sNum}E$eNum" }

                newEpisode(epUrl) {
                    this.name = epName
                    this.season = sNum
                    this.episode = eNum
                }
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

            val isAnime = tags.any { it.contains("anime", ignoreCase = true) }
            val tvType = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Subtitles are plain <track> files on the page.
        runCatching { getPage(data) }.getOrNull()?.select("video#movie-player track[src], track[kind='subtitles'][src]")?.forEach { track ->
            val subUrl = fixUrlNull(track.attr("src")) ?: return@forEach
            val rawLang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Indonesia" }
            val label = when (rawLang.lowercase()) {
                "id", "ind", "indonesia", "indonesian" -> "Indonesian"
                "en", "eng", "english" -> "English"
                else -> rawLang
            }
            subtitleCallback(newSubtitleFile(label, subUrl))
        }

        val caught = try {
            playThroughSite(data)
        } catch (e: Exception) {
            Log.d(TAG, "player window failed: $e")
            null
        } ?: return false

        if (caught.url.contains("pixeldrain.") || caught.url.contains("pixeldra.in")) {
            val file = resolvePixeldrain(caught.url) ?: return false
            callback(
                newExtractorLink(name, "$name Pixeldrain", file, type = ExtractorLinkType.VIDEO) {
                    this.referer = "https://pixeldrain.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val linkHeaders = mutableMapOf<String, String>()
        caught.userAgent?.let { linkHeaders["User-Agent"] = it }
        runCatching { CookieManager.getInstance().getCookie(mainUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { linkHeaders["Cookie"] = it }
        val isHls = caught.url.contains(".m3u8")
        callback(
            newExtractorLink(
                name,
                name,
                caught.url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.headers = linkHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private class Caught(val url: String, val userAgent: String?)

    /**
     * pixeldrain.com/d/<id>[/sub/path]?embed is a filesystem share. Its ?stat answer lists the path
     * nodes (path[base_index] is the shared node) and, for a directory, its children; a file node is
     * served at /api/filesystem<node.path>. Returns the video file URL, or null.
     */
    private suspend fun resolvePixeldrain(viewerUrl: String): String? {
        val share = PIXELDRAIN_SHARE.find(viewerUrl)?.groupValues?.get(1) ?: run {
            Log.d(TAG, "pixeldrain: no share id in ${viewerUrl.take(80)}")
            return null
        }
        val statText = try {
            app.get("https://pixeldrain.com/api/filesystem/$share?stat", referer = "https://pixeldrain.com/").text
        } catch (e: Exception) {
            Log.d(TAG, "pixeldrain stat failed: $e")
            return null
        }
        return try {
            val stat = JSONObject(statText)
            val path = stat.getJSONArray("path")
            val base = path.getJSONObject(stat.optInt("base_index", path.length() - 1))
            val node = if (base.optString("type") == "file") base else {
                val children = stat.optJSONArray("children")
                (0 until (children?.length() ?: 0)).map { children!!.getJSONObject(it) }
                    .filter { it.optString("type") == "file" && (it.optString("file_type").startsWith("video/") || VIDEO_NAME.containsMatchIn(it.optString("name"))) }
                    .maxByOrNull { it.optLong("file_size") }
            }
            if (node == null) {
                Log.d(TAG, "pixeldrain: no video in ${statText.take(300)}")
                return null
            }
            val nodePath = node.optString("path").split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            Log.d(TAG, "pixeldrain: ${node.optString("name")} ${node.optString("file_type")} ${node.optLong("file_size")} bytes")
            "https://pixeldrain.com/api/filesystem$nodePath"
        } catch (e: Exception) {
            Log.d(TAG, "pixeldrain stat not understood: $e ${statText.take(300)}")
            null
        }
    }

    /**
     * Shows [pageUrl] in a WebView on screen, presses the site's play button, and waits until the
     * player has its source: the http(s) src of video#movie-player (direct MP4 or native HLS), or an
     * .m3u8 request when the page plays through hls.js. The Turnstile check and the ads are left to
     * the viewer; nothing here touches them. Closing the window cancels.
     */
    private suspend fun playThroughSite(pageUrl: String): Caught? {
        val activity = CommonActivity.activity ?: run {
            Log.d(TAG, "no activity to show the player window")
            return null
        }
        val found = CompletableDeferred<Caught?>()
        var webView: WebView? = null
        var dialog: Dialog? = null
        Log.d(TAG, "player window: $pageUrl")
        withContext(Dispatchers.Main) {
            val blocked = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val view = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Ads must not take the page elsewhere.
                        val host = request.url.host.orEmpty()
                        val keep = host == SITE_HOST || isChallengeHost(host)
                        if (!keep) Log.d(TAG, "navigation blocked: $host")
                        return !keep
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        val host = request.url.host.orEmpty()
                        if (!isAllowed(host, request.url.path.orEmpty())) {
                            val n = (blocked[host] ?: 0) + 1
                            blocked[host] = n
                            if (n == 1) Log.d(TAG, "blocked: $host${(request.url.path ?: "").take(30)}")
                            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        if (TRACE_URL.containsMatchIn(url)) {
                            Log.d(TAG, "webview request: ${request.method} ${request.url.host}${(request.url.path ?: "").take(40)}")
                        }
                        if (PLAYLIST_URL.containsMatchIn(url) && !found.isCompleted) {
                            val ua = request.requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value
                            found.complete(Caught(url, ua))
                        }
                        return null
                    }
                }
            }
            webView = view

            val hint = TextView(activity).apply {
                text = "Centang \"Verify you are human\" di halaman ini. Iklan diblokir; video dibuka otomatis di CloudStream."
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            val close = Button(activity).apply { text = "Tutup" }
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 12, 12, 12)
                addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
                setContentView(root)
                setOnDismissListener { if (!found.isCompleted) found.complete(null) }
                close.setOnClickListener { dismiss() }
                show()
            }
            view.loadUrl(pageUrl, mapOf("Referer" to "$mainUrl/"))
        }

        var lastState = ""
        val caught = withTimeoutOrNull(WINDOW_TIMEOUT_MS) {
            // Every 1.5 s, unless the source comes first: press the play button when it is shown
            // and read the player's state and source.
            while (withTimeoutOrNull(1500) { found.await() } == null && !found.isCompleted) {
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript(PLAYER_JS) { raw ->
                        val state = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
                        if (state.startsWith("src:") || state.startsWith("pd:")) {
                            val url = state.substringAfter(':')
                            if (!found.isCompleted) found.complete(Caught(url, webView?.settings?.userAgentString))
                        } else if (state != lastState) {
                            lastState = state
                            Log.d(TAG, "page state: $state")
                        }
                    }
                }
            }
            found.await()
        }
        withContext(Dispatchers.Main) {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            runCatching { dialog?.dismiss() }
            dialog = null
        }
        if (caught == null) Log.d(TAG, "no source, last page state: $lastState")
        else Log.d(TAG, "source: ${caught.url.substringBefore("?").take(60)}")
        return caught
    }

    companion object {
        private const val TAG = "GfyMultimedx"
        private const val HOME = "home"

        // The viewer needs time for the check and the ads (about 35 s in a test).
        private const val WINDOW_TIMEOUT_MS = 5 * 60 * 1000L

        private val PLAYLIST_URL = Regex("""\.m3u8(\?|$)""")

        private val VIDEO_NAME = Regex("""\.(mp4|mkv|webm|m4v|mov)$""", RegexOption.IGNORE_CASE)

        private val PIXELDRAIN_SHARE = Regex("""(?:pixeldrain\.(?:com|net|nl)|pixeldra\.in)/d/([^?#]+)""")

        private val TRACE_URL = Regex("""gfymultimedx\.online/(_player|_security|_media)/""")

        private const val SITE_HOST = "gfymultimedx.online"

        // Hosts the player window may load: the site, Cloudflare's check, hls.js, fonts, Pixeldrain.
        private val ALLOWED_HOSTS = setOf(
            SITE_HOST, "challenges.cloudflare.com", "cdn.jsdelivr.net", "fonts.googleapis.com", "fonts.gstatic.com",
            "pixeldrain.com", "pixeldrain.net", "pixeldra.in", "pixeldrain.nl"
        )

        // Turnstile also loads from subdomains such as brunhild.challenges.cloudflare.com.
        private fun isChallengeHost(host: String) =
            host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com")

        private fun isAllowed(host: String, path: String): Boolean {
            val h = host.removePrefix("www.")
            if (isChallengeHost(h)) return true
            if (h !in ALLOWED_HOSTS) return false
            // The site's own VAST tag endpoint.
            return !(h == SITE_HOST && path.startsWith("/_ads/"))
        }

        // Scrolls the player into view once, presses the site's own play/resume button when it is
        // shown (never while the check window is open), and returns "src:<url>" once the player has
        // an http(s) source, else a short state for the log.
        private const val PLAYER_JS = """
            (function () {
                var video = document.getElementById('movie-player');
                var src = video ? (video.currentSrc || video.getAttribute('src') || '') : '';
                if (/^https?:/.test(src)) return 'src:' + src;
                var viewer = document.querySelector('iframe.gfy-pixeldrain-iframe');
                if (viewer && /^https:\/\/[^\/]*pixeldra/.test(viewer.src || '')) return 'pd:' + viewer.src;
                if (video && !window.__gfyScrolled) { window.__gfyScrolled = true; video.scrollIntoView({ block: 'center' }); }
                var check = Array.prototype.some.call(document.querySelectorAll('h1, h2, h3, p, div'), function (e) {
                    return e.offsetParent !== null && e.children.length === 0 && /Satu langkah sebelum menonton/.test(e.innerText || '');
                });
                if (check) return 'check open';
                var now = Date.now();
                if (window.__gfyPressedAt && now - window.__gfyPressedAt < 5000) return 'pressed';
                var button = Array.prototype.find.call(document.querySelectorAll('button'), function (e) {
                    var t = (e.innerText || '').trim();
                    return !e.disabled && e.offsetParent !== null && /^(▶ Putar video|▶ Lanjutkan video|▶ Lanjutkan|Lanjutkan)$/.test(t);
                });
                if (button) { window.__gfyPressedAt = now; button.click(); return 'pressed ' + button.innerText.trim(); }
                var busy = Array.prototype.find.call(document.querySelectorAll('button'), function (e) {
                    return e.offsetParent !== null && /Memuat|Menyiapkan/.test(e.innerText || '');
                });
                return busy ? 'busy ' + busy.innerText.trim() : 'waiting';
            })();
        """
    }
}
