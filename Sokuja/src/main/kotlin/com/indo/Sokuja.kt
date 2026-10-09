package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Sokuja : MainAPI() {
    override var mainUrl = "https://x6.sokuja.uk"
    override var name = "Sokuja"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/?page=" to "Update Terbaru",
        "$mainUrl/anime/?status=ongoing&order=update&page=" to "Sedang Tayang (Ongoing)",
        "$mainUrl/anime/?order=update&page=" to "Anime Terbaru",
        "$mainUrl/anime/?status=completed&order=update&page=" to "Tamat (Completed)",
        "$mainUrl/anime/?type=movie&order=update&page=" to "Anime Movie"
    )

    private fun parseQuality(format: String?): Int {
        if (format == null) return Qualities.Unknown.value
        return when {
            format.contains("1080") -> Qualities.P1080.value
            format.contains("720")  -> Qualities.P720.value
            format.contains("480")  -> Qualities.P480.value
            format.contains("360")  -> Qualities.P360.value
            else                    -> Qualities.Unknown.value
        }
    }

    private fun extractPoster(element: Element): String? {
        val src = element.selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("srcSet").split(" ").firstOrNull() }
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

    private fun toUpdateSearchResponse(element: Element, postersMap: Map<String, String>): SearchResponse? {
        val href = element.attr("href").ifBlank { null } ?: return null
        val epSlug = href.trim('/').substringAfterLast('/')
        if (epSlug.isBlank() || epSlug.contains("list-mode")) return null

        val cleanAnimeSlug = if (epSlug.contains("-episode-")) {
            epSlug.replace(Regex("-episode-\\d+-"), "-")
        } else {
            epSlug
        }

        val animeUrl = fixUrl("/anime/$cleanAnimeSlug/")

        val title = element.selectFirst("h3, h2")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val epText = element.selectFirst("span:contains(EP), span:contains(Episode), div.flex span")?.text()?.trim()
        val epNum = Regex("""(?:EP|Episode)\s*<!--\s*-->\s*(\d+)""", RegexOption.IGNORE_CASE).find(element.html())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:EP|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val baseSlug = cleanAnimeSlug.replace("-subtitle-indonesia", "")
        val verticalPoster = postersMap[cleanAnimeSlug]
            ?: postersMap[baseSlug]
            ?: postersMap.entries.firstOrNull { it.key.contains(baseSlug) }?.value
            ?: extractPoster(element)

        return newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = verticalPoster?.let { fixUrl(it) }
            addSub(epNum)
        }
    }

    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href*='/anime/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.contains("/list-mode/")) return null

        val title = element.selectFirst("h3, h2")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val poster = extractPoster(element)
        val score = element.selectFirst("span.text-yellow-400, span.rating")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()
        val typeBadge = element.selectFirst("span.uppercase")?.text()?.trim().orEmpty()
        val tvType = if (typeBadge.contains("MOVIE", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            score?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.startsWith("$mainUrl/?page=")) {
                "$mainUrl/"
            } else if (request.data.endsWith("page=")) {
                "${request.data}1"
            } else {
                request.data
            }
        } else {
            if (request.data.endsWith("page=")) {
                "${request.data}$page"
            } else {
                "${request.data}&page=$page"
            }
        }
        val doc = app.get(url, headers = headers).document

        if (request.name == "Update Terbaru") {
            val postersMap = mutableMapOf<String, String>()
            doc.select("a[href*='/anime/']").forEach { a ->
                val href = a.attr("href").trimEnd('/')
                val slug = href.substringAfterLast('/')
                if (slug.isNotBlank() && !slug.contains("list-mode") && !slug.contains("order=")) {
                    val p = extractPoster(a)
                    if (!p.isNullOrBlank()) {
                        postersMap[slug] = p
                    }
                }
            }

            val utSection = doc.selectFirst("section:has(h2:contains(Update Terbaru))")
            val cards = utSection?.select("a.group.block") ?: doc.select("a.group.block[href*='-episode-']")
            val items = cards.mapNotNull { toUpdateSearchResponse(it, postersMap) }
                .distinctBy { it.url }
            return newHomePageResponse(request.name, items)
        }

        val items = doc.select("a.group.block[href*='/anime/'], div.grid a[href*='/anime/']")
            .mapNotNull { toSearchResponse(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val apiUrl = "$mainUrl/api/search?q=${q.replace(" ", "+")}"
        try {
            val jsonText = app.get(apiUrl, headers = headers).text
            val json = JSONObject(jsonText)
            val results = json.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val list = mutableListOf<SearchResponse>()
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val slug = item.optString("slug")
                    val title = item.optString("title")
                    if (slug.isBlank() || title.isBlank()) continue

                    val itemUrl = fixUrl("/anime/$slug/")
                    val poster = item.optString("thumbnailUrl").ifBlank { item.optString("coverUrl") }
                    val type = item.optString("type")
                    val tvType = if (type.contains("MOVIE", true)) TvType.AnimeMovie else TvType.Anime
                    val score = item.optString("score").toDoubleOrNull()

                    list.add(newAnimeSearchResponse(title, itemUrl, tvType) {
                        this.posterUrl = if (poster.isNotBlank()) fixUrl(poster) else null
                        score?.let { this.score = Score.from10(it) }
                    })
                }
                return list
            }
        } catch (_: Exception) {}

        val doc = app.get("$mainUrl/?s=${q.replace(" ", "+")}", headers = headers).document
        return doc.select("a.group.block[href*='/anime/'], div.grid a[href*='/anime/']")
            .mapNotNull { toSearchResponse(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        var finalUrl = url
        var doc = app.get(finalUrl, headers = headers).document

        // If an episode page was opened directly, resolve to series page
        if (!finalUrl.contains("/anime/")) {
            val seriesHref = doc.selectFirst("a[href*='/anime/']:contains(Semua Episode), nav a[href*='/anime/'], a[href*='/anime/']:not([href*='list-mode']):not([href*='order='])")?.attr("href")
            if (!seriesHref.isNullOrBlank()) {
                finalUrl = fixUrl(seriesHref)
                doc = app.get(finalUrl, headers = headers).document
            }
        }

        val rawHtml = doc.html()

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: "Anime"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img.object-cover, div[class*='aspect-'] img, img[alt]")?.let { extractPoster(it) }
        val description = doc.selectFirst("div.rounded-xl p, p.synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(
            doc.select("div.flex:has(dt:contains(Tahun)) dd, div.flex.flex-wrap").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val score = doc.selectFirst("span.text-yellow-400")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()

        val epJsonRegex = Regex("""\\?"id\\?":\s*(\d+),\s*\\?"slug\\?":\s*\\?"([^\\"]+)\\?",\s*\\?"title\\?":\s*\\?"([^\\"]+)\\?",\s*\\?"episodeNumber\\?":\s*(\d+)""")
        val jsonMatches = epJsonRegex.findAll(rawHtml).toList()

        val episodes = if (jsonMatches.isNotEmpty()) {
            jsonMatches.mapNotNull { match ->
                val epId = match.groupValues[1]
                val slug = match.groupValues[2]
                val epTitle = match.groupValues[3]
                val epNum = match.groupValues[4].toIntOrNull() ?: 1

                if (slug.contains("batch", true) && jsonMatches.size > 1) return@mapNotNull null

                val epUrl = fixUrl("/$slug/")
                newEpisode("$epUrl::$epId") {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.distinctBy { it.data }.sortedBy { it.episode }
        } else {
            doc.select("div.space-y-1 a, a[href*='-episode-']").mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epText = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
                val epNum = Regex("""(?:episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""-episode-(\d+)-""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(href) {
                    this.name = epText
                    this.episode = epNum
                }
            }.distinctBy { it.data }.sortedBy { it.episode }
        }

        val typeText = doc.select("dt:contains(Tipe) + dd, dt:contains(Type) + dd").text().trim()
        val isMovie = episodes.size <= 1 && (typeText.contains("Movie", true) || (typeText.isBlank() && finalUrl.contains("-movie-", true)))

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)

        if (isMovie) {
            val streamData = if (episodes.isNotEmpty()) episodes.first().data else finalUrl
            return newMovieLoadResponse(title, finalUrl, TvType.AnimeMovie, streamData) {
                this.posterUrl = tracker?.image ?: poster
                this.backgroundPosterUrl = tracker?.cover ?: poster
                this.plot = description
                this.tags = genres
                this.year = year
                score?.let { this.score = Score.from10(it) }
                addMalId(tracker?.malId)
                addAniListId(tracker?.aniId?.toIntOrNull())
            }
        }

        return newAnimeLoadResponse(title, finalUrl, TvType.Anime) {
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = tracker?.cover ?: poster
            this.plot = description
            this.tags = genres
            this.year = year
            score?.let { this.score = Score.from10(it) }
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
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
        var epId = parts.getOrNull(1)

        if (epId.isNullOrBlank()) {
            try {
                val docHtml = app.get(pageUrl, headers = headers).text
                val m = Regex("""\\?"episodeId\\?":\s*(\d+)""").find(docHtml)
                if (m != null) {
                    epId = m.groupValues[1]
                }
            } catch (_: Exception) {}
        }

        if (!epId.isNullOrBlank()) {
            try {
                val apiUrl = "$mainUrl/api/video-mirrors?e=$epId"
                val jsonText = app.get(apiUrl, headers = headers).text
                val json = JSONObject(jsonText)
                val mirrors = json.optJSONArray("mirrors")
                if (mirrors != null && mirrors.length() > 0) {
                    var found = false
                    for (i in 0 until mirrors.length()) {
                        val mirror = mirrors.getJSONObject(i)
                        val embedUrl = mirror.optString("embedUrl")
                        val serverName = mirror.optString("serverName", "SOKUJA")
                        val qualityText = mirror.optString("quality", "")
                        val embedType = mirror.optString("embedType")

                        if (embedUrl.isBlank()) continue

                        val quality = parseQuality(qualityText)
                        if (embedType == "mp4" || embedUrl.contains(".mp4", true)) {
                            callback(
                                newExtractorLink(
                                    this.name,
                                    "$name ($qualityText - $serverName)".trim(),
                                    embedUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = "$mainUrl/"
                                }
                            )
                            found = true
                        } else {
                            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                            found = true
                        }
                    }
                    if (found) return true
                }
            } catch (_: Exception) {}
        }

        return false
    }
}
