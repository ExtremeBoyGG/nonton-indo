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
    // Animasu adalah situs movie anime, bukan series episode
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Anime Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        // Fix: ambil hanya dari div.film-poster atau div.item yang punya class spesifik
        // Hindari section headers (h2/h3 untuk genre seperti "Drama", "Action")
        val home = doc.select("div.item, div.film-poster, div.ml-item").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            // Judul dari h2/h3 di DALAM item card, bukan section header
            val title = el.selectFirst("h2, h3, .title, span.film-title")?.text()?.trim()
                ?: a.attr("title").removePrefix("Permalink ke:").removePrefix("Permalink ke: ").trim().ifBlank { null }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null } ?: img.attr("src").takeUnless { it.startsWith("data:") }
            }
            newAnimeSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster }
        }.distinctBy { it.url }.ifEmpty {
            // Fallback: ambil dari article tapi filter ketat
            doc.select("article").mapNotNull { article ->
                // Skip article yang hanya berisi link section header
                val links = article.select("a[href]")
                if (links.size == 1 && article.selectFirst("img") == null) return@mapNotNull null
                val a = article.selectFirst("h2 a, h3 a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                // Skip jika judul = nama genre (Action, Drama, dll)
                val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
                if (title.length < 4) return@mapNotNull null
                val poster = article.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { null } ?: img.attr("src").takeUnless { it.startsWith("data:") }
                } ?: return@mapNotNull null // kalau ga ada gambar, skip (kemungkinan section header)
                newAnimeSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster }
            }.distinctBy { it.url }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, div.item").mapNotNull { el ->
            if (el.selectFirst("img") == null) return@mapNotNull null
            val a = el.selectFirst("h2 a, h3 a, a[rel=bookmark]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { null } ?: img.attr("src").takeUnless { it.startsWith("data:") }
            }
            newAnimeSearchResponse(title, href, TvType.AnimeMovie) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        // Judul: h1 di halaman detail, strip prefix "Nonton Sub Indo" dan suffix genre
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("^Nonton\\s+(Sub\\s+Indo\\s+)?", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*\\|.*$"), "") // strip "| ANIMASU"
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("img[src*=uploads]")?.attr("src")?.ifBlank { null }
        val description = doc.selectFirst("p:not(:has(a)):not(:has(b))")?.text()?.trim()
        val genres = doc.select("a[href*=/category/]").map { it.text() }.filter { it.isNotBlank() }
        val year = doc.selectFirst("a[href*=/year/]")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            posterUrl = poster
            plot = description
            this.tags = genres
            this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document

        // Cari iframe langsung
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (src.startsWith("http")) loadExtractor(src, data, subtitleCallback, callback)
        }

        // Cari di dalam script tag — video sumber biasanya ada di JS
        doc.select("script").forEach { script ->
            val html = script.html()
            // Pattern umum: file: "https://..." atau src: "https://..."
            Regex("""(?:file|src|source)['":\s]+["'](https?://[^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""")
                .findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    loadExtractor(videoUrl, data, subtitleCallback, callback)
                }
            // Pattern embed URL
            Regex("""["'](https?://(?:embed\.|player\.|stream\.)[^"']+)["']""")
                .findAll(html).forEach { match ->
                    loadExtractor(match.groupValues[1], data, subtitleCallback, callback)
                }
        }

        return true
    }
}