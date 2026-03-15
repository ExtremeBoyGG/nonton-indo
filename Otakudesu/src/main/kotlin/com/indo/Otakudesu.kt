package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Anime Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else request.data
        val document = app.get(url).document

        val home = document.select("div.thumb a, div.detpost a, li.anime a").mapNotNull { el ->
            val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.nm, span.judul")?.text()?.trim()
                ?: el.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")
                ?.let { fixUrl(it) }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime").document

        return document.select("ul.chivsrc li, div.thumb a").mapNotNull { el ->
            val a = el.selectFirst("a") ?: el
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.nm")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val posterUrl = el.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, div.animeinfo h1")?.text()?.trim()
            ?.replace(Regex("\\s*\\(Episode.*"), "")
            ?.replace(Regex("\\s*Subtitle.*"), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.animeinfo img, div.thumb img, img.anime-thumbnail")
            ?.attr("src")

        val genres = document.select("div.genre-info a, td:contains(Genre) a, span:contains(Genre) a")
            .map { it.text() }

        val status = document.selectFirst("td:contains(Status), span:contains(Status)")
            ?.let { el ->
                val text = el.text().replace("Status:", "").trim()
                when (text) {
                    "Completed", "Selesai" -> ShowStatus.Completed
                    "Ongoing" -> ShowStatus.Ongoing
                    else -> ShowStatus.Completed
                }
            } ?: ShowStatus.Completed

        val description = document.selectFirst("div.sinopcis, div.sinopsis p, div.entry-content p")
            ?.text()?.trim()

        // Episode list - pattern: /episode/{slug}/
        val episodes = document.select("ul.episodelist li a, div.episodelist a").mapNotNull { el ->
            val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = el.text().trim()
            val episode = Regex("episode[\\s-]*(\\d+[.,]?\\d*)", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.getOrNull(1)?.replace(",", ".")?.toFloatOrNull()
            newEpisode(href) { this.name = name; this.episode = episode?.toInt() }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster?.let { fixUrl(it) }
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Find iframes (desustream, etc)
        val iframes = document.select("iframe").mapNotNull { iframe ->
            iframe.attr("src").ifBlank { null }?.let { fixUrl(it) }
        }

        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Find mirror/server selection
        val mirrors = document.select("div.mirrorstream ul li a, a[data-server]")
            .mapNotNull { el ->
                val onclick = el.attr("onclick").ifBlank { null }
                val dataSrc = el.attr("data-src").ifBlank { null }
                val href = el.attr("href").ifBlank { null }
                onclick ?: dataSrc ?: href
            }

        for (mirror in mirrors) {
            if (mirror.startsWith("http")) {
                loadExtractor(fixUrl(mirror), data, subtitleCallback, callback)
            }
        }

        return iframes.isNotEmpty() || mirrors.isNotEmpty()
    }
}
