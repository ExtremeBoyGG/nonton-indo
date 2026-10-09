package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Nimegami : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?sort=updated" to "Anime Terbaru",
        "$mainUrl/anime/?status=RELEASING&sort=updated" to "Sedang Tayang (Ongoing)",
        "$mainUrl/anime/?sort=score" to "Paling Populer"
    )

    private fun parseQuality(format: String): Int {
        return when {
            format.contains("1080") -> Qualities.P1080.value
            format.contains("720")  -> Qualities.P720.value
            format.contains("480")  -> Qualities.P480.value
            format.contains("360")  -> Qualities.P360.value
            format.contains("240")  -> Qualities.P240.value
            else                    -> Qualities.Unknown.value
        }
    }

    private fun extractPoster(element: Element): String? {
        val src = element.selectFirst("img.poster-image, img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        } ?: return null

        val encoded = Regex("""[?&]url=([^&]+)""").find(src)?.groupValues?.getOrNull(1)
        return if (encoded != null) {
            val decoded = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
            fixUrl(decoded)
        } else {
            fixUrl(src)
        }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = element.selectFirst("a.poster-link, a.card-title, a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.selectFirst("a.card-title, h2, h3")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = extractPoster(element)
        val ratingText = element.selectFirst(".score-badge, span.rating")?.text()
            ?.replace(Regex("[^0-9.]"), "")
        val rating = ratingText?.toDoubleOrNull()
        val format = element.selectFirst(".format-badge")?.text()?.trim().orEmpty()
        val tvType = if (format.contains("MOVIE", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            rating?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "${request.data}&page=1"
        } else {
            "${request.data}&page=$page"
        }
        val doc = app.get(url, headers = ua).document
        val items = doc.select("article.anime-card, div.anime-grid article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime/?q=${query.trim().replace(" ", "+")}"
        val doc = app.get(url, headers = ua).document
        return doc.select("article.anime-card, div.anime-grid article").mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s*Sub Indo.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: "Anime"

        val poster = doc.selectFirst("img.poster-image, img[alt]")?.let { extractPoster(it) }
        val description = doc.selectFirst("section#informasi p, div.synopsis p, p.synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val genres = doc.select("a[href*='/category/']").map { it.text().replace("#", "").trim() }
            .filter { it.isNotBlank() }.distinct()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(
            doc.selectFirst(".card-meta, .anime-meta, section#informasi")?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val score = doc.selectFirst(".score-badge")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        val isMovie = doc.selectFirst(".format-badge")?.text()?.contains("MOVIE", true) == true

        val epElements = doc.select("[id^=download_episode_]")
        val episodes = epElements.mapNotNull { el ->
            val epId = el.attr("id").removePrefix("download_episode_")
            val epNum = epId.toIntOrNull()
            val epName = el.selectFirst("h3")?.text()?.trim() ?: "Episode $epId"
            newEpisode("$url::$epId") {
                this.name = epName
                this.episode = epNum
            }
        }.sortedBy { it.episode }

        if (isMovie || episodes.size <= 1) {
            val streamData = if (episodes.isNotEmpty()) episodes.first().data else "$url::1"
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, streamData) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.year = year
            score?.let { this.score = Score.from10(it) }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("::")
        val pageUrl = parts[0]
        val epId = parts.getOrNull(1) ?: "1"

        val doc = app.get(pageUrl, headers = ua).document
        val container = doc.selectFirst("#download_episode_$epId")
            ?: doc.selectFirst("#download")
            ?: doc

        var found = false
        val rows = container.select(".download-row")
        val downloadRows = if (rows.isNotEmpty()) rows else listOf(container)

        for (row in downloadRows) {
            val qualityText = row.selectFirst(".quality-label strong")?.text()?.trim() ?: ""
            val quality = parseQuality(qualityText)

            for (a in row.select("a[href*='/download/']")) {
                val href = a.attr("href").ifBlank { null } ?: continue
                val mirrorName = a.text().trim().ifBlank { "Download" }
                val fullHref = fixUrl(href)

                try {
                    val resp = app.get(fullHref, headers = ua, allowRedirects = false)
                    val location = resp.headers["location"] ?: resp.headers["Location"]
                    if (!location.isNullOrBlank()) {
                        if (location.contains("berkasdrive.com")) {
                            val directStreamUrl = location
                                .replace("/hal/download/", "/public/download/")
                                .replace("/hal/streaming/", "/public/download/")

                            callback(
                                newExtractorLink(
                                    this.name,
                                    "$name ($qualityText - $mirrorName)".trim(),
                                    directStreamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = "$mainUrl/"
                                }
                            )
                            found = true
                        } else {
                            loadExtractor(location, pageUrl, subtitleCallback, callback)
                            found = true
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return found
    }
}