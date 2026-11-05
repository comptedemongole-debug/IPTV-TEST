package com.perso.iptv

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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

/** Liste de chaînes en cours de lecture, pour changer de chaîne avec haut/bas. */
object Zap {
    var items: List<Item> = emptyList()
    var index: Int = 0
    var api: Xtream? = null
    var repo: Repo? = null
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var exo: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var live = false
    private var retried = false
    private var curUrl = ""
    private var titleX = ""
    private var imgX: String? = null
    private var kindX = "vod"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        live = intent.getBooleanExtra("live", false)
        titleX = intent.getStringExtra("title") ?: ""
        imgX = intent.getStringExtra("img")
        kindX = intent.getStringExtra("kind") ?: "vod"
        val startMs = intent.getLongExtra("start", 0L)

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

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried && curUrl.endsWith(".m3u8")) {
                    retried = true
                    val alt = curUrl.removeSuffix(".m3u8") + ".ts"
                    p.setMediaItem(MediaItem.fromUri(alt))
                    p.prepare()
                    p.play()
                } else {
                    Toast.makeText(this@PlayerActivity, "Lecture impossible", Toast.LENGTH_LONG).show()
                }
            }
        })

        view = PlayerView(this)
        view.player = p
        info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 20f
        info.setBackgroundColor(0x99000000.toInt())
        info.setPadding(32, 16, 32, 16)
        info.visibility = View.GONE

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setMargins(48, 48, 0, 0)
        root.addView(info, lp)
        setContentView(root)
        view.requestFocus()

        start(url, startMs)
        if (live && Zap.items.isNotEmpty()) flash()
    }

    private fun start(url: String, startMs: Long = 0L) {
        curUrl = url
        retried = false
        exo?.setMediaItem(MediaItem.fromUri(url), startMs)
        exo?.prepare()
        exo?.playWhenReady = true
    }

    private fun zap(delta: Int) {
        val a = Zap.api ?: return
        val n = Zap.items.size
        if (n == 0) return
        Zap.index = (Zap.index + delta + n) % n
        start(a.liveUrl(Zap.items[Zap.index].id))
        flash()
    }

    private fun flash() {
        val item = Zap.items.getOrNull(Zap.index) ?: return
        val prog = Zap.repo?.progsFor(item)?.firstOrNull()?.title
        info.text = "${Zap.index + 1}   ${item.name}" + (if (prog != null) "\n$prog" else "")
        info.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ info.visibility = View.GONE }, 4000)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && live && Zap.items.isNotEmpty()) {
            when (e.keyCode) {
                // touches P+ / P- de la télécommande : toujours actives
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            }
            // flèches haut / bas : seulement si la barre de lecture n'est pas affichée
            if (!view.isControllerFullyVisible) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { zap(1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); return true }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        if (!live) {
            val p = exo
            val dur = p?.duration ?: -1L
            if (p != null && dur > 0) {
                Zap.repo?.saveResume(kindX, curUrl, titleX, imgX, p.currentPosition, dur)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
