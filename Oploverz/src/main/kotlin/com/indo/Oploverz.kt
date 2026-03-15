package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.ch"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t.trim()) {
                "Completed", "Selesai", "Tamat" -> ShowStatus.Completed
                "Ongoing", "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else request.data
        val document = app.get(url).document

        val home = document.select("div.bsx, div.listupd article, article").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, .tt, .ntitle")?.text()?.trim()
                ?: el.selectFirst("a")?.attr("title")?.ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")
                ?: el.selectFirst("div.set-bg")?.attr("data-setbg")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.bsx, div.listupd article, article").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, .tt")?.text()?.trim()
                ?: el.selectFirst("a")?.attr("title")?.ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*"), "")
            ?.replace(Regex("^Nonton\\s+"), "")
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.thumb img, div.animeinfo img, img.anime-thumbnail")
            ?.attr("src")
            ?.let { fixUrl(it) }

        val genres = document.select("div.genre-info a, div.genxed a, span:contains(Genre) a")
            .map { it.text() }

        val statusText = document.selectFirst("span:contains(Status), div.infoz span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim() ?: ""
        val status = getStatus(statusText)

        val description = document.selectFirst("div.desc, div.sinopsis, div.entry-content p")
            ?.text()?.trim()

        val year = document.selectFirst("span:contains(Year), span:contains(Rilis)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list
        val episodes = document.select("div.lstepsiode li, div.eplister ul li, ul li:has(a[href*=episode])")
            .mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                val name = a.text().trim()
                val episode = Regex("episode[\\s-]*(\\d+[.,]?\\d*)", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.getOrNull(1)?.replace(",", ".")?.toFloatOrNull()
                newEpisode(href) { this.name = name; this.episode = episode?.toInt() }
            }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Find iframes
        val iframes = document.select("iframe").mapNotNull { iframe ->
            iframe.attr("src").ifBlank { null }?.let { fixUrl(it) }
        }

        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Find server selection
        val serverLinks = document.select("div.server-item a, li.server a, a[data-server]")
            .mapNotNull { el ->
                val href = el.attr("href").ifBlank { null } ?: el.attr("data-src").ifBlank { null }
                href?.let { fixUrl(it) }
            }

        for (link in serverLinks) {
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // Find download links
        val dlLinks = document.select("div.download a, a.download-link")
            .mapNotNull { el ->
                el.attr("href").ifBlank { null }
            }

        for (link in dlLinks) {
            loadExtractor(fixUrl(link), data, subtitleCallback, callback)
        }

        return iframes.isNotEmpty() || serverLinks.isNotEmpty() || dlLinks.isNotEmpty()
    }
}
