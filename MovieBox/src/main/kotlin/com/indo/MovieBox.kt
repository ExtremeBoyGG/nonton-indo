package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest

class MovieBox : MainAPI() {
    override var mainUrl = "https://themoviebox.org"
    private val apiBase = "https://h5-api.aoneroom.com"

    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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

    private suspend fun apiPost(path: String, body: String): String {
        val headers = baseHeaders + mapOf(
            "Content-Type" to "application/json",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken()
        )
        return app.post("$apiBase$path", body = body, headers = headers, referer = "$mainUrl/").text
    }

    private suspend fun apiGetWithToken(path: String): String {
        val headers = baseHeaders + mapOf(
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
        val body = """{"keyword":"$query","page":1,"perPage":28,"subjectType":0}"""
        val raw = apiPost("/wefeed-h5api-bff/subject/search", body)
        val root = tryParseJson<Map<String, Any?>>(raw)
        val data = root?.get("data") as? Map<*, *> ?: return emptyList()
        val items = data["items"] as? List<*> ?: return emptyList()

        return items
            .mapNotNull { it as? Map<String, Any?> }
            .filter { it["detailPath"]?.toString()?.isNotBlank() == true }
            .mapNotNull { toSearchResponseFromSubject(it) }
            .distinctBy { it.url }
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

        val subjectId = if (!sid.isNullOrBlank()) sid else {
            val detRaw = apiGetWithToken("/wefeed-h5api-bff/detail?detailPath=$detailPath")
            val detRoot = tryParseJson<Map<String, Any?>>(detRaw)
            val detData = detRoot?.get("data") as? Map<*, *>
            val subject = detData?.get("subject") as? Map<*, *>
            subject?.get("subjectId")?.toString().orEmpty()
        }

        if (subjectId.isBlank()) return false

        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "X-Request-Lang" to "en",
            "X-Client-Token" to clientTimeToken(),
            "Referer" to "$mainUrl/movies/$detailPath"
        )

        val playRaw = app.get(
            "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath",
            headers = headers
        ).text

        val playRoot = tryParseJson<Map<String, Any?>>(playRaw)
        val playData = playRoot?.get("data") as? Map<*, *> ?: return false
        val hasResource = playData["hasResource"] as? Boolean ?: false
        if (!hasResource) return false

        val streams = (playData["streams"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val hls = (playData["hls"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val all = streams + hls

        all.forEach { item ->
            val u = item["url"]?.toString()?.takeIf { it.startsWith("http") } ?: return@forEach
            val res = item["resolutions"]?.toString()

            val q = when {
                (res ?: "").contains("1080") || u.contains("1080", true) -> Qualities.P1080.value
                (res ?: "").contains("720") || u.contains("720", true) -> Qualities.P720.value
                (res ?: "").contains("480") || u.contains("480", true) -> Qualities.P480.value
                (res ?: "").contains("360") || u.contains("360", true) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback(newExtractorLink(name, "$name ${res ?: "Auto"}", u) {
                this.quality = q
                this.referer = "$mainUrl/"
            })
        }

        return all.isNotEmpty()
    }
}
