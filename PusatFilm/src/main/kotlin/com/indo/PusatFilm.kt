package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
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
        return raw.replace(Regex("""^Nonton\s+(?:Film|Serial)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*/\s*PUSATFILM21.*$""", RegexOption.IGNORE_CASE), "")
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
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

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

        val rawTitle = doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: name
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='tmdb.org']")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("img.poster")?.attr("src")?.ifBlank { null }

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: doc.selectFirst("p.leading-relaxed, .synopsis, [itemprop='description']")?.text()?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.select(".text-sm, .meta").text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/series/")

        return if (isSeries) {
            val episodes = doc.select("a[href*='/season/'][href*='/episode/']").mapNotNull { a ->
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
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageText = app.get(data, headers = headers).text

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
            extractKotakAjaib(share, data, subtitleCallback, callback)
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
