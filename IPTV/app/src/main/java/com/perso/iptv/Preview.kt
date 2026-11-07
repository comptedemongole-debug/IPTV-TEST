package com.perso.iptv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Lecteur unique de l'aperçu (petit carré de l'onglet TV).
 * Quand on ouvre le plein écran, c'est CE MÊME lecteur qui est transmis : pas de nouvelle connexion, pas de coupure.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object Preview {
    var player: ExoPlayer? = null
    var url: String = ""
    var lent: Boolean = false
    var item by mutableStateOf<Item?>(null)

    private fun build(ctx: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0")
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(ctx)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http))
            .build()
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (url.endsWith(".m3u8")) {
                    val alt = url.removeSuffix(".m3u8") + ".ts"
                    url = alt
                    p.setMediaItem(MediaItem.fromUri(alt))
                    p.prepare()
                    p.play()
                }
            }
        })
        return p
    }

    /** Coupe ou remet le son (l'aperçu est muet). On désactive la piste audio : fiable même en « passthrough ». */
    fun muteAudio(p: ExoPlayer, mute: Boolean) {
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, mute)
            .build()
    }

    fun play(ctx: Context, u: String, ch: Item) {
        var p = player
        if (p == null) {
            p = build(ctx.applicationContext)
            player = p
            muteAudio(p, true)
        }
        url = u
        item = ch
        p.setMediaItem(MediaItem.fromUri(u))
        p.prepare()
        p.playWhenReady = true
    }

    fun release() {
        try { player?.release() } catch (e: Exception) { /* ignoré */ }
        player = null
        url = ""
        item = null
        lent = false
    }
}
