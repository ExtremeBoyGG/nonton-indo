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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val videoUrl = doc.selectFirst("source[src*=krakencloud], source[type=video/mp4]")
            ?.attr("src")?.ifBlank { null } ?: return

        callback.invoke(
            newExtractorLink(name, name, videoUrl) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Berkasdrive : ExtractorApi() {
    override val name = "Berkasdrive"
    override val mainUrl = "https://dlgan.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, allowRedirects = false)
        val location = response.headers["location"] ?: response.url

        if (location.contains(".mp4") || location.contains("videoplayback")) {
            callback.invoke(
                newExtractorLink(name, name, location) {
                    this.referer = referer ?: url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}