package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Donghub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rilis Terbaru",
        "$mainUrl/page/" to "Rilis Terbaru Lanjutan",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.let { if (page > 1) it + page else it }).document
        val sections = mutableListOf<HomePageList>()

        doc.select("div.listupd article.bs").mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.selectFirst(".tt")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = a.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val epx = a.selectFirst(".bt .epx")?.text()?.trim()
            val type = a.selectFirst(".typez")?.text()?.trim()

            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }.let { items ->
            if (items.isNotEmpty()) sections.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd article.bs").mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.selectFirst(".tt")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = a.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = a.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".single-info .thumb img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".desc p, .entry-content p").text().trim().ifBlank { null }

        val tags = doc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }

        val typeText = doc.select("div.spe span:contains(Type)")?.text()?.trim()
        val tvType = if (typeText?.contains("Movie") == true) TvType.AnimeMovie else TvType.Anime

        val episodes = doc.select("div[class*=\"eplist\"] > div, div.eplister > div, .eplister div[class]").mapNotNull { row ->
            val a = row.selectFirst("a[href]") ?: return@mapNotNull null
            val epHref = a.attr("href")
            val epText = a.text().trim()
            val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epHref) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("#pembed iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").ifBlank { return@forEach }
            if (encoded.length < 10) return@forEach
            try {
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                val iframeSrc = Regex("""iframe\s+[^>]*src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)
                if (iframeSrc != null) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        return true
    }
}
