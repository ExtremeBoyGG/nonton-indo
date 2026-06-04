package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import java.security.MessageDigest

class MovieBox : MainAPI() {
    override var mainUrl = "https://themoviebox.org"
    private val apiBase = "https://h5-api.aoneroom.com"

    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private var bearerToken: String? = null

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending",
        "4380734070238626200" to "K-Drama: New Release",
        "6528093688173053896" to "Trending Indonesia"
    )

    private val baseHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to USER_AGENT,
        "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}"
    )

    private fun clientTimeToken(): String {
        val ts = (System.currentTimeMillis() / 1000).toInt()
        val rev = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(rev.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private suspend fun apiGet(path: String): String {
        return app.get("$apiBase$path", headers = baseHeaders).text
    }

    private suspend fun apiPost(path: String, data: String): String {
        val headers = baseHeaders + mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken()
        )
        return app.post("$apiBase$path", json = JsonAsString(data), headers = headers, referer = "$mainUrl/").text ?: ""
    }

    private suspend fun getBearerToken(): String {
        bearerToken?.let { return it }
        val headers = baseHeaders + mapOf(
            "Authorization" to "",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken()
        )
        val resp = app.get("$apiBase/wefeed-h5api-bff/home?host=themoviebox.org", headers = headers, referer = "$mainUrl/")
        val xUser = resp.headers["x-user"] ?: ""
        val token = tryParseJson<Map<String, Any?>>(xUser)?.get("token")?.toString()
        if (!token.isNullOrBlank()) {
            bearerToken = token
        }
        return bearerToken ?: ""
    }

    private suspend fun apiGetWithToken(path: String): String {
        val headers = baseHeaders + mapOf(
            "Authorization" to "",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken()
        )
        return app.get("$apiBase$path", headers = headers, referer = "$mainUrl/").text
    }

    private suspend fun tokenGet(path: String): String {
        return app.get("$mainUrl$path", headers = baseHeaders).text
    }

    private fun detailPathFromUrl(url: String): String {
        return url.substringBefore("?").substringAfterLast("/")
    }

    private fun toTvType(subjectType: Int?): TvType = when (subjectType) {
        2 -> TvType.Anime
        3 -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun toInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is Float -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun toSubjectList(raw: String): List<Map<String, Any?>> {
        val root = tryParseJson<Map<String, Any?>>(raw) ?: return emptyList()
        val data = root["data"] as? Map<*, *> ?: return emptyList()
        val list = data["subjectList"] as? List<*> ?: return emptyList()
        return list.mapNotNull { it as? Map<String, Any?> }
    }

    private fun toSearchResponseFromSubject(s: Map<String, Any?>): SearchResponse? {
        val path = s["detailPath"]?.toString()?.ifBlank { null } ?: return null
        val title = s["title"]?.toString()?.ifBlank { null } ?: return null
        val subjectType = toInt(s["subjectType"])
        val tvType = toTvType(subjectType)
        val cover = (s["cover"] as? Map<*, *>)?.get("url")?.toString()
        val imdbRating = s["imdbRatingValue"]?.toString()?.toDoubleOrNull()

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.TvSeries) {
                this.posterUrl = cover
                this.score = Score.from10(imdbRating)
            }
            TvType.Anime -> newAnimeSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Anime) {
                this.posterUrl = cover
                this.score = Score.from10(imdbRating)
            }
            else -> newMovieSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Movie) {
                this.posterUrl = cover
                this.score = Score.from10(imdbRating)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rankingId = request.data
        val raw = apiGetWithToken("/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=12")
        val items = toSubjectList(raw)
            .mapNotNull { toSearchResponseFromSubject(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val token = getBearerToken()
        if (token.isNotBlank()) {
            val body = """{"keyword":"$query","page":1,"perPage":28,"subjectType":0}"""
            val headers = baseHeaders + mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token",
                "X-Request-Lang" to "en"
            )
            val raw = app.post("$apiBase/wefeed-h5api-bff/subject/search", json = JsonAsString(body), headers = headers, referer = "$mainUrl/").text ?: ""
            val root = tryParseJson<Map<String, Any?>>(raw)
            val data = root?.get("data") as? Map<*, *>
            if (data != null) {
                val items = data["items"] as? List<*> ?: emptyList<Any>()
                val results = items
                    .mapNotNull { it as? Map<String, Any?> }
                    .filter { it["detailPath"]?.toString()?.isNotBlank() == true }
                    .mapNotNull { toSearchResponseFromSubject(it) }
                    .distinctBy { it.url }
                if (results.isNotEmpty()) return results
            }
        }

        val pools = mainPage.flatMap { (_, id) ->
            val r = apiGetWithToken("/wefeed-h5api-bff/ranking-list/content?id=$id&page=1&perPage=20")
            toSubjectList(r)
        }

        return pools
            .distinctBy { it["detailPath"]?.toString() }
            .filter { (it["title"]?.toString() ?: "").contains(query, ignoreCase = true) }
            .mapNotNull { toSearchResponseFromSubject(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailPath = detailPathFromUrl(url)
        val raw = apiGetWithToken("/wefeed-h5api-bff/detail?detailPath=$detailPath")

        val root = tryParseJson<Map<String, Any?>>(raw) ?: throw ErrorLoadingException("Invalid detail response")
        val data = root["data"] as? Map<*, *> ?: throw ErrorLoadingException("Missing data")
        val subject = data["subject"] as? Map<*, *> ?: throw ErrorLoadingException("Missing subject")
        val resource = data["resource"] as? Map<*, *>

        val title = subject["title"]?.toString() ?: throw ErrorLoadingException("Title not found")
        val subjectId = subject["subjectId"]?.toString().orEmpty()
        val tvType = toTvType(toInt(subject["subjectType"]))
        val imdbRating = subject["imdbRatingValue"]?.toString()?.toDoubleOrNull()
        val plot = subject["description"]?.toString()
        val poster = (subject["cover"] as? Map<*, *>)?.get("url")?.toString()
        val tags = subject["genre"]?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val year = subject["releaseDate"]?.toString()?.take(4)?.toIntOrNull()
        val dubs = (subject["dubs"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()

        val seasons = (resource?.get("seasons") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()

        val isSeriesLike = seasons.isNotEmpty() && (toInt(seasons.first()["maxEp"]) ?: 0) > 1

        if (isSeriesLike) {
            val episodes = mutableListOf<Episode>()

            val primaryDubs = dubs.filter { it["type"] as? Int == 5 || it["type"] as? Int == 0 }
            val primaryDub = primaryDubs.firstOrNull()
            val dubSubjectId = primaryDub?.get("subjectId")?.toString() ?: subjectId

            seasons.forEach { s ->
                val seasonNo = toInt(s["se"]) ?: return@forEach
                val allEp = s["allEp"]?.toString()
                val eps = if (!allEp.isNullOrBlank()) {
                    allEp.split(',').mapNotNull { it.trim().toIntOrNull() }
                } else {
                    val max = toInt(s["maxEp"]) ?: 0
                    (1..max).toList()
                }

                eps.forEach { ep ->
                    episodes.add(newEpisode("$mainUrl/moviesDetail/$detailPath?sid=$dubSubjectId&se=$seasonNo&ep=$ep") {
                        this.season = seasonNo
                        this.episode = ep
                        this.name = "Episode $ep"
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = Score.from10(imdbRating)
            }
        }

        return newMovieLoadResponse(title, url, tvType, "$mainUrl/movies/$detailPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en") {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.score = Score.from10(imdbRating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailPath = detailPathFromUrl(data)
        val sid = Regex("[?&](sid|id)=([^&]+)").find(data)?.groupValues?.getOrNull(2)
        val se = Regex("[?&]se=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "0"
        val ep = Regex("[?&]ep=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "0"

        val detRaw = apiGetWithToken("/wefeed-h5api-bff/detail?detailPath=$detailPath")
        val detRoot = tryParseJson<Map<String, Any?>>(detRaw)
        val detData = detRoot?.get("data") as? Map<*, *>
        val detSubject = detData?.get("subject") as? Map<*, *>

        val subjectId = if (!sid.isNullOrBlank()) sid else detSubject?.get("subjectId")?.toString().orEmpty()
        if (subjectId.isBlank()) return false

        val dubs = (detSubject?.get("dubs") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()

        val allDubs = dubs.ifEmpty {
            listOf(mapOf<String, Any?>("subjectId" to subjectId, "lanName" to "Original"))
        }

        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
            "Authorization" to "",
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken(),
            "Referer" to "$mainUrl/movies/$detailPath"
        )

        var found = false

        for (dub in allDubs) {
            val dubId = dub["subjectId"]?.toString() ?: continue
            val dubName = dub["lanName"]?.toString() ?: "Unknown"

            val playRaw = app.get(
                "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$dubId&se=$se&ep=$ep&detailPath=$detailPath",
                headers = headers
            ).text

            val playRoot = tryParseJson<Map<String, Any?>>(playRaw) ?: continue
            val playData = playRoot["data"] as? Map<*, *> ?: continue
            val hasResource = playData["hasResource"] as? Boolean ?: false
            if (!hasResource) continue

            val streams = (playData["streams"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            val hls = (playData["hls"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            val dash = (playData["dash"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
            val all = streams + hls + dash

            all.forEach { item ->
                val u = item["url"]?.toString()?.takeIf { it.startsWith("http") } ?: return@forEach
                val res = item["resolutions"]?.toString()
                val streamId = item["id"]?.toString()?.takeIf { it.isNotBlank() }

                val label = if (dubs.isNotEmpty()) "$dubName ${res ?: "Auto"}" else "${res ?: "Auto"}"

                val q = when {
                    (res ?: "").contains("1080") || u.contains("1080", true) -> Qualities.P1080.value
                    (res ?: "").contains("720") || u.contains("720", true) -> Qualities.P720.value
                    (res ?: "").contains("480") || u.contains("480", true) -> Qualities.P480.value
                    (res ?: "").contains("360") || u.contains("360", true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                callback(newExtractorLink(name, label, u) {
                    this.quality = q
                    this.referer = "$mainUrl/"
                })
                found = true

                if (streamId != null) {
                    val capRaw = app.get(
                        "$apiBase/wefeed-h5api-bff/subject/caption?format=MP4&id=$streamId&subjectId=$dubId&detailPath=$detailPath",
                        headers = headers
                    ).text
                    val capRoot = tryParseJson<Map<String, Any?>>(capRaw)
                    val capData = capRoot?.get("data") as? Map<*, *>
                    val captions = capData?.get("captions") as? List<*>
                    captions?.forEach { cap ->
                        val capMap = cap as? Map<*, *> ?: return@forEach
                        val capUrl = capMap["url"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                        val capLang = capMap["lanName"]?.toString() ?: capMap["lan"]?.toString() ?: "Unknown"
                        subtitleCallback(SubtitleFile(capLang, capUrl))
                    }
                }
            }
        }

        return found
    }
}
