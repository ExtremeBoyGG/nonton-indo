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

    override val mainPage = mainPageOf("$mainUrl/page/" to "Anime Terbaru")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val home = doc.select("article a, div.article a").mapNotNull {
            val href = it.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = it.selectFirst("h2, h3")?.text()?.trim() ?: it.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article a").mapNotNull {
            val href = it.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = it.selectFirst("h2, h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.replace(Regex("\\s*Subtitle.*"), "")?.trim()
            ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("div.thumb img, img.anime-thumbnail")?.attr("src")
        val description = doc.selectFirst("div.desc, div.sinopsis p, div.entry-content p")?.text()?.trim()
        val episodes = doc.select("a[href*=-episode-]").mapNotNull {
            val href = it.attr("href") ?: return@mapNotNull null
            val name = it.text().trim()
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            Episode(href, name, episode = ep)
        }.reversed()
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster; plot = description; addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } } }.forEach {
            loadExtractor(fixUrl(it), data, subtitleCallback, callback)
        }
        return true
    }
}
