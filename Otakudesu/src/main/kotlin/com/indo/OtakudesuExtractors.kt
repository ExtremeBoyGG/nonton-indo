package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

class Filedon : ExtractorApi() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // URL bisa dari /view/{slug}, /embed/{slug}, /d/{slug} atau file langsung
        val slug = Regex("/(?:view|embed|d|f)/([A-Za-z0-9]+)").find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/').ifBlank { return }

        // Halaman /embed/{slug} menyimpan URL stream (presigned R2) di props "url"
        val embedUrl = "$mainUrl/embed/$slug"
        val doc = app.get(embedUrl).document
        val raw = doc.selectFirst("[data-page]")?.attr("data-page") ?: return
        val props = (tryParseJson<Map<String, Any?>>(raw)?.get("props") as? Map<*, *>) ?: return

        val media = props["media"] as? Map<*, *>
        val hls = media?.get("hls_url")?.toString()?.takeIf { it.isNotBlank() }
        val direct = props["url"]?.toString()?.takeIf { it.isNotBlank() }
        val streamUrl = hls ?: direct ?: return

        val fileName = (props["files"] as? Map<*, *>)?.get("name")?.toString()
        val quality = Regex("(\\d{3,4})[pP]")
            .find(fileName ?: streamUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback.invoke(
            newExtractorLink(name, name, streamUrl) {
                this.referer = embedUrl
                this.quality = quality
                this.type = if (hls != null) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
            }
        )
    }
}
