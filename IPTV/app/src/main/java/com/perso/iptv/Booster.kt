package com.perso.iptv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs

data class SysInfo(val freeRamMb: Long, val totalRamMb: Long, val freeStorageMb: Long, val totalStorageMb: Long)

/** Outils d'optimisation de la TV : mémoire libre, fermeture des processus en arrière-plan. */
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

    /**
     * Demande à Android de fermer les processus d'arrière-plan des autres applis
     * (ceux qu'il fermerait de toute façon si la mémoire manquait).
     * À appeler hors du thread principal. Retourne la mémoire gagnée en Mo (approximatif).
     */
    fun boost(ctx: Context): Long {
        val before = info(ctx).freeRamMb
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val home = try {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        val skip = setOf(ctx.packageName, "android", "com.android.systemui", home ?: "")
        try {
            pm.getInstalledApplications(0).forEach { a ->
                val n = a.packageName
                if (n !in skip) {
                    try { am.killBackgroundProcesses(n) } catch (e: Exception) { /* ignoré */ }
                }
            }
        } catch (e: Exception) {
            /* ignoré */
        }
        try { Thread.sleep(600) } catch (e: InterruptedException) { /* ignoré */ }
        return info(ctx).freeRamMb - before
    }
}
