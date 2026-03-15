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

    // Homepage menampilkan episode terbaru, bukan daftar anime
    // Struktur: <a href="/episode-url/"><img alt="Judul Anime" src="loading.gif">Judul ep</a>
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("a[href]").filter { a ->
            // Filter: link yang punya img dengan alt (judul anime)
            a.selectFirst("img[alt]") != null &&
            !a.attr("href").contains("anime-list") &&
            !a.attr("href").contains("list-genre") &&
            !a.attr("href").contains("movie") &&
            !a.attr("href").contains("jadwal")
        }.mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            // Judul ada di img alt karena gambar lazy-load
            val title = a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null
            // Gambar lazy-load, poster tidak tersedia di homepage
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("a[href]").filter { a ->
            a.selectFirst("img[alt]") != null
        }.mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: a.text().trim().ifBlank { null }
                ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, h2.title, .anime-title")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("img.poster, div.poster img, .thumb img, img[src*=upload]")
            ?.attr("src")?.ifBlank { null }

        val description = document.selectFirst("div.sinopsis, div.desc, p.desc")?.text()?.trim()
        val genres = document.select("a[href*=genre], a[href*=list-genre]").map { it.text() }.filter { it.isNotBlank() }

        val year = document.selectFirst("span:contains(Tahun), span:contains(Year), td:contains(Tahun)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list dari halaman anime (bukan episode page)
        val episodes = document.select("a[href*=episode]").mapNotNull { el ->
            val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("episode", ignoreCase = true)) return@mapNotNull null
            val name = el.text().trim().ifBlank { el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null }
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("-(\\d+)$").find(href.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) { this.name = name; this.episode = ep }
        }.distinctBy { it.data }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe").mapNotNull { it.attr("src").ifBlank { null }?.let { fixUrl(it) } }
        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        // Cari server links jika ada
        document.select("a[href*=play], a[href*=stream], a[data-video]").mapNotNull {
            it.attr("href").ifBlank { null } ?: it.attr("data-video").ifBlank { null }
        }.forEach { link ->
            loadExtractor(fixUrl(link), data, subtitleCallback, callback)
        }
        return true
    }
}
