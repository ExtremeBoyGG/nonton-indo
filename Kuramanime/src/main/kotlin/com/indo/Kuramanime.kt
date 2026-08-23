package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v20.kuramanime.ing"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val authValue = "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur"

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

        val episodes = mutableListOf<Episode>()
        var currentDoc = doc

        while (true) {
            val episodeHtml = currentDoc.selectFirst("a#episodeLists")?.attr("data-content") ?: ""
            val epDoc = Jsoup.parse(episodeHtml)

            epDoc.select("a[href*=/episode/]").forEach { a ->
                val epHref = a.attr("href").ifBlank { null } ?: return@forEach
                val epText = a.text().trim().ifBlank { null } ?: return@forEach
                if (epText.contains("Terlama") || epText.contains("Terbaru")) return@forEach

                val epNum = Regex("/episode/(\\d+)").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = "Episode ${epNum ?: epText}"
                        this.episode = epNum
                    }
                )
            }

            val nextPagePath = epDoc.selectFirst("a.page__link__episode:has(i.fa-forward)")?.attr("href")
            if (nextPagePath != null) {
                currentDoc = app.get(fixUrl(nextPagePath)).document
            } else {
                break
            }
        }

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = app.get(data).document
        val html = doc.outerHtml()

        try {
            val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            val kk = Regex("data-kk=\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)

            if (!csrf.isNullOrBlank() && !kk.isNullOrBlank()) {
                val cfg = app.get("$mainUrl/assets/js/$kk.js").text

                fun cfgValue(key: String): String =
                    Regex("$key:\\s*'([^']+)'").find(cfg)?.groupValues?.getOrNull(1) ?: ""

                val authParam = cfgValue("MIX_AUTH_ROUTE_PARAM")
                val pageTokenKey = cfgValue("MIX_PAGE_TOKEN_KEY")
                val serverKey = cfgValue("MIX_STREAM_SERVER_KEY")
                val fuckId = "${cfgValue("MIX_AUTH_KEY")}:${cfgValue("MIX_AUTH_TOKEN")}"

                if (authParam.isNotBlank() && pageTokenKey.isNotBlank() && serverKey.isNotBlank()) {
                    val requestId = (1..6).map { ('a'..'z').random() }.joinToString("")
                    val token = app.get(
                        "$mainUrl/assets/$authParam",
                        headers = mapOf(
                            "X-Fuck-ID" to fuckId,
                            "X-Request-ID" to requestId,
                            "X-Request-Index" to "0"
                        )
                    ).text.trim()

                    if (token.isNotBlank()) {
                        val postDoc = app.post(
                            "$data?$pageTokenKey=$token&$serverKey=kuramadrive&page=1",
                            headers = mapOf(
                                "Accept" to "text/html, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest",
                                "X-CSRF-TOKEN" to csrf,
                                "Origin" to mainUrl,
                                "Referer" to data
                            ),
                            data = mapOf("authorization" to authValue)
                        ).document

                        postDoc.select("video#player source[src]").forEach { source ->
                            val src = source.attr("src").ifBlank { null } ?: return@forEach
                            val q = source.attr("size").toIntOrNull()
                            callback(newExtractorLink("KuramaDrive", "KuramaDrive ${q ?: ""}p".trim(), src) {
                                this.quality = when (q) {
                                    1080 -> Qualities.P1080.value
                                    720 -> Qualities.P720.value
                                    480 -> Qualities.P480.value
                                    360 -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                                this.referer = mainUrl
                            })
                            found = true
                        }

                        var currentQuality = Qualities.Unknown.value
                        postDoc.selectFirst("#animeDownloadLink")?.children()?.forEach { element ->
                            if (element.tagName() == "h6") {
                                val t = element.text()
                                currentQuality = when {
                                    t.contains("1080") -> Qualities.P1080.value
                                    t.contains("720") -> Qualities.P720.value
                                    t.contains("480") -> Qualities.P480.value
                                    t.contains("360") -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                            } else {
                                element.select("a[href]").forEach { a ->
                                    val href = a.attr("href").ifBlank { null } ?: return@forEach
                                    val pdId = Regex("pixeldrain\\.com/[du]/(\\w+)").find(href)?.groupValues?.getOrNull(1)
                                    if (pdId != null) {
                                        callback(newExtractorLink("PixelDrain", "PixelDrain", "https://pixeldrain.com/api/file/$pdId") {
                                            this.quality = currentQuality
                                            this.referer = mainUrl
                                        })
                                        found = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        if (!found) {
            doc.getElementsByTag("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { null } ?: return@forEach
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fullSrc, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
