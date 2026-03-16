package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url).document

        val home = doc.select("a:has(div.product__sidebar__view__item)").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("/anime/")) return@mapNotNull null

            // Title from h5 inside div.product__sidebar__view__item
            val title = a.selectFirst("div h5")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            // Poster from data-setbg attribute on div.set-bg
            val poster = a.selectFirst("div.set-bg")?.attr("data-setbg")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        throw ErrorLoadingException("Not implemented yet")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}
