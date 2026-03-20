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

    // Homepage with three sections: New Episodes, Top Episodes Of The Week, New Anime Series
    override val mainPage = mainPageOf(
        "$mainUrl/" to "New Episodes",
        "$mainUrl/" to "Top Episodes Of The Week",
        "$mainUrl/" to "New Anime Series"
    )

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

    private fun parseArticleList(container: org.jsoup.nodes.Element): List<Triple<String, String, String?, Int?>> {
        return container.select("article.bsu").mapNotNull { article ->
            val link = article.selectFirst("div.bsux a[href]") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { null } ?: return@mapNotNull null
            val animeUrl = episodeToAnimeUrl(href)
            val title = article.selectFirst("div.bsux h2")?.text()?.trim()
                    ?: article.selectFirst("h2")?.text()?.trim()
                    ?: return@mapNotNull null
            val cleanTitle = title.replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { title }
            val poster = article.selectFirst("img[itemprop='image']")?.attr("src")
                    ?: article.selectFirst("img")?.attr("src")
                    ?: null
            val epNumText = article.selectFirst("div.ep")?.text()?.trim()
            val epNum = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            Triple(cleanTitle, animeUrl, poster, epNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) {
            // Only page 1 has the three sections
            return newHomePageResponse(request.name, emptyList())
        }
        val document = app.get(mainUrl).document
        val listUps = document.select("div.listupd")
        // Determine which listupd index based on section name
        val index = when (request.name) {
            "New Episodes" -> 0
            "Top Episodes Of The Week" -> 1
            "New Anime Series" -> 2
            else -> 0 // fallback to first
        }
        val container = if (listUps.size() > index) listUps.get(index) else null
        val items = if (container != null) {
            parseArticleList(container).mapNotNull { (title, animeUrl, poster, epNum) ->
                val animeUrlFix = episodeToAnimeUrl(animeUrl)
                newAnimeSearchResponse(title, animeUrlFix, TvType.Anime) {
                    this.posterUrl = poster
                    epNum?.let { addSub(it) }
                }
            }
        } else {
            emptyList()
        }
        return newHomePageResponse(request.name, items)
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
            val poster = article.selectFirst("img[itemprop='image']")?.attr("src")
                    ?: article.selectFirst("img")?.attr("src")
                    ?: null
            val epNumText = article.selectFirst("div.ep")?.text()?.trim()
            val epNum = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            val animeUrl = episodeToAnimeUrl(href)
            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                epNum?.let { addSub(it) }
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

        // Episodes: Look for div.ep a[href] (each episode link)
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

        // ---- Streaming links ----
        // Method 1: og:video meta (direct embed URL)
        val ogVideo = document.selectFirst("meta[property='og:video:url']")?.attr("content")
            ?.ifBlank { null }

        // Method 2: iframe with data-src after JS
        val iframe = document.selectFirst("iframe#iframedc")?.attr("data-src")
            ?.ifBlank { null }

        // Method 3: any iframe src that looks like player
        val playerIframe = document.select("iframe[src*='player']").firstOrNull()?.attr("src")

        val embedUrls = listOfNotNull(ogVideo, iframe, playerIframe).distinct()

        for (url in embedUrls) {
            val fullUrl = if (url.startsWith("//")) "https:$url" else url
            // Pass to extractor
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        // ---- Download links (krakenfiles, pixeldrain) ----
        // Krakenfiles: extract video URL directly from the page
        document.select("a[href]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            when {
                href.contains("krakenfiles.com") -> {
                    try {
                        val kfDoc = app.get(href).document
                        val videoUrl = kfDoc.selectFirst("source[src*=krakencloud], source[type=video/mp4]")
                            ?.attr("src")?.ifBlank { null }
                        if (videoUrl != null) {
                            callback(newExtractorLink("KrakenFiles", "Download", videoUrl) {
                                this.quality = Qualities.Unknown.value // Krakenfiles usually HD
                                this.referer = href
                            })
                        }
                    } catch (_: Exception) { }
                }
                // PixelDrain: use direct API download URL
                href.contains("pixeldrain.com") -> {
                    val pdId = Regex("pixeldrain\\.com/u/(\\w+)").find(href)?.groupValues?.getOrNull(1)
                    if (pdId != null) {
                        callback(newExtractorLink("PixelDrain", "Download", "https://pixeldrain.com/api/file/$pdId") {
                            this.quality = Qualities.Unknown.value
                            this.referer = href
                        })
                    }
                }
            }
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
