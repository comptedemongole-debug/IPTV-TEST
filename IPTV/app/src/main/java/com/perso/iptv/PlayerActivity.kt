package com.perso.iptv

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** Un épisode de la série en cours (pour le menu du lecteur). */
data class EpEntry(val url: String, val label: String, val title: String, val img: String?)

/** Ce qui est en cours de lecture : liste de chaînes ou d'épisodes, pour le menu et le zapping. */
object Zap {
    var items: List<Item> = emptyList()
    var index: Int = 0
    var api: Xtream? = null
    var repo: Repo? = null
    var eps: List<EpEntry> = emptyList()
    var epIndex: Int = 0
}

sealed class MenuEntry {
    class Header(val text: String) : MenuEntry()
    class Row(val label: String, val sub: String, val selected: Boolean, val onPick: () -> Unit) : MenuEntry()
}

private val MENU_BG = Color(0xEE0B0B0F)
private val MENU_ACCENT = Color(0xFFE5484D)

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var exo: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var menuView: ComposeView
    private lateinit var progBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { info.visibility = View.GONE }
    private var live = false
    private var retried = false
    private var curUrl = ""
    private var titleX = ""
    private var imgX: String? = null
    private var kindX = "vod"
    private var pcmMode = false
    private var curSpeed = 1f
    private var menuOpen by mutableStateOf(false)
    private var menuEntries by mutableStateOf<List<MenuEntry>>(emptyList())
    private var menuPinned by mutableStateOf<MenuEntry.Row?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        live = intent.getBooleanExtra("live", false)
        titleX = intent.getStringExtra("title") ?: ""
        imgX = intent.getStringExtra("img")
        kindX = intent.getStringExtra("kind") ?: "vod"
        val startMs = intent.getLongExtra("start", 0L)

        val p = buildPlayer(false)
        exo = p

        view = PlayerView(this)
        view.player = p
        info = TextView(this)
        info.setTextColor(AColor.WHITE)
        info.textSize = 20f
        info.setBackgroundColor(0x99000000.toInt())
        info.setPadding(32, 16, 32, 16)
        info.visibility = View.GONE

        menuView = ComposeView(this)
        menuView.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MENU_ACCENT)) {
                if (menuOpen) PlayerMenu(menuEntries, menuPinned) { closeMenu() }
            }
        }

        progBtn = TextView(this)
        progBtn.text = "≡  Prog"
        progBtn.textSize = 18f
        progBtn.setTextColor(AColor.WHITE)
        progBtn.setPadding(36, 18, 36, 18)
        progBtn.isFocusable = true
        progBtn.isFocusableInTouchMode = true
        progBtn.isClickable = true
        progBtn.background = progBg(false)
        progBtn.visibility = View.GONE
        progBtn.setOnClickListener { openMenu() }
        progBtn.setOnFocusChangeListener { _, hasFocus ->
            progBtn.background = progBg(hasFocus)
            if (hasFocus) view.showController()
        }
        view.controllerShowTimeoutMs = 8000
        view.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                progBtn.visibility = if (visibility == View.VISIBLE && !menuOpen) View.VISIBLE else View.GONE
            }
        })

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(menuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.setMargins(0, 48, 48, 0)
        root.addView(info, lp)
        val lp2 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.gravity = Gravity.TOP or Gravity.START
        lp2.setMargins(48, 48, 0, 0)
        root.addView(progBtn, lp2)
        setContentView(root)
        view.requestFocus()

        start(url, startMs)
        if (live && Zap.items.isNotEmpty()) {
            flash()
        } else if (!live) {
            flashMsg(
                "OK puis ↑ : bouton « Prog »" +
                    (if (episodeContext()) "\nEnchaîner les épisodes : " + (if (Zap.repo?.autoNext == true) "oui" else "non") else "")
            )
        }
    }

    // ---------------------------------------------------------------- lecteur
    private fun buildPlayer(pcm: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0")
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()
        // En mode « PCM », le son n'est pas envoyé tel quel à la TV (passthrough) : c'est ce qui permet de changer la vitesse.
        val rf = object : DefaultRenderersFactory(this@PlayerActivity) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                if (!pcm) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        val p = ExoPlayer.Builder(this, rf)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!retried && curUrl.endsWith(".m3u8")) {
                    retried = true
                    p.setMediaItem(MediaItem.fromUri(curUrl.removeSuffix(".m3u8") + ".ts"))
                    p.prepare()
                    p.play()
                } else if (pcm && curSpeed != 1f) {
                    toast("Vitesse indisponible avec ce son")
                    handler.post { rebuild(false, 1f) }
                } else {
                    toast("Lecture impossible")
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (live) return
                val s = playbackParameters.speed
                if (!pcmMode && s != 1f) {
                    handler.post { rebuild(true, s) }
                } else if (pcmMode && s == 1f && curSpeed != 1f) {
                    handler.post { rebuild(false, 1f) }
                } else {
                    curSpeed = s
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (pcm && curSpeed != 1f) {
                    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audio.isNotEmpty() && audio.none { it.isSelected }) {
                        toast("Vitesse indisponible avec ce son")
                        handler.post { rebuild(false, 1f) }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !live && Zap.repo?.autoNext == true) {
                    val cur = Zap.eps.getOrNull(Zap.epIndex)
                    if (cur != null && cur.url == curUrl && Zap.epIndex + 1 < Zap.eps.size) {
                        handler.post { playEpisode(Zap.epIndex + 1) }
                    }
                }
            }
        })
        return p
    }

    /** Recrée le lecteur (avec ou sans « passthrough » du son) à la même position, avec la vitesse voulue. */
    private fun rebuild(pcm: Boolean, speed: Float) {
        val old = exo ?: return
        val pos = old.currentPosition
        val play = old.playWhenReady
        old.release()
        pcmMode = pcm
        curSpeed = speed
        val p = buildPlayer(pcm)
        exo = p
        view.player = p
        p.setMediaItem(MediaItem.fromUri(curUrl), pos)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.playWhenReady = play
        if (speed != 1f) flashMsg("Vitesse x${fmtSpeed(speed)}")
    }

    private fun start(url: String, startMs: Long = 0L) {
        curUrl = url
        retried = false
        val p = exo ?: return
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fmtSpeed(s: Float): String =
        if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().replace('.', ',')

    // ---------------------------------------------------------------- chaînes et épisodes
    private fun zap(delta: Int) {
        val n = Zap.items.size
        if (n == 0) return
        zapTo((Zap.index + delta + n) % n)
    }

    private fun zapTo(i: Int) {
        val a = Zap.api ?: return
        if (i !in Zap.items.indices) return
        Zap.index = i
        start(a.liveUrl(Zap.items[i].id))
        flash()
    }

    private fun playEpisode(i: Int) {
        val e = Zap.eps.getOrNull(i) ?: return
        saveProgress()
        Zap.epIndex = i
        titleX = e.title
        imgX = e.img
        kindX = "series"
        start(e.url, Zap.repo?.resumeFor(e.url)?.pos ?: 0L)
    }

    private fun flash() {
        val item = Zap.items.getOrNull(Zap.index) ?: return
        val prog = Zap.repo?.progsFor(item)?.firstOrNull()?.title
        flashMsg("${Zap.index + 1}   ${item.name}" + (if (prog != null) "\n$prog" else ""))
    }

    private fun flashMsg(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 4000)
    }

    private fun saveProgress() {
        if (live) return
        val p = exo ?: return
        val dur = p.duration
        if (dur > 0) Zap.repo?.saveResume(kindX, curUrl, titleX, imgX, p.currentPosition, dur)
    }

    private fun progBg(focused: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0xCC000000.toInt())
        g.cornerRadius = 80f
        g.setStroke(if (focused) 6 else 2, if (focused) 0xFFE5484D.toInt() else 0x66FFFFFF)
        return g
    }

    private fun episodeContext(): Boolean = Zap.eps.getOrNull(Zap.epIndex)?.url == curUrl && curUrl.isNotEmpty()

    private fun autoToggleRow(): MenuEntry.Row {
        val auto = Zap.repo?.autoNext == true
        return MenuEntry.Row("Enchaîner les épisodes : " + (if (auto) "☑ oui" else "☐ non"), "", false) {
            Zap.repo?.updateAutoNext(!auto)
            menuPinned = autoToggleRow()
        }
    }

    // ---------------------------------------------------------------- menu latéral
    private fun buildMenu(): List<MenuEntry> {
        val out = ArrayList<MenuEntry>()
        if (live) {
            out.add(MenuEntry.Header("Chaînes"))
            Zap.items.forEachIndexed { i, ch ->
                val prog = Zap.repo?.progsFor(ch)?.firstOrNull()?.title ?: ""
                out.add(MenuEntry.Row("${i + 1}   ${ch.name}", prog, i == Zap.index) { zapTo(i); closeMenu() })
            }
        } else {
            out.add(MenuEntry.Header("Vitesse de lecture"))
            for (s in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)) {
                val label = "x" + fmtSpeed(s) + (if (s == 1f) "  (normal)" else "")
                out.add(MenuEntry.Row(label, "", s == curSpeed) { exo?.setPlaybackSpeed(s); closeMenu() })
            }
            val cur = Zap.eps.getOrNull(Zap.epIndex)
            if (cur != null && cur.url == curUrl) {
                out.add(MenuEntry.Header("Épisodes"))
                Zap.eps.forEachIndexed { i, e ->
                    out.add(MenuEntry.Row(e.label, "", i == Zap.epIndex) { playEpisode(i); closeMenu() })
                }
            }
        }
        return out
    }

    private fun openMenu() {
        menuEntries = buildMenu()
        menuPinned = if (!live && episodeContext()) autoToggleRow() else null
        menuOpen = true
        progBtn.visibility = View.GONE
        menuView.requestFocus()
    }

    private fun closeMenu() {
        menuOpen = false
        view.requestFocus()
        if (view.isControllerFullyVisible) progBtn.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            // Touche « ≡ » (menu) ou guide : ouvre / ferme le menu latéral
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_GUIDE) {
                if (menuOpen) closeMenu() else openMenu()
                return true
            }
            if (!menuOpen) {
                val zapping = live && Zap.items.isNotEmpty()
                if (zapping) {
                    // Touches P+ / P- : toujours actives
                    when (k) {
                        KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                    }
                }
                if (zapping && !view.isControllerFullyVisible) {
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_UP -> { zap(1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); return true }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        exo?.release()
        exo = null
        super.onDestroy()
    }
}

@Composable
fun PlayerMenu(entries: List<MenuEntry>, pinned: MenuEntry.Row?, onClose: () -> Unit) {
    val sel = entries.indexOfLast { it is MenuEntry.Row && it.selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, sel - 3))
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        try { fr.requestFocus() } catch (e: Exception) { /* ignoré */ }
    }
    BackHandler { onClose() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(400.dp).fillMaxHeight().background(MENU_BG).padding(vertical = 16.dp)) {
            Text(
                "≡  Menu du lecteur", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (pinned != null) MenuRow(pinned, Modifier)
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                itemsIndexed(entries) { i, e ->
                    when (e) {
                        is MenuEntry.Header -> Text(
                            e.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MENU_ACCENT,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                        )
                        is MenuEntry.Row -> MenuRow(e, if (i == sel) Modifier.focusRequester(fr) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
fun MenuRow(e: MenuEntry.Row, modifier: Modifier) {
    var f by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .background(if (f) Color(0x44FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = e.onPick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            (if (e.selected) "▶  " else "") + e.label, fontSize = 15.sp,
            color = if (e.selected) MENU_ACCENT else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (e.sub.isNotBlank()) {
            Text(e.sub, fontSize = 12.sp, color = Color(0xFFC9C9D2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
