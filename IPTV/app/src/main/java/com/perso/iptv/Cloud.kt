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

    /** Dernier problème réseau rencontré (affiché dans Réglages pour comprendre un échec). */
    @Volatile var lastErr: String = ""

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val idRe = Regex("[A-Za-z0-9-]{6,}")

    class Resp(val code: Int, val body: String, val location: String?)

    private fun req(method: String, url: String, body: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        // Un « vrai » navigateur : un User-Agent trop nu (Mozilla/5.0) se fait refuser (403) par certains services
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
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
                if (r.code != 404) lastErr = "keyval HTTP ${r.code}"
                Pair(r.code, null)
            } else {
                val t = r.body.trim()
                val v = try {
                    (JSONObject(t).opt("data")?.toString() ?: "").trim()
                } catch (e: Exception) {
                    t
                }
                Pair(r.code, if (idRe.matches(v)) v else null)
            }
        } catch (e: Exception) {
            lastErr = "keyval : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            Pair(-1, null)
        }
    }

    fun setPointer(key: String, id: String): Boolean {
        return try {
            val r = req("GET", "$KV/set/$key/$id")
            if (r.code in 200..299) true else { lastErr = "keyval HTTP ${r.code}"; false }
        } catch (e: Exception) {
            lastErr = "keyval : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            false
        }
    }

    fun getBlob(id: String): Pair<Int, JSONObject?> {
        return try {
            val r = req("GET", "$BLOB/$id")
            if (r.code != 200) lastErr = "jsonblob HTTP ${r.code}"
            if (r.code == 200) Pair(200, JSONObject(r.body)) else Pair(r.code, null)
        } catch (e: Exception) {
            lastErr = "jsonblob : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            Pair(-1, null)
        }
    }

    fun createBlob(json: String): String? {
        return try {
            val r = req("POST", BLOB, json)
            val id = (r.location ?: "").trim().substringAfterLast('/')
            if (r.code in 200..299 && idRe.matches(id)) id
            else {
                lastErr = "jsonblob HTTP ${r.code}" + if (r.code in 200..299) " (identifiant illisible)" else ""
                null
            }
        } catch (e: Exception) {
            lastErr = "jsonblob : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
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
