package com.perso.iptv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Synchronisation entre appareils (TCL, Chromecast…) sans compte :
 * - un « espace » JSON sur jsonblob.com (identifiant secret)
 * - un pointeur sur keyval.org, calculé à partir de ton compte IPTV (hash), pour que les autres appareils retrouvent l'espace tout seuls.
 * Tes identifiants IPTV ne sont jamais envoyés (seulement leur empreinte).
 */
object Cloud {
    private const val BLOB = "https://jsonblob.com/api/jsonBlob"
    private const val KV = "https://api.keyval.org"

    class Resp(val code: Int, val body: String, val location: String?)

    private fun req(method: String, url: String, body: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Resp(code, txt, c.getHeaderField("Location"))
    }

    fun pointerKey(accountKey: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(accountKey.toByteArray(Charsets.UTF_8))
        val hex = md.joinToString("") { b -> String.format("%02x", b) }
        return "kineo-sync-" + hex.take(40)
    }

    /** (code HTTP ou -1 si pas de réseau, identifiant de l'espace s'il existe) */
    fun getPointer(key: String): Pair<Int, String?> {
        return try {
            val r = req("GET", "$KV/get/$key")
            if (r.code !in 200..299) {
                Pair(r.code, null)
            } else {
                val t = r.body.trim()
                val v = try {
                    (JSONObject(t).opt("data")?.toString() ?: "").trim()
                } catch (e: Exception) {
                    t
                }
                val d = v.filter { it.isDigit() }
                Pair(r.code, if (d.length >= 6 && d.length == v.length) d else null)
            }
        } catch (e: Exception) {
            Pair(-1, null)
        }
    }

    fun setPointer(key: String, id: String) {
        try { req("GET", "$KV/set/$key/$id") } catch (e: Exception) { /* ignoré */ }
    }

    fun getBlob(id: String): Pair<Int, JSONObject?> {
        return try {
            val r = req("GET", "$BLOB/$id")
            if (r.code == 200) Pair(200, JSONObject(r.body)) else Pair(r.code, null)
        } catch (e: Exception) {
            Pair(-1, null)
        }
    }

    fun createBlob(json: String): String? {
        return try {
            val r = req("POST", BLOB, json)
            val id = (r.location ?: "").substringAfterLast('/').filter { it.isDigit() }
            if (r.code in 200..299 && id.length >= 6) id else null
        } catch (e: Exception) {
            null
        }
    }

    fun putBlob(id: String, json: String): Boolean {
        return try {
            req("PUT", "$BLOB/$id", json).code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
