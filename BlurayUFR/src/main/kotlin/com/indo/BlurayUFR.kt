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

class BlurayUFR : MainAPI() {
    override var mainUrl = "https://blurayufr.site"
    override var name = "BlurayUFR"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Film Terbaru",
        "$mainUrl/genre/box-office" to "Box Office",
        "$mainUrl/best-rating" to "Best Rating",
        "$mainUrl/tv" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) {
            "$base/"
        } else {
            "$base/page/$page/"
        }

        val doc = app.get(url, headers = headers).document
        val items = doc.select("article.item-infinite, article.item").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val doc = app.get(url, headers = headers).document
        return doc.select("article.item-infinite, article.item").mapNotNull { toSearchResponse(it) }
    }

    private fun cleanPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace(Regex("""-\d+x\d+(?=\.(?:jpg|jpeg|png|webp))""", RegexOption.IGNORE_CASE), "")
    }

    private fun cleanTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""^Permalink to:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Sub(?:title)?\s*Indo(?:nesia)?.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val linkEl = element.selectFirst("h2.entry-title a, .content-thumbnail a, a[href]") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null

        val rawTitle = element.selectFirst("h2.entry-title a, h2.entry-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = element.selectFirst(".content-thumbnail img, img")?.let { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            cleanPoster(fixUrlNull(src))
        }

        val score = element.selectFirst(".gmr-rating-item")?.text()?.trim()?.toDoubleOrNull()
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val qualityText = element.selectFirst(".gmr-quality-item a, .gmr-quality-item")?.text()?.trim()

        val isSeries = href.contains("/tv/") || href.contains("/eps/") || element.selectFirst(".gmr-numbeps") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
                if (!qualityText.isNullOrBlank()) {
                    addQuality(qualityText)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "BlurayUFR"
        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("figure.pull-left img, .gmr-movie-data img")?.let { img ->
            cleanPoster(fixUrlNull(img.attr("src").ifBlank { img.attr("data-src") }))
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanPoster(it) }

        val plot = doc.select(".entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Download ", ignoreCase = true) }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val score = doc.selectFirst("[itemprop='ratingValue']")?.text()?.trim()?.toDoubleOrNull()

        val year = doc.selectFirst(".gmr-moviedata time, .gmr-moviedata a[href*='/year/']")?.text()?.let {
            Regex("""(\d{4})""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val tags = doc.select(".gmr-moviedata a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = doc.select("article.item, .gmr-related-post article")
            .mapNotNull { toSearchResponse(it) }

        val episodeElements = doc.select(".gmr-listseries a[href*='/eps/']")
        val isSeries = url.contains("/tv/") || url.contains("/eps/") || episodeElements.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeElements.mapIndexedNotNull { index, epEl ->
                val epUrl = fixUrlNull(epEl.attr("href")) ?: return@mapIndexedNotNull null
                val epName = epEl.text().trim().ifBlank { "Episode ${index + 1}" }

                val sMatch = Regex("""S(\d+)\s*Eps?\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                val eMatch = Regex("""Eps(?:isode)?\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                val urlMatch = Regex("""season-(\d+)-episode-(\d+)""", RegexOption.IGNORE_CASE).find(epUrl)

                val seasonNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: urlMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1
                val epNum = sMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: eMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: urlMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: (index + 1)

                newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun sanitizeAbyssPayload(enc: String): String? {
        return try {
            val rawBytes = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val rawJsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val json = JSONObject(rawJsonStr)
            val userId = json.optString("user_id")
            val slug = json.optString("slug")
            val md5Id = json.optString("md5_id")
            val mediaStr = json.optString("media")
            if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || mediaStr.isBlank()) return null

            val keyStr = "$userId:$slug:$md5Id"
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            val md5Hex = md5.joinToString("") { "%02x".format(it) }
            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val mediaBytes = ByteArray(mediaStr.length) { mediaStr[it].code.toByte() }
            val cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(mediaBytes)
            val decryptedJsonStr = String(decryptedBytes, Charsets.UTF_8)

            val mediaObj = JSONObject(decryptedJsonStr)
            val mp4 = mediaObj.optJSONObject("mp4") ?: return null
            val sources = mp4.optJSONArray("sources") ?: return null
            val newSources = JSONArray()
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                if (src.has("sub") && src.optString("sub").isNotBlank()) {
                    newSources.put(src)
                }
            }
            mp4.put("sources", newSources)

            val newMediaStr = mediaObj.toString()
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val reEncryptedBytes = cipher.doFinal(newMediaStr.toByteArray(Charsets.UTF_8))
            val reEncryptedBin = StringBuilder(reEncryptedBytes.size)
            for (b in reEncryptedBytes) {
                reEncryptedBin.append((b.toInt() and 0xFF).toChar())
            }
            json.put("media", reEncryptedBin.toString())
            val finalJsonBytes = json.toString().toByteArray(Charsets.ISO_8859_1)
            android.util.Base64.encodeToString(finalJsonBytes, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
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

    private suspend fun extractAbyss(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
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
            var decRes = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json",
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                json = JsonAsString(jsonPayload)
            ).text

            var decJson = JSONObject(decRes)
            var result = decJson.optJSONObject("result")
            if (result == null || decJson.optInt("status") != 200) {
                val sanitizedEnc = sanitizeAbyssPayload(enc)
                if (sanitizedEnc != null) {
                    val sanitizedPayload = JSONObject().put("text", sanitizedEnc).toString()
                    decRes = app.post(
                        "https://enc-dec.app/api/dec-abyss",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                            "Content-Type" to "application/json",
                            "Origin" to "https://playhydrax.com",
                            "Referer" to "https://playhydrax.com/"
                        ),
                        json = JsonAsString(sanitizedPayload)
                    ).text
                    decJson = JSONObject(decRes)
                    result = decJson.optJSONObject("result")
                }
            }

            if (result == null) return false
            val sources = result.optJSONArray("sources") ?: return false

            val parsedSources = mutableListOf<Pair<ExtractorLink, Int>>()
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
                val link = newExtractorLink(
                    "Abyss",
                    "Abyss $type",
                    srcUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf(
                        "Referer" to "https://abyssplayer.com/"
                    )
                }
                parsedSources.add(Pair(link, q))
            }

            var found = false
            parsedSources.sortedByDescending { it.second }.forEach { (link, _) ->
                callback(link)
                found = true
            }
            return found
        } catch (_: Exception) {
            return false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document

        val iframeUrls = mutableSetOf<String>()

        doc.select(".gmr-embed-responsive iframe, iframe[src*='abyss'], iframe[src*='hydrax']").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            fixUrlNull(src)?.let { iframeUrls.add(it) }
        }

        val tabLinks = doc.select(".muvipro-player-tabs a[href*='player=']")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()

        for (tabUrl in tabLinks) {
            if (tabUrl == data) continue
            try {
                val tabDoc = app.get(tabUrl, headers = headers).document
                tabDoc.select(".gmr-embed-responsive iframe, iframe[src*='abyss'], iframe[src*='hydrax']").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    fixUrlNull(src)?.let { iframeUrls.add(it) }
                }
            } catch (_: Exception) {
            }
        }

        var foundAny = false
        for (embedUrl in iframeUrls) {
            if (embedUrl.contains("abyssplayer") || embedUrl.contains("hydrax") || embedUrl.contains("abysscdn")) {
                if (extractAbyss(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            } else {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
