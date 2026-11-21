package com.indo

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiBase = "https://guest.freeanimehentai.net/api/v11"

    private var searchCatalog: List<Map<String, Any?>>? = null
    private var searchCatalogTime = 0L

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    /**
     * The same rows as the site's home page (Recent Uploads, New Releases, Trending, Random,
     * 24 each). The page is rendered on the server: each row starts with a header island whose
     * props hold the row title (and an all_href), followed by the video cards. The landing API
     * answers 403 (Cloudflare block page), so if the page cannot be read, fall back to rows
     * built from the catalog.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        val catalog = getSearchCatalog().orEmpty()
        val bySlug = catalog.associateBy { it["slug"]?.toString() }

        val rows = try {
            val doc = app.get("$mainUrl/", headers = headers).document
            val rows = mutableListOf<Pair<String, MutableList<SearchResponse>>>()
            doc.select("astro-island[props*=all_href], a[href^=/videos/hentai/]").forEach { el ->
                if (el.tagName() == "astro-island") {
                    val title = tryParseJson<Map<String, Any?>>(el.attr("props"))
                        ?.get("title")?.let { (it as? List<*>)?.getOrNull(1)?.toString() }
                    if (!title.isNullOrBlank()) rows.add(title to mutableListOf())
                } else {
                    val row = rows.lastOrNull() ?: return@forEach
                    val slug = el.attr("href").removePrefix("/videos/hentai/").substringBefore("?").trimEnd('/')
                    if (slug.isBlank() || row.second.any { it.url.endsWith("/$slug") }) return@forEach
                    val item = toSearchResponse(bySlug[slug]) ?: cardToSearchResponse(el, slug)
                    if (item != null) row.second.add(item)
                }
            }
            rows.filter { it.second.isNotEmpty() }.map { HomePageList(it.first, it.second) }
        } catch (e: Exception) {
            Log.d(TAG, "home page failed: $e")
            emptyList()
        }
        Log.d(TAG, "home rows: ${rows.map { "${it.name}=${it.list.size}" }}")
        if (rows.isNotEmpty()) return newHomePageResponse(rows, hasNext = false)

        val fallback = listOf(
            "created_at_unix" to "Recent Uploads",
            "released_at_unix" to "New Releases",
            "views" to "Most Viewed",
            "likes" to "Most Liked"
        ).map { (key, title) ->
            HomePageList(title, catalog.sortedByDescending { (it[key] as? Number)?.toLong() ?: 0L }
                .take(PAGE_SIZE).mapNotNull { toSearchResponse(it) })
        }.filter { it.list.isNotEmpty() }
        return newHomePageResponse(fallback, hasNext = false)
    }

    private fun cardToSearchResponse(card: org.jsoup.nodes.Element, slug: String): SearchResponse? {
        val img = card.selectFirst("img")
        val title = img?.attr("alt")?.ifBlank { null }
            ?: card.attr("title").removePrefix("Watch ").substringBefore(" hentai stream").ifBlank { null }
            ?: return null
        val poster = img?.attr("src")?.ifBlank { null } ?: "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
        return newMovieSearchResponse(title, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return listOf()
        val catalog = getSearchCatalog() ?: return listOf()
        val q = query.lowercase()
        return catalog.asSequence()
            .filter { item ->
                item["name"]?.toString()?.lowercase()?.contains(q) == true ||
                    item["search_titles"]?.toString()?.lowercase()?.contains(q) == true ||
                    item["slug"]?.toString()?.lowercase()?.contains(q) == true
            }
            .mapNotNull { toSearchResponse(it) }
            .take(50)
            .toList()
    }

    /**
     * The whole catalog (a few thousand videos, ~4.5 MB) from search_hvs, cached for an hour.
     * It used to be a bare list; it is now {"data": [...], "ads": {...}}. Both shapes are read.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun getSearchCatalog(): List<Map<String, Any?>>? {
        val now = System.currentTimeMillis()
        if (searchCatalog != null && now - searchCatalogTime < 3_600_000L) return searchCatalog
        val text = try {
            app.get("$apiBase/search_hvs", headers = headers, timeout = 60L).text
        } catch (e: Exception) {
            Log.d(TAG, "catalog request failed: $e")
            null
        }
        val parsed = text?.let { body ->
            tryParseJson<Map<String, Any?>>(body)?.get("data") as? List<Map<String, Any?>>
                ?: tryParseJson<List<Map<String, Any?>>>(body)
        }
        Log.d(TAG, "catalog: ${parsed?.size ?: "unreadable"} videos")
        if (parsed != null) {
            searchCatalog = parsed
            searchCatalogTime = now
        }
        return parsed ?: searchCatalog
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast("/")

        val fromCatalog = getSearchCatalog()?.firstOrNull { it["slug"]?.toString() == slug }
        if (fromCatalog != null) return buildMovieResponse(fromCatalog, url)

        val detail = try {
            val text = app.get("$apiBase/video?id=$slug", headers = headers).text
            tryParseJson<Map<String, Any?>>(text)?.get("hentai_video") as? Map<*, *>
        } catch (_: Exception) { null }
        if (detail != null) return buildMovieResponse(detail, url)

        throw ErrorLoadingException("Video not found")
    }

    private suspend fun buildMovieResponse(video: Map<*, *>, url: String): LoadResponse {
        val title = video["name"]?.toString() ?: throw ErrorLoadingException("Title not found")
        val plot = (video["description"]?.toString() ?: "").replace(Regex("<[^>]*>"), "").trim().ifBlank { null }
        val poster = video["poster_url"]?.toString()?.ifBlank { null }
            ?: video["cover_url"]?.toString()?.replace("covers", "posters")?.replace("cv1", "pv1")
        val tags = when (val t = video["hentai_tags"] ?: video["tags"]) {
            is List<*> -> if (t.firstOrNull() is String) {
                t.mapNotNull { it?.toString() }
            } else {
                t.mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }
            }
            else -> emptyList()
        }
        val year = video["released_at"]?.toString()?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    /**
     * The stream manifest endpoint only answers requests signed by the site's own web player
     * (x-signature-version/x-signature/x-time). Instead of making such requests, open the video
     * page in a WebView and let the site's player load the stream, then take the HLS request it
     * makes. Requests and a short page state are logged under the Hanime tag.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast("/")
        val pageUrl = "$mainUrl/videos/hentai/$slug"
        var lastState = ""
        val request = try {
            val resolver = WebViewResolver(
                interceptUrl = STREAM_URL_REGEX,
                additionalUrls = listOf(TRACE_URL_REGEX),
                userAgent = null,
                useOkhttp = false,
                script = AUTOPLAY_JS,
                scriptCallback = { state ->
                    if (state != lastState) {
                        lastState = state
                        Log.d(TAG, "page state: $state")
                    }
                },
                timeout = 60_000L
            )
            Log.d(TAG, "webview start")
            resolver.resolveUsingWebView(
                url = pageUrl,
                referer = "$mainUrl/",
                headers = emptyMap(),
                method = "GET"
            ) { req ->
                Log.d(TAG, "webview request: ${req.method} ${req.url.host}${req.url.encodedPath}")
                false
            }.first
        } catch (e: Exception) {
            Log.d(TAG, "webview failed: $e")
            null
        }
        if (request == null) {
            Log.d(TAG, "webview gave no stream, last page state: $lastState")
            return false
        }

        val streamUrl = request.url.toString()
        val linkHeaders = mutableMapOf<String, String>()
        (request.header("User-Agent") ?: WebViewResolver.webViewUserAgent)?.let { linkHeaders["User-Agent"] = it }
        request.header("Origin")?.let { linkHeaders["Origin"] = it }
        // The playlist may be tied to the page's session; carry the WebView's cookies for it.
        runCatching { CookieManager.getInstance().getCookie(streamUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { linkHeaders["Cookie"] = it }
        val referer = request.header("Referer") ?: "$mainUrl/"
        Log.d(TAG, "stream: ${request.url.host}${request.url.encodedPath.take(16)}...")

        // The playlist is a master; the site's player builds its quality menu from its
        // EXT-X-STREAM-INF lines (and adds a "1080p" premium promotion when that variant is
        // missing). Emit the master as "Auto", and one link per variant when there are several.
        val playlistHeaders = linkHeaders + ("Referer" to referer)
        val heights = try {
            val text = app.get(streamUrl, headers = playlistHeaders).text
            STREAM_INF_HEIGHT.findAll(text).map { it.groupValues[1].toInt() }.toList()
        } catch (e: Exception) {
            Log.d(TAG, "playlist check failed: $e")
            emptyList()
        }
        Log.d(TAG, "playlist variants: $heights")
        callback(newExtractorLink(name, "$name Auto", streamUrl, type = ExtractorLinkType.M3U8) {
            this.referer = referer
            this.headers = linkHeaders
            this.quality = heights.maxOrNull() ?: Qualities.Unknown.value
        })
        if (heights.size >= 2) {
            runCatching { M3u8Helper.generateM3u8(name, streamUrl, referer, headers = playlistHeaders) }
                .getOrDefault(emptyList())
                .filter { it.url != streamUrl }
                .forEach { callback(it) }
        }
        return true
    }

    private fun toSearchResponse(video: Map<String, Any?>?): SearchResponse? {
        val v = video ?: return null
        val name = v["name"]?.toString()?.ifBlank { null } ?: return null
        val slug = v["slug"]?.toString()?.ifBlank { null } ?: return null
        val poster = v["poster_url"]?.toString()?.ifBlank { null }
            ?: "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
        return newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    companion object {
        private const val TAG = "Hanime"
        private const val PAGE_SIZE = 24

        // The player's HLS playlist is served as hanime.tv/hls/<id>/<token>, without an extension.
        private val STREAM_URL_REGEX = Regex("""\.m3u8(\?|$)|hanime\.tv/hls/""")

        // Height of each variant in a master playlist.
        private val STREAM_INF_HEIGHT = Regex("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)""")

        // Requests logged while the WebView runs, to see how far the player got.
        private val TRACE_URL_REGEX = Regex("""hanime\.tv/|freeanimehentai\.net/|/manifest|challenges\.cloudflare\.com/""")

        // Runs on every request the WebView makes, and its own loop once a second:
        // - The page is built from Astro islands, and the player is a client="visible" island: it
        //   only hydrates once an IntersectionObserver reports it on screen, which never happens in
        //   the resolver's WebView (no size, not shown). Island.start() reads the client attribute
        //   each time, so switch those islands to "load" and start them.
        // - Press the player poster ("Play video"), then start the <video> (muted) if it waits.
        // Returns a short state string for the log.
        private const val AUTOPLAY_JS = """
            (function () {
                function tick() {
                    document.querySelectorAll('astro-island[client="visible"]').forEach(function (el) {
                        if (el.__csStarted || typeof el.start !== 'function') return;
                        el.__csStarted = true;
                        el.setAttribute('client', 'load');
                        try { el.start(); } catch (e) {}
                    });
                    // The resolver blanks .css, so the poster never looks hidden; press it a few
                    // times at most (3 s apart) so a started player is not toggled again and again.
                    var poster = document.querySelector('[aria-label="Play video"]');
                    var now = Date.now();
                    if (poster && (window.__csPosterClicks || 0) < 3 && now - (window.__csPosterAt || 0) > 3000) {
                        window.__csPosterClicks = (window.__csPosterClicks || 0) + 1;
                        window.__csPosterAt = now;
                        poster.click();
                    }
                    document.querySelectorAll('video').forEach(function (v) {
                        if (v.paused && v.readyState > 0) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function () {}); }
                    });
                }
                function state() {
                    var cf = /attention required|just a moment/i.test(document.title || '');
                    return 'ready=' + document.readyState
                        + ' islands-forced=' + document.querySelectorAll('astro-island[client="load"]').length
                        + ' visible-left=' + document.querySelectorAll('astro-island[client="visible"]').length
                        + ' play-btn=' + !!document.querySelector('[aria-label="Play video"]')
                        + ' play-clicks=' + (window.__csPosterClicks || 0)
                        + ' videos=' + document.querySelectorAll('video').length + (cf ? ' cloudflare' : '');
                }
                if (!window.__csHanime) {
                    window.__csHanime = true;
                    var last = '';
                    setInterval(function () {
                        tick();
                        // When the state changes, request a .png that the resolver answers locally
                        // (no network), only so this script runs again and the state reaches the log.
                        var now = state();
                        if (now !== last) { last = now; new Image().src = location.origin + '/__cs_state.png?' + Date.now(); }
                    }, 1000);
                }
                tick();
                return state();
            })();
        """
    }
}
