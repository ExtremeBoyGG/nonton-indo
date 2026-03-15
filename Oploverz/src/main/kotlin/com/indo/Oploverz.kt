package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.Base64

class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.ch"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=&type=&order=update&page=" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.bsx, article.bs, div.animepost").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, h3, div.ntitle, .tt")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.bsx, article.bs, div.animepost").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, h3, .tt")?.text()?.trim()
                ?: a.attr("title").ifBlank { null }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.thumb img, img.attachment-post-thumbnail, img[src*=upload]")
            ?.attr("src")?.ifBlank { null }

        val description = document.selectFirst("div.entry-content > p, div.synp p, div.desc p")?.text()?.trim()
        val genres = document.select("a[href*=genres]").map { it.text() }.filter { it.isNotBlank() }

        val statusText = document.selectFirst("div.spe span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim() ?: ""
        val status = getStatus(statusText)

        val year = document.selectFirst("div.spe span:contains(Released), div.spe span:contains(Tahun)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = document.select("div.eplister ul li, ul#episodelist li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epNum = li.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
            val epTitle = li.selectFirst("div.epl-title")?.text()?.trim() ?: a.text().trim()
            newEpisode(href) { this.name = epTitle; this.episode = epNum }
        }.reversed()

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
        val document = app.get(data).document

        val iframes = document.select("iframe").mapNotNull { it.attr("src").ifBlank { null }?.let { fixUrl(it) } }
        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        // Decode base64 server options (pakai java.util.Base64)
        document.select("select.mirror option, div.mirror option").mapNotNull {
            it.attr("value").ifBlank { null }
        }.forEach { encoded ->
            try {
                val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                val src = Regex("""src="([^"]+)"""").find(decoded)?.groupValues?.getOrNull(1)
                if (src != null) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            } catch (_: Exception) { }
        }

        return true
    }
}