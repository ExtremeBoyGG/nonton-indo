package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).text

        val passMd5 = Regex("/pass_md5/([^/]+)/([^/\\s\"')]+)").find(doc) ?: return
        val passPath = passMd5.value
        val videoBase = app.get("$mainUrl$passPath", referer = url).text.trim()
        if (videoBase.isBlank()) return

        val token = passMd5.groupValues[2]
        val expiry = (System.currentTimeMillis() + 86400000).toString()
        val videoUrl = if (videoBase.endsWith("~")) "$videoBase$token?token=$token&expiry=$expiry" else videoBase

        val quality = when {
            Regex("""\b(?:2160|4k)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P2160.value
            Regex("""\b(?:1080|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P1080.value
            Regex("""\b720\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P720.value
            Regex("""\b480\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P480.value
            Regex("""\b360\b""", RegexOption.IGNORE_CASE).containsMatchIn(videoUrl) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(name, name, videoUrl) {
                this.referer = mainUrl
                this.quality = quality
                this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
            }
        )
    }
}

class Streampoi : ExtractorApi() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url).text

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

        val fileUrl = Regex("""['"]file['"]\s*:\s*['"]((?:[^'"]|\\.)*+)['"]""").find(result)?.groupValues?.getOrNull(1) ?: return

        val quality = when {
            Regex("""\b(?:2160|4k)\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileUrl) -> Qualities.P2160.value
            Regex("""\b(?:1080|hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileUrl) -> Qualities.P1080.value
            Regex("""\b720\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileUrl) -> Qualities.P720.value
            Regex("""\b480\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileUrl) -> Qualities.P480.value
            Regex("""\b360\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileUrl) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(name, name, fileUrl) {
                this.referer = url
                this.quality = quality
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf("Referer" to url, "Origin" to mainUrl)
            }
        )
    }

}
