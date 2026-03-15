package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        document.select("div#pembed iframe, div.player-embed iframe, div.video-content iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (!src.startsWith("http")) return@forEach

            // Handle Blogger video langsung tanpa custom extractor
            if (src.contains("blogger.com/video.g")) {
                extractBloggerVideo(src, data, callback)
            } else {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        // Fallback: download link (gofile, mega, dll)
        document.select("a[href]").filter { a ->
            val href = a.attr("href")
            href.contains("gofile.io") || href.contains("mega.nz") ||
            href.contains("pixeldrain") || href.contains("mediafire") ||
            href.contains("acefile")
        }.forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            loadExtractor(fixUrl(href), "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private suspend fun extractBloggerVideo(
        iframeSrc: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = Regex("[?&]token=([^&]+)").find(iframeSrc)
            ?.groupValues?.getOrNull(1) ?: return

        val freq = "[[[\"WcwnYd\",\"[\\\"" + token + "\\\",\\\"\\\",0]\",null,\"generic\"]]]"

        val response = try {
            app.post(
                "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute",
                params = mapOf("rpcids" to "WcwnYd", "rt" to "c"),
                data = mapOf("f.req" to freq, "" to ""),
                referer = iframeSrc,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                )
            ).text
        } catch (e: Exception) { return }

        val decoded = response
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        val videoUrl = Regex("https://r[\\w.%-]+googlevideo\\.com/videoplayback[\\w.=&%+,;:@!*/?~-]+")
            .find(decoded)?.value ?: return

        callback.invoke(
            ExtractorLink(
                source = "Blogger",
                name = "Blogger",
                url = videoUrl,
                referer = "https://www.blogger.com/",
                quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                isM3u8 = false
            )
        )
    }
}