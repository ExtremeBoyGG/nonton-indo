package com.indo

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
import org.json.JSONTokener
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * vivarium.st. The catalog (home, search, details, seasons and episodes) comes from TMDB through
 * CloudStream's TmdbProvider; the site itself is only used to play.
 *
 * The player's default "Vivarium" source streams through an encrypted relay into a blob: URL that
 * nothing outside the page can play. Some other sources (Hyperion, Hermes, Hemera) load a plain HLS
 * master from <sub>.itsmeowenthankyouforyourservice.online/api/cs/<id>/master.m3u8. So open the watch
 * page in a WebView, switch the player to one of those through its own source list, and take the
 * master playlist it requests. Nothing is decrypted here.
 *
 * When those are not offered, Tyche and Theia (an HLS playlist at <sub>.../c?t=<token>, the same
 * path as the segments), Eos (an MP4 at /c?t=<token> set as the video's src) and Nyx are tried.
 *
 * Subtitles come from the OpenSubtitles Stremio addon (a public API for clients), by IMDb id.
 */
class Vivarium(private val appContext: Context) : TmdbProvider() {
    override var name = "Vivarium"
    override var mainUrl = "https://vivarium.st"
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
        // Movies: /m/<tmdb>?w=1 (the site adds the slug itself). Episodes: /s/<slug>-<tmdb>?w=1&s=&e=,
        // where any slug works ("j" is what the site's own continue-watching links use).
        val pageUrl = if (link.season != null && link.episode != null) {
            "$mainUrl/s/j-$id?w=1&s=${link.season}&e=${link.episode}"
        } else {
            "$mainUrl/m/$id?w=1"
        }

        val caught = coroutineScope {
            // In parallel with the WebView, so the subtitles add no waiting.
            launch { addSubtitles(link, subtitleCallback) }
            try {
                catchMaster(pageUrl)
            } catch (e: Exception) {
                Log.d(TAG, "webview failed: $e")
                null
            }
        } ?: return false

        val streamUrl = caught.url
        val linkHeaders = mutableMapOf("Origin" to mainUrl)
        val userAgent = caught.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        userAgent?.let { linkHeaders["User-Agent"] = it }
        runCatching { CookieManager.getInstance().getCookie(streamUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { linkHeaders["Cookie"] = it }
        val type = if (caught.video) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        val label = if (caught.video) "$name MP4" else "$name HLS"
        callback(newExtractorLink(name, label, streamUrl, type = type) {
            this.referer = "$mainUrl/"
            this.headers = linkHeaders
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private class Caught(val url: String, val headers: Map<String, String>, val video: Boolean = false)

    private class CsRequest(val at: Long, val url: String, val headers: Map<String, String>)

    /**
     * Whether [request] answers with an HLS playlist: /c?t= serves playlists, segments and files
     * alike, so read the first bytes (a small range) and look for #EXTM3U.
     */
    private suspend fun isPlaylist(request: CsRequest): Boolean {
        val probeHeaders = mutableMapOf("Range" to "bytes=0-2047", "Origin" to mainUrl, "Referer" to "$mainUrl/")
        request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { probeHeaders["User-Agent"] = it.value }
        return try {
            val response = app.get(request.url, headers = probeHeaders).okhttpResponse
            val head = response.use { it.peekBody(16).string() }
            head.trimStart().startsWith("#EXTM3U")
        } catch (e: Exception) {
            Log.d(TAG, "probe failed: $e")
            false
        }
    }

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
     * Opens [pageUrl] in a WebView of our own and waits for the player to request a master playlist.
     * CloudStream's WebViewResolver answers .css requests with an empty body, and the site then never
     * starts its app (reproduced in a desktop browser: blanking only the CSS stops it before
     * /api/gate). So no request is touched here; the page loads as in a browser, the source-picking
     * script is run every 2 s, and the first MASTER_URL request is taken.
     */
    private suspend fun catchMaster(pageUrl: String): Caught? {
        val found = CompletableDeferred<Caught>()
        var webView: WebView? = null
        // /c?t= requests: a playlist of the sources that do not use /api/cs/.../master.m3u8 is among them.
        val csRequests = ConcurrentLinkedQueue<CsRequest>()
        Log.d(TAG, "webview start: $pageUrl")
        withContext(Dispatchers.Main) {
            // The plugin's own Context: com.lagradost.api.getContext() resolves to the JVM build of the
            // library at compile time, which the app does not have (NoClassDefFoundError).
            webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (TRACE_URL.containsMatchIn(url)) {
                            Log.d(TAG, "webview request: ${request.method} ${request.url.host}${(request.url.path ?: "").take(40)}")
                        }
                        if (MASTER_URL.containsMatchIn(url) && !found.isCompleted) {
                            found.complete(Caught(url, request.requestHeaders ?: emptyMap()))
                        } else if (request.url.host.orEmpty().endsWith(STREAM_HOST) && request.url.path == "/c") {
                            csRequests.add(CsRequest(System.currentTimeMillis(), url, request.requestHeaders ?: emptyMap()))
                        }
                        return null
                    }
                }
                loadUrl(pageUrl, mapOf("Referer" to "$mainUrl/"))
            }
        }
        var lastState = ""
        var pickedAt = 0L
        var probesForPick = 0
        val probed = HashSet<String>()
        val caught = withTimeoutOrNull(CATCH_TIMEOUT_MS) {
            // Every 2 s, unless the playlist comes first (a plain delay held it back up to 2 s).
            while (withTimeoutOrNull(2000) { found.await() } == null) {
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript(PICK_SOURCE_JS) { raw ->
                        val state = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
                        if (state.startsWith("src:")) {
                            // A source that plays a file (Eos): the video's own http(s) src.
                            val ua = webView?.settings?.userAgentString
                            val headers = if (ua != null) mapOf("User-Agent" to ua) else emptyMap()
                            val src = state.removePrefix("src:")
                            if (!found.isCompleted) found.complete(Caught(src, headers, video = !src.contains(".m3u8")))
                            return@evaluateJavascript
                        }
                        PICKED_AT.find(state)?.groupValues?.get(1)?.toLongOrNull()?.let {
                            if (it != pickedAt) { pickedAt = it; probesForPick = 0 }
                        }
                        if (state != lastState) {
                            lastState = state
                            Log.d(TAG, "page state: $state")
                        }
                    }
                }
                // After a pick, the first /c?t= request that answers with #EXTM3U is the playlist
                // (earlier ones may be the previous source's last segments). At most 3 probes a pick.
                if (pickedAt > 0 && !found.isCompleted) {
                    for (request in csRequests.filter { it.at >= pickedAt && it.url !in probed }) {
                        if (probesForPick >= 3 || found.isCompleted) break
                        probed.add(request.url)
                        probesForPick++
                        if (isPlaylist(request)) {
                            Log.d(TAG, "playlist at /c after the pick")
                            found.complete(Caught(request.url, request.headers))
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
        if (caught == null) Log.d(TAG, "no master playlist, last page state: $lastState")
        else Log.d(TAG, "stream: ${caught.url.substringAfter("://").substringBefore("/")}")
        return caught
    }

    companion object {
        private const val TAG = "Vivarium"
        private const val OPENSUBTITLES_ADDON = "https://opensubtitles-v3.strem.io"

        private val BIBLIOGRAPHIC_TO_TERMINOLOGY = mapOf(
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
            "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
            "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
            "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
        )

        private val MASTER_URL = Regex("""/api/cs/[^/?#]+/master\.m3u8""")

        private const val STREAM_HOST = ".itsmeowenthankyouforyourservice.online"

        private val PICKED_AT = Regex("""pickedAt=(\d+)""")

        // Requests logged while the WebView runs, to see how far the page got.
        private val TRACE_URL = Regex("""vivarium\.st/api/|itsmeowenthankyouforyourservice\.online/api/|challenges\.cloudflare\.com/""")

        private const val CATCH_TIMEOUT_MS = 90_000L

        // Once the player is up, opens the source list and picks the first usable source; if no stream
        // was found 4 s later (a working source asks for it at once), tries the next one. Returns
        // "src:<url>" once the video plays an http(s) file. The source list opens from a button labelled
        // with the current source ("Vivarium" at first) on both layouts; the desktop layout also has
        // it under Settings > Source, used when that button is missing (the phone layout's Settings
        // has no Source item). Run every 2 s; returns a short state string for the log.
        private const val PICK_SOURCE_JS = """
            (function () {
                // Phone test (Avatar Aang): Hyperion/Hermes/Hemera load /api/cs/.../master.m3u8, Tyche and
                // Theia an HLS playlist at /c?t=, Eos an MP4, Nyx a playlist that did not start in 12 s;
                // Orion answered 404 and Hades nothing, and the default "Vivarium" plays a blob.
                // Tyche's playlist took about 12 s to start in ExoPlayer on a POCO X3 (Hermes/Hemera ~3 s),
                // so the /c?t= sources only come after the master.m3u8 ones.
                var ORDER = ['Hyperion', 'Hermes', 'Hemera', 'Tyche', 'Theia', 'Eos', 'Nyx'];
                var NAMES = ['Vivarium', 'Hermes', 'Hemera', 'Nyx', 'Tyche', 'Eos', 'Theia', 'Hyperion', 'Orion', 'Hades'];
                function find(re, skip) {
                    var all = document.querySelectorAll('button, [role=button], [role=menuitem], [role=option], [role=radio], li, label');
                    for (var i = 0; i < all.length; i++) {
                        if (all[i] === skip) continue;
                        var t = (all[i].getAttribute('aria-label') || all[i].innerText || '').trim();
                        if (re.test(t)) return all[i];
                    }
                    return null;
                }
                function sourceButton() {
                    var all = document.querySelectorAll('button, [role=button]');
                    for (var i = 0; i < all.length; i++) {
                        var t = (all[i].getAttribute('aria-label') || all[i].innerText || '').trim();
                        if (NAMES.indexOf(t) >= 0) return all[i];
                    }
                    return null;
                }
                function pick(idx, skip) {
                    setTimeout(function () {
                        var opt = find(new RegExp('^' + ORDER[idx] + '$'), skip);
                        window.__csVivIdx = idx + 1;
                        window.__csVivNote = opt ? 'picked ' + ORDER[idx] : 'no ' + ORDER[idx];
                        if (opt) {
                            window.__csVivPickedAt = Date.now();
                            opt.click();
                        } else {
                            // Not offered for this title: close the list and try the next one at once.
                            if (skip) skip.click();
                            window.__csVivAt = 0;
                        }
                    }, 800);
                }
                function tick() {
                    if (!document.querySelector('video')) return;
                    var now = Date.now();
                    if (window.__csVivAt && now - window.__csVivAt < 4000) return;
                    var idx = window.__csVivIdx || 0;
                    if (idx >= ORDER.length) return;
                    var button = sourceButton();
                    if (button) {
                        window.__csVivAt = now;
                        button.click();
                        pick(idx, button);
                        return;
                    }
                    var settings = find(/^Settings$/);
                    if (!settings) return;
                    window.__csVivAt = now;
                    settings.click();
                    setTimeout(function () {
                        var source = find(/^Source/);
                        if (!source) { window.__csVivNote = 'no source item'; return; }
                        source.click();
                        pick(idx, null);
                    }, 800);
                }
                function state() {
                    var v = document.querySelector('video');
                    var src = v ? (v.currentSrc || v.getAttribute('src') || '') : '';
                    if (/^https?:/.test(src) && window.__csVivPickedAt) return 'src:' + src;
                    var b = sourceButton();
                    return 'ready=' + document.readyState + ' videos=' + document.querySelectorAll('video').length
                        + ' source-button=' + (b ? (b.getAttribute('aria-label') || b.innerText || '').trim() : '-')
                        + ' ' + (window.__csVivNote || 'waiting')
                        + (window.__csVivPickedAt ? ' pickedAt=' + window.__csVivPickedAt : '');
                }
                if (!window.__csViv) {
                    window.__csViv = true;
                    setInterval(tick, 1500);
                }
                return state();
            })();
        """
    }
}
