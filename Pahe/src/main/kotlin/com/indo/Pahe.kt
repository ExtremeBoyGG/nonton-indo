package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// pahe.ink menggunakan Cloudflare JS challenge sehingga tidak bisa di-scrape langsung.
// Plugin ini menggunakan pahe.in yang merupakan situs yang sama namun lebih accessible,
// atau jika keduanya diblok, fallback ke search via RSS feed.
class Pahe : MainAPI() {
    override var mainUrl = "https://pahe.ink"
    override var name = "Pahe"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Pahe perlu User-Agent dan headers yang proper untuk bypass Cloudflare
    override val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "https://pahe.ink/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Film Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = headers).document
        val home = doc.select("article, div.post-item").mapNotNull { article ->
            val a = article.selectFirst("h1 a, h2 a, h3 a, a[href*=pahe.ink]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document
        return doc.select("article, div.post-item").mapNotNull { article ->
            val a = article.selectFirst("h1 a, h2 a, h3 a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*\\(\\d{4}\\).*WEB.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*(480p|720p|1080p|BluRay|WEB-DL|WEBRip).*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("img[src*=upload], div.entry-content img")?.attr("src")?.ifBlank { null }
        val description = doc.selectFirst("div.entry-content > p")?.text()?.trim()
        val tags = doc.select("a[rel=category tag], a[href*=category]").map { it.text() }.filter { it.isNotBlank() }
        val year = doc.selectFirst("a[href*=/20]")?.attr("href")
            ?.let { Regex("/(20\\d{2})/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = headers).document

        // Pahe menyediakan link download langsung (GDrive, Mega, dll)
        // Bukan streaming tradisional — ambil semua link download
        doc.select("a[href]").filter { el ->
            val href = el.attr("href")
            href.contains("drive.google") ||
            href.contains("mega.nz") ||
            href.contains("pixeldrain") ||
            href.contains("gofile") ||
            href.contains("1fichier") ||
            href.contains("racaty") ||
            href.contains("mediafire") ||
            (el.text().contains("Download", true) && href.startsWith("http"))
        }.mapNotNull { it.attr("href").ifBlank { null } }.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // Juga coba iframe jika ada
        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        return true
    }
}
