package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // Keys are the sectionId of each HomeGrid island on the homepage.
    override val mainPage = mainPageOf(
        "latest-update-section" to "Update Terbaru",
        "series-section" to "Series",
        "movie-section" to "Movie",
    )

    /**
     * Astro serializes island props as `[type, value]` pairs:
     * type 0 is a plain value (objects nest further pairs), type 1 is an array of pairs.
     */
    private fun decodeAstro(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { decodeAstro(v.opt(it)) }
        is JSONArray -> {
            val type = v.opt(0)
            if (v.length() == 2 && type is Int) {
                val payload = v.opt(1)
                when (type) {
                    0 -> decodeAstro(payload)
                    1 -> (payload as? JSONArray)?.let { arr -> (0 until arr.length()).map { decodeAstro(arr.opt(it)) } }
                    else -> payload
                }
            } else {
                (0 until v.length()).map { decodeAstro(v.opt(it)) }
            }
        }
        JSONObject.NULL -> null
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private fun islands(doc: Document, component: String): List<Map<String, Any?>> =
        doc.select("astro-island[component-url*=$component]").mapNotNull { el ->
            try {
                decodeAstro(JSONObject(el.attr("props"))) as? Map<String, Any?>
            } catch (_: Exception) {
                null
            }
        }

    private fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()?.ifBlank { null }
    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt() ?: str(key)?.toIntOrNull()

    private fun toSearch(item: Map<String, Any?>): SearchResponse? {
        val id = item.str("id") ?: return null
        val title = item.str("title") ?: return null
        val isMovie = item.str("type").equals("Movie", ignoreCase = true)
        return newAnimeSearchResponse(title, "$mainUrl/synopsis/$id", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = item.str("poster_url")
            item.int("latest_ep")?.let { if (!isMovie) addSub(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The homepage renders every section at once; there is no server-side paging.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get("$mainUrl/", headers = headers).document
        @Suppress("UNCHECKED_CAST")
        val items = islands(doc, "HomeGrid")
            .firstOrNull { it.str("sectionId") == request.data }
            ?.let { it["items"] as? List<Map<String, Any?>> }
            .orEmpty()
            .mapNotNull { toSearch(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = app.get(
            "$mainUrl/ipa/search?q=${URLEncoder.encode(query, "UTF-8")}",
            headers = headers + mapOf("Accept" to "application/json")
        ).text
        val arr = try { JSONArray(text) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { null } ?: return@mapNotNull null
            val title = o.optString("title").ifBlank { null } ?: return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl/synopsis/$id", TvType.Anime) {
                this.posterUrl = o.optString("poster_url").ifBlank { null }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""/(?:synopsis|episode)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("ID tidak ditemukan")
        val synopsisUrl = "$mainUrl/synopsis/$id"
        val doc = app.get(synopsisUrl, headers = headers).document

        @Suppress("UNCHECKED_CAST")
        val info = islands(doc, "SynopsisActions").firstOrNull()?.get("donghua") as? Map<String, Any?>
        val title = info?.str("title")
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Judul tidak ditemukan")
        val poster = info?.str("poster_url") ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("#synopsis-full")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("#synopsis-short")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val isMovie = info?.str("type").equals("Movie", ignoreCase = true)
        val status = when (info?.str("status")?.lowercase()) {
            "completed" -> ShowStatus.Completed
            "on-going", "ongoing" -> ShowStatus.Ongoing
            else -> null
        }

        // The HTML only links the latest 20 episodes; the EpisodeGrid props carry the full list.
        @Suppress("UNCHECKED_CAST")
        val epNumbers = (islands(doc, "EpisodeGrid").firstOrNull()?.get("episodes") as? List<Map<String, Any?>>)
            ?.mapNotNull { it.int("ep_number") }
            ?.takeIf { it.isNotEmpty() }
            ?: doc.select("a[href^=/episode/$id/]").mapNotNull {
                it.attr("href").substringAfterLast("/").toIntOrNull()
            }
        val episodes = epNumbers.distinct().sorted().map { n ->
            newEpisode("$mainUrl/episode/$id/$n") {
                this.name = "Episode $n"
                this.episode = n
            }
        }

        if (isMovie && episodes.size <= 1) {
            return newMovieLoadResponse(title, synopsisUrl, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: "$mainUrl/episode/$id/1") {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newAnimeLoadResponse(title, synopsisUrl, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        @Suppress("UNCHECKED_CAST")
        val servers = (islands(doc, "WatchClient").firstOrNull()?.get("servers") as? List<Map<String, Any?>>).orEmpty()
            .mapNotNull { s -> s.str("embed_url")?.let { (s.str("name")?.trim() ?: "") to it } }
            .ifEmpty { doc.select("iframe[src]").map { "" to it.attr("src") } }

        var found = false
        servers.distinctBy { it.second }.forEach { (label, url) ->
            if (YunshanExtractors.resolve(url, label, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        return found
    }
}
