package com.perso.iptv

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Cat(val id: String, val name: String)
data class Item(
    val id: String, val name: String, val img: String?, val cat: String, val ext: String,
    val added: Long = 0, val rating: String = "", val plot: String = "",
    val year: String = "", val genre: String = "", val backdrop: String? = null,
    val epgId: String = "", val lang: String = ""
)
data class Ep(
    val id: String, val title: String, val ext: String, val season: String,
    val plot: String = "", val duration: String = "", val img: String? = null, val num: String = ""
)
data class Info(
    val plot: String, val year: String, val duration: String, val genre: String, val backdrop: String?,
    val cast: String = "", val director: String = "", val tmdb: String = ""
)
data class Prog(val title: String, val start: Long, val stop: Long)

private fun JSONObject.str(k: String): String {
    val v = opt(k)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

private fun JSONObject.firstOf(k: String): String? {
    val a = optJSONArray(k)
    return if (a != null && a.length() > 0) a.optString(0).ifBlank { null } else null
}

class Xtream(server: String, private val user: String, private val pass: String) {
    private val base = server.trim().trimEnd('/')

    private fun call(q: String): String {
        val c = URL("$base/player_api.php?username=$user&password=$pass&$q").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun cats(kind: String): List<Cat> = withContext(Dispatchers.IO) {
        val a = JSONArray(call("action=get_${kind}_categories"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Cat(o.str("category_id"), o.str("category_name"))
        }
    }

    suspend fun items(kind: String): List<Item> = withContext(Dispatchers.IO) {
        val series = kind == "series"
        val act = if (series) "get_series" else "get_${kind}_streams"
        val c = URL("$base/player_api.php?username=$user&password=$pass&action=$act").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8).buffered(65536)).use { r ->
            val out = ArrayList<Item>()
            r.beginArray()
            while (r.hasNext()) out.add(readItem(r, series))
            r.endArray()
            out
        }
    }

    private fun JsonReader.optStr(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }

    private fun readItem(r: JsonReader, series: Boolean): Item {
        var id = ""; var name = ""; var img = ""; var cat = ""; var ext = ""
        var added = 0L; var rating = ""; var plot = ""; var year = ""; var genre = ""
        var backdrop: String? = null
        var epgId = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "stream_id", "series_id" -> id = r.optStr()
                "name" -> name = r.optStr()
                "stream_icon", "cover" -> img = r.optStr()
                "category_id" -> cat = r.optStr()
                "container_extension" -> ext = r.optStr()
                "added", "last_modified" -> {
                    val v = r.optStr().toLongOrNull() ?: 0L
                    if (added == 0L) added = v
                }
                "epg_channel_id" -> epgId = r.optStr()
                "rating" -> rating = r.optStr()
                "plot" -> if (series) plot = r.optStr() else r.skipValue()
                "genre" -> if (series) genre = r.optStr() else r.skipValue()
                "year", "releaseDate", "release_date" -> {
                    val v = r.optStr()
                    if (year.isBlank()) year = v.take(4)
                }
                "backdrop_path" -> {
                    if (series && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        if (r.hasNext()) backdrop = r.optStr().ifBlank { null }
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Item(
            id = id, name = name, img = img.ifBlank { null }, cat = cat,
            ext = ext.ifBlank { "mp4" }, added = added, rating = rating, plot = plot,
            year = year, genre = genre, backdrop = backdrop, epgId = epgId
        )
    }

    suspend fun vodInfo(id: String): Info? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(call("action=get_vod_info&vod_id=$id")).optJSONObject("info")
                ?: return@withContext null
            Info(
                o.str("plot").ifBlank { o.str("description") },
                o.str("releasedate").ifBlank { o.str("release_date") }.take(4),
                o.str("duration"),
                o.str("genre"),
                o.firstOf("backdrop_path"),
                o.str("cast").ifBlank { o.str("actors") },
                o.str("director"),
                o.str("tmdb_id").ifBlank { o.str("tmdb") }
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seriesDetail(seriesId: String): Pair<Info, List<Ep>> = withContext(Dispatchers.IO) {
        val root = JSONObject(call("action=get_series_info&series_id=$seriesId"))
        val o = root.optJSONObject("info")
        val info = if (o != null) Info(
            o.str("plot"),
            o.str("releaseDate").ifBlank { o.str("release_date") }.take(4),
            o.str("episode_run_time").let { if (it.isBlank()) "" else "$it min" },
            o.str("genre"),
            o.firstOf("backdrop_path"),
            o.str("cast"),
            o.str("director"),
            o.str("tmdb").ifBlank { o.str("tmdb_id") }
        ) else Info("", "", "", "", null)
        val out = mutableListOf<Ep>()
        val eps = root.optJSONObject("episodes")
        if (eps != null) {
            eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { s ->
                val arr = eps.optJSONArray(s) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val ei = e.optJSONObject("info")
                    out += Ep(
                        id = e.str("id"),
                        title = e.str("title"),
                        ext = e.str("container_extension").ifBlank { "mp4" },
                        season = s,
                        plot = ei?.str("plot") ?: "",
                        duration = ei?.str("duration") ?: "",
                        img = (ei?.str("movie_image") ?: "").ifBlank { null },
                        num = e.str("episode_num")
                    )
                }
            }
        }
        info to out
    }

    suspend fun episodes(seriesId: String): List<Ep> = withContext(Dispatchers.IO) {
        val eps = JSONObject(call("action=get_series_info&series_id=$seriesId")).optJSONObject("episodes")
            ?: return@withContext emptyList()
        val out = mutableListOf<Ep>()
        eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.forEach { s ->
            val arr = eps.getJSONArray(s)
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out += Ep(e.str("id"), e.str("title"), e.str("container_extension").ifBlank { "mp4" }, s)
            }
        }
        out
    }

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).ifBlank { s }
    } catch (e: Exception) {
        s
    }

    suspend fun epg(streamId: String): List<Prog> = withContext(Dispatchers.IO) {
        try {
            val a = JSONObject(call("action=get_short_epg&stream_id=$streamId&limit=3")).optJSONArray("epg_listings")
                ?: return@withContext emptyList()
            (0 until a.length()).map {
                val e = a.getJSONObject(it)
                Prog(
                    decode(e.str("title")),
                    e.str("start_timestamp").toLongOrNull() ?: 0,
                    e.str("stop_timestamp").toLongOrNull() ?: 0
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun xmltvStream(): java.io.InputStream {
        val c = URL("$base/xmltv.php?username=$user&password=$pass").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 180000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.inputStream
    }

    fun liveUrl(id: String) = "$base/live/$user/$pass/$id.m3u8"
    fun vodUrl(id: String, ext: String) = "$base/movie/$user/$pass/$id.$ext"
    fun epUrl(id: String, ext: String) = "$base/series/$user/$pass/$id.$ext"
}
