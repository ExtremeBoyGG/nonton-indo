package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Hentai Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = ua).document
        val sections = mutableListOf<HomePageList>()

        doc.select("div.nk-hentai-grid ul li").mapNotNull { li ->
            val a = li.selectFirst("a.nk-series-link") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.selectFirst("div.title")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val style = a.selectFirst("div.nk-hentai-thumb")?.attr("style") ?: ""
            val poster = Regex("url\\('([^']+)'").find(style)?.groupValues?.getOrNull(1)
            newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.let { items ->
            if (items.isNotEmpty()) sections.add(HomePageList("Hentai Terbaru", items))
        }

        doc.select("#nk-episode-grid div.nk-post-card").mapNotNull { card ->
            val a = card.selectFirst("div.nk-post-meta h2 a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val style = card.selectFirst("div.nk-thumb-crop")?.attr("style") ?: ""
            val poster = Regex("url\\('([^']+)'").find(style)?.groupValues?.getOrNull(1)
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.let { items ->
            if (items.isNotEmpty()) sections.add(HomePageList("Episode Terbaru", items))
        }

        doc.select("div.nk-jav-grid ul li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.selectFirst("div.nk-jav-meta h2")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val style = li.selectFirst("div.nk-grid-thumb")?.attr("style") ?: ""
            val poster = Regex("url\\('([^']+)'").find(style)?.groupValues?.getOrNull(1)
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.let { items ->
            if (items.isNotEmpty()) sections.add(HomePageList("JAV Terbaru", items))
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query&post_type=anime", headers = ua).document
        return doc.select("div.nk-search-results ul li div.nk-search-item").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.nk-search-info h2")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val style = el.selectFirst("div.nk-search-thumb")?.attr("style") ?: ""
            val poster = Regex("url\\('([^']+)'").find(style)?.groupValues?.getOrNull(1)
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val html = doc.outerHtml()

        val title = doc.selectFirst("title")?.text()?.replace(" - NekoPoi", "")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val posterStyle = doc.selectFirst("div.nk-series-poster")?.attr("style")
            ?: doc.selectFirst("div.nk-featured-img img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val poster = Regex("url\\('([^']+)'").find(posterStyle ?: "")?.groupValues?.getOrNull(1) ?: posterStyle

        val description = doc.select("div.nk-series-synopsis p").text().trim().ifBlank {
            doc.select("div.nk-post-body div.konten p").text().trim()
        }.ifBlank { null }

        val tags = doc.select("div.nk-series-meta-list ul li a[href*=/genres/]").map { it.text() }

        val isSeries = url.contains("/hentai/")
        return if (isSeries) {
            val episodes = doc.select("div.nk-episode-grid ul li a.nk-episode-card").mapNotNull { a ->
                val epHref = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val epName = a.selectFirst("div.nk-episode-card-title")?.text()?.trim()?.ifBlank { null }
                    ?: a.selectFirst("span.nk-episode-badge")?.text()?.trim() ?: "Episode"
                val epNum = Regex("(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epStyle = a.selectFirst("div.nk-episode-card-thumb")?.attr("style") ?: ""
                val epPoster = Regex("url\\('([^']+)'").find(epStyle)?.groupValues?.getOrNull(1)
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = ua).document

        doc.select("div.nk-player-frame iframe, iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("div.player-embed iframe, div.tab-content iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("video source[src]").forEach { source ->
            val src = source.attr("src").ifBlank { return@forEach }
            callback(newExtractorLink(name, "$name - Video", src) {
                this.referer = mainUrl
            })
        }

        return true
    }
}
