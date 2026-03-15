package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.Base64

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = ua).document
        val home = document.select("div.venz ul li, div.detpost, div.thumb").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.jdl")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime", headers = ua).document
        return document.select("ul.chivsrc li, div.venz ul li").mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, span.jdl")?.text()?.trim()
                ?: a.attr("title").ifBlank { null } ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.ifBlank { null }
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = ua).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*\\(Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("div.fotoanime img, div.thumb img")?.attr("src")?.ifBlank { null }
        val description = document.selectFirst("div.sinopc, div.sinopsis, div.desc")?.text()?.trim()
        val genres = document.select("div.infozingle p:contains(Genre) a").map { it.text() }
        val statusText = document.selectFirst("div.infozingle p:contains(Status)")
            ?.text()?.replace("Status:", "")?.trim() ?: ""
        val status = if (statusText.contains("Ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed
        val year = document.selectFirst("div.infozingle p:contains(Musim), div.infozingle p:contains(Tahun)")
            ?.text()?.let { Regex("\\b(20\\d{2})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val episodes = document.select("div.episodelist ul li, div.episodelist li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("episode[\\s-]*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = name; this.episode = ep }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), year, true)
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = genres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = ua).document

        // 1. Iframe langsung
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (src.startsWith("http")) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
        }

        // 2. Otakudesu: mirrorstream — data-content berisi base64 encoded iframe HTML
        document.select("ul.mlist li").forEach { li ->
            // Coba dari onclick attribute
            val onclick = li.selectFirst("a, span")?.attr("onclick") ?: ""
            val dataContent = li.selectFirst("[data-content]")?.attr("data-content") ?: ""
            val encoded = when {
                dataContent.isNotBlank() -> dataContent
                onclick.contains("\"") -> onclick.substringAfter("\"").substringBefore("\"")
                else -> ""
            }
            if (encoded.isNotBlank()) {
                try {
                    val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                    val src = Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.getOrNull(1)
                    if (src != null) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                } catch (e: Exception) { }
            }
        }

        // 3. Script tags
        document.select("script:not([src])").forEach { script ->
            val html = script.html()
            Regex("""(?:file|src|source)\s*[=:]\s*["'](https?://[^"']+)["']""")
                .findAll(html).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.contains(".m3u8") || u.contains(".mp4") || u.contains("embed"))
                        loadExtractor(u, data, subtitleCallback, callback)
                }
        }

        return true
    }
}