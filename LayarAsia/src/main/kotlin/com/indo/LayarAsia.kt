package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LayarAsia : MainAPI() {
    override var mainUrl = "https://server-1.layar.asia"
    override var name = "LayarAsia"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Rilisan Terbaru",
        "$mainUrl/series/?genre%5B%5D=drama-korea&status=&type=&order=update" to "Drakor",
        "$mainUrl/series/?country%5B%5D=china&status=&type=&order=update" to "Drachin",
        "$mainUrl/series/?type=movie&order=latest" to "Movie",
        "$mainUrl/series/?genre%5B%5D=varshow&order=update" to "Variety Show",
        "$mainUrl/series/?status=&type=&order=update" to "Semua Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) {
            base
        } else {
            if (base.contains("/series/")) {
                if (base.contains("?")) {
                    val parts = base.split("?", limit = 2)
                    "${parts[0].trimEnd('/')}/page/$page/?${parts[1]}"
                } else {
                    "${base.trimEnd('/')}/page/$page/"
                }
            } else {
                "${base.trimEnd('/')}/page/$page/"
            }
        }

        val doc = app.get(url, headers = headers).document
        val items = doc.select("article.bs, .listupd article, .film-item, article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = app.get(url, headers = headers).document
        return doc.select("article.bs, .listupd article, article").mapNotNull { toSearchResponse(it) }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""^Ep\s*\d+\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:Korea|China|JAP|Thailand|Taiwan)\s+\d{4}\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val a = element.selectFirst("a[href]") ?: return null
        val rawHref = a.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#")) return null
        val href = fixUrlNull(rawHref) ?: return null

        val img = element.selectFirst("img")
        val poster = img?.attr("src")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }

        val rawTitle = a.attr("title").ifBlank { null }
            ?: element.selectFirst(".tt, .title, h2, h3, h4")?.text()?.ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: a.text().trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val typeText = element.selectFirst(".typez, .type, .status")?.text()?.trim().orEmpty()
        val isMovie = typeText.contains("movie", ignoreCase = true) || href.contains("type=movie")
        val tvType = if (isMovie) TvType.Movie else TvType.AsianDrama

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
            }
        } else {
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster?.let { fixUrlNull(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, headers = headers).document

        // If the URL is an episode URL, look for the parent series link
        if (!url.contains("/series/")) {
            val seriesUrl = doc.select("a[href*='/series/']").firstOrNull { a ->
                val h = a.attr("href")
                !h.contains("?type=") && !h.contains("?genre") && !h.contains("?country") && !h.contains("?status")
            }?.attr("href")

            if (!seriesUrl.isNullOrBlank()) {
                val fullSeriesUrl = fixUrl(seriesUrl)
                try {
                    val sDoc = app.get(fullSeriesUrl, headers = headers).document
                    if (sDoc.select(".eplister li a, .episodelst li a").isNotEmpty()) {
                        doc = sDoc
                    }
                } catch (_: Exception) {
                }
            }
        }

        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: name
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".thumb img, .poster img")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }

        val plot = doc.selectFirst(".entry-content, .desc, [itemprop='description'], .sinopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.select(".spe, .info-content").text())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = doc.select(".genxed a, a[rel='tag'], .genre a").map { it.text().trim() }.filter { it.isNotBlank() }

        val epElements = doc.select(".eplister li a, .episodelst li a, ul.clstyle li a")

        val isMovie = epElements.size <= 1 && (
            doc.select(".spe, .info-content").text().contains("Movie", ignoreCase = true) ||
            tags.any { it.contains("movie", ignoreCase = true) } ||
            url.contains("type=movie")
        )

        val tmdb = TmdbHelper.getMetadata(title, isMovie, year)

        return if (isMovie) {
            val streamUrl = epElements.firstOrNull()?.attr("href")?.let { fixUrlNull(it) } ?: url
            newMovieLoadResponse(title, url, TvType.Movie, streamUrl) {
                this.posterUrl = tmdb?.posterUrl ?: poster?.let { fixUrlNull(it) }
                this.backgroundPosterUrl = tmdb?.backgroundPosterUrl
                this.plot = tmdb?.plot ?: plot
                this.year = year
                this.tags = tags
                if (tmdb?.actors?.isNotEmpty() == true) {
                    this.actors = tmdb.actors
                }
                if (tmdb != null && tmdb.voteAverage > 0) {
                    this.score = Score.from10(tmdb.voteAverage)
                }
            }
        } else {
            val episodes = epElements.mapNotNull { a ->
                val epHref = a.attr("href").ifBlank { return@mapNotNull null }
                val epUrl = fixUrlNull(epHref) ?: return@mapNotNull null
                val epTitle = a.text().trim()

                val epNumMatch = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle)
                    ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                    ?: Regex("""/(\d+)/?$""").find(epHref)
                val epNum = epNumMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

                val nameText = if (epNum != null) "Episode $epNum" else epTitle.ifBlank { "Episode" }

                newEpisode(epUrl) {
                    this.name = nameText
                    this.episode = epNum
                }
            }.reversed()

            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            } else {
                episodes
            }

            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, finalEpisodes) {
                this.posterUrl = tmdb?.posterUrl ?: poster?.let { fixUrlNull(it) }
                this.backgroundPosterUrl = tmdb?.backgroundPosterUrl
                this.plot = tmdb?.plot ?: plot
                this.year = year
                this.tags = tags
                if (tmdb?.actors?.isNotEmpty() == true) {
                    this.actors = tmdb.actors
                }
                if (tmdb != null && tmdb.voteAverage > 0) {
                    this.score = Score.from10(tmdb.voteAverage)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document

        val iframes = mutableListOf<String>()

        // 1. Direct iframe in embed_holder, pembed, player-embed
        doc.select("#embed_holder iframe[src], #pembed iframe[src], .player-embed iframe[src]").forEach {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                iframes.add(fixUrl(src))
            }
        }

        // 2. Mirror select options
        doc.select("select.mirror option, select[name='mirror'] option").forEach { opt ->
            val valStr = opt.attr("value").trim()
            extractSrcFromOption(valStr)?.let { iframes.add(fixUrl(it)) }
        }

        for (src in iframes.distinct()) {
            when {
                src.contains("byse") || src.contains("bysesukior") -> {
                    extractByse(src, subtitleCallback, callback)
                }
                src.contains("upns") || src.contains("strp2p") || src.contains("p2pstream") -> {
                    extractUpns(src, subtitleCallback, callback)
                }
                src.contains("abyssplayer") || src.contains("hydrax") || src.contains("abyss.to") || src.contains("playhydrax") -> {
                    extractAbyss(src, data, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun extractSrcFromOption(valStr: String): String? {
        if (valStr.isBlank()) return null
        val directMatch = Regex("""src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(valStr)?.groupValues?.getOrNull(1)
            ?: if (valStr.startsWith("http")) valStr else null
        if (!directMatch.isNullOrBlank()) return directMatch

        return try {
            val decoded = String(android.util.Base64.decode(valStr, android.util.Base64.DEFAULT), Charsets.UTF_8)
            Regex("""src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)?.trim()
                ?: if (decoded.startsWith("http")) decoded.trim() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun base64UrlDecode(str: String): ByteArray {
        var s = str.trim().replace('-', '+').replace('_', '/')
        val pad = s.length % 4
        if (pad > 0) {
            s += "=".repeat(4 - pad)
        }
        return android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    }

    private suspend fun extractByse(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val domain = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val code = Regex("""/e/([a-zA-Z0-9_-]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val apiUrl = "https://$domain/api/videos/$code/"

            val resp = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to embedUrl,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text.trim()

            val json = JSONObject(resp)
            val playback = json.optJSONObject("playback") ?: return false
            val ivStr = playback.optString("iv").ifBlank { return false }
            val payloadStr = playback.optString("payload").ifBlank { return false }
            val version = playback.optString("version").toIntOrNull() ?: 1
            val keyParts = playback.optJSONArray("key_parts") ?: return false
            if (keyParts.length() < 30) return false

            val idx1 = version
            val idx2 = 31 - version
            if (idx1 < 1 || idx1 > keyParts.length() || idx2 < 1 || idx2 > keyParts.length()) return false

            val part1 = keyParts.getString(idx1 - 1)
            val part2 = keyParts.getString(idx2 - 1)

            val keyBytes = base64UrlDecode(part1) + base64UrlDecode(part2)
            val ivBytes = base64UrlDecode(ivStr)
            val payloadBytes = base64UrlDecode(payloadStr)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(payloadBytes)
            val decryptedJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            val sources = decryptedJson.optJSONArray("sources") ?: return false
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val m3u8Url = source.optString("url").ifBlank { null } ?: continue
                val label = source.optString("label", "1080p")
                val quality = when {
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                val links = try {
                    M3u8Helper.generateM3u8(
                        "Byse",
                        m3u8Url,
                        "https://$domain/",
                        headers = mapOf("Referer" to "https://$domain/")
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                if (links.isNotEmpty()) {
                    links.forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            "Byse",
                            "Byse $label",
                            m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.referer = "https://$domain/"
                            this.headers = mapOf("Referer" to "https://$domain/")
                        }
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractUpns(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val hashId = if (embedUrl.contains("#")) {
                embedUrl.substringAfter("#", "").substringBefore("&").trim()
            } else {
                embedUrl.substringAfterLast("/").substringBefore("?").trim()
            }
            if (hashId.isBlank()) return false

            val domain = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val apiUrl = "https://$domain/api/v1/video?id=$hashId"

            val hexResp = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to "https://$domain/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text.trim()

            if (hexResp.isBlank() || hexResp.length % 32 != 0) return false

            val decryptedJson = decryptAes128Cbc(hexResp, "kiemtienmua911ca", "1234567890oiuytr") ?: return false
            val json = JSONObject(decryptedJson)
            val masterM3u8 = json.optString("cfNative").ifBlank { null } ?: json.optString("source").ifBlank { null } ?: return false

            val links = try {
                M3u8Helper.generateM3u8(
                    "UPNShare",
                    masterM3u8,
                    "https://$domain/",
                    headers = mapOf("Referer" to "https://$domain/")
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        "UPNShare",
                        "UPNShare HLS",
                        masterM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$domain/"
                        this.headers = mapOf("Referer" to "https://$domain/")
                    }
                )
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptAes128Cbc(hex: String, keyStr: String, ivStr: String): String? {
        return try {
            val key = keyStr.toByteArray(Charsets.UTF_8)
            val iv = ivStr.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val cipherBytes = ByteArray(hex.length / 2)
            for (i in 0 until hex.length step 2) {
                cipherBytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            }
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
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
