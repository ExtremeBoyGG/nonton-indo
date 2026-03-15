package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/complete-anime/page/" to "Anime Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = ua).document
        val home = document.select("div.venz > ul > li").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2.jdlflm")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("div.thumbz > img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime", headers = ua).document
        return document.select("ul.chivsrc > li").mapNotNull { el ->
            val a = el.selectFirst("h2 > a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = ua).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")
            ?.ownText()?.replace(":", "")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")?.ifBlank { null }
        val description = document.select("div.sinopc > p").text().trim()
        val genres = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val statusText = document.selectFirst("div.infozingle > p:nth-child(6) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: ""
        val status = if (statusText.contains("Ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed
        val year = Regex("\\d, (\\d*)").find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()

        // Episode list — Otakudesu: div.episodelist[1] (index 1 = daftar episode, bukan batch)
        val episodeLists = document.select("div.episodelist")
        val episodeList = if (episodeLists.size > 1) episodeLists[1] else episodeLists.firstOrNull()
        val episodes = episodeList?.select("ul > li")?.mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = name; this.episode = ep }
        }?.reversed() ?: emptyList()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = ua).document

        // Iframe streaming
        document.select("div#pembed iframe, div.player-embed iframe, div.responsive-embed-stream iframe")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { null } ?: return@forEach
                if (src.startsWith("http")) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }

        // Download links dengan quality dari <strong>
        document.select("div.download li").forEach { li ->
            val qualityText = li.selectFirst("strong")?.text() ?: ""
            val quality = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                .find(qualityText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value

            li.select("a[href]").forEach { a ->
                val href = a.attr("href").ifBlank { null } ?: return@forEach
                // Pass quality langsung — extractor akan gunakan quality ini
                loadExtractor(fixUrl(href), data, subtitleCallback) { link ->
                    @Suppress("DEPRECATION")
                    callback(com.lagradost.cloudstream3.utils.ExtractorLink(
                        link.source,
                        "${a.text().trim()} ${qualityText.substringAfter(" ")}",
                        link.url,
                        link.referer,
                        quality,
                        link.isM3u8
                    ))
                }
            }
        }

        return true
    }

}