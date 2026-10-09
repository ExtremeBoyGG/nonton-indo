package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Popular Today",
        "$mainUrl/" to "Latest Release",
    )

    private fun parseItems(doc: Document, selector: String): List<SearchResponse> {
        return doc.select(selector).mapNotNull { item ->
            val a = item.selectFirst(".bsx > a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val poster = item.selectFirst(".limit img")?.let { img ->
                img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
            }?.let { fixUrl(it) }

            val type = item.selectFirst(".typez")?.text()?.trim()
            val tvType = when (type) {
                "Movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val title = item.selectFirst(".tt")?.ownText()?.trim()?.ifBlank { null }
                ?: item.selectFirst(".tt h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val epText = item.selectFirst(".bt .epx")?.text()?.trim()
            val epNum = Regex("""(\d+)""").find(epText ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Popular Today") {
            val doc = app.get(mainUrl).document
            val items = parseItems(doc, ".releases.hothome + .listupd article.bs, .popularslider article.bs")
            return newHomePageResponse(request.name, items)
        }

        val doc = app.get(if (page > 1) "$mainUrl/page/$page/" else mainUrl).document
        val items = parseItems(doc, ".releases.latesthome + .listupd article.bs, .listupd.normal article.bs")
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return parseItems(doc, "div.listupd article.bs")
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = fixUrl(url)
        val doc = app.get(fullUrl).document

        val breadcrumbSeries = doc.selectFirst(".ts-breadcrumb [itemprop=itemListElement]:nth-child(2) a [itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst(".ts-breadcrumb [itemprop=itemListElement]:nth-child(2) a")?.text()?.trim()

        val title = breadcrumbSeries
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".infolimit h2")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = doc.selectFirst(".thumb img, .thumbook img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
        }?.let { fixUrl(it) } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val synopsis = doc.select(".desc p, .entry-content p, .synp p").text().trim().ifBlank { null }
        val tags = doc.select(".genxed a, a[href*=/genres/]").mapNotNull { it.text().trim().ifBlank { null } }.distinct()

        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text()?.trim()
        val status = when {
            statusText?.contains("Ongoing", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val year = Regex("""\b(20\d{2}|19\d{2})\b""").find(
            doc.select(".spe span:contains(Tanggal rilis), .spe span:contains(Released)").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val eplisterElements = doc.select(".eplister ul li a")
        val episodelistElements = doc.select(".episodelist ul li a")

        val episodes = if (eplisterElements.isNotEmpty()) {
            eplisterElements.mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                val epTitle = a.selectFirst(".epl-title")?.text()?.trim()?.ifBlank { null }
                val numText = a.selectFirst(".epl-num")?.text()?.trim() ?: ""
                val epNum = Regex("""\d+""").find(numText)?.value?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle ?: (if (epNum != null) "Episode $epNum" else numText.ifBlank { "Episode" })
                    this.episode = epNum
                }
            }
        } else if (episodelistElements.isNotEmpty()) {
            episodelistElements.mapNotNull { a ->
                val epHref = fixUrl(a.attr("href"))
                val epThumb = a.selectFirst(".thumbnel img")?.let { img ->
                    img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
                }?.let { fixUrl(it) }
                val epTitle = a.selectFirst(".playinfo h3")?.text()?.trim()?.ifBlank { null }
                val epInfo = a.selectFirst(".playinfo span")?.text()?.trim() ?: ""
                val epNum = Regex("""(?:Eps?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?:Eps?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitle ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle ?: "Episode ${epNum ?: ""}"
                    this.episode = epNum
                    this.posterUrl = epThumb
                }
            }
        } else {
            // Fallback to series page from breadcrumbs if applicable
            val seriesHref = doc.selectFirst(".ts-breadcrumb [itemprop=itemListElement]:nth-child(2) a")?.attr("href")?.let { fixUrl(it) }
            if (seriesHref != null && seriesHref != fullUrl) {
                val seriesDoc = app.get(seriesHref).document
                seriesDoc.select(".eplister ul li a").mapNotNull { a ->
                    val epHref = fixUrl(a.attr("href"))
                    val epTitle = a.selectFirst(".epl-title")?.text()?.trim()?.ifBlank { null }
                    val numText = a.selectFirst(".epl-num")?.text()?.trim() ?: ""
                    val epNum = Regex("""\d+""").find(numText)?.value?.toIntOrNull()
                    newEpisode(epHref) {
                        this.name = epTitle ?: (if (epNum != null) "Episode $epNum" else numText.ifBlank { "Episode" })
                        this.episode = epNum
                    }
                }
            } else {
                emptyList()
            }
        }

        val sortedEpisodes = if (episodes.any { it.episode != null }) {
            episodes.sortedWith(compareBy(nullsLast()) { it.episode })
        } else {
            episodes.reversed()
        }

        val typeText = doc.selectFirst(".typez, .spe span:contains(Tipe), .spe span:contains(Type)")?.text()?.trim()
        val isMovie = typeText?.contains("Movie", ignoreCase = true) == true
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        if (sortedEpisodes.isNotEmpty()) {
            return newAnimeLoadResponse(title, fullUrl, tvType) {
                engName = title
                posterUrl = poster
                addEpisodes(DubStatus.Subbed, sortedEpisodes)
                showStatus = status
                this.year = year
                plot = synopsis
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, fullUrl, TvType.AnimeMovie, fullUrl) {
            this.posterUrl = poster
            this.plot = synopsis
            this.tags = tags
        }
    }

    private suspend fun extractVidhide(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, referer = "$mainUrl/").text
            val packed = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:[^'\\]|\\.)*+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\s*\.split\s*\(\s*'\|\s*'\s*\)\s*\)""",
                RegexOption.IGNORE_CASE
            ).find(html) ?: return

            val encoded = packed.groupValues[1]
            val radix = packed.groupValues[2].toIntOrNull() ?: return
            val count = packed.groupValues[3].toIntOrNull() ?: return
            val dictStr = packed.groupValues[4]
            val dictionary = dictStr.split("|")

            var result = encoded
            for (i in (count - 1) downTo 0) {
                val replacement = dictionary.getOrNull(i)
                if (!replacement.isNullOrEmpty()) {
                    val word = i.toString(radix)
                    result = result.replace(Regex("\\b" + Regex.escape(word) + "\\b"), replacement)
                }
            }

            val m3u8Url = Regex("""['"]file['"]\s*:\s*['"]((?:[^'"]|\\.)*+)['"]""").find(result)?.groupValues?.getOrNull(1)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(result)?.value ?: return

            val links = M3u8Helper.generateM3u8(
                source = "VidHide",
                streamUrl = m3u8Url,
                referer = url,
                headers = mapOf("Referer" to url)
            )
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
            } else {
                callback.invoke(
                    newExtractorLink("VidHide", "VidHide", m3u8Url) {
                        this.referer = url
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun extractRumble(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = Regex("""(?:embed/|v=)([a-zA-Z0-9]+)""").find(url)?.groupValues?.getOrNull(1) ?: return
            val apiUrl = "https://rumble.com/embedJS/u3/?request=video&v=$videoId"
            val jsonText = app.get(apiUrl).text
            val data = tryParseJson<Map<String, Any?>>(jsonText) ?: return
            val ua = data["ua"] as? Map<*, *> ?: return

            ua.forEach { (key, value) ->
                val qInt = key.toString().toIntOrNull()
                val list = value as? List<*> ?: return@forEach
                val streamUrl = list.firstOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val quality = when (qInt) {
                    1080 -> Qualities.P1080.value
                    720 -> Qualities.P720.value
                    480 -> Qualities.P480.value
                    360 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback.invoke(
                    newExtractorLink("Rumble", "Rumble ${qInt ?: ""}p".trim(), streamUrl) {
                        this.quality = quality
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun extractOkru(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url).text
            val opts = Regex("""data-options=["']([^"']+)["']""").find(doc)?.groupValues?.getOrNull(1) ?: return
            val jsonStr = opts.replace("&quot;", "\"")
            val root = tryParseJson<Map<String, Any?>>(jsonStr) ?: return
            val flashvars = root["flashvars"] as? Map<*, *> ?: return
            val metadataRaw = flashvars["metadata"]
            val metadata = (metadataRaw as? Map<*, *>) ?: (metadataRaw as? String)?.let { tryParseJson<Map<String, Any?>>(it) } ?: return
            val videos = metadata["videos"] as? List<*> ?: return
            videos.forEach { v ->
                val map = v as? Map<*, *> ?: return@forEach
                val qualityName = map["name"]?.toString() ?: "SD"
                val videoUrl = map["url"]?.toString() ?: return@forEach
                if (!videoUrl.startsWith("http")) return@forEach
                val q = when (qualityName.lowercase()) {
                    "full" -> Qualities.P1080.value
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest", "mobile" -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink("Okru", "Okru $qualityName", videoUrl) {
                        this.quality = q
                        this.type = ExtractorLinkType.VIDEO
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun extractPlaymogo(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url).text
            val passMd5 = Regex("/pass_md5/([^/]+)/([^/\\s\"')]+)").find(doc) ?: return
            val videoBase = app.get("https://playmogo.com${passMd5.value}", referer = url).text.trim()
            if (videoBase.isBlank() || !videoBase.startsWith("http")) return
            val token = passMd5.groupValues[2]
            val expiry = (System.currentTimeMillis() + 86400000).toString()
            val videoUrl = if (videoBase.endsWith("~")) "$videoBase$token?token=$token&expiry=$expiry" else videoBase
            if (!videoUrl.startsWith("http")) return
            callback.invoke(
                newExtractorLink("DoodStream", "DoodStream", videoUrl) {
                    this.referer = "https://playmogo.com"
                    this.headers = mapOf("Referer" to "https://playmogo.com")
                }
            )
        } catch (_: Exception) { }
    }

    private suspend fun extractUrl(
        url: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fullUrl = if (url.startsWith("//")) "https:$url" else url
        when {
            fullUrl.contains("ok.ru") -> {
                extractOkru(fullUrl, callback)
            }
            fullUrl.contains("morencius.com") || fullUrl.contains("minochinos.com") ||
            fullUrl.contains("vidhide") || fullUrl.contains("bingezove.com") || fullUrl.contains("vidhidepre.com") -> {
                extractVidhide(fullUrl, callback)
            }
            fullUrl.contains("rumble.com") -> {
                extractRumble(fullUrl, callback)
            }
            fullUrl.contains("playmogo.com") || fullUrl.contains("dood") -> {
                extractPlaymogo(fullUrl, callback)
            }
            else -> {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(fixUrl(data)).document

        doc.select("#embed_holder iframe[src], .player-embed iframe[src], #pembed iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim().ifBlank { return@forEach }
            extractUrl(src, data, subtitleCallback, callback)
        }

        doc.select("select.mirror option").forEach { option ->
            val encoded = option.attr("value").trim().ifBlank { return@forEach }
            if (encoded.length < 10) return@forEach

            if (encoded.startsWith("http://") || encoded.startsWith("https://")) {
                extractUrl(encoded, data, subtitleCallback, callback)
                return@forEach
            }

            try {
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)).trim()
                val iframeSrc = Regex("""<iframe[^>]+src=["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)
                    .find(decoded)?.groupValues?.getOrNull(1)?.trim()
                    ?: Regex("""https?://[^\s"'<>]+""").find(decoded)?.value

                if (!iframeSrc.isNullOrBlank()) {
                    extractUrl(iframeSrc, data, subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        return true
    }
}

