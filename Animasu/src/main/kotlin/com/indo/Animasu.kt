package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Animasu : MainAPI() {
    override var mainUrl = "https://animasuid.com"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Anime Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val home = doc.select("article, div.item-anime").mapNotNull { article ->
            // Ambil link yang punya teks (bukan link gambar "Permalink ke:")
            val a = article.select("a[href]").firstOrNull { el ->
                el.text().isNotBlank() && !el.attr("title").startsWith("Permalink ke:")
            } ?: article.selectFirst("a[href]") ?: return@mapNotNull null

            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            // Bersihkan "Permalink ke: " dari judul
            val title = a.text().trim().ifBlank {
                a.attr("title").removePrefix("Permalink ke:").removePrefix("Permalink ke: ").trim()
            }.ifBlank { null } ?: return@mapNotNull null

            // Cari gambar real (bukan base64 placeholder)
            val poster = article.selectFirst("img")?.let { img ->
                val src = img.attr("src")
                if (src.startsWith("data:")) null else src.ifBlank { null }
            } ?: article.selectFirst("img")?.attr("data-src")?.ifBlank { null }

            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, div.item-anime").mapNotNull { article ->
            val a = article.select("a[href]").firstOrNull { it.text().isNotBlank() }
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.let { img ->
                val src = img.attr("src")
                if (src.startsWith("data:")) null else src.ifBlank { null }
            }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.poster img, div.thumb img, img.wp-post-image")?.let { img ->
            val src = img.attr("src")
            if (src.startsWith("data:")) img.attr("data-src").ifBlank { null } else src.ifBlank { null }
        }

        val description = doc.selectFirst("div.entry-content > p, div.sinopsis p")?.text()?.trim()
        val genres = doc.select("a[rel=tag], span:contains(Genre) a").map { it.text() }
        val year = doc.selectFirst("span:contains(Tahun), span:contains(Year)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = doc.select("a[href*=-episode-]").mapNotNull {
            val href = it.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = it.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = name; this.episode = ep }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }
        doc.select("div[data-src], source[src]").mapNotNull {
            it.attr("data-src").ifBlank { null } ?: it.attr("src").ifBlank { null }
        }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }
        return true
    }
}
