package com.perso.iptv

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import coil.imageLoader

data class SysInfo(val freeRamMb: Long, val totalRamMb: Long, val freeStorageMb: Long, val totalStorageMb: Long)

/**
 * Gestion des ressources, sans jamais toucher aux autres applications : Android l'interdit aux applications normales
 * et fermer des processus n'accélère pas une TV (ils redémarrent aussitôt). On libère uniquement ce qui appartient à Kinéo.
 */
object Booster {
    fun info(ctx: Context): SysInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        return SysInfo(
            mi.availMem / 1048576L, mi.totalMem / 1048576L,
            st.availableBytes / 1048576L, st.totalBytes / 1048576L
        )
    }

    fun isLowRam(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    } catch (e: Exception) {
        false
    }

    /** Vide les caches mémoire de l'application (images, fiches TMDB). Retourne les Mo d'images libérés. */
    fun trimOwnCaches(ctx: Context): Long {
        var freed = 0L
        try {
            val mc = ctx.applicationContext.imageLoader.memoryCache
            if (mc != null) {
                freed = mc.size / 1048576L
                mc.clear()
            }
        } catch (e: Exception) {
            /* ignoré */
        }
        Tmdb.clearCache()
        return freed
    }
}
