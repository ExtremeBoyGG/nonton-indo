package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class Rebahin : MainAPI() {
    override var mainUrl = "https://139.59.197.199"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film & Series Terbaru"
    )

    private fun Element.href() = attr("href").ifBlank { null }
    private fun Element.src() = attr("src").ifBlank { null }

    private fun parseCard(a: Element): SearchResponse? {
        val href = a.href() ?: return null
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").ifBlank { null } ?: return null
        var poster = img.src()
        if (poster != null && poster.startsWith("/_next/image")) {
            poster = Regex("url=([^&]+)").find(poster)?.groupValues?.getOrNull(1)?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
        }
        val isSeries = href.startsWith("/tv/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get("$mainUrl/").document
        val home = doc.select("a[href^=/movies/], a[href^=/tv/]")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/api/search?q=$query")
        val text = resp.text ?: return emptyList()
        val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { return emptyList() }
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id", "")
            val title = item.optString("title", "")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val type = item.optString("type", "movie")
            val href = if (type == "tv") "/tv/$id" else "/movies/$id"
            if (type == "tv") {
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url)
        val doc = resp.document
        val raw = resp.text ?: ""
        val html = raw.replace("\\\"", "\"")

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val year = Regex("(20\\d{2})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = Regex("\"genres\":\\[([^\\]]+)\\]").find(html)?.let { m ->
            Regex("\"name\":\"([^\"]+)\"").findAll(m.value).map { it.groupValues[1] }.toList()
        } ?: doc.select("a[href*=genre], a[href*=category]").map { it.text() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/tv/")
        return if (isSeries) {
            val episodeUrls = mutableListOf<Episode>()
            val episodesMatch = Regex("\"episodes\":\\[([^\\]]+)\\]").find(html)
            if (episodesMatch != null) {
                val epsJson = episodesMatch.groupValues[1]
                Regex("\"episodeNumber\":(\\d+),\"seasonNumber\":(\\d+)").findAll(epsJson).forEach { ep ->
                    val epNum = ep.groupValues[1].toIntOrNull()
                    val seasonNum = ep.groupValues[2].toIntOrNull()
                    if (epNum != null) {
                        val slug = url.trimEnd('/').substringAfterLast("/")
                        val epUrl = if (seasonNum != null) "$url/season-$seasonNum/episode-$epNum"
                        else "$url/season-1/episode-$epNum"
                        episodeUrls.add(newEpisode(epUrl) {
                            this.name = "Eps $epNum"
                            this.episode = epNum
                            this.season = seasonNum ?: 1
                        })
                    }
                }
            }
            if (episodeUrls.isEmpty()) {
                doc.select("a[href*=/episode-]").forEach { a ->
                    val href = a.attr("href").ifBlank { return@forEach }
                    val epNum = Regex("episode-(\\d+)$").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (epNum != null) {
                        episodeUrls.add(newEpisode(fixUrl(href)) {
                            this.name = "Eps $epNum"
                            this.episode = epNum
                        })
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(data)
        val raw = resp.text ?: return true

        // JSON inside Next.js pages has escaped quotes (\")
        val html = raw.replace("\\\"", "\"")

        // Extract sources from "sources":[{...}] or "playerSources":[{...}]
        var pos = 0
        while (true) {
            val srcIdx = html.indexOf("\"sources\":[", pos)
            val playIdx = html.indexOf("\"playerSources\":[", pos)
            val idx = when {
                srcIdx >= 0 && playIdx >= 0 -> minOf(srcIdx, playIdx)
                srcIdx >= 0 -> srcIdx
                playIdx >= 0 -> playIdx
                else -> break
            }
            pos = idx + 1
            val arrayStart = html.indexOf('[', idx) + 1
            var depth = 1
            var end = arrayStart
            while (depth > 0 && end < html.length) {
                when (html[end]) {
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                end++
            }
            val arrayContent = html.substring(arrayStart, end - 1)
            var objPos = 0
            while (true) {
                val objStart = arrayContent.indexOf('{', objPos)
                if (objStart < 0) break
                val objEnd = findMatchingBraceObj(arrayContent, objStart)
                if (objEnd < 0) break
                val obj = arrayContent.substring(objStart, objEnd + 1)
                val videoUrl = Regex("\"playbackUrl\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1)
                val quality = Regex("\"quality\":\"([^\"]+)\"").find(obj)?.groupValues?.getOrNull(1) ?: "FHD"
                if (videoUrl != null) {
                    callback(newExtractorLink("Rebahin", "Rebahin - $quality", videoUrl) {
                        this.quality = parseQuality(quality)
                    })
                }
                objPos = objEnd + 1
            }
        }
        return true
    }

    private fun findMatchingBraceObj(s: String, start: Int): Int {
        var depth = 1
        var i = start + 1
        while (depth > 0 && i < s.length) {
            if (s[i] == '"') {
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\') i++
                    i++
                }
            } else if (s[i] == '{') depth++
            else if (s[i] == '}') depth--
            i++
        }
        return if (depth == 0) i - 1 else -1
    }

    private fun parseQuality(q: String): Int {
        return when {
            q.contains("4K", true) || q.contains("2160", true) -> 4
            q.contains("1080", true) -> 3
            q.contains("720", true) -> 2
            q.contains("480", true) || q.contains("360", true) -> 1
            else -> 3
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}
