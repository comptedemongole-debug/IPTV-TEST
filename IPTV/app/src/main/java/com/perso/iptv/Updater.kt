package com.perso.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour de l'appli sans Downloader : lit la dernière version publiée sur GitHub (Releases), télécharge l'APK
 * et lance l'installation. Le nom du dépôt est écrit dans l'appli par le workflow GitHub (assets/update_repo.txt).
 * Le dépôt doit être public pour que l'appli puisse lire ses versions.
 */
object Updater {
    data class Release(val tag: String, val versionCode: Int, val url: String, val sizeBytes: Long)

    fun repoName(ctx: Context): String? = try {
        ctx.assets.open("update_repo.txt").bufferedReader().use { it.readText() }.trim().takeIf { it.contains('/') }
    } catch (e: Exception) {
        null
    }

    private fun pkgInfo(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

    fun installedName(ctx: Context): String = try { pkgInfo(ctx).versionName ?: "?" } catch (e: Exception) { "?" }

    fun installedCode(ctx: Context): Long = try { PackageInfoCompat.getLongVersionCode(pkgInfo(ctx)) } catch (e: Exception) { 0L }

    /** Retourne la dernière version, ou un message d'erreur lisible. */
    suspend fun latest(ctx: Context): Pair<Release?, String?> = withContext(Dispatchers.IO) {
        val repo = repoName(ctx)
            ?: return@withContext null to "Dépôt GitHub inconnu : l'APK doit être compilé par le workflow GitHub pour pouvoir se mettre à jour."
        try {
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Kineo")
            val code = c.responseCode
            if (code == 404) return@withContext null to "Aucune version trouvée : le dépôt « $repo » doit être public et avoir au moins une release."
            if (code == 403) return@withContext null to "GitHub limite les requêtes pour le moment : réessaie dans quelques minutes."
            if (code !in 200..299) return@withContext null to "GitHub a répondu : erreur $code."
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            var url = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isBlank()) return@withContext null to "La dernière version ne contient pas de fichier APK."
            val vc = Regex("versionCode=(\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Release(o.optString("tag_name"), vc, url, size) to null
        } catch (e: java.net.UnknownHostException) {
            null to "Pas de réseau."
        } catch (e: Exception) {
            null to "Impossible de lire les versions (${e.javaClass.simpleName})."
        }
    }

    /** Télécharge l'APK dans le cache de l'appli. Retourne le fichier, ou null en cas d'échec. */
    suspend fun download(ctx: Context, rel: Release, onProgress: suspend (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "update")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "Kineo-update.apk")
            val c = URL(rel.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Kineo")
            if (c.responseCode !in 200..299) return@withContext null
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.sizeBytes
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= last + 3) {
                                last = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            if (out.length() < 100_000L) null else out
        } catch (e: Exception) {
            null
        }
    }

    /** Lance l'installation. Retourne un message si l'utilisateur doit d'abord autoriser l'installation d'applis. */
    fun install(ctx: Context, f: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            return try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Autorise « Kinéo » à installer des applications dans l'écran qui vient de s'ouvrir, puis reviens ici et relance l'installation."
            } catch (e: Exception) {
                "Autorise « Kinéo » à installer des applications (Paramètres > Applications > Sécurité et restrictions > Sources inconnues), puis relance l'installation."
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        } catch (e: Exception) {
            "Impossible de lancer l'installateur de ta TV (${e.javaClass.simpleName})."
        }
    }
}
