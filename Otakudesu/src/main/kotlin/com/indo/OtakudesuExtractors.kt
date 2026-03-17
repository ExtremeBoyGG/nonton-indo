package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        // Handle /view/ URLs directly (from Otakudesu)
        val pageUrl = if ("/view/" in url) {
            url
        } else {
            // Extract ID and use embed URL
            val id = Regex("/(?:view|embed-video)/([\\da-zA-Z]+)").find(url)?.groupValues?.get(1) ?: return
            "$mainUrl/embed-video/$id"
        }

        val doc = app.get(pageUrl).document
        val videoUrl = doc.selectFirst("source[src*=krakencloud], source[type=video/mp4]")
            ?.attr("src")?.ifBlank { null } ?: return

        callback.invoke(
            newExtractorLink(name, name, videoUrl) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
