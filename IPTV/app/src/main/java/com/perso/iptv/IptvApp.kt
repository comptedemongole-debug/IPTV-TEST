package com.perso.iptv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/** Réglage du chargeur d'images (cache mémoire adapté à l'appareil, cache disque toujours utilisé). */
class IptvApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val low = try {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        } catch (e: Exception) {
            false
        }
        return ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (low) 0.12 else 0.20).build() }
            // beaucoup de serveurs IPTV envoient « no-cache » : sans ça, chaque affiche est retéléchargée
            .respectCacheHeaders(false)
            .crossfade(false)
            // affiches sans transparence : 2 octets par pixel au lieu de 4, donc moitié moins de mémoire
            .allowRgb565(true)
            .build()
    }
}
