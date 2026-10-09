package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

// Pahe.ink memakai tema TieLabs + plugin IMDbWP untuk metadata (rating, genre, tahun).
// Halaman tidak di-render via JS, jadi cukup HTML scraping biasa.
class Pahe : MainAPI() {
    override var mainUrl = "https://pahe.ink"
    override var name = "Pahe"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "https://pahe.ink/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Film & Series Terbaru"
    )

    // -------------------------------------------------------------------------
    // List parsing
    // -------------------------------------------------------------------------

    private fun parseListItem(el: Element): SearchResponse? {
        val a = el.selectFirst("h2.post-box-title a[href], h2 a[href], h3 a[href]")
            ?: return null
        val href = a.attr("href").ifBlank { null } ?: return null
        val title = a.text().trim().ifBlank { null } ?: return null
        val poster = el.selectFirst("img.wp-post-image, img")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.ifBlank { null }

        val isSeries = title.contains("Season", true) ||
            title.contains("Episode", true) ||
            title.contains("Complete", true)

        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
    }

    private fun listNodes(doc: org.jsoup.nodes.Document): List<Element> {
        // Batasi ke kontainer listing utama agar widget sidebar tidak ikut terambil
        val scopes = doc.select("section.recent-blog .cat-box-content, div.post-listing .post-inner")
        val nodes = if (scopes.isNotEmpty()) {
            scopes.flatMap { it.select("article.item-list, li.timeline-post") }
        } else {
            doc.select("article.item-list, li.timeline-post")
        }
        return nodes
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val doc = app.get(targetUrl, headers = ua).document
        val home = listNodes(doc).mapNotNull { parseListItem(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return listNodes(doc).mapNotNull { parseListItem(it) }.distinctBy { it.url }
    }

    // -------------------------------------------------------------------------
    // Detail
    // -------------------------------------------------------------------------

    private fun cleanTitle(text: String): String = text
        .replace(
            Regex(
                "\\b(480p|720p|1080p|2160p|4K|BluRay|WEB[- ]?DL|WEB[- ]?HD|WEBRip|HDTV|HDRip|x264|x265|HEVC|Complete)\\b.*",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        .replace(Regex("\\s{2,}"), " ")
        .trim(' ', '-', '\u2013', '\u2014')

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document

        val rawTitle = doc.selectFirst(".imdbwp__title")?.text()?.trim()
            ?: doc.selectFirst("h1.post-title")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val title = cleanTitle(rawTitle).ifBlank { rawTitle }

        // Metadata dari plugin IMDbWP
        val headerText = doc.selectFirst("div.imdbwp__header")?.text().orEmpty()
        val releaseYear = Regex("(?:19|20)\\d{2}").find(headerText)?.value?.toIntOrNull()
        val rating = doc.selectFirst("span.imdbwp__star")?.text()?.trim()
            ?.replace(",", ".")?.toDoubleOrNull()

        val genreList = doc.selectFirst("div.imdbwp__meta")?.select("span")
            ?.getOrNull(1)?.text()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: doc.select("a[rel=category tag]").map { it.text().trim() }.filter { it.isNotBlank() }

        val synopsis = doc.selectFirst("div.imdbwp__teaser")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("div.entry > p")?.text()?.trim()

        val poster = doc.selectFirst("img.imdbwp__img")?.attr("src")?.ifBlank { null }
            ?: doc.selectFirst("div.entry img, img.wp-post-image, img")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }?.ifBlank { null }

        val isSeries = rawTitle.contains("Season", true) ||
            rawTitle.contains("Episode", true) ||
            rawTitle.contains("Complete", true)

        val blocks = doc.select("div.box-inner-block")
        val episodes = mutableListOf<Episode>()

        blocks.forEach { block ->
            val blockText = block.text()
            val epMatch = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(blockText)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull()
                val epName = "Episode ${epNum ?: ""}".trim()
                episodes.add(
                    newEpisode(url) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        }

        if (episodes.isNotEmpty() || isSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genreList
                this.year = releaseYear
                this.score = Score.from10(rating)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = genreList
            this.year = releaseYear
            this.score = Score.from10(rating)
        }
    }

    // -------------------------------------------------------------------------
    // Links (belum diproses penuh — link di balik intercelestial.com/?ht=...)
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = ua).document

        doc.select("a[href]").filter { el ->
            val href = el.attr("href")
            href.contains("drive.google") ||
                href.contains("mega.nz") ||
                href.contains("pixeldrain") ||
                href.contains("gofile") ||
                href.contains("1fichier") ||
                href.contains("racaty") ||
                href.contains("mediafire")
        }.mapNotNull { it.attr("href").ifBlank { null } }.forEach { link ->
            loadExtractor(link, data, subtitleCallback, callback)
        }

        doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }.forEach { src ->
            loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        return true
    }
}
