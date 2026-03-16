package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
        "$mainUrl/page/" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Page 1 = root URL, page 2+ = /page/N/ (butuh trailing slash)
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url).document

        // Selector: a[href] di dalam div.menu, filter yang punya child div.list-anime
        // :has() gak support di Jsoup, jadi pakai filter manual
        val home = document.select("div.menu a[href]").mapNotNull { a ->
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Judul ada di <p>, bukan <h2>/<h3>
            val title = inner.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            // Gambar pakai data-original (lazy load)
            val poster = inner.selectFirst("img")?.let { img ->
                img.attr("data-original").ifBlank { null } ?: img.attr("src").takeUnless { it.contains("loading") }
            }

            val epNum = inner.selectFirst("span.eps")?.text()?.trim()?.toIntOrNull()

            // Ini link ke episode — kita tampilkan sebagai anime search result
            // URL episode: /judul-anime-episode-N/
            // Kita perlu convert ke URL anime: /anime/judul-anime/
            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, fixUrl(animeUrl), TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }

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
            loadExtractor(fullUrl, data, subtitleCallback, callback)
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