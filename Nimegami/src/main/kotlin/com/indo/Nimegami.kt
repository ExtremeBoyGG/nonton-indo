package com.indo

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

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

    data class NimegamiStream(
        @JsonProperty("format") val format: String?,
        @JsonProperty("url") val url: List<String>?
    )

    private fun getImg(el: org.jsoup.nodes.Element): String? {
        val img = el.selectFirst("img") ?: return null
        val dataSrc = img.attr("data-src")
        if (dataSrc.isNotBlank()) return dataSrc
        val src = img.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:")) return src
        return null
    }

    private fun parseQuality(format: String): Int {
        return when {
            format.contains("1080") -> Qualities.P1080.value
            format.contains("720")  -> Qualities.P720.value
            format.contains("480")  -> Qualities.P480.value
            format.contains("360")  -> Qualities.P360.value
            format.contains("240")  -> Qualities.P240.value
            else                    -> Qualities.Unknown.value
        }
    }

    private val jsonMapper = ObjectMapper()

    private fun decodeStreamData(b64: String): List<NimegamiStream> {
        return try {
            val json = String(Base64.decode(b64, Base64.DEFAULT))
            jsonMapper.readValue(json, object : TypeReference<List<NimegamiStream>>() {})
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
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

        val title = doc.selectFirst("h1.title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Sub Indo.*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s*:.*Episode.*", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst("div.thumbnail img, div.coverthumbnail img")
            ?.let { it.attr("src").ifBlank { null } }

        val description = doc.selectFirst("div.content[id=Sinopsis] p")?.text()?.trim()
        val genres = doc.select("div.info2 td.info_a a").map { it.text() }.filter { it.isNotBlank() }

        // Cek apakah movie (batch-dlcuy) atau series (per-episode download)
        val isBatch = doc.selectFirst("div.batch-dlcuy") != null
        val epUls    = doc.select("div.download ul")
        val epH4s    = doc.select("div.download h4")
        val streamEps = doc.select("li.select-eps")

        return if (isBatch || epUls.size <= 1) {
            // === MOVIE ===
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                plot = description
                this.tags = genres
            }
        } else {
            // === SERIES — semua episode di satu halaman ===
            // Data episode = "pageUrl::index"
            val episodes = epUls.mapIndexed { i, _ ->
                val epName = epH4s.getOrNull(i)?.text()?.trim()
                    ?: "Episode ${i + 1}"
                val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (i + 1)
                newEpisode("$url::$i") {
                    name    = epName
                    episode = epNum
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = poster
                plot = description
                this.tags = genres
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data bisa berupa "url" (movie) atau "url::index" (episode series)
        val parts    = data.split("::")
        val pageUrl  = parts[0]
        val epIndex  = parts.getOrNull(1)?.toIntOrNull()

        val doc = app.get(pageUrl, headers = ua).document

        if (epIndex == null) {
            // === MOVIE: gunakan batch-dlcuy ===
            doc.select("div.batch-dlcuy li").forEach { li ->
                li.select("a[href]").forEach { a ->
                    val href = a.attr("href").ifBlank { null } ?: return@forEach
                    loadExtractor(fixUrl(href), pageUrl, subtitleCallback, callback)
                }
            }

            // Streaming dari li.select-eps (hanya 1 untuk movie)
            val streamB64 = doc.selectFirst("li.select-eps")?.attr("data") ?: ""
            if (streamB64.isNotBlank()) {
                decodeStreamData(streamB64).forEach { entry ->
                    val format = entry.format ?: return@forEach
                    val quality = parseQuality(format)
                    val urls = entry.url ?: return@forEach
                    urls.forEachIndexed { idx, streamUrl ->
                        if (streamUrl.isNotBlank()) {
                            val serverLabel = if (urls.size > 1) "Server ${idx + 1}" else "Stream"
                            callback(newExtractorLink("Nimegami", "$format $serverLabel", streamUrl) {
                                this.quality = quality
                                this.referer = pageUrl
                            })
                        }
                    }
                }
            }
        } else {
            // === SERIES: ambil berdasarkan index ===
            // Download links — ul ke-N di dalam div.download
            val ul = doc.select("div.download ul").getOrNull(epIndex)
            ul?.select("li")?.forEach { li ->
                li.select("a[href]").forEach { a ->
                    val href = a.attr("href").ifBlank { null } ?: return@forEach
                    loadExtractor(fixUrl(href), pageUrl, subtitleCallback, callback)
                }
            }

            // Streaming dari li.select-eps ke-N
            val streamB64 = doc.select("li.select-eps").getOrNull(epIndex)?.attr("data") ?: ""
            if (streamB64.isNotBlank()) {
                decodeStreamData(streamB64).forEach { entry ->
                    val format = entry.format ?: return@forEach
                    val quality = parseQuality(format)
                    val urls = entry.url ?: return@forEach
                    urls.forEachIndexed { idx, streamUrl ->
                        if (streamUrl.isNotBlank()) {
                            val serverLabel = if (urls.size > 1) "Server ${idx + 1}" else "Stream"
                            callback(newExtractorLink("Nimegami", "$format $serverLabel", streamUrl) {
                                this.quality = quality
                                this.referer = pageUrl
                            })
                        }
                    }
                }
            }
        }

        return true
    }
}