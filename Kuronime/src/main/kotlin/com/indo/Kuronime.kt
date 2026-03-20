package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.regex.PatternSyntaxException

class Kuronime : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Homepage with paginated new episodes
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes"
    )

    private fun episodeToAnimeUrl(url: String): String {
        val path = url.trimEnd('/').substringAfterLast("/")
        val withoutPrefix = if (path.startsWith("nonton-")) path.removePrefix("nonton-") else path
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

        // Items in bixbox > listupd > excstf > article.bsu
        val items = document.select("article.bsu").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsux") ?: return@mapNotNull null
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Title from div.bsuxtt > h2 (clean title without episode info)
            val title = bsux.selectFirst("div.bsuxtt h2")?.text()?.trim()
                ?: return@mapNotNull null

            // Poster from img[itemprop=image] inside div.limit
            val poster = a.selectFirst("div.limit img[itemprop=image]")?.attr("src")?.ifBlank { null }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            // Episode number from div.bt > div.ep
            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bsu").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsux") ?: return@mapNotNull null
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = bsux.selectFirst("div.bsuxtt h2")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = a.selectFirst("div.limit img[itemprop=image]")?.attr("src")?.ifBlank { null }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = if (url.contains("/anime/")) url else episodeToAnimeUrl(url)
        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val cleanTitle = title
            .replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.ime img, div.thumb img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        val description = document.selectFirst("div.synp p, div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val genres = document.select("div.genxed a, a[href*=/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        // Episodes: li elements containing a[href] with /nonton-...-episode-N/ URLs
        val episodes = document.select("div.bixbox ul li").mapNotNull { li ->
            val a = li.selectFirst("a[href*=nonton-]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim().ifBlank { null } ?: return@mapNotNull null
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

        val ogVideo = document.selectFirst("meta[property='og:video:url']")?.attr("content")
            ?.ifBlank { null }
        val iframe = document.selectFirst("iframe#iframedc")?.attr("data-src")
            ?.ifBlank { null }
        val playerIframe = document.select("iframe[src]").firstOrNull()?.attr("src")?.ifBlank { null }

        val embedUrls = listOfNotNull(ogVideo, iframe, playerIframe).distinct()

        for (url in embedUrls) {
            val fullUrl = if (url.startsWith("//")) "https:$url" else url
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        // Download links
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
