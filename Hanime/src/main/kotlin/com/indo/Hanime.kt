package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to "$mainUrl/"
    )

    private val searchApiUrl = "https://cached.freeanimehentai.net/api/v10/search_hvs"

    override val mainPage = mainPageOf(
        "$mainUrl/browse/trending" to "Trending",
        "$mainUrl/browse/random" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val hvcSelector = "a[href^=/videos/hentai/]"

        if (page > 1) {
            val allVideos = fetchAllVideos()
            val itemsPerPage = 48
            val start = (page - 1) * itemsPerPage
            val pageVideos = allVideos.drop(start).take(itemsPerPage)
            val items = pageVideos.mapNotNull { video ->
                val slug = video.optString("slug", "")
                val name = video.optString("name", "")
                val poster = video.optString("poster_url", "").ifBlank { null }
                if (slug.isBlank() || name.isBlank()) return@mapNotNull null
                newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            return newHomePageResponse(HomePageList(request.name, items))
        }

        val doc = app.get(request.data, headers = ua).document

        val name = doc.selectFirst("title")?.text()?.trim() ?: request.name

        val items = doc.select(hvcSelector).mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { null } ?: return@mapNotNull null
            val slug = href.removePrefix("/videos/hentai/")
            val poster = "https://hanime-cdn.com/images/posters/${slug}-pv1.webp"
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(HomePageList(name, items))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allVideos = fetchAllVideos()
        val lowerQuery = query.lowercase()
        return allVideos.filter { video ->
            val name = video.optString("name", "").lowercase()
            name.contains(lowerQuery)
        }.take(50).mapNotNull { video ->
            val slug = video.optString("slug", "")
            val name = video.optString("name", "")
            val poster = video.optString("poster_url", "").ifBlank { null }
            if (slug.isBlank() || name.isBlank()) return@mapNotNull null
            newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private var cachedVideos: List<JSONObject>? = null

    private suspend fun fetchAllVideos(): List<JSONObject> {
        cachedVideos?.let { return it }
        val resp = app.get(searchApiUrl, headers = ua)
        val text = resp.text ?: return emptyList()
        val arr = try { JSONArray(text) } catch (e: Exception) { return emptyList() }
        val videos = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)
        }
        cachedVideos = videos
        return videos
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val html = doc.outerHtml()

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val resp = app.get(data, headers = ua)
        val html = resp.text ?: return true

        val nuxtMatch = Regex("window\\.__NUXT__=.*?</script>", RegexOption.DOT_MATCHES_ALL).find(html)
        val nuxtCode = nuxtMatch?.value ?: return true

        val cleaned = nuxtCode.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\u003D", "=")
        val hlsUrls = Regex("\"url\":\"(https?://[^\"]+\\.m3u8[^\"]*)\"").findAll(cleaned)
        hlsUrls.forEach { match ->
            val videoUrl = match.groupValues.getOrNull(1) ?: return@forEach
            callback(newExtractorLink(name, "$name - HLS", videoUrl) {
                this.referer = "$mainUrl/"
                this.quality = 3
            })
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}
