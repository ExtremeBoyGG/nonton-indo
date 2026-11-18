package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import org.json.JSONObject

class Idlix : MainAPI() {
    override var mainUrl = "https://z3.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cfKiller = CloudflareKiller()

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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
                                    val epId = ep.optString("id", "")
                                    if (epNum > 0) {
                                        val epName = ep.optString("name").ifBlank { ep.optString("title") }.ifBlank { "S${sNum}E$epNum" }
                                        val stillPath = ep.optString("stillPath", "")
                                        val still = if (stillPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else null
                                        val epUrl = fixUrl("/series/$slug/season/$sNum/episode/$epNum") + (if (epId.isNotBlank()) "#episodeId=$epId" else "")
                                        episodeUrls.add(newEpisode(epUrl) {
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

                    val movieId = movieJson.optString("id", "")
                    val movieData = url + (if (movieId.isNotBlank()) "#movieId=$movieId" else "")
                    return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
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
        if (tryLoadFromWebView(data, callback)) return true

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
        val directEpId = Regex("""#episodeId=([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.getOrNull(1)
        val directMovieId = Regex("""#movieId=([a-zA-Z0-9_-]+)""").find(data)?.groupValues?.getOrNull(1)
        val cleanData = data.substringBefore("#")

        val slug: String
        val contentType: String
        val extra: Map<String, String>

        val episodeMatch = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(cleanData)
        if (episodeMatch != null) {
            slug = episodeMatch.groupValues[1]
            contentType = "tv_series"
            extra = mapOf("season" to episodeMatch.groupValues[2], "episode" to episodeMatch.groupValues[3])
        } else if (cleanData.contains("/series/")) {
            slug = cleanData.substringAfter("/series/").substringBefore("/").substringBefore("?")
            contentType = "tv_series"
            extra = emptyMap()
        } else {
            slug = cleanData.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            contentType = "movie"
            extra = emptyMap()
        }
        if (slug.isBlank()) return false

        val pageUrl = if (contentType == "movie") "$mainUrl/movie/$slug"
        else if (extra.containsKey("season")) "$mainUrl/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
        else "$mainUrl/series/$slug"

        try {
            app.get(pageUrl, interceptor = cfKiller)
        } catch (_: Exception) {}

        val contentId: String
        try {
            contentId = when {
                !directEpId.isNullOrBlank() -> directEpId
                !directMovieId.isNullOrBlank() -> directMovieId
                contentType == "movie" -> {
                    val movieResp = app.get("$mainUrl/api/movies/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                    JSONObject(movieResp.text).optString("id", "")
                }
                extra.containsKey("season") -> {
                    val sNum = extra["season"] ?: "1"
                    val epNum = extra["episode"]?.toIntOrNull() ?: 1
                    var matchedId = ""
                    try {
                        val seasonResp = app.get("$mainUrl/api/series/$slug/season/$sNum", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                        val seasonJson = JSONObject(seasonResp.text)
                        val episodes = seasonJson.optJSONObject("season")?.optJSONArray("episodes")
                            ?: seasonJson.optJSONArray("episodes")
                        if (episodes != null) {
                            for (i in 0 until episodes.length()) {
                                val ep = episodes.optJSONObject(i) ?: continue
                                if (ep.optInt("episodeNumber", 0) == epNum) {
                                    matchedId = ep.optString("id", "")
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (matchedId.isBlank()) {
                        try {
                            val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                            val seriesJson = JSONObject(seriesResp.text)
                            val defEps = seriesJson.optJSONObject("defaultSeason")?.optJSONArray("episodes")
                            if (defEps != null) {
                                for (i in 0 until defEps.length()) {
                                    val ep = defEps.optJSONObject(i) ?: continue
                                    if (ep.optInt("episodeNumber", 0) == epNum) {
                                        matchedId = ep.optString("id", "")
                                        break
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    matchedId
                }
                else -> {
                    val seriesResp = app.get("$mainUrl/api/series/$slug", headers = apiHeaders + mapOf("Referer" to "$pageUrl/"), interceptor = cfKiller)
                    val seriesJson = JSONObject(seriesResp.text)
                    seriesJson.optJSONObject("defaultSeason")?.optJSONArray("episodes")?.optJSONObject(0)?.optString("id", "")
                        ?: seriesJson.optString("id", "")
                }
            }
            if (contentId.isBlank()) return false
        } catch (_: Exception) {
            return false
        }

        try {
            val playInfoType = if (contentType == "tv_series" || extra.containsKey("season") || !directEpId.isNullOrBlank()) "episode" else "movie"
            val playInfoResp = app.get(
                "$mainUrl/api/watch/play-info/$playInfoType/$contentId",
                headers = apiHeaders + mapOf("Referer" to "$pageUrl/"),
                interceptor = cfKiller
            )
            val playInfo = JSONObject(playInfoResp.text)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false
            // A Turnstile-gated claim always fails here; let the WebView path handle it right away.
            if (playInfo.has("verification")) return false

            val kind = playInfo.optString("kind", "")
            if (kind == "gate") {
                val unlockAt = playInfo.optLong("unlockAt", 0L)
                val serverNow = playInfo.optLong("serverNow", 0L)
                val waitMs = if (unlockAt > 0 && serverNow > 0) maxOf(0L, unlockAt - serverNow) else 0L
                if (waitMs in 1L..15500L) {
                    delay(waitMs + 300L)
                }
            }

            var claimJson = postClaim(gateToken, pageUrl)

            var retries = 0
            while (claimJson.optString("kind") == "pending" && retries < 3) {
                val remainingMs = claimJson.optLong("remainingMs", 1000L)
                delay(minOf(maxOf(500L, remainingMs + 200L), 3000L))
                claimJson = postClaim(gateToken, pageUrl)
                retries++
            }

            val rawRedeemUrl = claimJson.optString("redeemUrl", "")
            val claim = claimJson.optString("claim", "")
            val initialMaster = claimJson.optString("initialMasterUrl", "")

            var streamUrl: String? = null
            var subtitles: List<Pair<String, String>> = emptyList()

            if (claim.isNotBlank()) {
                val fullRedeemUrl = when {
                    rawRedeemUrl.startsWith("http") -> rawRedeemUrl
                    rawRedeemUrl.isNotBlank() -> "$mainUrl" + (if (rawRedeemUrl.startsWith("/api")) "" else "/api") + rawRedeemUrl
                    else -> "$mainUrl/api/watch/session/redeem"
                }

                val redeemPayload = JSONObject().put("claim", claim).put("mode", "browser").toString()

                try {
                    val redeemResp = app.post(
                        fullRedeemUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "Content-Type" to "application/json"
                        ),
                        json = JsonAsString(redeemPayload),
                        interceptor = cfKiller
                    )
                    val redeemJson = JSONObject(redeemResp.text)
                    streamUrl = redeemJson.optString("url", "").ifBlank { null }
                    val subsArray = redeemJson.optJSONArray("subtitles")
                    if (subsArray != null) {
                        subtitles = (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    }
                } catch (_: Exception) {}

                if (streamUrl == null && !fullRedeemUrl.contains("majorplay.net")) {
                    try {
                        val pentosResp = app.post(
                            "https://e2e.majorplay.net/api/play",
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/",
                                "Content-Type" to "application/json"
                            ),
                            json = JsonAsString(redeemPayload)
                        )
                        val pentosJson = JSONObject(pentosResp.text)
                        streamUrl = pentosJson.optString("url", "").ifBlank { null }
                        val subsArray = pentosJson.optJSONArray("subtitles")
                        if (subsArray != null && subtitles.isEmpty()) {
                            subtitles = (0 until subsArray.length()).mapNotNull { i ->
                                val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                                val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                                val path = sub.optString("path", "")
                                if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (streamUrl == null && initialMaster.isNotBlank()) {
                    streamUrl = initialMaster
                }

                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        val subUrl = if (path.startsWith("http")) path else fixUrl(path)
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                    val maxHeight = claimJson.optInt("maxHeight", 720)
                    processHlsStream(streamUrl, maxHeight, callback)
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                val maxHeight = claimJson.optInt("maxHeight", 720)
                processHlsStream(initialMaster, maxHeight, callback)
                return true
            }
        } catch (_: Exception) {
            return false
        }

        return false
    }

    /**
     * Guest playback now needs a Cloudflare Turnstile token on the session claim, which only the
     * site's own player can obtain. Load the real page in a WebView, start the player, wait out the
     * preroll, and capture the master playlist it requests. The master is served as
     * `majorplay.net/v/<zone>/<videoId>/config-<n>.json?t=...&pm=browser` (HLS despite the .json name);
     * its token must be carried to every variant, init map and segment, which processHlsStream does.
     */
    private suspend fun tryLoadFromWebView(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Stored links may still point at an older mirror domain.
        val pageUrl = data.substringBefore("#").replace(Regex("""^https?://[^/]+"""), mainUrl)
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""majorplay\.net/v/[^?#]+/config-[^/?#]+\.json|\.m3u8"""),
                script = PLAYER_AUTOSTART_JS,
                timeout = 90_000L
            )
            val (request, extra) = resolver.resolveUsingWebView(pageUrl, "$mainUrl/")
            val streamUrl = (request ?: extra.firstOrNull())?.url?.toString() ?: return false
            processHlsStream(streamUrl, 720, callback)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun postClaim(gateToken: String, pageUrl: String): JSONObject {
        val claimResp = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ),
            json = JsonAsString("""{"gateToken":"$gateToken"}"""),
            interceptor = cfKiller
        )
        return JSONObject(claimResp.text)
    }

    private suspend fun processHlsStream(
        streamUrl: String,
        maxHeight: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        val tokenQuery = streamUrl.substringAfter("?", "")
        val streamBaseUri = try { java.net.URI.create(streamUrl) } catch (_: Exception) { null }

        try {
            val masterText = app.get(streamUrl, headers = streamHeaders).text

            if (masterText.contains("#EXT-X-STREAM-INF")) {
                val lines = masterText.lines().toMutableList()
                var currentRes: String? = null
                val audioDataUris = mutableMapOf<String, String>()

                // 1. Process and rewrite audio tracks if present
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                        val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                        if (uriMatch != null) {
                            val rawAudioUri = uriMatch.groupValues[1]
                            val fullAudioUrl = appendToken(
                                streamBaseUri?.resolve(rawAudioUri)?.toString() ?: rawAudioUri,
                                tokenQuery
                            )
                            val audioDataUri = audioDataUris.getOrPut(rawAudioUri) {
                                try {
                                    val aText = app.get(fullAudioUrl, headers = streamHeaders).text
                                    val rewrittenA = rewriteMediaPlaylist(aText, fullAudioUrl, tokenQuery)
                                    val b64 = android.util.Base64.encodeToString(
                                        rewrittenA.toByteArray(Charsets.UTF_8),
                                        android.util.Base64.NO_WRAP
                                    )
                                    "data:application/vnd.apple.mpegurl;base64,$b64"
                                } catch (_: Exception) {
                                    fullAudioUrl
                                }
                            }
                            lines[i] = line.replace("""URI="$rawAudioUri"""", """URI="$audioDataUri"""")
                        }
                    }
                }

                // 2. Process and rewrite video variant streams
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                        currentRes = resMatch?.groupValues?.getOrNull(2)
                    } else if (!line.startsWith("#") && line.isNotBlank()) {
                        val rawVideoUri = line
                        val fullVideoUrl = appendToken(
                            streamBaseUri?.resolve(rawVideoUri)?.toString() ?: rawVideoUri,
                            tokenQuery
                        )
                        try {
                            val vText = app.get(fullVideoUrl, headers = streamHeaders).text
                            if (vText.contains("#EXTINF")) {
                                val rewrittenV = rewriteMediaPlaylist(vText, fullVideoUrl, tokenQuery)
                                val b64 = android.util.Base64.encodeToString(
                                    rewrittenV.toByteArray(Charsets.UTF_8),
                                    android.util.Base64.NO_WRAP
                                )
                                val videoDataUri = "data:application/vnd.apple.mpegurl;base64,$b64"

                                // Replace raw video uri with rewritten data URI in master playlist
                                lines[i] = videoDataUri

                                val qNum = currentRes?.toIntOrNull() ?: maxHeight
                                val qName = "Idlix - ${qNum}p"
                                val qVal = when {
                                    qNum >= 1080 -> Qualities.P1080.value
                                    qNum >= 720 -> Qualities.P720.value
                                    qNum >= 480 -> Qualities.P480.value
                                    qNum >= 360 -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }

                                callback(newExtractorLink("Idlix", qName, videoDataUri, type = ExtractorLinkType.M3U8) {
                                    this.quality = qVal
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Origin" to mainUrl,
                                        "Referer" to "$mainUrl/"
                                    )
                                })
                            }
                        } catch (_: Exception) {}
                        currentRes = null
                    }
                }

                // 3. Emit master playlist (contains full video + audio tracks)
                try {
                    val rewrittenMaster = lines.joinToString("\n")
                    val masterB64 = android.util.Base64.encodeToString(
                        rewrittenMaster.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    val masterDataUri = "data:application/vnd.apple.mpegurl;base64,$masterB64"
                    callback(newExtractorLink("Idlix", "Idlix - Auto (Full Audio/Video)", masterDataUri, type = ExtractorLinkType.M3U8) {
                        this.quality = if (maxHeight >= 1080) Qualities.P1080.value else Qualities.P720.value
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    })
                } catch (_: Exception) {}

            } else if (masterText.contains("#EXTINF")) {
                val rewritten = rewriteMediaPlaylist(masterText, streamUrl, tokenQuery)
                val b64 = android.util.Base64.encodeToString(
                    rewritten.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val dataUri = "data:application/vnd.apple.mpegurl;base64,$b64"
                val qualityName = if (maxHeight > 0) "Idlix - ${maxHeight}p" else "Idlix"
                val qVal = when {
                    maxHeight >= 1080 -> Qualities.P1080.value
                    maxHeight >= 720 -> Qualities.P720.value
                    maxHeight >= 480 -> Qualities.P480.value
                    maxHeight >= 360 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink("Idlix", qualityName, dataUri, type = ExtractorLinkType.M3U8) {
                    this.quality = qVal
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                })
            }
        } catch (_: Exception) {}

        // 4. Always emit direct stream URL as fallback
        val fallbackQuality = if (maxHeight > 0) "Idlix (Direct) - ${maxHeight}p" else "Idlix (Direct)"
        callback(newExtractorLink("Idlix", fallbackQuality, streamUrl, type = ExtractorLinkType.M3U8) {
            this.referer = "$mainUrl/"
            this.headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        })
    }

    private fun appendToken(url: String, tokenQuery: String): String {
        if (tokenQuery.isBlank() || url.contains("t=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$tokenQuery"
    }

    private fun rewriteMediaPlaylist(playlistText: String, subUrl: String, tokenQuery: String): String {
        val baseUri = try { java.net.URI.create(subUrl) } catch (_: Exception) { null }
        return playlistText.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-MAP:") -> {
                    val m = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (m != null) {
                        val rawUri = m.groupValues[1]
                        val fullUri = baseUri?.resolve(rawUri)?.toString() ?: rawUri
                        val withToken = appendToken(fullUri, tokenQuery)
                        line.replace("""URI="$rawUri"""", """URI="$withToken"""")
                    } else line
                }
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val m = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (m != null) {
                        val rawUri = m.groupValues[1]
                        val fullUri = baseUri?.resolve(rawUri)?.toString() ?: rawUri
                        val withToken = appendToken(fullUri, tokenQuery)
                        line.replace("""URI="$rawUri"""", """URI="$withToken"""")
                    } else line
                }
                trimmed.startsWith("#") || trimmed.isBlank() -> line
                else -> {
                    val fullUri = baseUri?.resolve(trimmed)?.toString() ?: trimmed
                    appendToken(fullUri, tokenQuery)
                }
            }
        }
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

    companion object {
        // Presses the hero play button, then the preroll "Skip" button once its countdown ends.
        private const val PLAYER_AUTOSTART_JS = """
            (function () {
                if (window.__csIdlixAuto) return;
                window.__csIdlixAuto = true;
                setInterval(function () {
                    var play = document.querySelector('button.group.cursor-pointer.z-10');
                    if (play) play.click();
                    document.querySelectorAll('button').forEach(function (b) {
                        var t = (b.innerText || '').trim();
                        if (!b.disabled && /^(skip|lewati)/i.test(t) && !/\d+\s*s$/i.test(t)) b.click();
                    });
                }, 1000);
            })();
        """
    }
}
