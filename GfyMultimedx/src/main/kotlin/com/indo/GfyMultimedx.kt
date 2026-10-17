package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

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

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Film Terbaru",
        "$mainUrl/serial" to "Serial TV",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/genre/action" to "Action",
        "$mainUrl/genre/horror" to "Horor",
        "$mainUrl/genre/drama" to "Drama",
        "$mainUrl/genre/comedy" to "Komedi",
        "$mainUrl/genre/thriller" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/cari?q=$encoded"
        val doc = app.get(url, headers = headers).document
        return doc.select("a.web-media-link, a[href*='/film/']").mapNotNull { toSearchResponse(it) }
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "GfyMultimedx"
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("video[poster]")?.attr("poster")
            ?: doc.selectFirst(".web-media-poster img, img")?.attr("src")

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst(".synopsis, p")?.text()?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val trailerUrl = doc.selectFirst("[data-web-trailer]")?.attr("data-web-trailer")?.ifBlank { null }?.let {
            if (it.startsWith("http")) it else "https://www.youtube.com/watch?v=$it"
        }

        val epLinks = doc.select("a[href*='/season-'][href*='/episode-']")
        val isSeries = epLinks.isNotEmpty() || url.contains("/serial")

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
            }.ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            val isAnime = tags.any { it.contains("anime", ignoreCase = true) } || url.contains("/anime")
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
        val pageResp = app.get(data, headers = headers)
        val doc = pageResp.document

        // 1. Extract subtitles from <track>
        doc.select("track[kind='subtitles'], track[src]").forEach { track ->
            val subUrl = fixUrlNull(track.attr("src")) ?: return@forEach
            val rawLang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Indonesia" }
            val label = when (rawLang.lowercase()) {
                "id", "ind", "indonesia", "indonesian" -> "Indonesian"
                "en", "eng", "english" -> "English"
                else -> rawLang
            }
            subtitleCallback(newSubtitleFile(label, subUrl))
        }

        var foundStream = false

        // 2. Extract video from data-gfy-deferred-source
        val video = doc.selectFirst("video[data-gfy-deferred-source]")
        val deferredSource = video?.attr("data-gfy-deferred-source")

        if (!deferredSource.isNullOrBlank()) {
            try {
                val endpoint = fixUrl(deferredSource)
                val postResp = app.post(
                    endpoint,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "Origin" to mainUrl
                    ),
                    cookies = pageResp.cookies
                ).text

                val json = JSONObject(postResp)
                val streamUrl = json.optString("url")
                val isHls = json.optString("type") == "hls" || streamUrl.contains(".m3u8")

                if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                    if (isHls) {
                        try {
                            val m3u8Links = M3u8Helper.generateM3u8(this.name, streamUrl, mainUrl)
                            if (m3u8Links.isNotEmpty()) {
                                m3u8Links.forEach { callback(it) }
                                foundStream = true
                            } else {
                                callback(
                                    newExtractorLink(
                                        this.name,
                                        this.name,
                                        streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = mainUrl
                                    }
                                )
                                foundStream = true
                            }
                        } catch (_: Exception) {
                            callback(
                                newExtractorLink(
                                    this.name,
                                    this.name,
                                    streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            foundStream = true
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                this.name,
                                this.name,
                                streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        foundStream = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback: check for iframes
        doc.select("iframe.external-embed, .player iframe, iframe[src]").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            if (!src.contains("quge5") && !src.contains("adsterra") && !src.contains("highrevenueformat")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundStream = true
            }
        }

        return foundStream
    }
}
