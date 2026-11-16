package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PusatFilm : MainAPI() {
    override var mainUrl = "https://new1.pusatfilm21info.com"
    override var name = "PusatFilm"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movie?sort=release" to "Film Terbaru",
        "$mainUrl/series?sort=release" to "Serial TV Terbaru",
        "$mainUrl/movie?sort=popular" to "Film Populer",
        "$mainUrl/series?sort=popular" to "Serial Populer",
        "$mainUrl/movie?sort=rating" to "Rating Tertinggi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) {
            base
        } else {
            if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
        }

        val doc = app.get(url, headers = headers).document
        val seenUrls = mutableSetOf<String>()
        val items = doc.select(".card-grid a[href*='/movie/'], .card-grid a[href*='/series/'], a[href*='/movie/'], a[href*='/series/']").mapNotNull {
            if (it.parents().any { p -> p.attr("aria-roledescription") == "slide" }) return@mapNotNull null
            val href = it.attr("href").trim()
            if (href.isBlank() || seenUrls.contains(href)) return@mapNotNull null
            val img = it.selectFirst("img") ?: return@mapNotNull null
            seenUrls.add(href)
            toSearchResponse(it)
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search?q=$encoded"
        val doc = app.get(url, headers = headers).document
        val seenUrls = mutableSetOf<String>()
        return doc.select("a[href*='/movie/'], a[href*='/series/']").mapNotNull {
            val href = it.attr("href").trim()
            if (href.isBlank() || seenUrls.contains(href)) return@mapNotNull null
            if (it.selectFirst("img") == null) return@mapNotNull null
            seenUrls.add(href)
            toSearchResponse(it)
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""^Nonton\s+(?:Film|Serial)?\s*(?:sub\s*indo(?:nesia)?)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:sub\s*indo(?:nesia)?|subtitle\s*indonesia)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[|/]\s*PUSATFILM21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '/')
            .trim()
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val rawHref = element.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#")) return null
        val href = fixUrlNull(rawHref) ?: return null

        val img = element.selectFirst("img")
        val poster = img?.attr("src")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }

        val titleEl = element.selectFirst("h2, h3, h4, p.truncate, p[class*='text-text-primary']")
        val rawTitle = titleEl?.text()?.ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: element.text().trim()
        var title = cleanTitle(rawTitle)
        if (title.isBlank() || title.equals("Film", ignoreCase = true) || title.equals("Serial", ignoreCase = true)) {
            val alt = img?.attr("alt")?.ifBlank { null }
            if (!alt.isNullOrBlank() && !alt.equals("Film", ignoreCase = true) && !alt.equals("Serial", ignoreCase = true)) {
                title = cleanTitle(alt)
            }
        }
        if (title.isBlank()) return null

        val isSeries = href.contains("/series/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val rawTitle = doc.selectFirst("h1 img[alt]")?.attr("alt")?.ifBlank { null }
            ?: doc.selectFirst("h1 span[class*='text-4xl'], h1 span[class*='font-black']")?.text()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.ifBlank { null }
            ?: name
        val title = cleanTitle(rawTitle)

        val pageHtml = doc.html()

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='tmdb.org']")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("img.poster")?.attr("src")?.ifBlank { null }
            ?: Regex(""""posterPath"\s*:\s*"([^"\\]+)"""").find(pageHtml)?.groupValues?.getOrNull(1)?.let { "https://image.tmdb.org/t/p/w500$it" }

        val backdrop = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("p.leading-relaxed, .synopsis, [itemprop='description']")?.text()?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.select(".text-sm, .meta").text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val voteAvg = Regex(""""voteAverage"\s*:\s*"([0-9.]+)"""").find(pageHtml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val score = Score.from10(voteAvg)

        val isSeries = url.contains("/series/")

        return if (isSeries) {
            val seriesSlug = url.substringAfter("/series/").substringBefore("/").substringBefore("?")
            val nextFEpisodes = extractSeasonsFromHtml(pageHtml, seriesSlug)

            val episodes = if (nextFEpisodes.isNotEmpty()) {
                nextFEpisodes
            } else {
                doc.select("a[href*='/season/'][href*='/episode/']").mapNotNull { a ->
                    val epHref = a.attr("href").ifBlank { return@mapNotNull null }
                    val epUrl = fixUrlNull(epHref) ?: return@mapNotNull null
                    val epText = a.text().trim()

                    val sMatch = Regex("""/season/(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                        ?: Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                    val eMatch = Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                        ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(epText)

                    val sNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val eNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val titleMatch = Regex("""\b\d+m\s*(.+)$""").find(epText)?.groupValues?.getOrNull(1)?.trim()
                    val epName = titleMatch?.ifBlank { null }
                        ?: (if (eNum != null) "Episode $eNum" else epText.ifBlank { "Episode" })

                    newEpisode(epUrl) {
                        this.name = epName
                        this.season = sNum
                        this.episode = eNum
                    }
                }
            }

            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                episodes
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
                this.backgroundPosterUrl = backdrop?.let { fixUrlNull(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
                this.backgroundPosterUrl = backdrop?.let { fixUrlNull(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
    }

    private fun extractSeasonsFromHtml(html: String, seriesSlug: String): List<Episode> {
        val pushRegex = Regex("""self\.__next_f\.push\(\[1,"([\s\S]*?)"\]\)""")
        for (match in pushRegex.findAll(html)) {
            val rawChunk = match.groupValues[1]
            if (rawChunk.contains("seasons") && rawChunk.contains("seriesSlug")) {
                val unescaped = rawChunk.replace("\\\"", "\"").replace("\\\\", "\\")
                val seasonsIdx = unescaped.indexOf("\"seasons\":[")
                if (seasonsIdx == -1) continue

                val start = seasonsIdx + "\"seasons\":".length
                var depth = 0
                var inString = false
                var escape = false
                var end = -1

                for (i in start until unescaped.length) {
                    val c = unescaped[i]
                    if (escape) {
                        escape = false
                        continue
                    }
                    if (c == '\\') {
                        escape = true
                        continue
                    }
                    if (c == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        if (c == '[') depth++
                        else if (c == ']') {
                            depth--
                            if (depth == 0) {
                                end = i + 1
                                break
                            }
                        }
                    }
                }

                if (end != -1) {
                    try {
                        val seasonsJson = unescaped.substring(start, end)
                        val seasonsArray = JSONArray(seasonsJson)
                        val episodeList = mutableListOf<Episode>()

                        for (sIdx in 0 until seasonsArray.length()) {
                            val seasonObj = seasonsArray.optJSONObject(sIdx) ?: continue
                            val sNum = seasonObj.optInt("number", sIdx + 1)
                            val episodesArray = seasonObj.optJSONArray("episodes") ?: continue

                            for (eIdx in 0 until episodesArray.length()) {
                                val epObj = episodesArray.optJSONObject(eIdx) ?: continue
                                val eNum = epObj.optInt("number", eIdx + 1)
                                val epName = epObj.optString("name", "").ifBlank { "Episode $eNum" }
                                val epOverview = epObj.optString("overview", "").ifBlank { null }
                                val stillPath = epObj.optString("stillPath", "")
                                val stillUrl = if (stillPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else null
                                val embedUrl = epObj.optString("embedUrl", "")

                                val baseEpUrl = fixUrl("/series/$seriesSlug/season/$sNum/episode/$eNum")
                                val data = if (embedUrl.isNotBlank()) "$baseEpUrl#embedUrl=$embedUrl" else baseEpUrl

                                episodeList.add(newEpisode(data) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = eNum
                                    this.posterUrl = stillUrl
                                    this.description = epOverview
                                })
                            }
                        }
                        if (episodeList.isNotEmpty()) {
                            return episodeList
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedFromData = Regex("""#embedUrl=(https?://[^\s&]+)""").find(data)?.groupValues?.getOrNull(1)
        val directShare = embedFromData?.let { Regex("""kotakajaib\.me/(?:embed|file)/([a-zA-Z0-9_-]+)""").find(it)?.groupValues?.getOrNull(1) }
        val refererUrl = data.substringBefore("#")

        var foundAny = false
        if (!directShare.isNullOrBlank()) {
            extractKotakAjaib(directShare, refererUrl, subtitleCallback) { link ->
                foundAny = true
                callback(link)
            }
            if (foundAny) return true
        } else if (!embedFromData.isNullOrBlank()) {
            if (embedFromData.contains("playhydrax.com") || embedFromData.contains("abyssplayer.com")) {
                if (extractAbyss(embedFromData, refererUrl, subtitleCallback, callback)) return true
            } else {
                loadExtractor(embedFromData, refererUrl, subtitleCallback, callback)
                return true
            }
        }

        val pageText = try {
            app.get(refererUrl, headers = headers).text
        } catch (_: Exception) { "" }
        if (pageText.isBlank()) return foundAny

        val unescaped = pageText.replace("\\\"", "\"").replace("\\/", "/")

        val activeShare = Regex("""["']embedUrl["']:\s*["']https?://kotakajaib\.me/(?:embed|file)/([a-zA-Z0-9_-]+)""").find(unescaped)?.groupValues?.getOrNull(1)

        val shares = if (!activeShare.isNullOrBlank()) {
            listOf(activeShare)
        } else {
            Regex("""kotakajaib\.me/(?:embed|file)/([a-zA-Z0-9_-]+)""").findAll(unescaped)
                .map { it.groupValues[1] }
                .distinct()
                .take(2)
                .toList()
        }

        for (share in shares) {
            extractKotakAjaib(share, refererUrl, subtitleCallback, callback)
        }

        val otherEmbeds = Regex("""["']embedUrl["']:\s*["'](https?://[^"']+)["']""").findAll(unescaped)
            .map { it.groupValues[1] }
            .filter { !it.contains("kotakajaib.me") }
            .distinct()
            .toList()

        for (otherEmbed in otherEmbeds) {
            if (otherEmbed.contains("playhydrax.com") || otherEmbed.contains("abyssplayer.com")) {
                extractAbyss(otherEmbed, refererUrl, subtitleCallback, callback)
            } else {
                loadExtractor(otherEmbed, refererUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractKotakAjaib(
        share: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = "https://kotakajaib.me/embed/$share"
            val embedDoc = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to refererUrl
                )
            ).document

            val kp = embedDoc.selectFirst("#kp") ?: return
            val token = kp.attr("data-token").ifBlank { null } ?: return
            val providers = kp.select("button[data-p]").map { it.attr("data-p") }.filter { it.isNotBlank() }
                .distinct()
                .ifEmpty { listOf("hydrax", "gdriveplayer") }

            for (provider in providers) {
                try {
                    val encodedToken = URLEncoder.encode(token, "UTF-8")
                    val sourceUrl = "https://kotakajaib.me/embed/$share/source/$provider?t=$encodedToken"
                    val res = app.get(
                        sourceUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Referer" to embedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text

                    val resJson = JSONObject(res)
                    var targetUrl = resJson.optString("u").trim()
                    if (targetUrl.isBlank()) continue
                    if (targetUrl.startsWith("//")) {
                        targetUrl = "https:$targetUrl"
                    }

                    if (targetUrl.contains("playhydrax.com") || targetUrl.contains("abyssplayer.com")) {
                        extractAbyss(targetUrl, embedUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(targetUrl, embedUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun extractAbyss(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val areq = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to refererUrl
                )
            ).text

            val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(areq)?.groupValues?.getOrNull(1) ?: return false

            extractAbyssSubtitles(enc, subtitleCallback)

            val jsonPayload = JSONObject().put("text", enc).toString()
            val decRes = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json",
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                json = JsonAsString(jsonPayload)
            ).text

            val decJson = JSONObject(decRes)
            val result = decJson.optJSONObject("result") ?: return false
            val sources = result.optJSONArray("sources") ?: return false

            for (i in 0 until sources.length()) {
                val srcObj = sources.optJSONObject(i) ?: continue
                if (!srcObj.optBoolean("status", true)) continue
                val srcUrl = srcObj.optString("url")
                if (srcUrl.isBlank() || !srcUrl.startsWith("http")) continue
                val type = srcObj.optString("type")
                val q = when {
                    type.contains("1080") -> Qualities.P1080.value
                    type.contains("720") -> Qualities.P720.value
                    type.contains("480") -> Qualities.P480.value
                    type.contains("360") -> Qualities.P360.value
                    type.equals("Origin", true) -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        "HydraX",
                        "HydraX $type",
                        srcUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = q
                        this.referer = "https://abyssplayer.com/"
                        this.headers = mapOf("Referer" to "https://abyssplayer.com/")
                    }
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractAbyssSubtitles(enc: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val rawBytes = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val rawJsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val json = JSONObject(rawJsonStr)
            val md5Id = json.optString("md5_id")
            if (md5Id.isBlank()) return

            val subs = json.optJSONObject("config")?.optJSONArray("subtitles") ?: return
            for (i in 0 until subs.length()) {
                val subObj = subs.optJSONObject(i) ?: continue
                val subSlug = subObj.optString("slug")
                if (subSlug.isBlank()) continue
                val subType = subObj.optString("type", "srt").ifBlank { "srt" }
                val rawLang = subObj.optString("lang", "id").ifBlank { "id" }
                val langLabel = when (rawLang.lowercase()) {
                    "id", "ind", "indonesian", "indonesia" -> "Indonesian"
                    "en", "eng", "english" -> "English"
                    "ms", "may", "malay" -> "Malay"
                    else -> rawLang
                }
                val subUrl = "https://cdn.iamcdn.net/subtitle/$md5Id/$subSlug.$subType"
                subtitleCallback(newSubtitleFile(langLabel, subUrl))
            }
        } catch (_: Exception) {
        }
    }
}
