package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.jsoup.nodes.Document
import java.util.regex.PatternSyntaxException

class Kuronime : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Homepage: halaman utama menampilkan episode terbaru di section .listupd > article.bsu
    // dan juga ada pagination
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val home = document.select("div.listupd article.bsu").mapNotNull { article ->
            val link = article.selectFirst("div.bsux a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Episode to anime URL conversion
            val animeUrl = episodeToAnimeUrl(href)

            val title = article.selectFirst("div.bsux h2")?.text()?.trim()
                ?: article.selectFirst("h2")?.text()?.trim()
                ?: return@mapNotNull null

            // Remove "Episode X" from title if present
            val cleanTitle = title.replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title }

            val poster = article.selectFirst("img")?.attr("src")
                ?.ifBlank { null }
                ?.let { fixUrl(it) }

            val epNumText = article.selectFirst("div.ep")?.text()?.trim()
            val epNum = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            if (animeUrl.contains("/anime/")) {
                newAnimeSearchResponse(cleanTitle, animeUrl, TvType.Anime) {
                    this.posterUrl = poster
                    addSub(epNum)
                }
            } else {
                // If not anime pattern, treat as direct? skip
                null
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bsu").mapNotNull { article ->
            val link = article.selectFirst("div.bsux a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = article.selectFirst("div.bsux h2")?.text()?.trim()
                ?: article.selectFirst("h2")?.text()?.trim()
                ?: return@mapNotNull null

            val cleanTitle = title.replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title }

            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }?.let { fixUrl(it) }

            // Convert to anime URL if possible
            val animeUrl = episodeToAnimeUrl(href).takeIf { it.contains("/anime/") } ?: href

            newAnimeSearchResponse(cleanTitle, animeUrl, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // If URL is episode, find anime page
        val isAnimePage = url.contains("/anime/")
        val (animeUrl, episodeDoc) = if (isAnimePage) {
            Pair(url, null)
        } else {
            // Fetch episode page to get "All Episodes" link
            val doc = app.get(url).document
            val allEpLink = doc.selectFirst("div.navi a[href*='/anime/']")
                ?: doc.selectFirst("a[href*='/anime/']")
            val animeLink = allEpLink?.attr("href")?.ifBlank { null }?.let { fixUrl(it) }
                ?: episodeToAnimeUrl(url) // fallback
            Pair(animeLink, doc)
        }

        val document = app.get(animeUrl).document

        // Title
        val title = document.selectFirst("h1.entry-title, h1.title, h2.title, h1, h2")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        // Poster: Prefer meta image or detail img
        val poster = document.selectFirst("div.detail img, div.thumb img, meta[property='og:image']")
            ?.attr("src")?.ifBlank { null }
            ?.let { fixUrl(it) }

        // Description: meta description or content
        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("div.entry-content p, div.detail p, p.des")?.text()?.trim()

        // Genres: Usually in div.detail li a or .genres
        val genres = document.select("div.detail li a, div.genres a, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // Episodes: Look for div.ep a (each episode link)
        val episodes = document.select("div.ep a[href]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim()
            val epNum = epText.toIntOrNull() ?: Regex("(\\d+)").find(href.trimEnd('/').substringAfterLast("/"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) {
                this.name = "Episode $epText"
                this.episode = epNum
            }
        }.sortedBy { it.episode }

        // Extract MAL/AniList IDs: Possibly in meta tags? If not available, skip
        val malId = document.selectFirst("meta[property='og:video:series']")?.attr("content")?.toIntOrNull()
        val aniId = null // no data

        // Build response
        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = genres.distinct()
            addMalId(malId)
            addAniListId(aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Method 1: og:video meta (direct embed URL)
        val ogVideo = document.selectFirst("meta[property='og:video:url']")?.attr("content")
            ?.ifBlank { null }

        // Method 2: iframe with data-src after JS
        val iframe = document.selectFirst("iframe#iframedc")?.attr("data-src")
            ?.ifBlank { null }

        // Method 3: any iframe src that looks like player
        val playerIframe = document.select("iframe[src*='player']").firstOrNull()?.attr("src")

        val embedUrls = listOfNotNull(ogVideo, iframe, playerIframe).distinct()

        if (embedUrls.isEmpty()) {
            // Try to extract xenHash and let JS do the work? Not possible server side. Give up.
            return false
        }

        for (url in embedUrls) {
            val fullUrl = if (url.startsWith("//")) "https:$url" else url
            // Pass to extractor
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        // Also check for direct video links in the page (rare)
        // Could also check for a[data-video] attributes (some themes use)
        document.select("a[data-video]").forEach { a ->
            val videoUrl = a.attr("data-video").ifBlank { null } ?: return@forEach
            loadExtractor(videoUrl, data, subtitleCallback, callback)
        }

        return true
    }

    // Helper: convert episode URL to anime page URL
    private fun episodeToAnimeUrl(url: String): String {
        // Example: /nonton-yuusha-party-wo-oidasareta-kiyoubinbou-episode-12/
        // => /anime/yuusha-party-wo-oidasareta-kiyoubinbou/
        val path = url.trimEnd('/').substringAfterLast("/")
        // Remove -episode-<number> and everything after
        val slug = try {
            Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(path, "")
        } catch (e: PatternSyntaxException) {
            // Fallback: split
            val idx = path.indexOf("-episode-", ignoreCase = true)
            if (idx != -1) path.substring(0, idx) else path
        }
        return "$mainUrl/anime/$slug/"
    }
}
