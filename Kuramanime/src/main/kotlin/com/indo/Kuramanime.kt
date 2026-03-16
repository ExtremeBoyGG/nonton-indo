package com.indo

import com.lagradost.cloudstream3.*
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
        val doc = app.get(url).document

        val isMovie = request.data.contains("/movie")

        val home = doc.select("a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("/anime/")) return@mapNotNull null
            if (href.contains("/episode/")) return@mapNotNull null
            if (!Regex("/anime/\\d+/").containsMatchIn(href)) return@mapNotNull null

            val title = a.selectFirst("h5")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img")?.attr("src")?.ifBlank { null }

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

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/anime?search_key=$query").document
        return doc.select("a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (href.contains("/episode/")) return@mapNotNull null
            if (!Regex("/anime/\\d+/").containsMatchIn(href)) return@mapNotNull null

            val title = a.selectFirst("h5")?.text()?.trim()
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img")?.attr("src")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("img[alt]")
            ?.attr("src")?.ifBlank { null }

        val description = doc.selectFirst("p")?.text()?.trim()

        val genres = doc.select("a[href*='/properties/genre/']").map { it.text() }

        val episodes = doc.select("a[href*='/episode/']").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("/episode/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(href) {
                this.name = "Episode $ep"
                this.episode = ep
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        val isMovie = url.contains("/quick/movie") || doc.selectFirst("a[href*='/properties/type/movie']") != null

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
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
        // TODO: Implement streaming extraction later
        return false
    }
}
