package com.perso.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmEp(val name: String, val overview: String, val still: String?)

data class TmMovie(
    val overview: String, val backdrop: String?, val cast: String, val director: String,
    val genre: String, val year: String, val runtime: String, val rating: String
)

/** Récupère les vrais noms d'épisodes et les fiches de films (en français) sur TMDB. */
object Tmdb {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun findTv(key: String, name: String): String? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(name, "UTF-8")
            val res = JSONObject(get("https://api.themoviedb.org/3/search/tv?api_key=$key&language=fr-FR&query=$q"))
                .optJSONArray("results")
            if (res != null && res.length() > 0) res.getJSONObject(0).optInt("id").toString() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Fiches déjà chargées : rouvrir un film (ou passer d'une version à l'autre) ne refait pas la requête. */
    private val movieCache = object : LinkedHashMap<String, TmMovie>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmMovie>?): Boolean = size > 60
    }

    private val seasonCache = HashMap<String, Map<Int, TmEp>>()

    fun clearCache() {
        synchronized(movieCache) { movieCache.clear() }
        synchronized(seasonCache) { seasonCache.clear() }
    }

    suspend fun movie(key: String, tmdbId: String, name: String, year: String): TmMovie? = withContext(Dispatchers.IO) {
        val ck = "$tmdbId|$name|$year"
        synchronized(movieCache) { movieCache[ck] }?.let { return@withContext it }
        try {
            var id = tmdbId
            if (id.isBlank()) {
                val q = URLEncoder.encode(name, "UTF-8")
                val y = if (year.length == 4) "&year=$year" else ""
                val res = JSONObject(get("https://api.themoviedb.org/3/search/movie?api_key=$key&language=fr-FR&query=$q$y"))
                    .optJSONArray("results")
                if (res == null || res.length() == 0) return@withContext null
                id = res.getJSONObject(0).optInt("id").toString()
            }
            val o = JSONObject(get("https://api.themoviedb.org/3/movie/$id?api_key=$key&language=fr-FR&append_to_response=credits"))
            val credits = o.optJSONObject("credits")
            val names = ArrayList<String>()
            val cast = credits?.optJSONArray("cast")
            if (cast != null) {
                for (i in 0 until minOf(cast.length(), 6)) names.add(cast.getJSONObject(i).optString("name"))
            }
            var director = ""
            val crew = credits?.optJSONArray("crew")
            if (crew != null) {
                for (i in 0 until crew.length()) {
                    val c = crew.getJSONObject(i)
                    if (c.optString("job") == "Director") { director = c.optString("name"); break }
                }
            }
            val gl = ArrayList<String>()
            val gs = o.optJSONArray("genres")
            if (gs != null) {
                for (i in 0 until gs.length()) gl.add(gs.getJSONObject(i).optString("name"))
            }
            val bd = o.optString("backdrop_path", "")
            val rt = o.optInt("runtime", 0)
            val va = o.optDouble("vote_average", 0.0)
            val tm = TmMovie(
                overview = o.optString("overview", ""),
                backdrop = if (bd.isBlank() || bd == "null") null else "https://image.tmdb.org/t/p/w1280$bd",
                cast = names.joinToString(", "),
                director = director,
                genre = gl.joinToString(", "),
                year = o.optString("release_date", "").take(4),
                runtime = if (rt > 0) "${rt / 60} h ${(rt % 60).toString().padStart(2, '0')}" else "",
                rating = if (va > 0.0) String.format(java.util.Locale.US, "%.1f", va) else ""
            )
            synchronized(movieCache) { movieCache[ck] = tm }
            tm
        } catch (e: Exception) {
            null
        }
    }

    suspend fun season(key: String, tvId: String, season: String): Map<Int, TmEp> = withContext(Dispatchers.IO) {
        val ck = "$tvId|$season"
        synchronized(seasonCache) { seasonCache[ck] }?.let { return@withContext it }
        try {
            val arr = JSONObject(get("https://api.themoviedb.org/3/tv/$tvId/season/$season?api_key=$key&language=fr-FR"))
                .optJSONArray("episodes")
            val m = HashMap<Int, TmEp>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val still = e.optString("still_path", "")
                    m[e.optInt("episode_number")] = TmEp(
                        e.optString("name", ""),
                        e.optString("overview", ""),
                        if (still.isBlank() || still == "null") null else "https://image.tmdb.org/t/p/w300$still"
                    )
                }
            }
            if (m.isNotEmpty()) synchronized(seasonCache) { seasonCache[ck] = m }
            m
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
