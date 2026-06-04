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
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(if (page > 1) "$mainUrl/page/$page/" else mainUrl).document
        val items = doc.select("div.listupd article.bs").mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val seriesTitle = item.selectFirst(".eggtitle")?.text()?.trim()?.ifBlank { null }
            val epText = item.selectFirst(".eggepisode")?.text()?.trim()
            val title = seriesTitle ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = item.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = item.selectFirst(".eggtype")?.text()?.trim()
                ?: item.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd article.bs").mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = item.selectFirst(".eggtitle")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = item.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = item.selectFirst(".eggtype")?.text()?.trim()
                ?: item.selectFirst(".typez")?.text()?.trim()
            val epText = item.selectFirst(".eggepisode")?.text()?.trim()
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

        val isEpisode = url.contains("-episode-", ignoreCase = true)

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".single-info .thumb img")?.attr("src")
            ?: doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".desc p, .entry-content p").text().trim().ifBlank { null }
        val tags = doc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }
        val typeText = doc.select("div.spe span:contains(Type)")?.text()?.trim()
        val tvType = if (typeText?.contains("Movie") == true) TvType.AnimeMovie else TvType.Anime

        if (isEpisode) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()

        doc.select(".eplister > div, .eplister > a, div[class*=\"eps\"] > a").forEach { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: return@forEach
            val epHref = a.attr("href").ifBlank { return@forEach }
            val epText = a.text().trim().ifBlank { return@forEach }
            val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            episodes.add(newEpisode(epHref) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            })
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*=\"/\"]").filter { a ->
                val href = a.attr("href")
                href.contains(url.trimEnd('/')) && href.contains("-episode-")
            }.forEach { a ->
                val epHref = a.attr("href")
                val epText = a.text().trim()
                val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epText
                    this.episode = epNum
                    this.posterUrl = poster
                })
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
