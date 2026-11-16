package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

    private val cfKiller = CloudflareKiller()

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sections = listOfNotNull(
            getApiSection("Movies", page, "api/movies"),
            getApiSection("TV Series", page, "api/series"),
        )
        return newHomePageResponse(sections)
    }

    private suspend fun getApiSection(name: String, page: Int, apiPath: String): HomePageList? {
        val resp = app.get("$mainUrl/$apiPath?page=$page&limit=24", headers = apiHeaders, interceptor = cfKiller)
        val text = resp.text
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
        val resp = app.get("$mainUrl/api/search?q=$query", headers = apiHeaders, interceptor = cfKiller)
        val text = resp.text
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
        val isSeries = url.contains("/series/")
        val slug = if (isSeries) {
            url.substringAfter("/series/").substringBefore("/").substringBefore("?")
        } else {
            url.substringAfter("/movie/").substringBefore("/").substringBefore("?")
        }

        if (isSeries) {
            try {
                val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders, interceptor = cfKiller)
                val seriesJson = JSONObject(seriesResp.text)
                val title = seriesJson.optString("title", "").ifBlank { null }
                if (title != null) {
                    val posterPath = seriesJson.optString("posterPath", "")
                    val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
                    val backdropPath = seriesJson.optString("backdropPath", "")
                    val backdrop = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/original$backdropPath" else null
                    val plot = seriesJson.optString("overview", "").ifBlank { null }
                    val year = seriesJson.optString("firstAirDate", "").take(4).toIntOrNull()
                    val score = Score.from10(seriesJson.opt("voteAverage").let { v ->
                        when (v) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                    })
                    val genresArray = seriesJson.optJSONArray("genres")
                    val tags = if (genresArray != null) {
                        (0 until genresArray.length()).mapNotNull { i ->
                            genresArray.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
                        }
                    } else emptyList()

                    val episodeUrls = mutableListOf<Episode>()
                    val seasonsArray = seriesJson.optJSONArray("seasons")
                    val defaultSeason = seriesJson.optJSONObject("defaultSeason")
                    val defaultSeasonNum = defaultSeason?.optInt("seasonNumber", 1) ?: 1

                    if (seasonsArray != null && seasonsArray.length() > 0) {
                        for (i in 0 until seasonsArray.length()) {
                            val sObj = seasonsArray.optJSONObject(i) ?: continue
                            val sNum = sObj.optInt("seasonNumber", i + 1)
                            val episodesArray = if (sNum == defaultSeasonNum && defaultSeason != null) {
                                defaultSeason.optJSONArray("episodes")
                            } else {
                                try {
                                    val sResp = app.get("$mainUrl/api/series/$slug/season/$sNum", headers = apiHeaders, interceptor = cfKiller)
                                    val sJson = JSONObject(sResp.text)
                                    sJson.optJSONObject("season")?.optJSONArray("episodes")
                                } catch (_: Exception) { null }
                            }
                            if (episodesArray != null) {
                                for (j in 0 until episodesArray.length()) {
                                    val ep = episodesArray.optJSONObject(j) ?: continue
                                    val epNum = ep.optInt("episodeNumber", 0)
                                    if (epNum > 0) {
                                        val epName = ep.optString("name").ifBlank { ep.optString("title") }.ifBlank { "S${sNum}E$epNum" }
                                        val stillPath = ep.optString("stillPath", "")
                                        val still = if (stillPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else null
                                        episodeUrls.add(newEpisode(fixUrl("/series/$slug/season/$sNum/episode/$epNum")) {
                                            this.name = epName
                                            this.episode = epNum
                                            this.season = sNum
                                            this.posterUrl = still
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (episodeUrls.isNotEmpty()) {
                        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeUrls) {
                            posterUrl = poster
                            backgroundPosterUrl = backdrop
                            this.plot = plot
                            this.tags = tags
                            this.year = year
                            this.score = score
                        }
                    }
                }
            } catch (_: Exception) {}
        } else {
            try {
                val movieResp = app.get("$mainUrl/api/movies/$slug", headers = apiHeaders, interceptor = cfKiller)
                val movieJson = JSONObject(movieResp.text)
                val title = movieJson.optString("title", "").ifBlank { null }
                if (title != null) {
                    val posterPath = movieJson.optString("posterPath", "")
                    val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
                    val backdropPath = movieJson.optString("backdropPath", "")
                    val backdrop = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/original$backdropPath" else null
                    val plot = movieJson.optString("overview", "").ifBlank { null }
                    val year = movieJson.optString("releaseDate", "").take(4).toIntOrNull()
                    val score = Score.from10(movieJson.opt("voteAverage").let { v ->
                        when (v) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                    })
                    val genresArray = movieJson.optJSONArray("genres")
                    val tags = if (genresArray != null) {
                        (0 until genresArray.length()).mapNotNull { i ->
                            genresArray.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
                        }
                    } else emptyList()

                    return newMovieLoadResponse(title, url, TvType.Movie, url) {
                        posterUrl = poster
                        backgroundPosterUrl = backdrop
                        this.plot = plot
                        this.tags = tags
                        this.year = year
                        this.score = score
                    }
                }
            } catch (_: Exception) {}
        }

        // HTML fallback
        val resp = app.get(url, interceptor = cfKiller)
        val raw = resp.text
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

        return if (isSeries) {
            val episodeUrls = mutableListOf<Episode>()
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

        val resp = app.get(data, interceptor = cfKiller)
        val raw = resp.text
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

        val pageUrl = if (contentType == "movie") "$mainUrl/movie/$slug"
        else if (extra.containsKey("season")) "$mainUrl/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
        else "$mainUrl/series/$slug"

        var cookieHeader = emptyMap<String, String>()
        try {
            val pageResp = app.get(pageUrl, interceptor = cfKiller)
            val setCookie = pageResp.headers["set-cookie"].orEmpty()
            val did = Regex("""\bdid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)
            if (!did.isNullOrBlank()) {
                cookieHeader = mapOf("Cookie" to "did=$did")
            }
        } catch (_: Exception) {}

        val contentId: String
        try {
            val apiPath = if (contentType == "movie") "api/movies/$slug"
            else if (extra.containsKey("season")) "api/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
            else "api/series/$slug"
            val detailResp = app.get("$mainUrl/$apiPath", headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader, interceptor = cfKiller)
            val detailJson = JSONObject(detailResp.text)
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
            val playInfoResp = app.get(
                "$mainUrl/api/watch/play-info/$playInfoType/$contentId",
                headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader,
                interceptor = cfKiller
            )
            val playInfo = JSONObject(playInfoResp.text)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false

            val kind = playInfo.optString("kind", "")
            if (kind == "gate") {
                val unlockAt = playInfo.optLong("unlockAt", 0L)
                val serverNow = playInfo.optLong("serverNow", 0L)
                val waitMs = if (unlockAt > 0 && serverNow > 0) unlockAt - serverNow else 15000L
                if (waitMs > 0) delay(waitMs + 2000)
            }

            var claimJson = postClaim(gateToken, pageUrl, cookieHeader)

            if (claimJson.optString("kind") == "pending") {
                val remainingMs = claimJson.optLong("remainingMs", 0)
                if (remainingMs > 0) delay(remainingMs + 2000)
                claimJson = postClaim(gateToken, pageUrl, cookieHeader)
            }

            val redeemUrl = claimJson.optString("redeemUrl", "")
            val claim = claimJson.optString("claim", "")
            val initialMaster = claimJson.optString("initialMasterUrl", "")

            if (redeemUrl.isNotBlank() && claim.isNotBlank()) {
                try {
                    val pentosResp = app.post(
                        redeemUrl,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "Content-Type" to "application/json"
                        ),
                        json = JsonAsString("""{"claim":"$claim"}""")
                    )
                    val pentosText = pentosResp.text
                    val pentosJson = JSONObject(pentosText)
                    streamUrl = pentosJson.optString("url", "").ifBlank { null }
                    val subsArray = pentosJson.optJSONArray("subtitles")
                    subtitles = if (subsArray != null) {
                        (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    } else emptyList()
                } catch (_: Exception) {}
                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        subtitleCallback(newSubtitleFile(lang, path))
                    }
                    val maxHeight = claimJson.optInt("maxHeight", 720)
                    val qualityName = if (maxHeight > 0) "Idlix - ${maxHeight}p" else "Idlix"
                    callback(newExtractorLink("Idlix", qualityName, streamUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                    })
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                callback(newExtractorLink("Idlix", "Idlix", initialMaster, type = ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                })
                return true
            }
        } catch (e: Exception) {
            return false
        }

        return false
    }

    private suspend fun postClaim(gateToken: String, pageUrl: String, cookieHeader: Map<String, String>): JSONObject {
        val claimResp = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ) + cookieHeader,
            json = JsonAsString("""{"gateToken":"$gateToken"}"""),
            interceptor = cfKiller
        )
        return JSONObject(claimResp.text)
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
