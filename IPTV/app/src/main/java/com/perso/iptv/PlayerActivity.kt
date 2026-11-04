package com.perso.iptv

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var exo: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0")
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        exo = p

        var retried = false
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried && url.endsWith(".m3u8")) {
                    retried = true
                    p.setMediaItem(MediaItem.fromUri(url.removeSuffix(".m3u8") + ".ts"))
                    p.prepare()
                    p.play()
                } else {
                    Toast.makeText(this@PlayerActivity, "Lecture impossible", Toast.LENGTH_LONG).show()
                }
            }
        })

        val view = PlayerView(this)
        view.player = p
        setContentView(view)
        view.requestFocus()

        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    override fun onDestroy() {
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
