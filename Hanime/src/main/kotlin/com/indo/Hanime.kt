package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

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

    override val mainPage = mainPageOf(
        "$apiBase/landing" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val json = app.get(request.data, headers = headers).text ?: return newHomePageResponse(emptyList())
        val root = tryParseJson<Map<String, Any?>>(json) ?: return newHomePageResponse(emptyList())

        val sections = root["sections"] as? List<Map<String, Any?>> ?: return newHomePageResponse(emptyList())
        val videosMap = (root["hentai_videos"] as? List<Map<String, Any?>>)
            ?.associateBy { it["id"] } ?: emptyMap()

        return newHomePageResponse(sections.mapNotNull { section ->
            val title = section["title"]?.toString() ?: return@mapNotNull null
            val ids = section["hentai_video_ids"] as? List<*> ?: return@mapNotNull null
            HomePageList(title, ids.mapNotNull { id -> toSearchResponse(videosMap[id]) })
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return listOf()

        val doc = app.get("$mainUrl/browse?search=$query", headers = headers).document
        return doc.select("div.hvc.item.card a[href^=/videos/hentai/]").mapNotNull { a ->
            val slug = a.attr("href").removePrefix("/videos/hentai/").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { return@mapNotNull null }
            newMovieSearchResponse(title, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
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

        val json = app.get("$apiBase/video?id=$slug", headers = headers).text ?: return false
        val root = tryParseJson<Map<String, Any?>>(json) ?: return false

        val manifest = root["videos_manifest"] as? Map<*, *> ?: return false
        val servers = manifest["servers"] as? List<Map<*, *>> ?: return false

        servers.forEach { server ->
            val streams = server["streams"] as? List<Map<*, *>> ?: return@forEach
            streams.forEach { stream ->
                val videoUrl = stream["url"]?.toString() ?: return@forEach
                if (videoUrl.isBlank()) return@forEach
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = "$mainUrl/",
                    headers = headers
                )
                if (links.isEmpty()) {
                    callback(newExtractorLink(name, "$name - HLS", videoUrl) {
                        this.referer = "$mainUrl/"
                    })
                } else {
                    links.forEach { callback(it) }
                }
            }
        }

        return true
    }

    private fun toSearchResponse(video: Map<String, Any?>?): SearchResponse? {
        val v = video ?: return null
        val name = v["name"]?.toString()?.ifBlank { null } ?: return null
        val slug = v["slug"]?.toString()?.ifBlank { null } ?: return null
        return newMovieSearchResponse(name, "$mainUrl/videos/hentai/$slug", TvType.NSFW) {
            this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
        }
    }
}
