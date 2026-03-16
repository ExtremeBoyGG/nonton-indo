package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Nimegami : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Anime Terbaru"
    )

    private fun getImg(el: org.jsoup.nodes.Element): String? {
        val img = el.selectFirst("img") ?: return null
        val dataSrc = img.attr("data-src")
        if (dataSrc.isNotBlank()) return dataSrc
        val src = img.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:")) return src
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, headers = ua).document
        val home = doc.select("article, div.item, div.animepost").mapNotNull { article ->
            if (getImg(article) == null) return@mapNotNull null
            val a = article.selectFirst("h2 a, h3 a, a[rel=bookmark]")
                ?: article.select("a[href]").firstOrNull { it.text().isNotBlank() }
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = getImg(article) }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = ua).document
        return doc.select("article, div.item, div.animepost").mapNotNull { article ->
            if (getImg(article) == null) return@mapNotNull null
            val a = article.selectFirst("h2 a, h3 a, a[rel=bookmark]")
                ?: article.select("a[href]").firstOrNull { it.text().isNotBlank() }
                ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = getImg(article) }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = ua).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.thumb img, div.poster img, img.wp-post-image")
            ?.let { img -> getImg(img.parent() ?: doc) }

        val description = doc.selectFirst("div.entry-content > p, div.sinopsis p, div.desc")?.text()?.trim()
        val genres = doc.select("a[rel=tag], div.genre a").map { it.text() }.filter { it.isNotBlank() }
        val year = doc.selectFirst("span:contains(Tahun), span:contains(Year)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = doc.select("a[href*=-episode-]").mapNotNull { el ->
            val href = el.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epName = el.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = epName; this.episode = ep }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data, headers = ua).document

        // Link download langsung ada di HTML — div.download li atau div.batch-dlcuy li
        // Struktur: <li><strong>720p</strong><a href="...">Berkasdrive</a><a href="...">Krakenfiles</a></li>
        doc.select("div.download li, div.batch-dlcuy li").forEach { li ->
            val quality = fixQuality(li.selectFirst("strong")?.text() ?: "")
            li.select("a[href]").forEach { a ->
                val href = a.attr("href").ifBlank { null } ?: return@forEach
                loadExtractor(fixUrl(href), data, subtitleCallback) { link ->
                    callback.invoke(
                        newExtractorLink(
                            link.name,
                            "${link.name} (${a.text()})",
                            link.url,
                        ) {
                            this.referer = link.referer
                            this.quality = quality
                            this.isM3u8 = link.isM3u8
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }

        return true
    }

    private fun fixQuality(str: String): Int {
        return when (str.uppercase()) {
            "4K" -> com.lagradost.cloudstream3.utils.Qualities.P2160.value
            "1080P", "FULLHD" -> com.lagradost.cloudstream3.utils.Qualities.P1080.value
            "720P" -> com.lagradost.cloudstream3.utils.Qualities.P720.value
            "480P" -> com.lagradost.cloudstream3.utils.Qualities.P480.value
            "360P" -> com.lagradost.cloudstream3.utils.Qualities.P360.value
            else -> Regex("(\\d{3,4})p?", RegexOption.IGNORE_CASE).find(str)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        }
    }
}