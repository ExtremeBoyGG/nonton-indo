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




class LK21 : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest" to "Film Terbaru",
        "$mainUrl/populer" to "Film Populer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "${request.data}/"
        } else {
            "${request.data}/page/$page"
        }

        val doc = app.get(url, headers = headers).document
        val items = doc.select("article[itemscope][itemtype*='Movie'], article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?s=${query.trim().replace(" ", "+")}"
        val doc = app.get(searchUrl, headers = headers).document
        val body = doc.selectFirst("body")
        val apiBase = body?.attr("data-search_url")?.ifBlank { null } ?: "https://gudangvape.com/"
        val thumbBase = body?.attr("data-thumbnail_url")?.ifBlank { null } ?: "https://poster.assetsy.de/wp-content/uploads/"

        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val apiUrl = "${apiBase.trimEnd('/')}/search.php?s=$encodedQuery&page=1"

        val apiRes = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            ).text
        } catch (_: Exception) {
            null
        }

        if (!apiRes.isNullOrBlank()) {
            val json = try { JSONObject(apiRes) } catch (_: Exception) { null }
            val data = json?.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val slug = item.optString("slug").trim().removePrefix("/")
                    if (slug.isBlank()) continue
                    if (item.optString("type").equals("series", ignoreCase = true)) continue

                    val href = "$mainUrl/$slug"
                    val rawTitle = item.optString("title").ifBlank { slug }
                    val title = rawTitle.replace(Regex("""\(\d{4}\).*$"""), "").trim().ifBlank { rawTitle }
                    val posterPath = item.optString("poster")
                    val posterUrl = if (posterPath.isNotBlank()) {
                        if (posterPath.startsWith("http")) posterPath else "${thumbBase.trimEnd('/')}/$posterPath"
                    } else null
                    val year = item.optInt("year").takeIf { it > 0 }
                    val rating = item.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 }

                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.year = year
                        rating?.let { this.score = Score.from10(it) }
                    })
                }
                if (results.isNotEmpty()) {
                    return results
                }
            }
        }

        return doc.select("article[itemscope][itemtype*='Movie'], article").mapNotNull { toSearchResponse(it) }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = element.selectFirst("figure a[itemprop=url], a[itemprop=url], figure a, a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.selectFirst("h3.poster-title, [itemprop=name], h3")?.text()?.trim()
            ?: link.attr("title")
                .replace(Regex("^(?:Nonton movie|Nonton|Streaming)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*streaming gratis$", RegexOption.IGNORE_CASE), "")
                .trim()
        if (title.isBlank()) return null

        val poster = element.selectFirst("picture source[srcset]")?.attr("srcset")
            ?: element.selectFirst("img[itemprop=image], img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }
        val score = element.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val year = element.selectFirst("span.year, [itemprop=datePublished]")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            score?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("^(?:Lk21\\s*|Nonton\\s*)", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Sub Indo.*$", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: "Movie"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("picture img, figure img, .poster img")?.attr("src")

        val plot = doc.selectFirst("div.synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

        val year = doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            ?: doc.selectFirst("div.detail p:contains(Release)")?.text()?.let {
                Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val tags = doc.select("main a[href*='/genre/'], .meta-info a[href*='/genre/']").map { it.text().trim() }.distinct()
        val score = doc.selectFirst("[itemprop=ratingValue]")?.text()?.toDoubleOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            score?.let { this.score = Score.from10(it) }
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
            val newSources = org.json.JSONArray()
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

    private suspend fun extractHydrax(
        embedUrl: String,
        playerUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val areq = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to playerUrl
                )
            ).text

            val enc = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(areq)?.groupValues?.getOrNull(1) ?: return false

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
                    "Hydrax",
                    "Hydrax $type",
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

        val playerElements = doc.select("#player-list li a")
        val playerList = if (playerElements.isNotEmpty()) {
            playerElements.mapNotNull { a ->
                val playerUrl = a.attr("data-url").ifBlank { a.attr("href") }
                if (playerUrl.isNotBlank()) {
                    val name = a.attr("data-server").ifBlank { a.text().trim() }.ifBlank { "Server" }
                    Pair(name, playerUrl)
                } else null

            }
        } else {
            val iframeSrc = doc.selectFirst("iframe#main-player")?.attr("src")
                ?: doc.selectFirst("iframe[src*='videonode']")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) {
                listOf(Pair("Main", iframeSrc))
            } else emptyList()
        }

        // Prioritize Hydrax servers first
        val sortedPlayerList = playerList.sortedByDescending { (name, url) ->
            name.contains("hydrax", ignoreCase = true) || url.contains("hydrax", ignoreCase = true)
        }

        var foundAny = false

        for ((serverName, playerUrl) in sortedPlayerList) {
            try {
                val match = Regex("""/iframe3/([^/]+)/([^/?&]+)""").find(playerUrl)
                if (match != null) {
                    val host = match.groupValues[1]
                    val id = match.groupValues[2]

                    val postRes = app.post(
                        "https://videonode.de/api.php",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to playerUrl,
                            "Origin" to "https://videonode.de"
                        ),
                        data = mapOf("host" to host, "id" to id)
                    ).text

                    val embedUrl = try { JSONObject(postRes).optString("embedUrl") } catch (e: Exception) { null }
                    if (!embedUrl.isNullOrBlank()) {
                        if (host.equals("hydrax", ignoreCase = true) || embedUrl.contains("abyssplayer") || embedUrl.contains("playhydrax")) {
                            val hydraxFound = extractHydrax(embedUrl, playerUrl, callback)
                            if (hydraxFound) {
                                foundAny = true
                            }
                        } else if (embedUrl.contains("playcdn.de")) {
                            val slug = embedUrl.substringAfterLast("/").substringBefore("?")
                            val verifyRes = app.get(
                                "https://playcdn.de/verify/$slug",
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                    "Referer" to embedUrl,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            ).text

                            val fileUrl = try { JSONObject(verifyRes).optString("fileUrl") } catch (e: Exception) { null }
                            if (!fileUrl.isNullOrBlank()) {
                                try {
                                    val m3u8Links = com.lagradost.cloudstream3.utils.M3u8Helper.generateM3u8(
                                        this.name,
                                        fileUrl,
                                        "https://playcdn.de/"
                                    )
                                    if (m3u8Links.isNotEmpty()) {
                                        m3u8Links.forEach { callback(it) }
                                    } else {
                                        callback(
                                            newExtractorLink(
                                                this.name,
                                                "$name ($serverName)",
                                                fileUrl,
                                                type = ExtractorLinkType.M3U8
                                            ) {
                                                this.referer = "https://playcdn.de/"
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    callback(
                                        newExtractorLink(
                                            this.name,
                                            "$name ($serverName)",
                                            fileUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://playcdn.de/"
                                        }
                                    )
                                }
                                foundAny = true
                            }
                        } else {
                            loadExtractor(embedUrl, playerUrl, subtitleCallback, callback)
                            foundAny = true
                        }
                    }
                } else {
                    loadExtractor(playerUrl, data, subtitleCallback, callback)
                    foundAny = true
                }
            } catch (e: Exception) {
                // Abaikan server bermasalah, lanjutkan ke server berikutnya
            }
        }

        return foundAny
    }
}

