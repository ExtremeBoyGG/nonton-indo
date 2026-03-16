package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document

        val home = document.select("div.anime__page__content a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("/anime/")) return@mapNotNull null
            // Skip episode links, only want anime detail links
            if (href.contains("/episode/")) return@mapNotNull null

            val title = a.selectFirst("h5")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
            }

            val isMovie = href.contains("/quick/movie") || request.data.contains("/movie")
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }

        // Fallback: if no cards found, try generic anime links
        val results = if (home.isEmpty()) {
            document.select("a[href*='/anime/']").mapNotNull { a ->
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                if (!href.contains("/anime/")) return@mapNotNull null
                if (href.contains("/episode/")) return@mapNotNull null
                // Must be a direct anime link like /anime/123/slug
                if (!Regex("/anime/\\d+/").containsMatchIn(href)) return@mapNotNull null

                val title = a.selectFirst("h5")?.text()?.trim()
                    ?: a.text().trim().ifBlank { null }
                    ?: return@mapNotNull null

                val poster = a.selectFirst("img")?.let { img ->
                    img.attr("src").ifBlank { null }
                        ?: img.attr("data-src").ifBlank { null }
                }

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        } else home

        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/anime?search_key=$query").document
        return document.select("a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("/anime/")) return@mapNotNull null
            if (href.contains("/episode/")) return@mapNotNull null
            if (!Regex("/anime/\\d+/").containsMatchIn(href)) return@mapNotNull null

            val title = a.selectFirst("h5")?.text()?.trim()
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
            }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2, h1, .anime__details__title")
            ?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.anime__details__pic img, div.anime__details img")
            ?.let { img ->
                img.attr("src").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
            }
            ?: document.selectFirst("img[alt*='$title']")
                ?.let { img ->
                    img.attr("src").ifBlank { null }
                        ?: img.attr("data-src").ifBlank { null }
                }

        val description = document.selectFirst("div.anime__details__text p, div.anime__details__content p")
            ?.text()?.trim()

        val genres = document.select("a[href*='/properties/genre/']").map { it.text() }.filter { it.isNotBlank() }

        val year = document.selectFirst("a[href*='/properties/season/']")
            ?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        // Check if it's a movie
        val isMovie = document.selectFirst("a[href*='/properties/type/movie']") != null
            || url.contains("/quick/movie")

        // Episodes from popover or direct links
        val episodeLinks = document.select("a[href*='/episode/']")
            .mapNotNull { a ->
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val epNum = Regex("/episode/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                Pair(href, epNum)
            }
            .distinctBy { it.first }
            .sortedBy { it.second }

        val episodes = episodeLinks.map { (href, epNum) ->
            newEpisode(href) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }

        return if (isMovie && episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Streaming extraction needs API sniffing
        // User will implement after analyzing network requests
        val document = app.get(data).document

        // Try to find iframe or direct video sources
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            val fullUrl = if (src.startsWith("/")) "$mainUrl$src" else src
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
