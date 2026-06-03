package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class KrakenFiles : ExtractorApi() {
    override val name = "KrakenFiles"
    override val mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    private fun parseQuality(text: String): Int {
        return when {
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720")  -> Qualities.P720.value
            text.contains("480")  -> Qualities.P480.value
            text.contains("360")  -> Qualities.P360.value
            text.contains("240")  -> Qualities.P240.value
            else                  -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("source[src*=krakencloud], source[type=video/mp4]")
            ?.attr("src")?.ifBlank { null } ?: return

        // Try to detect quality from page title or filename
        val pageTitle = doc.title() ?: ""
        val fileName = doc.selectFirst("div.coin-name h1, div.file-name, h1")?.text() ?: ""
        val qualityText = "$pageTitle $fileName $videoUrl"
        val quality = parseQuality(qualityText)

        callback.invoke(
            newExtractorLink(name, name, videoUrl) {
                this.referer = referer ?: url
                this.quality = quality
            }
        )
    }
}

class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://dlgan.halahgan.com"
    override val requiresReferer = true

    private fun parseQualityFromUrl(url: String): Int {
        val nameParam = url.substringAfter("name=").substringBefore("&").substringBefore(".mp4")
        return when {
            nameParam.contains("1080") -> Qualities.P1080.value
            nameParam.contains("720")  -> Qualities.P720.value
            nameParam.contains("480")  -> Qualities.P480.value
            nameParam.contains("360")  -> Qualities.P360.value
            nameParam.contains("240")  -> Qualities.P240.value
            else                       -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("id=").substringBefore("&")
        if (id.isBlank()) return

        val apiUrl = "$mainUrl/streaming.php?id=$id&proxy=1"
        val respText = app.get(apiUrl, referer = referer ?: url).text
        val streamUrl = Regex(""""stream_url"\s*:\s*"([^"]+)""")
            .find(respText)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/") ?: return

        callback.invoke(
            newExtractorLink(name, name, streamUrl) {
                this.referer = apiUrl
                this.quality = parseQualityFromUrl(url)
            }
        )
    }
}