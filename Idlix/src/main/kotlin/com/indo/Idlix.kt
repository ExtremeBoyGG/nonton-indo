package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Idlix : MainAPI() {
    // Idlix sering ganti domain — pakai IP stabil yang ditemukan
    override var mainUrl = "http://139.59.203.130"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Film Terbaru",
        "$mainUrl/tvseries/page/" to "Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val home = doc.select("div.ml-item, article, div.item").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.mli-info h2, .title")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-original").ifBlank { null } ?: img.attr("src").ifBlank { null }
            }
            val isSeries = href.contains("/tvseries/") || href.contains("/series/")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.ml-item, article, div.item").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.mli-info h2")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-original").ifBlank { null } ?: img.attr("src").ifBlank { null }
            }
            val isSeries = href.contains("/tvseries/") || href.contains("/series/")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.movie-thumbnail img, div.thumb img, img.wp-post-image")
            ?.let { img -> img.attr("data-original").ifBlank { null } ?: img.attr("src").ifBlank { null } }

        val description = doc.selectFirst("div.entry-content p, div.desc, div.overview")?.text()?.trim()
        val tags = doc.select("a[href*=genre], a[href*=category]").map { it.text() }.filter { it.isNotBlank() }
        val year = doc.selectFirst("span.year, a[href*=/year/]")?.text()?.trim()?.toIntOrNull()

        val isSeries = url.contains("/tvseries/") || url.contains("/series/")
        return if (isSeries) {
            val episodes = doc.select("div.episодelist a, div.episodes a, ul.episodelist li a").mapNotNull { el ->
                val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
                val epName = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                val ep = Regex("(?:episode|eps?)[.\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) { this.name = epName; this.episode = ep }
            }.distinctBy { it.data }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }
        doc.select("a[href*=dl], a.download-btn, div.download a").mapNotNull {
            it.attr("href").ifBlank { null }
        }.forEach { link ->
            loadExtractor(fixUrl(link), data, subtitleCallback, callback)
        }
        return true
    }
}
