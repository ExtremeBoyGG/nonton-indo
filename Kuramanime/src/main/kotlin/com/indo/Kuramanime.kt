package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Episode Terbaru",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document

        val home = doc.select("a[href*=/anime/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("/anime/")) return@mapNotNull null

            val title = a.selectFirst("h5.sidebar-title-h5")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("div.set-bg[data-setbg]")?.attr("data-setbg")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=text"
        val doc = app.get(url).document

        return doc.select("a[href*=/anime/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.selectFirst("h5.sidebar-title-h5")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val poster = a.selectFirst("div.set-bg[data-setbg]")?.attr("data-setbg")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = url.replace("/episode/.*".toRegex(), "")
        val doc = app.get(animeUrl).document

        val title = doc.selectFirst("h2, .anime__details__title")
            ?.text()?.trim() ?: "Unknown"

        val poster = doc.selectFirst("div.set-bg[data-setbg]")
            ?.attr("data-setbg")?.ifBlank { null }

        val description = doc.selectFirst(".anime__details__content p")
            ?.text()?.trim()

        val episodes = doc.select("a.ep-button[type=episode]").mapNotNull { ep ->
            val epHref = ep.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epTitle = ep.text().trim().ifBlank { null } ?: return@mapNotNull null
            newEpisode(epHref) { this.name = epTitle }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("source, video source").forEach { source ->
            val src = source.attr("src").ifBlank { null } ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = src,
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }
}
