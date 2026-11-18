package com.indo

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/** Host resolvers for the servers CA3D embeds. Each returns true when it emitted at least one link. */
object Ca3dExtractors {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    suspend fun resolve(
        url: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        return try {
            when {
                fixed.contains("dailymotion.com") || fixed.contains("dai.ly") -> dailymotion(fixed, label, callback)
                fixed.contains("ok.ru") -> okru(fixed, label, callback)
                fixed.contains("rubyvid") -> rubyvid(fixed, label, referer, callback)
                fixed.contains("drive.google.com") -> gdrive(fixed, label, callback)
                fixed.contains("d.tube") -> dtube(fixed, label, callback)
                else -> loadExtractor(fixed, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun dailymotion(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""(?:video=|dai\.ly/|/video/)([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return false
        val meta = JSONObject(
            app.get(
                "https://www.dailymotion.com/player/metadata/video/$id",
                headers = mapOf("User-Agent" to UA, "Referer" to "https://geo.dailymotion.com/")
            ).text
        )
        // Deleted/blocked videos come back with an "error" object and no qualities.
        if (meta.has("error")) return false
        val m3u8 = meta.optJSONObject("qualities")?.optJSONArray("auto")?.optJSONObject(0)?.optString("url")
            ?.ifBlank { null } ?: return false
        val links = M3u8Helper.generateM3u8(
            source = "Dailymotion",
            streamUrl = m3u8,
            referer = "https://www.dailymotion.com/",
            headers = mapOf("Referer" to "https://www.dailymotion.com/")
        )
        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(newExtractorLink("Dailymotion", "Dailymotion $label".trim(), m3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://www.dailymotion.com/"
            })
        }
        return true
    }

    private suspend fun okru(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val opts = Regex("""data-options="([^"]+)"""").find(page)?.groupValues?.get(1) ?: return false
        val root = JSONObject(org.jsoup.parser.Parser.unescapeEntities(opts, true))
        val flashvars = root.optJSONObject("flashvars") ?: return false
        val metadata = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").ifBlank { null }?.let { JSONObject(it) }
            ?: return false
        val hls = metadata.optString("hlsManifestUrl").ifBlank { null }
        if (hls != null) {
            M3u8Helper.generateM3u8("OK.ru", hls, "https://ok.ru/").forEach(callback)
        }
        val videos = metadata.optJSONArray("videos")
        var found = hls != null
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val videoUrl = v.optString("url")
                if (!videoUrl.startsWith("http")) continue
                val q = v.optString("name", "sd")
                val quality = when (q.lowercase()) {
                    "ultra", "quad" -> Qualities.P1440.value
                    "full" -> Qualities.P1080.value
                    "hd" -> Qualities.P720.value
                    "sd" -> Qualities.P480.value
                    "low" -> Qualities.P360.value
                    "lowest", "mobile" -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink("OK.ru", "OK.ru $q", videoUrl, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = "https://ok.ru/"
                })
                found = true
            }
        }
        return found
    }

    private suspend fun rubyvid(url: String, label: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to referer)).text
        val m3u8 = Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(page)?.groupValues?.get(1) ?: return false
        val origin = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: "https://rubyvidhub.com"
        val headers = mapOf("Referer" to "$origin/", "Origin" to origin)
        val links = M3u8Helper.generateM3u8("Rubyvid", m3u8, "$origin/", headers = headers)
        if (links.isNotEmpty()) {
            links.forEach(callback)
        } else {
            callback(newExtractorLink("Rubyvid", "Rubyvid $label".trim(), m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.headers = headers
            })
        }
        return true
    }

    private suspend fun gdrive(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""/d/([\w-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([\w-]+)""").find(url)?.groupValues?.get(1)
            ?: return false
        // The usercontent host serves the file directly (with Range support), skipping the virus-scan page.
        val direct = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        callback(newExtractorLink("GDrive", "Google Drive", direct, ExtractorLinkType.VIDEO) {
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private suspend fun dtube(url: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""[?&]v=([\w-]+)""").find(url)?.groupValues?.get(1) ?: return false
        val links = M3u8Helper.generateM3u8("DTube", "https://nas1.d.tube/videos/$id/master.m3u8", "https://play.d.tube/")
        links.forEach(callback)
        return links.isNotEmpty()
    }
}
