package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class SemiRebahin : MainAPI() {
    override var mainUrl = "http://167.71.237.49"
    override var name = "SemiRebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = ua).document
        val sections = mutableListOf<HomePageList>()

        doc.select("div.home-widget.muvipro-posts-module").forEach { widget ->
            val name = widget.selectFirst("h3.homemodule-title")?.text()?.trim() ?: return@forEach
            val items = widget.select("article").mapNotNull { article ->
                val a = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                val poster = article.selectFirst("div.content-thumbnail img")?.attr("src")?.ifBlank { null }
                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }
            if (items.isNotEmpty()) sections.add(HomePageList(name, items))
        }

        doc.select("#gmr-main-load article").mapNotNull { article ->
            val a = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("div.content-thumbnail img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.let { items ->
            if (items.isNotEmpty()) sections.add(HomePageList("Latest Movie", items))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select("#gmr-main-load article").mapNotNull { article ->
            val a = article.selectFirst("h2.entry-title a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("div.content-thumbnail img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document

        val title = doc.selectFirst("h1.entry-title[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.gmr-movie-data figure img")?.attr("src")?.let { src ->
            src.replace("-60x90", "")
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("div.entry-content-single p")?.text()?.trim()?.ifBlank { null }

        val genres = doc.select("div.gmr-moviedata:has(strong)").mapNotNull { div ->
            if (div.text().startsWith("Genre")) {
                div.select("a[rel=category tag]").map { it.text() }
            } else null
        }.flatten()

        val tags = doc.select("div.gmr-moviedata a[rel=tag]").mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotBlank()) text else null
        }

        val rating = doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
        val score = Score.from10(rating)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres + tags
            this.score = score
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = ua).document

        var found = false

        doc.select("div.gmr-embed-responsive iframe, iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            loadExtractor(src, data, subtitleCallback, callback)
            found = true
        }

        doc.select("ul.muvipro-player-tabs li a").forEach { tab ->
            val playerParam = tab.attr("href")
            if (!playerParam.contains("player=")) return@forEach
            try {
                val tabDoc = app.get("$data$playerParam", headers = ua).document
                tabDoc.select("div.gmr-embed-responsive iframe, iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { return@forEach }
                    loadExtractor(src, data, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Exception) { }
        }

        doc.select("video source[src]").forEach { source ->
            val src = source.attr("src").ifBlank { return@forEach }
            callback(newExtractorLink(name, "$name - Video", src) {
                this.referer = mainUrl
            })
            found = true
        }

        return true
    }
}
