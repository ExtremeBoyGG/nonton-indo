package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Rebahin : MainAPI() {
    // URL asli Rebahin adalah IP langsung
    override var mainUrl = "http://165.245.188.180"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Film Terbaru",
        "$mainUrl/tv/page/" to "Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val home = doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = article.selectFirst("h2, h3")?.text()?.trim()
                ?: a.attr("title").removePrefix("Permalink to:").trim().ifBlank { null }
                ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isSeries = href.contains("/tv/")
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
        return doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = article.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isSeries = href.contains("/tv/")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("^Nonton\\s+Film\\s+", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.poster img, div.thumb img, img[src*=upload]")?.attr("src")?.ifBlank { null }
        val description = doc.selectFirst("div.entry-content > p, div.sinopsis")
            ?.text()?.trim()
            ?.replace(Regex("^Nonton Film.*Sub Indo.*?–\\s*", RegexOption.DOT_MATCHES_ALL), "")
            ?.trim()
        val tags = doc.select("a[href*=category], a[rel=category]").map { it.text() }.filter { it.isNotBlank() }
        val year = doc.selectFirst("a[href*=/year/]")?.text()?.trim()?.toIntOrNull()

        val isSeries = url.contains("/tv/")
        return if (isSeries) {
            // Episode list ada di div.gmr-listseries, link ke /eps/
            val episodes = doc.select("div.gmr-listseries a[href*=/eps/]").mapNotNull { el ->
                val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
                val epText = el.text().trim().ifBlank { null } ?: return@mapNotNull null
                // Text contoh: "S1 Eps1", "S1 Eps2"
                val season = Regex("S(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val ep = Regex("Eps?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = epText
                    this.season = season
                    this.episode = ep
                }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Download links langsung di HTML: #download .gmr-download-list li a
        doc.select("div#download ul.gmr-download-list li a[href]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            loadExtractor(fixUrl(href), data, subtitleCallback, callback)
        }

        // Fallback: coba iframe jika ada (untuk beberapa halaman yang embed langsung)
        doc.select("div.tab-content iframe, div.gmr-embed-responsive iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (!src.contains("youtube")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}