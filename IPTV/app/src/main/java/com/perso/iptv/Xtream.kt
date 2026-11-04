package com.perso.iptv

import android.util.Base64
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
    val year: String = "", val genre: String = "", val backdrop: String? = null
)
data class Ep(val id: String, val title: String, val ext: String, val season: String)
data class Info(val plot: String, val year: String, val duration: String, val genre: String, val backdrop: String?)
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
        val a = JSONArray(call("action=$act"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val img = if (series) o.str("cover") else o.str("stream_icon")
            Item(
                id = o.str(if (series) "series_id" else "stream_id"),
                name = o.str("name"),
                img = img.ifBlank { null },
                cat = o.str("category_id"),
                ext = o.str("container_extension").ifBlank { "mp4" },
                added = o.str("added").toLongOrNull() ?: o.str("last_modified").toLongOrNull() ?: 0,
                rating = o.str("rating"),
                plot = o.str("plot"),
                year = o.str("year").ifBlank { o.str("releaseDate").ifBlank { o.str("release_date") } }.take(4),
                genre = o.str("genre"),
                backdrop = o.firstOf("backdrop_path")
            )
        }
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
                o.firstOf("backdrop_path")
            )
        } catch (e: Exception) {
            null
        }
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

    fun liveUrl(id: String) = "$base/live/$user/$pass/$id.m3u8"
    fun vodUrl(id: String, ext: String) = "$base/movie/$user/$pass/$id.$ext"
    fun epUrl(id: String, ext: String) = "$base/series/$user/$pass/$id.$ext"
}
