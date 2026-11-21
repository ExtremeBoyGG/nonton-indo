package com.indo

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

private const val TAG = "Nekopoi"

private val JW_SOURCE = Regex("""sources\s*:\s*\[\s*\{\s*["']?file["']?\s*:\s*["']([^"']+)["']""")
private val M3U8_FILE = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")

class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).text

        val title = Regex("""\[(\d+)[Pp]\]""").find(doc)?.groupValues?.getOrNull(1)
        val pageQuality = title?.toIntOrNull() ?: 0

        val passMd5 = Regex("/pass_md5/([^/]+)/([^/\\s\"')]+)").find(doc)
        if (passMd5 == null) {
            Log.d(TAG, "playmogo: no pass_md5 in the embed page (${doc.length} chars)")
            return
        }
        val passPath = passMd5.value
        val videoBase = app.get("$mainUrl$passPath", referer = url).text.trim()
        if (videoBase.isBlank() || !videoBase.startsWith("http")) {
            Log.d(TAG, "playmogo: pass_md5 gave no video base (${videoBase.take(40)})")
            return
        }
        Log.d(TAG, "playmogo: video base on ${videoBase.substringAfter("://").substringBefore("/")}")

        val token = passMd5.groupValues[2]
        val expiry = (System.currentTimeMillis() + 86400000).toString()
        val videoUrl = if (videoBase.endsWith("~")) "$videoBase$token?token=$token&expiry=$expiry" else videoBase

        val q = if (pageQuality > 0) pageQuality else when {
            Regex("""\b(?:2160|4k)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P2160.value
            Regex("""\b(?:1080|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P1080.value
            Regex("""\b720\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P720.value
            Regex("""\b480\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P480.value
            Regex("""\b360\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.quality = q
                this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
            }
        )
    }
}

/**
 * Streampoi is an XFileSharing host. Its embed page used to hide the player setup in packed JS
 * with a quoted "file" key; it now has the jwplayer setup in plain JS:
 * sources: [{file:"https://<host>.streamruby.net/.../master.m3u8?t=...&s=...&e=..."}]
 * Look in the unpacked script (if any) and in the page, and take the jwplayer source, else the
 * first .m3u8 "file" (the other "file" entries are thumbnail tracks and images).
 */
class Streampoi : ExtractorApi() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer).text
        val texts = listOfNotNull(getPacked(html), html)
        val fileUrl = texts.firstNotNullOfOrNull { text ->
            JW_SOURCE.find(text)?.groupValues?.get(1) ?: M3U8_FILE.find(text)?.groupValues?.get(1)
        }
        if (fileUrl == null) {
            Log.d(TAG, "streampoi: no source in the embed page (${html.length} chars)")
            return
        }
        Log.d(TAG, "streampoi: source on ${fileUrl.substringAfter("://").substringBefore("/")}")

        val links = M3u8Helper.generateM3u8(
            source = name,
            streamUrl = fileUrl,
            referer = url,
            headers = mapOf("Referer" to url, "Origin" to mainUrl)
        )
        if (links.isNotEmpty()) {
            links.forEach { callback(it) }
            return
        }

        callback.invoke(
            newExtractorLink(name, name, fileUrl) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf("Referer" to url, "Origin" to mainUrl)
            }
        )
    }
}

/**
 * Vidara gets its sources from POST /api/stream inside its own page and plays them with
 * jwplayer. Rather than making that request ourselves, open the embed in a WebView, let the
 * page's player start (muted), and take the first .m3u8 request it makes. Requests to vidara and
 * a short player state are logged under the Nekopoi tag, to see how far it got.
 */
class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var lastState = ""
        val request = try {
            WebViewResolver(
                interceptUrl = VIDARA_STREAM,
                additionalUrls = listOf(VIDARA_TRACE),
                userAgent = null,
                useOkhttp = false,
                script = VIDARA_AUTOPLAY_JS,
                scriptCallback = { state ->
                    if (state != lastState) {
                        lastState = state
                        Log.d(TAG, "vidara state: $state")
                    }
                },
                timeout = 40_000L
            ).resolveUsingWebView(
                url = url,
                referer = referer ?: "https://nekopoi.care/",
                headers = emptyMap(),
                method = "GET"
            ) { req ->
                Log.d(TAG, "vidara request: ${req.method} ${req.url.host}${req.url.encodedPath}")
                false
            }.first
        } catch (e: Exception) {
            Log.d(TAG, "vidara webview failed: $e")
            null
        }
        if (request == null) {
            Log.d(TAG, "vidara: no stream request, last state: $lastState")
            return
        }
        val streamUrl = request.url.toString()
        Log.d(TAG, "vidara: stream on ${request.url.host}")
        val linkHeaders = mutableMapOf<String, String>()
        (request.header("User-Agent") ?: WebViewResolver.webViewUserAgent)?.let { linkHeaders["User-Agent"] = it }
        request.header("Origin")?.let { linkHeaders["Origin"] = it }
        val linkReferer = request.header("Referer") ?: "$mainUrl/"
        callback(newExtractorLink(name, name, streamUrl, type = ExtractorLinkType.M3U8) {
            this.referer = linkReferer
            this.headers = linkHeaders
            this.quality = Qualities.Unknown.value
        })
    }
}

private val VIDARA_STREAM = Regex("""\.m3u8(\?|$)""")
private val VIDARA_TRACE = Regex("""vidara\.to/""")

// Starts jwplayer muted (or presses its play icon) once a second, and returns a short state
// string for the log. The resolver only runs this when the page makes a request.
private const val VIDARA_AUTOPLAY_JS = """
    (function () {
        function tick() {
            try {
                if (window.jwplayer) {
                    var p = window.jwplayer();
                    if (p && p.getState && p.getState() !== 'playing') { p.setMute && p.setMute(true); p.play && p.play(); }
                }
            } catch (e) {}
            var icon = document.querySelector('.jw-display-icon-container, .jw-icon-display');
            if (icon && (window.__csVidaraClicks || 0) < 3) { window.__csVidaraClicks = (window.__csVidaraClicks || 0) + 1; icon.click(); }
        }
        if (!window.__csVidara) { window.__csVidara = true; setInterval(tick, 1000); }
        tick();
        var st = '';
        try { st = window.jwplayer && window.jwplayer().getState ? window.jwplayer().getState() : 'no-jw'; } catch (e) { st = 'jw-error'; }
        return 'ready=' + document.readyState + ' jw=' + st + ' clicks=' + (window.__csVidaraClicks || 0);
    })();
"""
