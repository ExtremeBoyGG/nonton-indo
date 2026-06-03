package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MovieBox : MainAPI() {
    override var mainUrl = "https://themoviebox.org"
    private val apiBase = "https://h5-api.aoneroom.com"

    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending🔥",
        "4380734070238626200" to "K-Drama: New Release",
        "6528093688173053896" to "Trending Indonesia"
    )

    private val baseHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to USER_AGENT,
        "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}"
    )

    private suspend fun apiGet(path: String): String {
        return app.get("$apiBase$path", headers = baseHeaders).text
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

        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.TvSeries) {
                this.posterUrl = cover
            }
            TvType.Anime -> newAnimeSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Anime) {
                this.posterUrl = cover
            }
            else -> newMovieSearchResponse(title, "$mainUrl/moviesDetail/$path", TvType.Movie) {
                this.posterUrl = cover
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rankingId = request.data
        val raw = apiGet("/wefeed-h5api-bff/ranking-list/content?id=$rankingId&page=$page&perPage=12")
        val items = toSubjectList(raw)
            .mapNotNull { toSearchResponseFromSubject(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val pools = mainPage.flatMap { (_, id) ->
            val raw = apiGet("/wefeed-h5api-bff/ranking-list/content?id=$id&page=1&perPage=24")
            toSubjectList(raw)
        }

        return pools
            .distinctBy { it["detailPath"]?.toString() }
            .filter { (it["title"]?.toString() ?: "").contains(query, ignoreCase = true) }
            .mapNotNull { toSearchResponseFromSubject(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailPath = detailPathFromUrl(url)
        val raw = apiGet("/wefeed-h5api-bff/detail?detailPath=$detailPath")

        val root = tryParseJson<Map<String, Any?>>(raw) ?: throw ErrorLoadingException("Invalid detail response")
        val data = root["data"] as? Map<*, *> ?: throw ErrorLoadingException("Missing data")
        val subject = data["subject"] as? Map<*, *> ?: throw ErrorLoadingException("Missing subject")
        val resource = data["resource"] as? Map<*, *>

        val title = subject["title"]?.toString() ?: throw ErrorLoadingException("Title not found")
        val subjectId = subject["subjectId"]?.toString().orEmpty()
        val tvType = toTvType(toInt(subject["subjectType"]))
        val plot = subject["description"]?.toString()
        val poster = (subject["cover"] as? Map<*, *>)?.get("url")?.toString()
        val tags = subject["genre"]?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val year = subject["releaseDate"]?.toString()?.take(4)?.toIntOrNull()

        val seasons = (resource?.get("seasons") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()

        val isSeriesLike = seasons.isNotEmpty() && (toInt(seasons.first()["maxEp"]) ?: 0) > 1

        if (isSeriesLike) {
            val episodes = mutableListOf<Episode>()

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
                    episodes.add(newEpisode("$mainUrl/moviesDetail/$detailPath?sid=$subjectId&se=$seasonNo&ep=$ep") {
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
            }
        }

        return newMovieLoadResponse(title, url, tvType, "$mainUrl/movies/$detailPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en") {
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
        val detailPath = detailPathFromUrl(data)
        val sid = Regex("[?&](sid|id)=([^&]+)").find(data)?.groupValues?.getOrNull(2)
        val se = Regex("[?&]se=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "0"
        val ep = Regex("[?&]ep=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "0"

        val subjectId = if (!sid.isNullOrBlank()) sid else {
            val detRaw = apiGet("/wefeed-h5api-bff/detail?detailPath=$detailPath")
            val detRoot = tryParseJson<Map<String, Any?>>(detRaw)
            val detData = detRoot?.get("data") as? Map<*, *>
            val subject = detData?.get("subject") as? Map<*, *>
            subject?.get("subjectId")?.toString().orEmpty()
        }

        if (subjectId.isBlank()) return false

        val referer = "$mainUrl/movies/$detailPath"
        val playHeaders = baseHeaders + ("Referer" to referer)
        val playRaw = app.get("$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath", headers = playHeaders).text
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
