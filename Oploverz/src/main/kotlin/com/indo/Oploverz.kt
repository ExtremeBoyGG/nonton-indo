package com.indo

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.Base64

class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.ch"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Completed", true) == true || t?.contains("Tamat", true) == true -> ShowStatus.Completed
                t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        private const val TAG = "Oploverz"

        // Series pages list 12 episodes per page; long series (One Piece) have a few dozen.
        private const val MAX_EPISODE_PAGES = 60

        // Blogger and Upbolt stream URLs refuse requests without a browser user agent (403), so the
        // pages are fetched with this one and the links carry the same.
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

        private val BACKSLASH = 92.toChar().toString()

        // \uXXXX escapes in the JSON that Blogger embeds in its page.
        private val UNICODE_ESCAPE = Regex(Regex.escape(BACKSLASH) + "u([0-9a-fA-F]{4})")

        private val GOOGLEVIDEO_URL =
            Regex("""https://[a-z0-9.-]+\.googlevideo\.com/videoplayback\?[^"'\s<>""" + BACKSLASH + BACKSLASH + "]+")

        private val ITAG = Regex("""[?&]itag=(\d+)""")

        private val JW_SOURCE = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rekomendasi",
        "$mainUrl/series/?status=ongoing&order=update&page=" to "Anime Ongoing",
        "$mainUrl/page/" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.endsWith("page=")) "${request.data}1" else request.data
        } else {
            if (request.data.endsWith("page=")) "${request.data}$page" else "${request.data}$page/"
        }
        val document = app.get(url).document

        val home = if (request.name == "Rekomendasi") {
            val recElements = document.select("div.series-gen article.bs, div.series-gen div.bsx")
            recElements.mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = el.selectFirst("div.tt.tts h2, div.tt.tts, div.tt")?.ownText()?.trim()
                    ?: el.selectFirst("h2")?.text()?.trim()
                    ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
                val cleanTitle = title.replace(Regex("\\s*Episode\\s*\\d+.*", RegexOption.IGNORE_CASE), "").trim()
                val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
                val animeUrl = episodeUrlToAnimeUrl(href)
                newAnimeSearchResponse(cleanTitle, animeUrl, TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        } else {
            document.select("div.bsx").mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = el.selectFirst("div.tt")?.ownText()?.trim()
                    ?: el.selectFirst("h2")?.text()?.trim()
                    ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
                val epText = el.selectFirst("span.epx")?.text()?.trim() ?: ""
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val animeUrl = if (epNum != null) episodeUrlToAnimeUrl(href) else href
                newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                    this.posterUrl = poster
                    addSub(epNum)
                }
            }.distinctBy { it.url }
        }
        return newHomePageResponse(request.name, home)
    }

    private fun episodeUrlToAnimeUrl(episodeUrl: String): String {
        val slug = episodeUrl.trimEnd('/').substringAfterLast("/")
        val animeSlug = slug.replace(Regex("-episode-\\d+.*", RegexOption.IGNORE_CASE), "")
        return "$mainUrl/series/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$q").document
        return document.select("div.bsx").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            // div.tt holds the title as its own text and the same title again in an h2.
            val title = el.selectFirst("div.tt")?.ownText()?.trim()?.ifBlank { null }
                ?: el.selectFirst("h2")?.text()?.trim()?.ifBlank { null }
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var finalUrl = url
        var document = app.get(finalUrl).document

        if (!finalUrl.contains("/series/")) {
            val seriesHref = document.selectFirst("div.nvs.nvsc a[href], a[aria-label='All Episodes'], a[href*='/series/']")?.attr("href")
                ?: episodeUrlToAnimeUrl(finalUrl)
            if (seriesHref.isNotBlank()) {
                finalUrl = fixUrl(seriesHref)
                document = app.get(finalUrl).document
            }
        }

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        // The site logo also lives under wp-content/uploads, so do not pick the first upload image.
        val poster = (document.selectFirst("div.thumb img")
            ?: document.selectFirst("div.bigcontent img, div.infox img, img.wp-post-image"))
            ?.attr("src")?.ifBlank { null }
        val description = document.selectFirst("div.entry-content > p, div.synp p")?.text()?.trim()
        val genres = document.select("a[href*=genres]").map { it.text() }.filter { it.isNotBlank() }
        val statusText = document.selectFirst("div.spe span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim()
        val status = getStatus(statusText)
        val year = document.selectFirst("div.spe span:contains(Released)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // The episode list shows 12 per page (?ep_page=N, newest first), and the page links sit in
        // the same <ul>; read every page and skip the page links.
        val lastPage = document.select("div.eplister a.page-numbers[href*=ep_page]")
            .mapNotNull { Regex("""ep_page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        val seriesUrl = finalUrl.substringBefore("?")
        val morePages = (2..minOf(lastPage, MAX_EPISODE_PAGES)).toList().amap { page ->
            runCatching { app.get("$seriesUrl?ep_page=$page").document }.getOrNull()
        }
        val episodes = (listOf(document) + morePages.filterNotNull())
            .flatMap { parseEpisodes(it) }
            .distinctBy { it.data }
            .reversed()
        Log.d(TAG, "load $seriesUrl: ${episodes.size} episodes from $lastPage page(s)")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        return newAnimeLoadResponse(title, finalUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> =
        document.select("div.eplister ul li, ul#episodelist li").mapNotNull { li ->
            // Page links (1, 2, ...) share the list; real episodes have an epl-num.
            val num = li.selectFirst("div.epl-num") ?: return@mapNotNull null
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epNum = num.text().trim().toIntOrNull()
            val epTitle = li.selectFirst("div.epl-title")?.text()?.trim() ?: a.text().trim()
            newEpisode(href) { this.name = epTitle; this.episode = epNum }
        }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // The server buttons (data-url and base64 data-embed), the player iframe and the mirror
        // options usually point at the same few embeds; collect them once.
        val embeds = linkedSetOf<String>()
        document.select("button.dsu-server").forEach { btn ->
            fixEmbedUrl(btn.attr("data-url"))?.let(embeds::add)
            btn.attr("data-embed").ifBlank { null }?.let { iframeSrc(decodeBase64(it)) }?.let(embeds::add)
        }
        document.select("div#pembed iframe, div.player-embed iframe, div.video-content iframe").forEach { iframe ->
            fixEmbedUrl(iframe.attr("src"))?.let(embeds::add)
        }
        document.select("select.mirror option").forEach { option ->
            option.attr("value").ifBlank { null }?.let { iframeSrc(decodeBase64(it)) }?.let(embeds::add)
        }

        val downloads = document.select("a[href*=gofile.io], a.dsu-download[href]")
            .mapNotNull { fixEmbedUrl(it.attr("href")) }
            .distinct()
        Log.d(TAG, "loadLinks $data: embeds ${embeds.map(::hostOf)}, downloads ${downloads.map(::hostOf)}")

        var total = 0
        suspend fun collect(url: String, block: suspend ((ExtractorLink) -> Unit) -> Unit) {
            var count = 0
            block { link ->
                count++
                callback(link)
            }
            total += count
            Log.d(TAG, "${hostOf(url)}: $count link(s)")
        }
        embeds.forEach { url -> collect(url) { cb -> handleUrl(url, data, subtitleCallback, cb) } }
        // Gofile & direct download links
        downloads.forEach { url -> collect(url) { cb -> loadExtractor(url, data, subtitleCallback, cb) } }

        return total > 0
    }

    private fun hostOf(url: String) = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1) ?: url

    private fun decodeBase64(value: String): String? =
        try { String(Base64.getDecoder().decode(value.trim())) } catch (_: Exception) { null }

    private fun iframeSrc(html: String?): String? =
        html?.let { Regex("""src\s*=\s*["']([^"']+)["']""").find(it)?.groupValues?.getOrNull(1) }
            ?.let { fixEmbedUrl(it) }

    private fun fixEmbedUrl(url: String?): String? {
        val u = url?.trim().orEmpty()
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            else -> null
        }
    }

    private suspend fun handleUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        when {
            url.contains("blogger.com") -> handleBloggerUrl(url, referer, subtitleCallback, callback)
            url.contains("upbolt.") -> handleUpboltUrl(url, referer, callback)
            url.contains("vkspeed.com") -> handleVkspeedUrl(url, callback)
            url.contains("play.xtwap.top") || url.contains("xtwap.top") -> handleXtwapUrl(url, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun handleXtwapUrl(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, referer = "$mainUrl/").text
            val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (m3u8 != null) {
                callback(
                    newExtractorLink("XTWAP", "XTWAP HLS", m3u8, type = ExtractorLinkType.M3U8) {
                        this.referer = "https://play.xtwap.top/"
                    }
                )
            }
            val directSrc = Regex("""<source[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            if (directSrc != null && directSrc.startsWith("http")) {
                callback(
                    newExtractorLink("BTube", "BTube Direct", directSrc, type = ExtractorLinkType.VIDEO) {
                        this.referer = "https://play.xtwap.top/"
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun handleVkspeedUrl(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, referer = "$mainUrl/").text
            val unpacked = getPacked(html) ?: html
            val mp4s = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(unpacked).map { it.value }.toList()
            mp4s.forEach { mp4 ->
                callback(
                    newExtractorLink("VKSpeed", "VKSpeed MP4", mp4, type = ExtractorLinkType.VIDEO) {
                        this.referer = "https://vkspeed.com/"
                    }
                )
            }
            val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(unpacked).map { it.value }.toList()
            m3u8s.forEach { m3u8 ->
                callback(
                    newExtractorLink("VKSpeed", "VKSpeed HLS", m3u8, type = ExtractorLinkType.M3U8) {
                        this.referer = "https://vkspeed.com/"
                    }
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Blogger's video.g page no longer has the "streams":[...] block that the built-in extractor
     * and the old parsing looked for. The stream URLs now sit in HTML-escaped JSON in the page:
     * googlevideo videoplayback URLs with their itag (18 = 360p, 22 = 720p). They are tied to the IP
     * that loaded the page and refuse requests without a browser user agent, so fetch the page with
     * BROWSER_UA and send the same one with the links. The built-in extractor stays as a fallback.
     */
    private suspend fun handleBloggerUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var found = false
        try {
            val page = app.get(url, headers = mapOf("User-Agent" to BROWSER_UA), referer = referer).text
            val text = UNICODE_ESCAPE.replace(Parser.unescapeEntities(page, false)) {
                it.groupValues[1].toInt(16).toChar().toString()
            }.replace(BACKSLASH + "/", "/")
            val seenItags = mutableSetOf<String>()
            GOOGLEVIDEO_URL.findAll(text).forEach { match ->
                val videoUrl = match.value
                val itag = ITAG.find(videoUrl)?.groupValues?.get(1) ?: return@forEach
                if (!seenItags.add(itag)) return@forEach
                val (label, quality) = when (itag) {
                    "18" -> "360p" to Qualities.P360.value
                    "22" -> "720p" to Qualities.P720.value
                    "37" -> "1080p" to Qualities.P1080.value
                    else -> "itag $itag" to Qualities.Unknown.value
                }
                callback(
                    newExtractorLink("Blogger", "Blogger $label", videoUrl, type = ExtractorLinkType.VIDEO) {
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to BROWSER_UA)
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            Log.d(TAG, "blogger failed: $e")
        }
        if (!found) {
            Log.d(TAG, "blogger: no stream URL in the page, trying the built-in extractor")
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    /**
     * Upbolt is an XFileSharing host: its embed page only shows a play button that posts the embed
     * form to /dl, and the answer is the player page with the HLS master in the jwplayer sources
     * (sometimes inside packed JS). The master lists absolute variant URLs that carry their token.
     */
    private suspend fun handleUpboltUrl(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        try {
            val host = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: return
            // Same as the embed page: last path part, after the last '-', without .html.
            val code = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
                .removeSuffix(".html").substringAfterLast("-")
            if (code.isBlank()) return
            val resp = app.post(
                "$host/dl",
                headers = mapOf("User-Agent" to BROWSER_UA, "Origin" to host),
                referer = url,
                data = mapOf("op" to "embed", "file_code" to code, "auto" to "1", "referer" to (referer ?: ""))
            )
            val page = resp.text
            val stream = listOfNotNull(getPacked(page), page)
                .firstNotNullOfOrNull { JW_SOURCE.find(it)?.groupValues?.get(1) }
                ?.let { if (it.startsWith("//")) "https:$it" else it }
            if (stream == null) {
                Log.d(TAG, "upbolt $code: no source in /dl answer (${resp.code}, ${page.length} chars)")
                return
            }
            callback(
                newExtractorLink(
                    "Upbolt", "Upbolt", stream,
                    type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$host/"
                    this.headers = mapOf("User-Agent" to BROWSER_UA)
                }
            )
        } catch (e: Exception) {
            Log.d(TAG, "upbolt failed: $e")
        }
    }
}
