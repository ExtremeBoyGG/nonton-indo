package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import org.json.JSONObject

data class TmdbMetadata(
    val tmdbId: Int,
    val title: String?,
    val plot: String?,
    val posterUrl: String?,
    val backgroundPosterUrl: String?,
    val voteAverage: Double,
    val actors: List<ActorData>
)

object TmdbHelper {
    private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val IMG_W1280 = "https://image.tmdb.org/t/p/w1280"
    private const val IMG_W780 = "https://image.tmdb.org/t/p/w780"

    suspend fun getMetadata(title: String, isMovie: Boolean, year: Int?): TmdbMetadata? {
        return try {
            val type = if (isMovie) "movie" else "tv"
            val cleanTitle = title
                .replace(Regex("""\s*\(\d{4}\).*"""), "")
                .replace(Regex("""(?i)\s+(?:subtitle|sub)\s+indonesia.*"""), "")
                .trim()
            if (cleanTitle.isBlank()) return null

            val yearParam = if (year != null) {
                if (isMovie) "&year=$year" else "&first_air_date_year=$year"
            } else ""

            var searchUrl = "$BASE_URL/search/$type?api_key=$API_KEY&query=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}$yearParam&language=id-ID"
            var resText = app.get(searchUrl).text
            var json = JSONObject(resText)
            var results = json.optJSONArray("results")

            if (results == null || results.length() == 0) {
                searchUrl = "$BASE_URL/search/$type?api_key=$API_KEY&query=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}&language=en-US"
                resText = app.get(searchUrl).text
                json = JSONObject(resText)
                results = json.optJSONArray("results")
            }

            if (results == null || results.length() == 0) return null

            val first = results.getJSONObject(0)
            val tmdbId = first.getInt("id")
            val name = first.optString("title").ifBlank { null } ?: first.optString("name").ifBlank { null }
            val plot = first.optString("overview").ifBlank { null }
            val posterPath = first.optString("poster_path").ifBlank { null }
            val backdropPath = first.optString("backdrop_path").ifBlank { null }
            val voteAverage = first.optDouble("vote_average", 0.0)

            val posterUrl = if (profileCheck(posterPath)) "$IMG_W780$posterPath" else null
            val backdropUrl = if (profileCheck(backdropPath)) "$IMG_W1280$backdropPath" else null

            val creditsUrl = "$BASE_URL/$type/$tmdbId/credits?api_key=$API_KEY"
            val creditsJson = JSONObject(app.get(creditsUrl).text)
            val castArray = creditsJson.optJSONArray("cast")

            val actors = mutableListOf<ActorData>()
            if (castArray != null) {
                for (i in 0 until minOf(castArray.length(), 15)) {
                    val c = castArray.getJSONObject(i)
                    val actorName = c.optString("name").ifBlank { null } ?: continue
                    val charName = c.optString("character").ifBlank { null }
                    val profilePath = c.optString("profile_path").ifBlank { null }
                    val actorImg = if (profileCheck(profilePath)) "$IMG_W500$profilePath" else null
                    actors.add(
                        ActorData(
                            actor = Actor(actorName, actorImg),
                            roleString = charName
                        )
                    )
                }
            }

            TmdbMetadata(
                tmdbId = tmdbId,
                title = name,
                plot = plot,
                posterUrl = posterUrl,
                backgroundPosterUrl = backdropUrl,
                voteAverage = voteAverage,
                actors = actors
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun profileCheck(path: String?): Boolean {
        return !path.isNullOrBlank() && path != "null"
    }
}
