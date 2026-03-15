package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities

class BloggerExtractor : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Token langsung dari iframe URL: blogger.com/video.g?token=XXX
        val token = Regex("[?&]token=([^&]+)").find(url)
            ?.groupValues?.getOrNull(1) ?: return

        // Fetch direct video URL via Blogger batchexecute API
        // Sama persis dengan request yang terlihat di Network tab
        val response = app.post(
            "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute",
            params = mapOf(
                "rpcids" to "WcwnYd",
                "rt" to "c"
            ),
            data = mapOf(
                "f.req" to """[[["WcwnYd","[\"$token\",\"\",0]",null,"generic"]]]""",
                "" to ""
            ),
            referer = url,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Same-Domain" to "1"
            )
        ).text

        // Parse URL googlevideo.com dari response JSON
        // URL ada di dalam string dengan unicode escape
        val videoUrl = Regex(""""(https://r[^"\\]+googlevideo\.com/videoplayback[^"\\]+)"""")
            .find(response)?.groupValues?.getOrNull(1)
            ?.replace("\\u003d", "=")
            ?.replace("\\u0026", "&")
            ?: return

        callback.invoke(
            ExtractorLink(
                name,
                name,
                videoUrl,
                referer = "https://www.blogger.com/",
                quality = Qualities.Unknown.value,
                type = INFER_TYPE
            )
        )
    }
}