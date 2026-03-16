package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Homepage menampilkan episode terbaru
    // Struktur: <a href="/judul-episode-N/"><div class="list-anime">
    //               <img data-original="..."><p>Judul</p><span class="eps">N</span>
    //           </div></a>
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie/page/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovie = request.data.contains("/movie/")

        val url = if (isMovie) {
            if (page == 1) "$mainUrl/movie/" else "$mainUrl/movie/page/$page/"
        } else {
            if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        }
        val document = app.get(url).document

        val home = if (isMovie) {
            // Movie page: table.otable > tr > td.vithumb (poster) + td.videsc (info)
            document.select("table.otable").mapNotNull { table ->
                val link = table.selectFirst("td.vithumb a[href]") ?: return@mapNotNull null
                val href = link.attr("href").ifBlank { null } ?: return@mapNotNull null
                val poster = link.selectFirst("img")?.attr("src")?.ifBlank { null }?.let { fixUrl(it) }
                val desc = table.selectFirst("td.videsc") ?: return@mapNotNull null
                val title = desc.selectFirst("a[href]")?.text()?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null
                newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        } else {
            // Episode page: div.menu a[href] > div.list-anime
            document.select("div.menu a[href]").mapNotNull { a ->
                val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

                val title = inner.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                    ?: return@mapNotNull null

                val poster = inner.selectFirst("img")?.let { img ->
                    img.attr("data-original").ifBlank { null } ?: img.attr("src").takeUnless { it.contains("loading") }
                }

                val epNum = inner.selectFirst("span.eps")?.text()?.trim()?.toIntOrNull()

                val animeUrl = episodeToAnimeUrl(href)

                newAnimeSearchResponse(title, fixUrl(animeUrl), TvType.Anime) {
                    this.posterUrl = poster
                    addSub(epNum)
                }
            }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home)
    }

    // Convert URL episode ke URL anime
    // /jigokuraku-2nd-season-episode-10/ → /anime/jigokuraku-2nd-season/
    private fun episodeToAnimeUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val animeSlug = Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        return "$mainUrl/anime/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search.php?q=$query").document
        return document.select("div.menu a[href]").mapNotNull { a ->
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = inner.selectFirst("p")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = inner.selectFirst("img")?.attr("data-original")?.ifBlank { null }
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // Kalau URL episode, ambil URL anime dari link "Semua Episode" di halaman
        val isEpisode = !url.contains("/anime/")
        val episodeDoc = if (isEpisode) app.get(url).document else null

        // Dari HTML: <a href="/anime/ikoku-nikki/">Semua Episode</a>
        val animeUrl = episodeDoc?.selectFirst("div.navi a[href*=/anime/]")?.attr("href")
            ?.let { fixUrl(it) }
            ?: if (url.contains("/anime/")) url
            else episodeToAnimeUrl(url)

        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.title, h2.title, h1, h2")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.detail img, td.vithumb img")
            ?.attr("src")?.ifBlank { null }?.let { fixUrl(it) }

        val description = document.selectFirst("div.detail p, p.des")?.text()?.trim()
        val genres = document.select("div.detail li a").map { it.text() }.filter { it.isNotBlank() }

        // Episode list: div.ep a — teks berisi nomor episode
        val episodes = document.select("div.ep a[href]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim()
            val ep = epText.toIntOrNull()
                ?: Regex("(\\d+)").find(href.trimEnd('/').substringAfterLast("/"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) { this.name = "Episode $epText"; this.episode = ep }
        }.sortedBy { it.episode }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = genres
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
        val document = app.get(data).document

        // Kumpulkan semua server URLs dari data-video attribute
        // Struktur: <a class="server" data-video="URL">Nama Server</a>
        val serverUrls = mutableListOf<String>()

        // Default server dari iframe src
        document.selectFirst("iframe#tontonin")?.attr("src")?.ifBlank { null }?.let {
            serverUrls.add(it)
        }

        // Server tambahan dari a.server[data-video]
        document.select("a.server[data-video]").forEach { a ->
            val url = a.attr("data-video").ifBlank { null } ?: return@forEach
            if (!serverUrls.contains(url)) serverUrls.add(url)
        }

        // Load semua server
        serverUrls.forEach { url ->
            val fullUrl = if (url.startsWith("/")) "$mainUrl$url" else url
            if (fullUrl.contains("btube3.php")) {
                // Internal player (btube3.php) — ambil direct video URL dari <source> tag
                try {
                    val playerDoc = app.get(fullUrl).document
                    val videoSrc = playerDoc.selectFirst("video source[src]")?.attr("src")
                    if (!videoSrc.isNullOrBlank()) {
                        callback(
                            newExtractorLink(
                                "AnimeIndo",
                                "B-TUBE",
                                videoSrc
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = data
                            }
                        )
                    }
                } catch (_: Exception) {}
            } else if (fullUrl.contains("xtwap.top")) {
                // CEPAT server — parse JWPlayer source dari JavaScript
                try {
                    val html = app.get(fullUrl).text
                    val fileMatch = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    val filePath = fileMatch?.groupValues?.getOrNull(1)
                    if (!filePath.isNullOrBlank()) {
                        val videoUrl = if (filePath.startsWith("/")) "https://xtwap.top$filePath" else filePath
                        callback(
                            newExtractorLink(
                                "AnimeIndo",
                                "CEPAT",
                                videoUrl
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = fullUrl
                            }
                        )
                    }
                } catch (_: Exception) {}
            } else {
                // External servers (blogger.com, gdplayer.to, dll)
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        // Download link dari .navi (biasanya GDrive)
        document.select("div.navi a[href]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            if (href.startsWith("http") && !href.contains(mainUrl)) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        return true
    }
}