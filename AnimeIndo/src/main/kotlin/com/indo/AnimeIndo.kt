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

    // AnimeIndo homepage: link langsung ke episode (bukan series page)
    // Format URL episode: anime-indo.lol/judul-anime-episode-1/
    // Format URL anime:   anime-indo.lol/anime/judul-anime/
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru"
    )

    private fun isEpisodeUrl(url: String) = url.contains("-episode-", ignoreCase = true)
    private fun isAnimeUrl(url: String) = url.contains("/anime/")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("a[href]").filter { a ->
            val href = a.attr("href")
            // Ambil link episode atau anime, skip navigasi
            (isEpisodeUrl(href) || isAnimeUrl(href)) &&
            href.contains(mainUrl) &&
            a.selectFirst("img") != null
        }.mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            // Judul dari img alt
            val title = img.attr("alt").trim().ifBlank { null }
                ?: return@mapNotNull null

            // Kalau episode URL, convert ke anime URL untuk scraping
            val animeUrl = if (isEpisodeUrl(href)) {
                // Coba cari link ke halaman anime di sekitarnya
                val seriesLink = a.parent()?.selectFirst("a[href*=/anime/]")?.attr("href")
                seriesLink ?: href // fallback ke episode URL
            } else href

            newAnimeSearchResponse(title, fixUrl(animeUrl), TvType.Anime) {
                this.posterUrl = img.attr("src").takeUnless { it.contains("loading") }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("a[href]").filter { a ->
            a.selectFirst("img[alt]") != null && a.attr("href").contains(mainUrl)
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

        val title = document.selectFirst("h1, h2.title, .entry-title, h2")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("img[src*=upload], img.poster, div.poster img")
            ?.attr("src")?.takeUnless { it.contains("loading") }

        val description = document.selectFirst("div.sinopsis, div.desc, p.description")?.text()?.trim()
        val genres = document.select("a[href*=genre]").map { it.text() }.filter { it.isNotBlank() }
        val year = document.selectFirst("span:contains(Tahun), span:contains(Year)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Cari semua link episode dari halaman ini
        val episodes = document.select("a[href]").filter { el ->
            isEpisodeUrl(el.attr("href")) && el.attr("href").contains(mainUrl)
        }.mapNotNull { el ->
            val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = el.text().trim().ifBlank {
                el.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
            }
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("-(\\d+)/?$").find(href.trimEnd('/'))?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) { this.name = epText; this.episode = ep }
        }.distinctBy { it.data }.sortedBy { it.episode }

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

        // Cari iframe
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (src.startsWith("http")) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // Cari video URL di script tags
        document.select("script").forEach { script ->
            val html = script.html()
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(html).forEach { match ->
                    loadExtractor(match.groupValues[1], data, subtitleCallback, callback)
                }
            Regex("""["'](https?://(?:embed\.|player\.|stream\.|video\.)[^"']{10,})["']""")
                .findAll(html).forEach { match ->
                    loadExtractor(match.groupValues[1], data, subtitleCallback, callback)
                }
        }

        return true
    }
}