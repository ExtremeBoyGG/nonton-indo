package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString

class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiBase = "https://cached.freeanimehentai.net/api/v8"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to "$mainUrl/"
    )

    private val playerHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer" to "https://player.hanime.tv/",
        "Origin" to "https://player.hanime.tv/"
    )

    override val mainPage = mainPageOf(
        "$apiBase/landing" to "Recent Uploads"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = app.get("https://cached.freeanimehentai.net/api/v10/search_hvs", headers = headers).text ?: return newHomePageResponse(emptyList())
        val videos = tryParseJson<List<Map<String, Any?>>>(json) ?: return newHomePageResponse(emptyList())

        val allItems = videos.reversed().mapNotNull { video ->
            val name = video["name"]?.toString()?.ifBlank { null } ?: return@mapNotNull null
            val slug = video["slug"]?.toString()?.ifBlank { null } ?: return@mapNotNull null
            newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }

        val pageSize = 20
        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, allItems.size)
        val items = if (start < allItems.size) allItems.subList(start, end) else emptyList<SearchResponse>()

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return listOf()

        val doc = app.get("$mainUrl/browse?search=$query", headers = headers).document
        return doc.select("div.hvc.item.card a[href^=/videos/hentai/]").mapNotNull { a ->
            val slug = a.attr("href").removePrefix("/videos/hentai/").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { return@mapNotNull null }
            newMovieSearchResponse(title, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")

        val json = app.get("$apiBase/video?id=$slug", headers = headers).text
            ?: throw ErrorLoadingException("No response")

        val root = tryParseJson<Map<String, Any?>>(json)
            ?: throw ErrorLoadingException("Invalid response")

        val video = root["hentai_video"] as? Map<*, *>
            ?: throw ErrorLoadingException("Missing video")

        val title = video["name"]?.toString() ?: throw ErrorLoadingException("Title not found")
        val plot = (video["description"]?.toString() ?: "").replace(Regex("<[^>]*>"), "").ifBlank { null }
        val poster = video["poster_url"]?.toString() ?: video["cover_url"]?.toString()
        val tags = (video["hentai_tags"] as? List<Map<*, *>>)?.mapNotNull { it["text"]?.toString() }.orEmpty()
        val year = video["released_at"]?.toString()?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/")

        val detailJson = app.get("$apiBase/video?id=$slug", headers = headers).text ?: return false
        val detailRoot = tryParseJson<Map<String, Any?>>(detailJson) ?: return false
        val video = detailRoot["hentai_video"] as? Map<*, *> ?: return false
        val hvId = video["id"]?.toString() ?: return false

        app.post("$apiBase/hentai_videos/$slug/play", headers = headers, json = JsonAsString("{}"))

        val manifestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to "$mainUrl",
            "Accept" to "application/json",
            "x-signature-version" to "web2",
            "x-signature" to "",
            "x-time" to "0",
            "x-session-token" to "",
            "x-csrf-token" to ""
        )

        val manifestJson = try {
            app.get("$apiBase/guest/videos/$hvId/manifest", headers = manifestHeaders).text
        } catch (_: Exception) { null }

        val hlsUrl = if (!manifestJson.isNullOrBlank()) {
            val manifestRoot = tryParseJson<Map<String, Any?>>(manifestJson)
            if (manifestRoot != null) {
                manifestRoot["url"]?.toString()
                    ?: (manifestRoot["videos_manifest"] as? Map<*, *>)
                        ?.let { m -> (m["servers"] as? List<Map<*, *>>)?.firstOrNull()
                            ?.let { s -> (s["streams"] as? List<Map<*, *>>)?.firstOrNull()
                                ?.let { st -> st["url"]?.toString() } } }
            } else null
        } else {
            (detailRoot["videos_manifest"] as? Map<*, *>)
                ?.let { m -> (m["servers"] as? List<Map<*, *>>)?.firstOrNull()
                    ?.let { s -> (s["streams"] as? List<Map<*, *>>)?.firstOrNull()
                        ?.let { st -> st["url"]?.toString() } } }
        } ?: return false

        if (hlsUrl.isBlank()) return false

        val links = M3u8Helper.generateM3u8(
            source = name,
            streamUrl = hlsUrl,
            referer = "https://player.hanime.tv/",
            headers = playerHeaders
        )
        if (links.isEmpty()) {
            callback(newExtractorLink(name, "$name - HLS", hlsUrl) {
                this.referer = "https://player.hanime.tv/"
            })
        } else {
            links.forEach { callback(it) }
        }

        return true
    }
}
