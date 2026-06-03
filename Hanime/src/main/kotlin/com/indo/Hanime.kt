package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/browse/trending" to "Trending",
        "$mainUrl/browse/random" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(HomePageList(request.name, listOf()))

        val doc = app.get(request.data, headers = ua).document
        val name = doc.selectFirst("title")?.text()?.trim() ?: request.name

        val items = doc.select("div.hvc.item.card a[href^=/videos/hentai/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { return@mapNotNull null }
            val slug = href.removePrefix("/videos/hentai/")
            newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
            }
        }.distinctBy { it.url }

        return newHomePageResponse(HomePageList(name, items))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return listOf()
        val doc = app.get("$mainUrl/browse?search=$query", headers = ua).document
        return doc.select("div.hvc.item.card a[href^=/videos/hentai/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("alt").ifBlank { return@mapNotNull null }
            val slug = href.removePrefix("/videos/hentai/")
            newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                this.posterUrl = "https://hanime-cdn.com/images/posters/$slug-pv1.webp"
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url, headers = ua)
        val html = resp.text ?: throw ErrorLoadingException("No response")
        val doc = resp.document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val nuxtMatch = Regex("window\\.__NUXT__=.*?</script>", RegexOption.DOT_MATCHES_ALL).find(html)
        val poster = if (nuxtMatch != null) {
            Regex("\"poster_url\":\"([^\"]+)\"").find(nuxtMatch.value)?.groupValues?.getOrNull(1)
        } else null

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val resp = app.get(data, headers = ua)
        val html = resp.text ?: return true

        val nuxtMatch = Regex("window\\.__NUXT__=.*?</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: return true

        val cleaned = nuxtMatch.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\u003D", "=")
        val hlsUrls = Regex("\"url\":\"(https?://[^\"]+\\.m3u8[^\"]*)\"").findAll(cleaned)
        hlsUrls.forEach { match ->
            val videoUrl = match.groupValues.getOrNull(1) ?: return@forEach
            callback(newExtractorLink(name, "$name - HLS", videoUrl) {
                this.referer = "$mainUrl/"
                this.quality = 3
            })
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}
