package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.cafe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release",
    )

    private fun parseItems(doc: Document, selector: String): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = a.attr("href")
            val poster = item.selectFirst(".limit img")?.attr("src")?.ifBlank { null }
            val type = item.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val title = item.selectFirst(".tt")?.ownText()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val epText = item.selectFirst(".bt .epx")?.text()?.trim()
            val epNum = Regex("""(\d+)""").find(epText ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Popular Today") {
            val doc = app.get(mainUrl).document
            val items = parseItems(doc, ".releases.hothome + .listupd.normal article.bs")
            return newHomePageResponse(request.name, items)
        }

        val doc = app.get(if (page > 1) "$mainUrl/page/$page/" else mainUrl).document
        val items = parseItems(doc, ".releases.latesthome + .listupd.normal article.bs")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return parseItems(doc, "div.listupd article.bs")
    }

    private fun episodeToSeriesUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val seriesSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        if (seriesSlug == slug || seriesSlug.isBlank()) return null
        return "$mainUrl/seri/$seriesSlug/"
    }

    override suspend fun load(url: String): LoadResponse {
        val isEpisode = url.contains("-episode-", ignoreCase = true)
        val seriesUrl = if (isEpisode) episodeToSeriesUrl(url) else null

        val animeUrl = seriesUrl ?: url

        if (isEpisode && seriesUrl != null) {
            val episodeDoc = app.get(url).document
            val title = episodeDoc.selectFirst("h1.entry-title")?.text()?.trim()
                ?: throw ErrorLoadingException("Title not found")
            val poster = episodeDoc.selectFirst(".thumb img")?.attr("src")
                ?: episodeDoc.selectFirst("meta[property=og:image]")?.attr("content")
            val synopsis = episodeDoc.select(".desc p, .entry-content p").text().trim().ifBlank { null }
            val tags = episodeDoc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }

            val slug = animeUrl.trimEnd('/').substringAfterLast("/")
            val catRaw = app.get("$mainUrl/wp-json/wp/v2/categories?slug=$slug&per_page=1&_fields=id").text
            val catList = tryParseJson<List<Map<String, Any?>>>(catRaw)
            val categoryId = catList?.firstOrNull()?.get("id")?.toString()

            val episodes = mutableListOf<Episode>()
            if (categoryId != null) {
                var apiPage = 1
                while (true) {
                    val postRaw = app.get("$mainUrl/wp-json/wp/v2/posts?categories=$categoryId&per_page=100&page=$apiPage&_fields=id,title,link").text
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

            return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
                engName = title
                posterUrl = poster
                addEpisodes(DubStatus.Subbed, episodes)
                plot = synopsis
                this.tags = tags
            }
        }

        // Series detail page
        val doc = app.get(animeUrl).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".thumb img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val synopsis = doc.select(".desc p, .entry-content p").text().trim().ifBlank { null }
        val tags = doc.select(".genxed a").mapNotNull { it.text().trim().ifBlank { null } }

        val slug = animeUrl.trimEnd('/').substringAfterLast("/")
        val catRaw = app.get("$mainUrl/wp-json/wp/v2/categories?slug=$slug&per_page=1&_fields=id").text
        val catList = tryParseJson<List<Map<String, Any?>>>(catRaw)
        val categoryId = catList?.firstOrNull()?.get("id")?.toString()

        val episodes = mutableListOf<Episode>()
        if (categoryId != null) {
            var apiPage = 1
            while (true) {
                val postRaw = app.get("$mainUrl/wp-json/wp/v2/posts?categories=$categoryId&per_page=100&page=$apiPage&_fields=id,title,link").text
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

        episodes.sortBy { it.episode }

        if (episodes.isNotEmpty()) {
            return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
                engName = title
                posterUrl = poster
                addEpisodes(DubStatus.Subbed, episodes)
                plot = synopsis
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, animeUrl, TvType.Anime, animeUrl) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("#embed_holder iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { return@forEach }
            loadExtractor(src, data, subtitleCallback, callback)
        }

        doc.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").ifBlank { return@forEach }
            if (encoded.length < 10) return@forEach
            try {
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                val iframeSrc = Regex("""iframe\s+[^>]*src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)
                if (iframeSrc != null) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        return true
    }
}
