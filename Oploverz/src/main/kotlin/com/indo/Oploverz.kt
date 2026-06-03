package com.indo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.bsx").mapNotNull { el ->
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
        return newHomePageResponse(request.name, home)
    }

    private fun episodeUrlToAnimeUrl(episodeUrl: String): String {
        val slug = episodeUrl.trimEnd('/').substringAfterLast("/")
        val animeSlug = slug.replace(Regex("-episode-\\d+.*", RegexOption.IGNORE_CASE), "")
        return "$mainUrl/series/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.bsx, article.bs").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, .tt")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.thumb img, img[src*=upload]")?.attr("src")?.ifBlank { null }
        val description = document.selectFirst("div.entry-content > p, div.synp p")?.text()?.trim()
        val genres = document.select("a[href*=genres]").map { it.text() }.filter { it.isNotBlank() }
        val statusText = document.selectFirst("div.spe span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim()
        val status = getStatus(statusText)
        val year = document.selectFirst("div.spe span:contains(Released)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list dari halaman series
        val episodes = document.select("div.eplister ul li, ul#episodelist li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epNum = li.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
            val epTitle = li.selectFirst("div.epl-title")?.text()?.trim() ?: a.text().trim()
            newEpisode(href) { this.name = epTitle; this.episode = epNum }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // Iframe dari player-embed
        document.select("div#pembed iframe, div.player-embed iframe, div.video-content iframe")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { null } ?: return@forEach
                if (src.startsWith("http")) handleUrl(src, data, subtitleCallback, callback)
            }

        // Mirror option values (base64 encoded iframes)
        document.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").ifBlank { null } ?: return@forEach
            val decoded = try { String(Base64.getDecoder().decode(encoded)) } catch (e: Exception) { null } ?: return@forEach
            val src = Regex("src\\s*=\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.getOrNull(1) ?: return@forEach
            if (src.startsWith("http")) handleUrl(src, data, subtitleCallback, callback)
        }

        // Gofile download link — pakai built-in Gofile extractor (API v2)
        document.select("a[href*=gofile.io]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            loadExtractor(href, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun handleUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (url.contains("blogger.com")) {
            handleBloggerUrl(url, referer, subtitleCallback, callback)
        } else {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun handleBloggerUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Try built-in extractor first (handles old-style pages with VIDEO_CONFIG)
        val extracted = loadExtractor(url, referer, subtitleCallback, callback)
        if (!extracted.isNullOrEmpty()) return

        // Manual parsing of the Blogger page (fallback)
        try {
            val doc = app.get(url).document
            doc.select("script").forEach { script ->
                val text = script.data()
                val streamsStart = text.indexOf("\"streams\":[")
                if (streamsStart >= 0) {
                    val from = streamsStart + "\"streams\":[".length
                    val end = text.indexOf(']', from)
                    if (end > from) {
                        val streamsJson = "[" + text.substring(from, end) + "]"
                        tryParseJson<List<ResponseSource>>(streamsJson)?.forEach { source ->
                            callback(
                                newExtractorLink(name, name, source.play_url) {
                                    this.referer = "https://www.youtube.com/"
                                    this.quality = when (source.format_id) {
                                        18 -> 360
                                        22 -> 720
                                        else -> Qualities.Unknown.value
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private data class ResponseSource(
        @JsonProperty("play_url") val play_url: String,
        @JsonProperty("format_id") val format_id: Int
    )
}