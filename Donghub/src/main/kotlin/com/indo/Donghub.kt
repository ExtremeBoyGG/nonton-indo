package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

class Donghub : MainAPI() {
    override var mainUrl = "https://donghive.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release",
    )

    private fun parseItems(doc: Document, selector: String): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val poster = item.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = item.selectFirst(".eggtype")?.text()?.trim()
                ?: item.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val title = item.selectFirst(".eggtitle")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt")?.ownText()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val epText = item.selectFirst(".eggepisode")?.text()?.trim()
                ?: item.selectFirst(".bt .epx")?.text()?.trim()
            val epNum = Regex("""(\d+)""").find(epText ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Popular Today") {
            val doc = app.get(mainUrl, headers = defaultHeaders).document
            val items = parseItems(doc, ".listupd.popularslider article.bs")
            return newHomePageResponse(request.name, items)
        }

        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val doc = app.get(url, headers = defaultHeaders).document
        val items = parseItems(doc, ".listupd.normal article.bs")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        return parseItems(doc, "div.listupd article.bs")
    }

    private fun episodeToSeriesUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val seriesSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        if (seriesSlug == slug || seriesSlug.isBlank()) return null
        return "$mainUrl/$seriesSlug/"
    }

    private suspend fun resolveSeriesUrl(url: String): String {
        if (!url.contains("-episode-", ignoreCase = true)) return url
        try {
            val doc = app.get(url, headers = defaultHeaders).document
            val breadcrumbLink = doc.select(".ts-breadcrumb a[itemprop=item], .breadcrumb a")
                .map { it.attr("href") }
                .firstOrNull { it.isNotBlank() && !it.equals("$mainUrl/", ignoreCase = true) && !it.equals(url, ignoreCase = true) }
            if (!breadcrumbLink.isNullOrBlank()) return breadcrumbLink
        } catch (_: Exception) {}
        return episodeToSeriesUrl(url) ?: url
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = resolveSeriesUrl(url)
        val doc = app.get(animeUrl, headers = defaultHeaders).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".single-info .thumb img")?.attr("src")
            ?: doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".desc p, .entry-content p").text().trim().ifBlank { null }
        val tags = doc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }

        val slug = animeUrl.trimEnd('/').substringAfterLast("/")
        val catRaw = app.get("$mainUrl/wp-json/wp/v2/categories?slug=$slug&per_page=1&_fields=id", headers = defaultHeaders).text
        val catList = tryParseJson<List<Map<String, Any?>>>(catRaw)
        val categoryId = catList?.firstOrNull()?.get("id")?.toString()

        val episodes = mutableListOf<Episode>()
        if (categoryId != null) {
            var apiPage = 1
            while (true) {
                val postRaw = app.get(
                    "$mainUrl/wp-json/wp/v2/posts?categories=$categoryId&per_page=100&page=$apiPage&_fields=id,title,link",
                    headers = defaultHeaders
                ).text
                val posts = tryParseJson<List<Map<String, Any?>>>(postRaw) ?: break
                if (posts.isEmpty()) break
                posts.forEach { post ->
                    val epHref = post["link"]?.toString() ?: return@forEach
                    val titleObj = post["title"] as? Map<*, *>
                    val epTitle = titleObj?.get("rendered")?.toString()?.ifBlank { null } ?: return@forEach
                    val epNum = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episodes.add(newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
                if (posts.size < 100) break
                apiPage++
            }
        }

        if (episodes.isEmpty()) {
            doc.select(".eplister ul li a").forEach { a ->
                val epHref = a.attr("href").ifBlank { return@forEach }
                val epTitle = a.selectFirst(".epl-title")?.text()?.trim() ?: a.text().trim()
                val epNum = a.selectFirst(".epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }
        }

        if (episodes.isEmpty() && url.contains("-episode-", ignoreCase = true)) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.episode = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                this.posterUrl = poster
            })
        }

        episodes.sortBy { it.episode }

        val seasonDataList = mutableListOf<SeasonData>()
        if (episodes.size > 10) {
            val chunks = episodes.chunked(10)
            chunks.forEachIndexed { index, chunk ->
                val seasonNum = index + 1
                val firstEp = chunk.first().episode ?: (index * 10 + 1)
                val lastEp = chunk.last().episode ?: ((index + 1) * 10)
                val sName = "Episode $firstEp - $lastEp"
                seasonDataList.add(SeasonData(seasonNum, sName))
                chunk.forEach { ep ->
                    ep.season = seasonNum
                }
            }
        }

        if (episodes.isNotEmpty()) {
            return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
                this.engName = title
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
                if (seasonDataList.isNotEmpty()) {
                    this.seasonNames = seasonDataList
                }
                addEpisodes(DubStatus.Subbed, episodes)
                this.plot = synopsis
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, animeUrl, TvType.Anime, animeUrl) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
            this.plot = synopsis
            this.tags = tags
        }
    }

    private suspend fun extractDailymotion(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
            val metaText = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "https://geo.dailymotion.com/"
                )
            ).text

            val m3u8Url = Regex(""""type":\s*"application/x-mpegURL",\s*"url":\s*"([^"]+)"""").find(metaText)?.groupValues?.getOrNull(1)
                ?: Regex(""""url":\s*"([^"]+\.m3u8[^"]*)"""").find(metaText)?.groupValues?.getOrNull(1)
                ?: return

            val links = M3u8Helper.generateM3u8(
                source = "Dailymotion",
                streamUrl = m3u8Url,
                referer = "https://www.dailymotion.com/",
                headers = mapOf("Referer" to "https://www.dailymotion.com/")
            )
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback(
                    newExtractorLink("Dailymotion", "Dailymotion", m3u8Url) {
                        this.referer = "https://www.dailymotion.com/"
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
        } catch (_: Exception) { }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document
        val seenDm = mutableSetOf<String>()

        suspend fun handleUrl(url: String) {
            val dmId = Regex("""(?:dailymotion\.com/(?:video/|player/[^?]+\.html\?video=)|dai\.ly/|geo\.dailymotion\.com/player/[^?]+\.html\?video=)([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            if (dmId != null) {
                if (seenDm.add(dmId)) {
                    extractDailymotion(dmId, callback)
                }
                return
            }

            loadExtractor(url, data, subtitleCallback, callback)
        }

        doc.select("#pembed iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            handleUrl(src)
        }

        doc.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").ifBlank { return@forEach }
            if (encoded.length < 10) return@forEach
            try {
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                val iframeSrc = Regex("""iframe\s+[^>]*src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)
                if (iframeSrc != null) {
                    handleUrl(iframeSrc)
                }
            } catch (_: Exception) { }
        }

        return true
    }
}
