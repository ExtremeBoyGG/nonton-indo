package com.indo

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * arrowtv.net. The catalog (home, search, details, seasons and episodes) comes from TMDB through
 * CloudStream's TmdbProvider; ArrowTV itself uses TMDB ids.
 *
 * ArrowTV's watch page (/watch/movie/<tmdb>, /watch/tv/<tmdb>?season=&episode=) embeds the Cinezo
 * player (player.cinezo.live/embed/...), which looks up its servers on its own and loads an HLS
 * master (media.punkflix.net/s/<hash>/index.m3u8 on the first server) without any click. Open the
 * watch page in a WebView and take the first .m3u8 request; the player's own requests to its
 * source endpoints are left to the page.
 *
 * Subtitles: the player lists those of the OpenSubtitles Stremio addon (same files on
 * subs5.strem.io, same count) through a proxy that refuses requests from outside the page. The
 * addon itself is a public API for clients, so it is asked directly, by IMDb id.
 */
class ArrowTV(private val appContext: Context) : TmdbProvider() {
    override var name = "ArrowTV"
    override var mainUrl = "https://arrowtv.net"
    override var lang = "en"
    override val hasMainPage = true
    override val useMetaLoadResponse = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = tryParseJson<TmdbLink>(data) ?: return false
        val id = link.tmdbID ?: return false
        val pageUrl = if (link.season != null && link.episode != null) {
            "$mainUrl/watch/tv/$id?season=${link.season}&episode=${link.episode}"
        } else {
            "$mainUrl/watch/movie/$id"
        }

        val caught = coroutineScope {
            // In parallel with the WebView, so the subtitles add no waiting.
            launch { addSubtitles(link, subtitleCallback) }
            try {
                catchPlaylist(pageUrl)
            } catch (e: Exception) {
                Log.d(TAG, "webview failed: $e")
                null
            }
        } ?: return false

        fun header(name: String) = caught.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        val linkHeaders = mutableMapOf<String, String>()
        header("User-Agent")?.let { linkHeaders["User-Agent"] = it }
        linkHeaders["Origin"] = header("Origin") ?: PLAYER_ORIGIN
        callback(newExtractorLink(name, "$name HLS", caught.url, type = ExtractorLinkType.M3U8) {
            this.referer = header("Referer") ?: "$PLAYER_ORIGIN/"
            this.headers = linkHeaders
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private class Caught(val url: String, val headers: Map<String, String>)

    private data class OsSubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    private data class OsResponse(@JsonProperty("subtitles") val subtitles: List<OsSubtitle>? = null)

    /**
     * Subtitles from the OpenSubtitles Stremio addon: movie/<imdb> or series/<imdb>:S:E (episode
     * links carry the show's IMDb id). Each is passed with its language name only; CloudStream
     * numbers equal names itself ("English 1", "English 2", ...).
     */
    private suspend fun addSubtitles(link: TmdbLink, subtitleCallback: (SubtitleFile) -> Unit) {
        val imdb = link.imdbID?.takeIf { it.startsWith("tt") } ?: return
        val path = if (link.season != null && link.episode != null) {
            "series/$imdb:${link.season}:${link.episode}"
        } else {
            "movie/$imdb"
        }
        val subs = try {
            app.get("$OPENSUBTITLES_ADDON/subtitles/$path.json").parsedSafe<OsResponse>()?.subtitles
        } catch (e: Exception) {
            Log.d(TAG, "subtitles failed: $e")
            null
        }.orEmpty().filter { !it.url.isNullOrBlank() && !it.lang.isNullOrBlank() }

        subs.map { languageName(it.lang!!) to it.url!! }
            .sortedBy { it.first }
            .distinctBy { it.second }
            .forEach { (language, url) -> subtitleCallback(newSubtitleFile(language, url)) }
        Log.d(TAG, "subtitles: ${subs.size} for $path")
    }

    private fun languageName(code: String): String {
        val low = code.lowercase()
        // OpenSubtitles' own code for Brazilian Portuguese, not in CloudStream's table.
        if (low == "pob") return "Portuguese (Brazil)"
        // The addon uses ISO 639-2/B codes, which CloudStream's table leaves empty for these.
        val tag = BIBLIOGRAPHIC_TO_TERMINOLOGY[low] ?: low
        return SubtitleHelper.fromTagToEnglishLanguageName(tag) ?: code
    }

    /**
     * Opens [pageUrl] in a WebView of our own and waits for the first HLS playlist request (the
     * player's master). CloudStream's WebViewResolver is not used: it answers .css requests with an
     * empty body, which breaks sites that wait for their stylesheets; here every request is left
     * alone. The WebView is built with the plugin's context (com.lagradost.api.getContext() is not
     * available to plugins).
     */
    private suspend fun catchPlaylist(pageUrl: String): Caught? {
        val found = CompletableDeferred<Caught>()
        var webView: WebView? = null
        var playerSeen = false
        Log.d(TAG, "webview start: $pageUrl")
        withContext(Dispatchers.Main) {
            webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                var consoleErrors = 0
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR && consoleErrors++ < 10) {
                            Log.d(TAG, "console: ${message.message().take(160)} (${message.sourceId().substringAfterLast('/').take(40)}:${message.lineNumber()})")
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "page finished: ${url.take(80)} title=${view.title}")
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame || request.url.host?.contains("cinezo") == true) {
                            Log.d(TAG, "load error: ${request.url.host}${(request.url.path ?: "").take(40)} ${error.errorCode} ${error.description}")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame || request.url.host?.contains("cinezo") == true) {
                            Log.d(TAG, "http error: ${request.url.host}${(request.url.path ?: "").take(40)} ${response.statusCode}")
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (TRACE_URL.containsMatchIn(url)) {
                            Log.d(TAG, "webview request: ${request.method} ${request.url.host}${(request.url.path ?: "").take(40)}")
                        }
                        if (PLAYER_URL.containsMatchIn(url)) playerSeen = true
                        if (PLAYLIST_URL.containsMatchIn(url) && !found.isCompleted) {
                            found.complete(Caught(url, request.requestHeaders ?: emptyMap()))
                        }
                        return null
                    }
                }
                loadUrl(pageUrl, mapOf("Referer" to "$mainUrl/"))
            }
        }
        var lastState = ""
        val startedAt = System.currentTimeMillis()
        var reloaded = false
        val caught = withTimeoutOrNull(CATCH_TIMEOUT_MS) {
            // Every 3 s, unless the playlist comes first: log what the page shows (main frame only),
            // and once, if the page has not asked for the Cinezo player after 10 s, reload it (seen on
            // a phone: the player never came, while the same page loaded it a few minutes later).
            while (withTimeoutOrNull(3000) { found.await() } == null) {
                val reload = !playerSeen && !reloaded && System.currentTimeMillis() - startedAt > PLAYER_WAIT_MS
                if (reload) {
                    reloaded = true
                    Log.d(TAG, "no player after ${PLAYER_WAIT_MS / 1000} s, reloading")
                }
                withContext(Dispatchers.Main) {
                    if (reload) webView?.reload()
                    webView?.evaluateJavascript(STATE_JS) { state ->
                        if (state != lastState) {
                            lastState = state
                            Log.d(TAG, "page state: ${state.take(240)}")
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
        }
        if (caught == null) Log.d(TAG, "no playlist within ${CATCH_TIMEOUT_MS / 1000} s, last page state: $lastState")
        else Log.d(TAG, "stream: ${caught.url.substringAfter("://").substringBefore("/")}")
        return caught
    }

    companion object {
        private const val TAG = "ArrowTV"
        private const val CATCH_TIMEOUT_MS = 45_000L
        private const val PLAYER_WAIT_MS = 10_000L
        private const val PLAYER_ORIGIN = "https://player.cinezo.live"
        private const val OPENSUBTITLES_ADDON = "https://opensubtitles-v3.strem.io"

        private val BIBLIOGRAPHIC_TO_TERMINOLOGY = mapOf(
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
            "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
            "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
            "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
        )

        private val PLAYLIST_URL = Regex("""\.m3u8(\?|$)""")
        private val PLAYER_URL = Regex("""player\.cinezo\.live/embed/""")

        // Requests logged while the WebView runs: the watch page, a Cloudflare check, the player and
        // its source lookups.
        private val TRACE_URL = Regex(
            """arrowtv\.net/watch|challenges\.cloudflare\.com|/cdn-cgi/challenge|player\.cinezo\.live/embed|punkflix\.net/|yenime\.net/|flikhub\.net/"""
        )

        private const val STATE_JS = """
            (function () {
                var frames = Array.prototype.map.call(document.querySelectorAll('iframe'), function (f) {
                    return (f.src || '-').replace(/^https?:\/\//, '').slice(0, 60);
                }).join(',');
                var text = document.body ? document.body.innerText.replace(/\s+/g, ' ').slice(0, 100) : '';
                return 'ready=' + document.readyState + ' title=' + document.title + ' iframes=' + frames + ' text=' + text;
            })();
        """
    }
}
