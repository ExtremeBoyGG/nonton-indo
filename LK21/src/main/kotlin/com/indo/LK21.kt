package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val doc = app.get(url, headers = headers).document
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
                    val name = a.text().trim().ifBlank { "Server" }
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

        var foundAny = false

        for ((serverName, playerUrl) in playerList) {
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
                        if (embedUrl.contains("playcdn.de")) {
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
