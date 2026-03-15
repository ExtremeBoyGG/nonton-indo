package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
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
        "$mainUrl/page/" to "Episode Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("div.list-anime a").mapNotNull { el ->
            val href = el.attr("href")
            val title = el.selectFirst("h2, h3, span, div")?.text()?.trim()
                ?: el.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")
                ?: el.attr("data-setbg").ifBlank { null }

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.list-anime a, div.anime-list a, article a").mapNotNull { el ->
            val href = el.attr("href")
            val title = el.selectFirst("h2, h3, span")?.text()?.trim()
                ?: el.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .anime-title, .entry-title")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*"), "")
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".anime-poster img, .thumb img, .cover img")
            ?.attr("src")
            ?: document.selectFirst("img.anime-thumbnail, img.thumb")?.attr("src")

        val genres = document.select("a[rel=tag], .genre-info a, div.genre a").map { it.text() }
        val status = document.selectFirst("li:contains(Status), span:contains(Status)")
            ?.let { el ->
                val text = el.text().replace("Status:", "").trim()
                getStatus(text)
            } ?: ShowStatus.Completed

        val description = document.selectFirst("div.sinopsis, div.desc, div.anime-synopsis p, div.entry-content p")
            ?.text()?.trim()

        val year = document.selectFirst("li:contains(Rilis), span:contains(Year)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list
        val episodes = document.select("a[href*=-episode-]").mapNotNull { el ->
            val href = el.attr("href")
            if (!href.contains("-episode-", ignoreCase = true)) return@mapNotNull null
            val name = el.text().trim()
            val episode = Regex("episode[\\s-]*(\\d+[.,]?\\d*)", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.getOrNull(1)?.replace(",", ".")?.toFloatOrNull()
            newEpisode(fixUrl(href)) { this.name = name; this.episode = episode?.toInt() }
        }.sortedByDescending { it.episode }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster?.let { fixUrl(it) }
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

        // Primary: iframe sources
        val iframes = document.select("iframe").mapNotNull { iframe ->
            iframe.attr("src").ifBlank { null }?.let { fixUrl(it) }
        }

        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Secondary: server selection links
        val serverLinks = document.select("a[data-server], li.server a, div.server-option a")
            .mapNotNull { el ->
                val href = el.attr("href").ifBlank { null } ?: el.attr("data-src").ifBlank { null }
                href?.let { fixUrl(it) }
            }

        for (link in serverLinks) {
            if (link.contains(".php", ignoreCase = true)) {
                val playerDoc = app.get(link, referer = data).document
                val playerIframe = playerDoc.selectFirst("iframe")?.attr("src")
                if (playerIframe != null) {
                    loadExtractor(playerIframe, link, subtitleCallback, callback)
                }
            } else {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return iframes.isNotEmpty() || serverLinks.isNotEmpty()
    }
}
