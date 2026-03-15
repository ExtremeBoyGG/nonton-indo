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
        val document = app.get(request.data + page).document

        // Selector: a yang mengandung div.list-anime
        val home = document.select("div.menu a:has(div.list-anime)").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null

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
        return document.select("div.menu a:has(div.list-anime)").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val inner = a.selectFirst("div.list-anime") ?: return@mapNotNull null
            val title = inner.selectFirst("p")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val poster = inner.selectFirst("img")?.attr("data-original")?.ifBlank { null }
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // Kalau URL episode, redirect ke halaman anime
        val animeUrl = if (url.contains("/anime/")) url else episodeToAnimeUrl(url)
        val document = app.get(animeUrl).document

        val title = document.selectFirst("h2")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        // Gambar di halaman anime: td.vithumb img
        val poster = document.selectFirst("td.vithumb img, .detail img")
            ?.attr("src")?.ifBlank { null }

        val description = document.selectFirst("p.des, div.videsc p")?.text()?.trim()

        val genres = document.select("div.detail li a").map { it.text() }.filter { it.isNotBlank() }

        // Episode list: div.ep a
        // Struktur: <div class="ep"><a href="/judul-episode-1/">1</a><a href="/judul-episode-2/">2</a></div>
        val episodes = document.select("div.ep a").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim()
            val ep = epText.toIntOrNull()
                ?: Regex("(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

        // Cari iframe player — ada di #tontonin atau div.nonton iframe
        document.select("#tontonin, div.nonton iframe, iframe[src]").forEach { el ->
            val src = when {
                el.tagName() == "iframe" -> el.attr("src")
                else -> el.attr("src")
            }.ifBlank { null } ?: return@forEach
            if (src.startsWith("http")) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // Cari server buttons — struktur AnimeIndo pakai form input.server
        // <input class="server" type="button" value="Server 1" onClick="ganti('URL')">
        document.select("input.server[onclick], .server[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            // Format: ganti('https://...')  atau  nonton('https://...')
            val url = Regex("""(?:ganti|nonton)\(['"]([^'"]+)['"]\)""").find(onclick)
                ?.groupValues?.getOrNull(1)?.ifBlank { null } ?: return@forEach
            loadExtractor(fixUrl(url), data, subtitleCallback, callback)
        }

        return true
    }
}