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

    // Homepage with paginated new episodes + sidebar sections
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes"
    )

    private fun episodeToAnimeUrl(url: String): String {
        // Example: /nonton-yuusha-party-wo-oidasareta-kiyoubinbou-episode-12/
        // => /anime/yuusha-party-wo-oidasareta-kiyoubinbou/
        val path = url.trimEnd('/').substringAfterLast("/")
        // Remove "nonton-" prefix
        val withoutPrefix = if (path.startsWith("nonton-")) path.removePrefix("nonton-") else path
        // Remove -episode-<number> and everything after
        val slug = try {
            Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(withoutPrefix, "")
        } catch (e: PatternSyntaxException) {
            val idx = withoutPrefix.indexOf("-episode-", ignoreCase = true)
            if (idx != -1) withoutPrefix.substring(0, idx) else withoutPrefix
        }
        return "$mainUrl/anime/$slug/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        // Items are div.bs inside div.listupd
        val items = document.select("div.listupd div.bs").mapNotNull { item ->
            val a = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Title from div.tt h2 or just h2 inside the item
            val title = item.selectFirst("div.tt h2")?.text()?.trim()
                ?: item.selectFirst("h2")?.text()?.trim()
                ?: return@mapNotNull null

            // Clean title: remove "Episode X Subtitle Indonesia" suffix
            val cleanTitle = title
                .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title }

            // Poster from img inside div.limit
            val poster = item.selectFirst("div.limit img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            } ?: item.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            // Sub/Dub info from span.sb or div.typez
            val subInfo = item.selectFirst("span.sb, div.typez")?.text()?.trim()
            val isSub = subInfo?.contains("Sub", ignoreCase = true) != false

            // Episode number from span.ep or div.ep
            val epNumText = item.selectFirst("span.ep, div.ep")?.text()?.trim()
            val epNum = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            // Convert episode URL to anime URL
            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(cleanTitle, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                epNum?.let { addSub(it) }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd div.bs").mapNotNull { item ->
            val a = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = item.selectFirst("div.tt h2")?.text()?.trim()
                ?: item.selectFirst("h2")?.text()?.trim()
                ?: return@mapNotNull null

            val cleanTitle = title
                .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title }

            val poster = item.selectFirst("div.limit img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            } ?: item.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            }

            val epNumText = item.selectFirst("span.ep, div.ep")?.text()?.trim()
            val epNum = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(cleanTitle, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                epNum?.let { addSub(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // If URL is episode, convert to anime page
        val animeUrl = if (url.contains("/anime/")) {
            url
        } else {
            episodeToAnimeUrl(url)
        }

        val document = app.get(animeUrl).document

        // Title: h1.entry-title
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val cleanTitle = title
            .replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            .trim()

        // Poster: thumb or detail image
        val poster = document.selectFirst("div.thumb img, div.ime img")?.let { img ->
            img.attr("data-src").ifBlank { null }
                ?: img.attr("src").ifBlank { null }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        // Description from div.entry-content or synopis area
        val description = document.selectFirst("div.entry-content p, div.synp p, div.desc p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        // Genres
        val genres = document.select("div.genxed a, a[href*=/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // Episodes: listed as li with a[href] inside the episode list area
        // The episode section has links like /nonton-{slug}-episode-{N}/
        val episodes = document.select("div.eplister ul li a[href], div.episodelist ul li a[href]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("nonton-") && !href.contains("episode")) return@mapNotNull null
            val epText = a.selectFirst("div.epl-num")?.text()?.trim()
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null
            val epNum = Regex("(\\d+)").find(
                epText.replace(Regex("Episode\\s*", RegexOption.IGNORE_CASE), "")
            )?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) {
                this.name = "Episode ${epNum ?: epText}"
                this.episode = epNum
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(cleanTitle, animeUrl, TvType.Anime) {
            engName = cleanTitle
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = genres.distinct()
            addEpisodes(DubStatus.Subbed, episodes)
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
        val playerIframe = document.select("iframe[src]").firstOrNull()?.attr("src")?.ifBlank { null }

        val embedUrls = listOfNotNull(ogVideo, iframe, playerIframe).distinct()

        for (url in embedUrls) {
            val fullUrl = if (url.startsWith("//")) "https:$url" else url
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        // ---- Download links (krakenfiles, pixeldrain) ----
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
                                this.quality = Qualities.Unknown.value
                                this.referer = href
                            })
                        }
                    } catch (_: Exception) { }
                }
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

}
