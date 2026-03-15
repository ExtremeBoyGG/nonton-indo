package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Oploverz : MainAPI() {
    override var mainUrl = "https://oploverz.ch"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("Completed", true) == true || t?.contains("Tamat", true) == true -> ShowStatus.Completed
                t?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=&type=&order=update&page=" to "Update Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.bsx, article.bs").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, .tt")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.bsx, article.bs").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("div.tt h4, h4, .tt")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.thumb img, img[src*=upload]")?.attr("src")?.ifBlank { null }
        val description = document.selectFirst("div.entry-content > p, div.synp p")?.text()?.trim()
        val genres = document.select("a[href*=genres]").map { it.text() }.filter { it.isNotBlank() }
        val statusText = document.selectFirst("div.spe span:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim()
        val status = getStatus(statusText)
        val year = document.selectFirst("div.spe span:contains(Released)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list dari halaman series
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

        // Iframe Blogger langsung ada di static HTML di div#pembed
        document.select("div#pembed iframe, div.player-embed iframe, div.video-content iframe")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { null } ?: return@forEach
                if (src.startsWith("http")) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }

        // Gofile download link — perlu guest token dulu
        document.select("a[href*=gofile.io]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            extractGofile(href, callback)
        }

        return true
    }

    private suspend fun extractGofile(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Step 1: guest token
            val tokenResp = app.post("https://api.gofile.io/accounts/guest")
                .parseJson<GofileTokenResponse>()
            val token = tokenResp?.data?.token ?: return

            // Step 2: folder ID dari URL gofile.io/d/XXXXX
            val folderId = Regex("gofile\\.io/d/([^/?&]+)").find(url)
                ?.groupValues?.getOrNull(1) ?: return

            // Step 3: fetch content
            val content = app.get(
                "https://api.gofile.io/contents/$folderId?wt=4fd6sg89d7s6",
                headers = mapOf("Authorization" to "Bearer $token")
            ).parseJson<GofileContentResponse>()

            // Step 4: tiap file = 1 kualitas
            // Nama file: "Ikoku Nikki - 10.720.mp4" → quality = "720"
            content?.data?.children?.values?.forEach { file ->
                val directLink = file.link ?: return@forEach
                val name = file.name ?: return@forEach

                // Parse quality dari nama file: ambil angka sebelum .mp4/.mkv
                val qualityStr = Regex("\\.(1K|\\d{3,4})\\.[^.]+$", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.getOrNull(1) ?: "?"
                val quality = when (qualityStr.uppercase()) {
                    "1K" -> 1080
                    else -> qualityStr.toIntOrNull()
                        ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        "Gofile",
                        "Gofile ${quality}p",
                        directLink,
                    ) {
                        this.referer = ""
                        this.quality = quality
                        this.headers = mapOf("Authorization" to "Bearer $token")
                    }
                )
            }
        } catch (e: Exception) { }
    }

    data class GofileTokenResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("data") val data: GofileTokenData?
    )
    data class GofileTokenData(
        @com.fasterxml.jackson.annotation.JsonProperty("token") val token: String?
    )
    data class GofileContentResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("data") val data: GofileContentData?
    )
    data class GofileContentData(
        @com.fasterxml.jackson.annotation.JsonProperty("children") val children: Map<String, GofileFile>?
    )
    data class GofileFile(
        @com.fasterxml.jackson.annotation.JsonProperty("name") val name: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("link") val link: String?
    )
}