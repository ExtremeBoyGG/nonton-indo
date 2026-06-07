package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import org.json.JSONObject

class Idlix : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            val apiPage = page - 1
            val sections = listOfNotNull(
                getApiSection("Movies", apiPage, "api/movies"),
                getApiSection("TV Series", apiPage, "api/series"),
            )
            return newHomePageResponse(sections)
        }
        val sections = listOfNotNull(
            getApiSection("Movies", 1, "api/movies"),
            getApiSection("TV Series", 1, "api/series"),
        )
        return newHomePageResponse(sections)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = app.get("$mainUrl/$apiPath?page=$page&limit=24")
        val text = resp.text ?: return null
        val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { return null }
        if (data == null) return null
        val items = (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val isSeries = apiPath.contains("series")
            val href = if (isSeries) "/series/$slug" else "/movie/$slug"
            if (isSeries)
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
        return if (items.isEmpty()) null else HomePageList(name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get("$mainUrl/api/search?q=$query")
        val text = resp.text ?: return emptyList()
        val data = try { JSONObject(text).optJSONArray("results") } catch (e: Exception) { return emptyList() }
        if (data == null) return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug", "")
            val title = item.optString("title", "")
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val posterPath = item.optString("posterPath", "")
            val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val contentType = item.optString("contentType", "movie")
            val voteAvg = item.opt("voteAverage").let { v ->
                when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
            }
            val href = if (contentType == "tv_series") "/series/$slug" else "/movie/$slug"
            if (contentType == "tv_series")
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
            else
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster; this.score = Score.from10(voteAvg)
                }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url)
        val raw = resp.text ?: throw ErrorLoadingException("No response")
        val html = raw.replace("\\\"", "\"")

        val title = Regex("\"og:title\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("Title not found")

        val poster = Regex("\"og:image\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?.ifBlank { null }

        val description = Regex("\"og:description\",\"content\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
            ?.ifBlank { null }

        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\"releaseDate\":\"(\\d{4})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\"firstAirDate\":\"(\\d{4})").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = Regex("\"genres\":\\[([^\\]]+)\\]").find(html)?.let { m ->
            Regex("\"name\":\"([^\"]+)\"").findAll(m.value).map { it.groupValues[1] }.toList()
        } ?: emptyList()

        val voteAvg = Regex("\"voteAverage\":\"?([0-9.]+)\"?").find(html)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        val isSeries = url.contains("/series/")
        return if (isSeries) {
            val slug = url.substringAfter("/series/").substringBefore("/").substringBefore("?")
            val episodeUrls = mutableListOf<Episode>()
            try {
                val seriesResp = app.get("$mainUrl/api/series/$slug")
                val seriesJson = JSONObject(seriesResp.text ?: "")

                val defaultSeason = seriesJson.optJSONObject("defaultSeason")
                if (defaultSeason != null) {
                    val seasonNum = defaultSeason.optInt("seasonNumber", 1)
                    val episodes = defaultSeason.optJSONArray("episodes")
                    if (episodes != null) {
                        for (j in 0 until episodes.length()) {
                            val ep = episodes.optJSONObject(j) ?: continue
                            val epNum = ep.optInt("episodeNumber", 0)
                            if (epNum > 0) {
                                val epName = ep.optString("title", "").ifBlank { "S${seasonNum}E$epNum" }
                                episodeUrls.add(newEpisode(fixUrl("/series/$slug/season/$seasonNum/episode/$epNum")) {
                                    this.name = epName
                                    this.episode = epNum
                                    this.season = seasonNum
                                })
                            }
                        }
                    }
                } else {
                    val seasons = seriesJson.optJSONArray("seasons")
                    if (seasons != null) {
                        for (i in 0 until seasons.length()) {
                            val season = seasons.optJSONObject(i) ?: continue
                            val seasonNum = season.optInt("seasonNumber", 1)
                            val episodes = season.optJSONArray("episodes")
                            if (episodes != null) {
                                for (j in 0 until episodes.length()) {
                                    val ep = episodes.optJSONObject(j) ?: continue
                                    val epNum = ep.optInt("episodeNumber", 0)
                                    if (epNum > 0) {
                                        val epName = ep.optString("title", "").ifBlank { "S${seasonNum}E$epNum" }
                                        episodeUrls.add(newEpisode(fixUrl("/series/$slug/season/$seasonNum/episode/$epNum")) {
                                            this.name = epName
                                            this.episode = epNum
                                            this.season = seasonNum
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            if (episodeUrls.isEmpty()) {
                Regex("""/series/[^/]+/season/(\d+)/episode/(\d+)""").findAll(html).forEach { m ->
                    val seasonNum = m.groupValues[1].toIntOrNull() ?: 1
                    val epNum = m.groupValues[2].toIntOrNull()
                    if (epNum != null) {
                        episodeUrls.add(newEpisode(fixUrl(m.value)) {
                            this.name = "S${seasonNum}E$epNum"
                            this.episode = epNum
                            this.season = seasonNum
                        })
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; plot = description; this.tags = tags; this.year = year; this.score = score
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (tryLoadFromApi(data, subtitleCallback, callback)) return true

        val resp = app.get(data)
        val raw = resp.text ?: return true
        val html = raw.replace("\\\"", "\"")

        val sources = listOf(
            Regex("""sources":\[([^\]]+)\]"""),
            Regex("""playlist":\[([^\]]+)\]"""),
            Regex("""playerSources":\[([^\]]+)\]"""),
        )

        for (pattern in sources) {
            val match = pattern.find(html) ?: continue
            val content = match.groupValues[1]
            Regex("""https?://[^\s"',]+\.(?:m3u8|mp4)[^\s"',]*""").findAll(content).forEach { m ->
                callback(newExtractorLink("Idlix", "Idlix", m.value) {
                    this.referer = "$mainUrl/"
                })
            }
            Regex("""playbackUrl":"([^"]+)""").findAll(content).forEach { m ->
                val quality = Regex("""quality":"([^"]+)""").find(content)?.groupValues?.getOrNull(1) ?: "FHD"
                callback(newExtractorLink("Idlix", "Idlix - $quality", m.groupValues[1]) {
                    this.quality = parseQuality(quality)
                    this.referer = "$mainUrl/"
                })
            }
        }

        return true
    }

    private suspend fun tryLoadFromApi(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug: String
        val contentType: String
        val extra: Map<String, String>

        val episodeMatch = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(data)
        if (episodeMatch != null) {
            slug = episodeMatch.groupValues[1]
            contentType = "tv_series"
            extra = mapOf("season" to episodeMatch.groupValues[2], "episode" to episodeMatch.groupValues[3])
        } else if (data.contains("/series/")) {
            slug = data.substringAfter("/series/").substringBefore("/").substringBefore("?")
            contentType = "tv_series"
            extra = emptyMap()
        } else {
            slug = data.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            contentType = "movie"
            extra = emptyMap()
        }
        if (slug.isBlank()) return false

        val contentId: String
        try {
            val apiPath = if (contentType == "movie") "api/movies/$slug"
            else if (extra.containsKey("season")) "api/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
            else "api/series/$slug"
            val detailResp = app.get("$mainUrl/$apiPath")
            val detailJson = JSONObject(detailResp.text ?: return false)
            contentId = detailJson.optJSONObject("episode")?.optString("id", "")?.ifBlank { null }
                ?: detailJson.optString("id", "")
            if (contentId.isBlank()) return false
        } catch (e: Exception) {
            return false
        }

        var streamUrl: String? = null
        var subtitles: List<Pair<String, String>> = emptyList()
        try {
            val playInfoType = if (extra.containsKey("season")) "episode" else contentType
            val playInfoResp = app.get("$mainUrl/api/watch/play-info/$playInfoType/$contentId")
            val playInfo = JSONObject(playInfoResp.text ?: return false)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false

            val claimResp = app.post(
                "$mainUrl/api/watch/session/claim",
                json = JsonAsString("""{"gateToken":"$gateToken"}""")
            )
            val claimText = claimResp.text ?: return false
            val claimJson = JSONObject(claimText)

            var redeemUrl: String
            var claim: String
            var initialMaster: String
            if (claimJson.optString("kind") == "pending") {
                val remainingMs = claimJson.optLong("remainingMs", 0)
                if (remainingMs > 0) delay(remainingMs + 2000)
                val retryResp = app.post(
                    "$mainUrl/api/watch/session/claim",
                    json = JsonAsString("""{"gateToken":"$gateToken"}""")
                )
                val retryText = retryResp.text ?: return false
                val retryJson = JSONObject(retryText)
                redeemUrl = retryJson.optString("redeemUrl", "")
                claim = retryJson.optString("claim", "")
                initialMaster = retryJson.optString("initialMasterUrl", "")
            } else {
                redeemUrl = claimJson.optString("redeemUrl", "")
                claim = claimJson.optString("claim", "")
                initialMaster = claimJson.optString("initialMasterUrl", "")
            }

            if (redeemUrl.isNotBlank() && claim.isNotBlank()) {
                try {
                    val pentosResp = app.post(
                        redeemUrl,
                        json = JsonAsString("""{"claim":"$claim"}""")
                    )
                    val pentosText = pentosResp.text ?: ""
                    val pentosJson = JSONObject(pentosText)
                    streamUrl = pentosJson.optString("url", "").ifBlank { null }
                    val subsArray = pentosJson.optJSONArray("subtitles")
                    subtitles = if (subsArray != null) {
                        (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("lang", "")
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    } else emptyList()
                } catch (_: Exception) {}
                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        subtitleCallback(SubtitleFile(lang, path))
                    }
                    callback(newExtractorLink("Idlix", "Idlix", streamUrl) {
                        this.referer = "$mainUrl/"
                    })
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                callback(newExtractorLink("Idlix", "Idlix", initialMaster) {
                    this.referer = "$mainUrl/"
                })
                return true
            }
        } catch (e: Exception) {
            return false
        }

        return false
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
