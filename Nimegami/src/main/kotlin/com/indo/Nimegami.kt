package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Nimegami : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Nimegami juga blok bot — perlu User-Agent
    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Anime Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = ua).document
        val home = doc.select("article, div.item, div.animepost").mapNotNull { article ->
            val a = article.selectFirst("h2 a, h3 a, a[rel=bookmark]")
                ?: article.select("a[href]").firstOrNull { it.text().isNotBlank() }
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { a.attr("title").ifBlank { null } } ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null } ?: img.attr("src").takeIf { !it.startsWith("data:") }
            }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select("article, div.item, div.animepost").mapNotNull { article ->
            val a = article.selectFirst("h2 a, h3 a, a[rel=bookmark]")
                ?: article.select("a[href]").firstOrNull { it.text().isNotBlank() }
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null } ?: img.attr("src").takeIf { !it.startsWith("data:") }
            }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("div.thumb img, div.poster img, img.wp-post-image")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").takeIf { !it.startsWith("data:") }
        }
        val description = doc.selectFirst("div.entry-content > p, div.sinopsis p, div.desc")?.text()?.trim()
        val genres = doc.select("a[rel=tag], div.genre a").map { it.text() }
        val year = doc.selectFirst("span:contains(Tahun), span:contains(Year)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = doc.select("a[href*=-episode-]").mapNotNull {
            val href = it.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = it.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = name; this.episode = ep }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = ua).document
        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }
        return true
    }
}