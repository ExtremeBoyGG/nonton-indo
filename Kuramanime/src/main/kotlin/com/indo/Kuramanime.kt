package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    // Each section has its own paginated URL
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document

        val home = doc.select("div.product__item").mapNotNull { item ->
            // The link wrapping the poster image
            val a = item.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href").ifBlank { null } ?: return@mapNotNull null)

            // Title: h5 below the image, or inside the product item
            val title = item.selectFirst("h5")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst("a:last-of-type")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            // Poster: div with class set-bg using data-setbg attribute
            val poster = item.selectFirst(".set-bg")
                ?.attr("data-setbg")?.ifBlank { null }

            // Strip episode path from URL to get anime page URL
            val animeUrl = href.replace(Regex("/episode/\\d+.*$"), "")

            // Episode count from text like "Ep 13 / 26" inside the card
            val itemText = item.text()
            val epNum = Regex("Ep\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(itemText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=text"
        val doc = app.get(url).document

        return doc.select("div.product__item").mapNotNull { item ->
            val a = item.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href").ifBlank { null } ?: return@mapNotNull null)

            val title = item.selectFirst("h5")?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst("a:last-of-type")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val poster = item.selectFirst(".set-bg")
                ?.attr("data-setbg")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // Strip episode path if present
        val animeUrl = url.replace(Regex("/episode/.*$"), "")
        val doc = app.get(animeUrl).document

        // Title: inside anime__details__title h3
        val title = doc.selectFirst(".anime__details__title h3")?.text()?.trim()
            ?: doc.selectFirst("h3")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Unknown"

        // Poster: div.set-bg with data-setbg in anime__details__pic
        val poster = doc.selectFirst(".anime__details__pic.set-bg")
            ?.attr("data-setbg")?.ifBlank { null }
            ?: doc.selectFirst(".set-bg")?.attr("data-setbg")?.ifBlank { null }

        // Description: inside anime__details__text p
        val description = doc.selectFirst(".anime__details__text p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Genres: from the anime__details__widget
        val genres = doc.select(".anime__details__widget a[href*=/properties/genre/]")
            .map { it.text().replace(",", "").trim() }
            .filter { it.isNotBlank() }

        // Episodes are HTML-encoded inside the data-content attribute of a#episodeLists
        // e.g. data-content="&lt;a class='btn ...' href='...episode/1'&gt;Ep 1&lt;/a&gt;..."
        val episodeHtml = doc.selectFirst("a#episodeLists")?.attr("data-content") ?: ""
        val epDoc = Jsoup.parse(episodeHtml)
        val episodes = epDoc.select("a[href*=/episode/]").mapNotNull { a ->
            val epHref = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            // Extract episode number from URL
            val epNum = Regex("/episode/(\\d+)").find(epHref)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(epHref)) {
                this.name = "Episode ${epNum ?: epText}"
                this.episode = epNum
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.getElementsByTag("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            val fullSrc = if (src.startsWith("//")) "https:$src" else src
            loadExtractor(fullSrc, data, subtitleCallback, callback)
        }

        doc.getElementsByTag("source").forEach { source ->
            val src = source.attr("src").ifBlank { null } ?: return@forEach
            callback.invoke(
                newExtractorLink(this.name, this.name, src) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val downloadSection = doc.selectFirst("div#animeDownloadLink")
        if (downloadSection != null) {
            var currentQuality = Qualities.Unknown.value

            downloadSection.children().forEach { element ->
                if (element.tagName() == "h6") {
                    val currentQualityText = element.text()
                    currentQuality = when {
                        currentQualityText.contains("1080") -> Qualities.P1080.value
                        currentQualityText.contains("720") -> Qualities.P720.value
                        currentQualityText.contains("480") -> Qualities.P480.value
                        currentQualityText.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                } else if (element.tagName() == "a") {
                    val href = element.attr("href").ifBlank { null } ?: return@forEach
                    val serverName = element.text().trim()

                    if (href.contains("pixeldrain.com")) {
                        val pdId = Regex("pixeldrain\\.com/d/(\\w+)").find(href)?.groupValues?.getOrNull(1)
                            ?: Regex("pixeldrain\\.com/u/(\\w+)").find(href)?.groupValues?.getOrNull(1)
                        if (pdId != null) {
                            callback(newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$pdId") {
                                this.quality = currentQuality
                                this.referer = data
                            })
                        }
                    } else if (serverName.contains("kDrive", ignoreCase = true) || serverName.contains("kTurbo", ignoreCase = true)) {
                        try {
                            val kdriveDoc = app.get(href).document
                            val filename = kdriveDoc.selectFirst("h2.drive-file-label")?.text()?.trim()
                            val dataDomain = kdriveDoc.selectFirst("button.check-status")?.attr("data-domain")

                            if (dataDomain != null && filename != null) {
                                val path = java.net.URI(href).path
                                val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                                val streamUrl = "${dataDomain.trimEnd('/')}$path/$encodedFilename"

                                callback.invoke(
                                    newExtractorLink("KuramaDrive", serverName, streamUrl) {
                                        this.referer = ""
                                        this.quality = currentQuality
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            // Ignore fetch errors
                        }
                    } else {
                        loadExtractor(href, data, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }
}
