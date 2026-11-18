package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class CA3D : MainAPI() {
    override var mainUrl = "https://ca3d.site"
    override var name = "CA3D"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "latest" to "Update Terbaru",
        "popular" to "Populer",
        "series" to "Series",
        "movie" to "Movie",
    )

    // ---- SvelteKit __data.json (devalue) ----

    /**
     * devalue flattens the payload into one array where objects and arrays hold indexes into it.
     * Negative indexes are sentinels (undefined, NaN, Infinity...), all read as null here.
     */
    private fun unflatten(values: JSONArray): Any? {
        val cache = HashMap<Int, Any?>()
        fun hydrate(index: Int): Any? {
            if (index < 0) return null
            if (cache.containsKey(index)) return cache[index]
            return when (val v = values.opt(index)) {
                is JSONObject -> {
                    val map = LinkedHashMap<String, Any?>()
                    cache[index] = map
                    v.keys().forEach { k -> map[k] = hydrate(v.optInt(k, -1)) }
                    map
                }
                is JSONArray -> {
                    val tag = v.opt(0)
                    if (tag is String) {
                        // Typed values such as ["Date", "..."]; only the raw payload is needed.
                        v.opt(1).also { cache[index] = it }
                    } else {
                        val list = ArrayList<Any?>()
                        cache[index] = list
                        for (i in 0 until v.length()) list.add(hydrate(v.optInt(i, -1)))
                        list
                    }
                }
                JSONObject.NULL -> null
                else -> v.also { cache[index] = it }
            }
        }
        return hydrate(0)
    }

    /** Returns the hydrated data of every route node, in layout-to-page order. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun routeData(path: String): List<Map<String, Any?>> {
        val text = app.get("$mainUrl$path/__data.json", headers = headers).text
        // The response can be streamed as several JSON lines; the first one holds the nodes.
        val root = JSONObject(text.lineSequence().first { it.isNotBlank() })
        val nodes = root.optJSONArray("nodes") ?: return emptyList()
        return (0 until nodes.length()).mapNotNull { i ->
            val data = nodes.optJSONObject(i)?.optJSONArray("data") ?: return@mapNotNull null
            unflatten(data) as? Map<String, Any?>
        }
    }

    private fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()?.ifBlank { null }
    private fun Map<String, Any?>.num(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

    // ---- catalog ----

    private var catalogCache: List<Map<String, Any?>>? = null
    private var catalogTime = 0L

    /** The layout node of every page ships the whole catalog (titles + episodes). */
    @Suppress("UNCHECKED_CAST")
    private suspend fun catalog(): List<Map<String, Any?>> {
        val cached = catalogCache
        if (cached != null && System.currentTimeMillis() - catalogTime < 10 * 60 * 1000) return cached
        val list = routeData("").firstNotNullOfOrNull { it["donghua"] as? List<Map<String, Any?>> }.orEmpty()
        if (list.isNotEmpty()) {
            catalogCache = list
            catalogTime = System.currentTimeMillis()
        }
        return list
    }

    private fun isMovie(item: Map<String, Any?>) = item.str("type").equals("movie", ignoreCase = true)

    private fun toSearch(item: Map<String, Any?>): SearchResponse? {
        val id = item.str("id") ?: return null
        val title = item.str("title") ?: return null
        val movie = isMovie(item)
        val epCount = (item["episodes"] as? List<*>)?.size
        return newAnimeSearchResponse(title, "$mainUrl/synopsis/$id", if (movie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = item.str("image")
            if (!movie && epCount != null) addSub(epCount)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val all = catalog()
        val picked = when (request.data) {
            "latest" -> all.sortedByDescending { it.num("lastUpdated") }.take(40)
            "popular" -> all.sortedByDescending { it.num("views") }.take(40)
            "series" -> all.filter { !isMovie(it) }.sortedByDescending { it.num("lastUpdated") }
            "movie" -> all.filter { isMovie(it) }.sortedByDescending { it.num("lastUpdated") }
            else -> all
        }
        return newHomePageResponse(request.name, picked.mapNotNull { toSearch(it) }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return catalog().filter { it.str("title")?.contains(q, ignoreCase = true) == true }.mapNotNull { toSearch(it) }
    }

    // ---- detail & links ----

    /** Episode server strings are "label|url" lines; older entries are a single bare URL. */
    private fun parseServers(raw: String?): List<Pair<String, String>> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (!text.contains("|")) return if (text.startsWith("http")) listOf("" to text) else emptyList()
        return text.lines().mapNotNull { line ->
            if (!line.contains("|")) return@mapNotNull null
            val label = line.substringBefore("|").trim()
            var value = line.substringAfter("|").trim().trimEnd('\\')
            // Some entries paste a whole <iframe> instead of its src.
            Regex("""src=["']([^"']+)["']""").find(value)?.let { value = it.groupValues[1] }
            if (value.startsWith("http") || value.startsWith("//")) label to value else null
        }
    }

    private fun episodeServers(ep: Map<String, Any?>) = parseServers(ep.str("url") ?: ep.str("video"))

    @Suppress("UNCHECKED_CAST")
    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""/(?:synopsis|watch)/(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("ID tidak ditemukan")
        val synopsisUrl = "$mainUrl/synopsis/$id"
        val item = routeData("/synopsis/$id").firstNotNullOfOrNull { it["item"] as? Map<String, Any?> }
            ?: catalog().firstOrNull { it.str("id") == id }
            ?: throw ErrorLoadingException("Data tidak ditemukan")

        val title = item.str("title") ?: throw ErrorLoadingException("Judul tidak ditemukan")
        val poster = item.str("image")
        val plot = item.str("synopsis")
        val movie = isMovie(item)
        val status = when (item.str("status")?.lowercase()) {
            "completed", "tamat" -> ShowStatus.Completed
            "ongoing", "on-going" -> ShowStatus.Ongoing
            else -> null
        }

        val rawEpisodes = (item["episodes"] as? List<Map<String, Any?>>).orEmpty()
        val episodes = rawEpisodes.mapNotNull { ep ->
            val epKey = ep.str("ep") ?: return@mapNotNull null
            val servers = episodeServers(ep)
            // Episodes the site has not uploaded yet have no link at all.
            if (servers.isEmpty()) return@mapNotNull null
            // A "review" server alone is a ~30s preview of an upcoming episode.
            val previewOnly = servers.all { it.first.equals("review", ignoreCase = true) }
            val number = Regex("""\d+""").find(epKey)?.value?.toIntOrNull()
            newEpisode("$mainUrl/watch/$id/$epKey") {
                this.name = (ep.str("title") ?: "Episode $epKey") + if (previewOnly) " (Preview)" else ""
                this.episode = number
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        if (movie && episodes.size <= 1) {
            return newMovieLoadResponse(title, synopsisUrl, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: synopsisUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newAnimeLoadResponse(title, synopsisUrl, if (movie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("""/watch/(\d+)/([^/?#]+)""").find(data) ?: return false
        val (id, epKey) = match.destructured

        var servers = try {
            routeData("/watch/$id/$epKey").firstNotNullOfOrNull { it["episode"] as? Map<String, Any?> }
                ?.let { episodeServers(it) }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (servers.isEmpty()) {
            // Fall back to the server <select> rendered on the watch page.
            servers = app.get(data, headers = headers).document.select("select option[value^=http]")
                .map { it.text().trim() to it.attr("value") }
        }

        var found = false
        servers.distinctBy { it.second }.forEach { (label, url) ->
            if (Ca3dExtractors.resolve(url, label, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        return found
    }
}
