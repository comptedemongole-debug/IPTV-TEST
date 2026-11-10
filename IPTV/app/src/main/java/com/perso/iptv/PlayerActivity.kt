package com.perso.iptv

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Un épisode de la série en cours (pour le menu Prog). */
data class EpEntry(
    val url: String, val label: String, val title: String, val img: String?, val season: String,
    val seriesId: String = "", val num: String = ""
)

/** Ce qui est en cours de lecture : chaînes, épisodes, ou file de films (catégorie / playlist). */
object Zap {
    var items: List<Item> = emptyList()
    var index: Int = 0
    var api: Xtream? = null
    var repo: Repo? = null
    var eps: List<EpEntry> = emptyList()
    var epIndex: Int = 0
    var vodQueue: List<Item> = emptyList()
    var vodIndex: Int = 0
    var queueIsPlaylist: Boolean = false
    var queueName: String? = null

    /** Vrai tant qu'un lecteur plein écran est ouvert : les mises à jour en arrière-plan se mettent en pause. */
    @Volatile var playing: Boolean = false
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
    private var listener: Player.Listener? = null
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var menuView: ComposeView
    private lateinit var progBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { info.visibility = View.GONE }
    private var live = false
    private var retried = false
    private var curUrl = ""

    // --- fiabilité : reconnexion, surveillance du buffer, changement de source
    private var baseUrl = ""            // adresse « officielle » du contenu (reprise de lecture, épisode suivant…)
    private var attempts = 0            // reconnexions tentées depuis la dernière lecture stable
    private var bufSince = 0L           // début de la mise en mémoire en cours (0 = pas en cours)
    private var readySince = 0L         // début de la lecture stable en cours
    private var hasPlayed = false
    private var failed = false          // plus aucune source ne fonctionne
    private var offlineFail = false
    private var statusOn = false
    private val triedUrls = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectPending = false
    private val reconnectTask = Runnable { doReconnect() }
    private val watchdog = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }
    // --- confort de lecture : vitesse, minuteur, taille des sous-titres, numéro de chaîne
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIdx = 1
    private var sleepEnd = 0L           // 0 = pas de minuteur (SystemClock.elapsedRealtime)
    private val sleepTask = Runnable {
        saveProgress()
        toast("Minuteur de sommeil : lecture arrêtée")
        finish()
    }
    private val subSizes = floatArrayOf(0.04f, 0.0533f, 0.07f, 0.09f)
    private val subSizeNames = arrayOf("Petite", "Normale", "Grande", "Très grande")
    private var subSizeIdx = 1
    private val numBuf = StringBuilder()
    private val numTask = Runnable { commitNumber() }
    private var titleX = ""
    private var imgX: String? = null
    private var kindX = "vod"
    private var menuOpen by mutableStateOf(false)
    private var menuEntries by mutableStateOf<List<MenuEntry>>(emptyList())
    private var menuPinned by mutableStateOf<MenuEntry.Row?>(null)
    private var menuTitle by mutableStateOf("")
    private var menuSub by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        Zap.playing = true
        live = intent.getBooleanExtra("live", false)
        titleX = intent.getStringExtra("title") ?: ""
        imgX = intent.getStringExtra("img")
        kindX = intent.getStringExtra("kind") ?: "vod"
        val startMs = intent.getLongExtra("start", 0L)

        // Plein écran depuis l'aperçu : on reprend le MÊME lecteur, sans couper la chaîne
        val handoffId = intent.getStringExtra("handoffId")
        val prev = Preview.player
        val borrowed = live && handoffId != null && prev != null && Preview.item?.id == handoffId
        val p: ExoPlayer
        if (borrowed && prev != null) {
            p = prev
            Preview.lent = true
            Preview.muteAudio(p, false)
            curUrl = Preview.url
            baseUrl = curUrl
            triedUrls.add(curUrl)
            retried = curUrl.endsWith(".ts")
            attachListener(p)
        } else {
            p = buildPlayer()
        }
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
                if (menuOpen) PlayerMenu(menuEntries, menuPinned, menuTitle, menuSub) { closeMenu() }
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
        subSizeIdx = try {
            getSharedPreferences("kineo_player", MODE_PRIVATE).getInt("sub_size", 1).coerceIn(0, subSizes.size - 1)
        } catch (e: Exception) { 1 }
        applySubSize()
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

        if (!borrowed) start(url, startMs)
        handler.postDelayed(watchdog, 1000)
        if (live && Zap.items.isNotEmpty()) {
            flash()
        } else if (!live) {
            flashMsg(
                "OK puis ↑ : bouton « Prog »\nLecture automatique : " +
                    (if (Zap.repo?.autoNext == true) "oui" else "non")
            )
        }
    }

    // ---------------------------------------------------------------- lecteur
    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val perf = Zap.repo?.perf == true
        val lb = DefaultLoadControl.Builder()
        // Mode performance : tampon plus court = moins de mémoire, plus de marge pour l'interface
        if (perf) lb.setBufferDurationsMs(15_000, 50_000, 2_000, 4_000)
        else lb.setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
        if (perf || Booster.isLowRam(this)) lb.setTargetBufferBytes(48 * 1024 * 1024)
        val p = ExoPlayer.Builder(this, makeRenderers(this, Zap.repo?.pcmAudio == true))
            .setLoadControl(lb.build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        attachListener(p)
        return p
    }

    private fun attachListener(p: ExoPlayer) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handleError(p, error)
            }

            override fun onTracksChanged(tracks: Tracks) {
                val ag = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (ag.isNotEmpty() && ag.none { it.isSelected }) {
                    val codecs = ag.flatMap { g -> (0 until g.length).map { codecName(g.getTrackFormat(it).sampleMimeType) } }
                        .filter { it.isNotBlank() }.distinct().joinToString(", ")
                    flashMsg(
                        "Pas de son : format audio non pris en charge" + (if (codecs.isNotBlank()) " ($codecs)" else "") +
                            ".\nMenu Prog > piste audio, ou Réglages > Son."
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val now = SystemClock.elapsedRealtime()
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (bufSince == 0L) bufSince = now
                        readySince = 0L
                    }
                    Player.STATE_READY -> {
                        bufSince = 0L
                        hasPlayed = true
                        if (readySince == 0L) readySince = now
                        failed = false
                        if (statusOn) clearStatus()
                    }
                    Player.STATE_ENDED -> {
                        bufSince = 0L
                        if (live) recover("Le flux s'est arrêté", 4) else handler.post { onEnded() }
                    }
                    else -> {
                        bufSince = 0L
                        readySince = 0L
                    }
                }
            }
        }
        listener = l
        p.addListener(l)
    }

    private fun start(url: String, startMs: Long = 0L) {
        curUrl = url
        baseUrl = url
        retried = false
        resetReliability()
        triedUrls.add(url)
        val p = exo ?: return
        if (p === Preview.player) Preview.url = url
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- anti-coupure
    private fun resetReliability() {
        handler.removeCallbacks(reconnectTask)
        reconnectPending = false
        attempts = 0
        bufSince = 0L
        readySince = 0L
        hasPlayed = false
        failed = false
        offlineFail = false
        triedUrls.clear()
        if (statusOn) clearStatus()
    }

    private fun online(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val c = if (n != null) cm.getNetworkCapabilities(n) else null
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }

    private fun showStatus(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideTask)
        statusOn = true
    }

    private fun clearStatus() {
        statusOn = false
        info.visibility = View.GONE
    }

    private fun handleError(p: ExoPlayer, error: PlaybackException) {
        val code = error.errorCode
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        // Flux en .m3u8 refusé : on essaie le même flux en .ts (comportement d'origine, une seule fois)
        if (!retried && curUrl.endsWith(".m3u8")) {
            retried = true
            val alt = curUrl.removeSuffix(".m3u8") + ".ts"
            curUrl = alt
            triedUrls.add(alt)
            if (p === Preview.player) Preview.url = alt
            p.setMediaItem(MediaItem.fromUri(alt))
            p.prepare()
            p.play()
            return
        }
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
        val decoder = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        val network = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            code == PlaybackException.ERROR_CODE_TIMEOUT
        val reason: String
        val sameUrlRetries: Int
        when {
            http == 404 || http == 410 -> { reason = "Flux introuvable"; sameUrlRetries = 0 }
            http == 401 || http == 403 -> { reason = "Accès refusé par le serveur"; sameUrlRetries = 1 }
            http >= 500 -> { reason = "Serveur indisponible"; sameUrlRetries = 3 }
            decoder -> { reason = "Format audio/vidéo non pris en charge"; sameUrlRetries = 1 }
            network -> { reason = if (online()) "Connexion perdue" else "Pas de connexion réseau"; sameUrlRetries = 4 }
            else -> { reason = "Erreur de lecture"; sameUrlRetries = 3 }
        }
        recover(reason, sameUrlRetries)
    }

    /**
     * Reconnexion progressive (1 s, 2 s, 4 s, 8 s) sur la même adresse ; si ça ne suffit pas, message clair. Le nombre d'essais est borné : jamais de boucle infinie.
     * Une simple lenteur ne déclenche rien (voir tick()).
     */
    private fun recover(reason: String, sameUrlRetries: Int) {
        if (failed || exo == null) return
        if (reconnectPending) return
        attempts++
        if (attempts <= sameUrlRetries) {
            val wait = minOf(1000L shl (attempts - 1), 8000L)
            showStatus("$reason\nReconnexion ($attempts/$sameUrlRetries)…")
            reconnectPending = true
            handler.postDelayed(reconnectTask, wait)
        } else {
            giveUp(reason)
        }
    }

    private fun doReconnect() {
        reconnectPending = false
        val p = exo ?: return
        bufSince = 0L
        readySince = 0L
        val pos = if (live) 0L else p.currentPosition
        if (pos > 0L) p.setMediaItem(MediaItem.fromUri(curUrl), pos) else p.setMediaItem(MediaItem.fromUri(curUrl))
        p.prepare()
        p.playWhenReady = true
    }

    private fun giveUp(reason: String) {
        failed = true
        offlineFail = !online()
        showStatus(
            if (offlineFail) "Pas de connexion réseau.\nLa lecture reprendra toute seule au retour du réseau."
            else "$reason.\nAucune source ne fonctionne pour ce contenu.\nOK : réessayer   ·   Retour : quitter"
        )
    }

    private fun retryAll() {
        val p = exo ?: return
        val pos = if (live) 0L else p.currentPosition
        val url = baseUrl.ifBlank { curUrl }
        resetReliability()
        curUrl = url
        triedUrls.add(url)
        retried = false
        if (pos > 0L) p.setMediaItem(MediaItem.fromUri(url), pos) else p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    /** Surveillance une fois par seconde : blocage prolongé du buffer, retour du réseau, fin d'une série de reconnexions. */
    private fun tick() {
        val p = exo ?: return
        val now = SystemClock.elapsedRealtime()
        if (failed) {
            if (offlineFail && online()) retryAll()
            return
        }
        if (!p.playWhenReady) return
        when (p.playbackState) {
            Player.STATE_READY -> {
                // 20 s de lecture continue : la source est saine, on repart d'un compteur neuf
                if (readySince != 0L && now - readySince > 20_000L && attempts != 0) attempts = 0
            }
            Player.STATE_BUFFERING -> {
                if (bufSince == 0L) bufSince = now
                val waited = now - bufSince
                val limit = if (!hasPlayed) 30_000L else if (live) 15_000L else 25_000L
                if (waited > 8_000L && !statusOn && !reconnectPending) {
                    showStatus("Connexion lente… patience")
                }
                if (waited > limit) {
                    bufSince = now
                    recover(if (online()) "Le flux est bloqué" else "Pas de connexion réseau", 3)
                }
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- chaînes, épisodes, films
    private fun episodeContext(): Boolean = baseUrl.isNotEmpty() && Zap.eps.getOrNull(Zap.epIndex)?.url == baseUrl

    private fun vodContext(): Boolean {
        val m = Zap.vodQueue.getOrNull(Zap.vodIndex) ?: return false
        val a = Zap.api ?: return false
        return a.vodUrl(m.id, m.ext) == baseUrl
    }

    private fun onEnded() {
        saveProgress()
        val auto = Zap.repo?.autoNext == true
        if (episodeContext()) {
            if (auto && Zap.epIndex + 1 < Zap.eps.size) playEpisode(Zap.epIndex + 1)
        } else if (vodContext()) {
            if ((Zap.queueIsPlaylist || auto) && Zap.vodIndex + 1 < Zap.vodQueue.size) playMovie(Zap.vodIndex + 1)
        }
    }

    private fun zap(delta: Int) {
        val n = Zap.items.size
        if (n == 0) return
        zapTo((Zap.index + delta + n) % n)
    }

    private fun zapTo(i: Int) {
        val a = Zap.api ?: return
        if (i !in Zap.items.indices) return
        Zap.index = i
        val ch = Zap.items[i]
        Zap.repo?.addRecent(ch)
        if (exo === Preview.player) Preview.item = ch
        start(a.liveUrl(ch.id))
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

    private fun playMovie(i: Int) {
        val m = Zap.vodQueue.getOrNull(i) ?: return
        val a = Zap.api ?: return
        saveProgress()
        Zap.vodIndex = i
        val url = a.vodUrl(m.id, m.ext)
        titleX = m.name
        imgX = m.img
        kindX = "vod"
        start(url, Zap.repo?.resumeFor(url)?.pos ?: 0L)
    }

    private fun playSingle(m: Item) {
        Zap.vodQueue = listOf(m)
        Zap.vodIndex = 0
        Zap.queueIsPlaylist = false
        Zap.queueName = null
        playMovie(0)
    }

    private fun flash() {
        val item = Zap.items.getOrNull(Zap.index) ?: return
        val prog = Zap.repo?.progsFor(item)?.firstOrNull()?.title
        flashMsg("${Zap.index + 1}   ${item.name}" + (if (prog != null) "\n$prog" else ""))
    }

    private fun flashMsg(text: String) {
        statusOn = false
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 4000)
    }

    private fun fmtPos(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }

    private fun saveProgress() {
        if (live) return
        val p = exo ?: return
        val dur = p.duration
        if (dur > 0) Zap.repo?.saveResume(kindX, baseUrl.ifBlank { curUrl }, titleX, imgX, p.currentPosition, dur)
    }

    // ---------------------------------------------------------------- bouton et menu Prog
    private fun progBg(focused: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0xCC000000.toInt())
        g.cornerRadius = 80f
        g.setStroke(if (focused) 6 else 2, if (focused) 0xFFE5484D.toInt() else 0x66FFFFFF)
        return g
    }

    private fun autoToggleRow(): MenuEntry.Row {
        val auto = Zap.repo?.autoNext == true
        return MenuEntry.Row("Lecture automatique : " + (if (auto) "☑ oui" else "☐ non"), "épisode ou film suivant", false) {
            Zap.repo?.updateAutoNext(!auto)
            menuPinned = autoToggleRow()
        }
    }

    private fun codecName(mime: String?): String = when (mime) {
        null -> ""
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/eac3-joc" -> "Atmos"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS"
        "audio/true-hd" -> "TrueHD"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun langName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return ""
        return try {
            val n = java.util.Locale(code).getDisplayLanguage(java.util.Locale.FRENCH)
            if (n.isNullOrBlank() || n.equals(code, true)) code.uppercase() else n.replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            code.uppercase()
        }
    }

    /** Toutes les pistes audio réellement exposées par le lecteur, avec langue et codec ; celles non décodables sont marquées. */
    private fun audioEntries(): List<MenuEntry> {
        val p = exo ?: return emptyList()
        val groups = p.currentTracks.groups
        if (groups.isEmpty()) return emptyList()       // pistes pas encore chargées
        val rows = ArrayList<MenuEntry>()
        var total = 0
        var anyBad = false
        groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                total++
                val f = g.getTrackFormat(i)
                val ok = g.isTrackSupported(i)
                if (!ok) anyBad = true
                val ch = when (f.channelCount) {
                    1 -> "mono"
                    2 -> "stéréo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> if (f.channelCount > 0) "${f.channelCount} canaux" else ""
                }
                val lang = langName(f.language).ifBlank { f.label ?: "Piste $total" }
                val label = listOf(lang, codecName(f.sampleMimeType), ch).filter { it.isNotBlank() }.joinToString(" · ")
                rows.add(
                    MenuEntry.Row(label + (if (!ok) "  ✕" else ""), if (!ok) "non pris en charge sur cet appareil" else "", g.isTrackSelected(i)) {
                        if (ok) {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            closeMenu()
                        }
                    }
                )
            }
        }
        if (total <= 1 && !anyBad && !(total == 0 && !live)) return emptyList()
        val out = ArrayList<MenuEntry>()
        out.add(MenuEntry.Header("Piste audio"))
        if (total == 0) {
            out.add(MenuEntry.Row("Aucune piste audio détectée dans ce flux", "", false) { })
        } else {
            out.addAll(rows)
            if (total > 1) {
                out.add(MenuEntry.Row("Automatique", "choix par défaut du lecteur", false) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .build()
                    closeMenu()
                })
            }
        }
        return out
    }

    private fun applySubSize() {
        view.subtitleView?.setFractionalTextSize(subSizes[subSizeIdx])
    }

    private fun refreshMenu() {
        menuEntries = buildMenu()
    }

    private fun speedLabel(f: Float): String = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString().replace('.', ',')) + "×"

    private fun sleepLabel(): String {
        if (sleepEnd == 0L) return "désactivé"
        val left = ((sleepEnd - SystemClock.elapsedRealtime()) / 60000L).toInt() + 1
        return "arrêt dans $left min"
    }

    private fun setSleep(minutes: Int) {
        handler.removeCallbacks(sleepTask)
        if (minutes <= 0) {
            sleepEnd = 0L
        } else {
            sleepEnd = SystemClock.elapsedRealtime() + minutes * 60000L
            handler.postDelayed(sleepTask, minutes * 60000L)
        }
    }

    /** Vitesse (films / séries), minuteur de sommeil, saut d'intro. */
    private fun playbackEntries(): List<MenuEntry> {
        val p = exo ?: return emptyList()
        val out = ArrayList<MenuEntry>()
        out.add(MenuEntry.Header("Lecture"))
        if (!live) {
            out.add(MenuEntry.Row("Vitesse : ${speedLabel(speeds[speedIdx])}", "OK pour changer (0,75× à 2×)", false) {
                speedIdx = (speedIdx + 1) % speeds.size
                p.setPlaybackSpeed(speeds[speedIdx])
                refreshMenu()
            })
            out.add(MenuEntry.Row("Passer l'intro (+90 s)", "avance de 1 min 30", false) {
                val dur = p.duration
                var t = p.currentPosition + 90_000L
                if (dur > 0) t = minOf(t, dur - 1000L)
                p.seekTo(maxOf(0L, t))
                closeMenu()
            })
        }
        out.add(MenuEntry.Row("Minuteur de sommeil : ${sleepLabel()}", "OK pour changer (30 / 60 / 90 min)", false) {
            val cur = if (sleepEnd == 0L) 0 else ((sleepEnd - SystemClock.elapsedRealtime()) / 60000L).toInt() + 1
            setSleep(when { sleepEnd == 0L -> 30; cur <= 30 -> 60; cur <= 60 -> 90; else -> 0 })
            refreshMenu()
        })
        return out
    }

    /** Pistes de sous-titres réellement présentes + taille d'affichage. */
    private fun subtitleEntries(): List<MenuEntry> {
        val p = exo ?: return emptyList()
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val out = ArrayList<MenuEntry>()
        out.add(MenuEntry.Header("Sous-titres"))
        if (groups.isEmpty()) {
            out.add(MenuEntry.Row("Aucun sous-titre dans ce flux", "", false) { })
            return out
        }
        out.add(MenuEntry.Row("Désactivés", "", groups.none { it.isSelected }) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            closeMenu()
        })
        var n = 0
        groups.forEach { g ->
            for (i in 0 until g.length) {
                n++
                val f = g.getTrackFormat(i)
                val ok = g.isTrackSupported(i)
                val label = langName(f.language).ifBlank { f.label ?: "Piste $n" }
                out.add(MenuEntry.Row(label + (if (!ok) "  ✕" else ""), if (!ok) "non pris en charge" else (f.label ?: ""), g.isTrackSelected(i)) {
                    if (ok) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                        closeMenu()
                    }
                })
            }
        }
        out.add(MenuEntry.Row("Taille : ${subSizeNames[subSizeIdx]}", "OK pour changer", false) {
            subSizeIdx = (subSizeIdx + 1) % subSizes.size
            applySubSize()
            try { getSharedPreferences("kineo_player", MODE_PRIVATE).edit().putInt("sub_size", subSizeIdx).apply() } catch (e: Exception) { /* ignoré */ }
            refreshMenu()
        })
        return out
    }

    // ---- numéro de chaîne tapé à la télécommande
    private fun digitOf(k: Int): Int = when (k) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    private fun commitNumber() {
        handler.removeCallbacks(numTask)
        if (numBuf.isEmpty()) return
        val n = numBuf.toString().toIntOrNull() ?: 0
        numBuf.setLength(0)
        if (n in 1..Zap.items.size) zapTo(n - 1) else flashMsg("Chaîne $n introuvable")
    }

    private fun buildMenu(): List<MenuEntry> {
        val out = ArrayList<MenuEntry>()
        out.addAll(playbackEntries())
        out.addAll(audioEntries())
        out.addAll(subtitleEntries())
        if (live) {
            out.add(MenuEntry.Header("Chaînes"))
            Zap.items.forEachIndexed { i, ch ->
                val prog = Zap.repo?.progsFor(ch)?.firstOrNull()?.title ?: ""
                out.add(MenuEntry.Row("${i + 1}   ${ch.name}", prog, i == Zap.index) { zapTo(i); closeMenu() })
            }
        } else if (episodeContext()) {
            var season = ""
            Zap.eps.forEachIndexed { i, e ->
                if (e.season != season) {
                    season = e.season
                    out.add(MenuEntry.Header("Saison $season"))
                }
                out.add(MenuEntry.Row(e.label, "", i == Zap.epIndex) { playEpisode(i); closeMenu() })
            }
        } else {
            if (vodContext()) {
                val title = if (Zap.queueIsPlaylist) "Playlist « ${Zap.queueName ?: ""} »" else "Dans la même catégorie"
                out.add(MenuEntry.Header(title))
                Zap.vodQueue.forEachIndexed { i, m ->
                    out.add(MenuEntry.Row(m.name, m.lang, i == Zap.vodIndex) { playMovie(i); closeMenu() })
                }
            }
            val favs = Zap.repo?.favItems("vod") ?: emptyList()
            if (favs.isNotEmpty()) {
                out.add(MenuEntry.Header("Ma liste"))
                favs.forEach { m -> out.add(MenuEntry.Row(m.name, m.lang, false) { playSingle(m); closeMenu() }) }
            }
        }
        return out
    }

    private fun openMenu() {
        menuEntries = buildMenu()
        menuPinned = if (!live) autoToggleRow() else null
        val curEp = Zap.eps.getOrNull(Zap.epIndex)
        if (live) {
            menuTitle = Zap.items.getOrNull(Zap.index)?.name ?: ""
            menuSub = "Chaîne en cours"
        } else if (episodeContext() && curEp != null) {
            menuTitle = curEp.title.substringBefore(" · ")
            menuSub = "En cours : " + curEp.label
        } else {
            menuTitle = titleX
            menuSub = "En cours de lecture"
        }
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
            // Touche « ≡ » de la télécommande (si la TV la transmet) : ouvre / ferme le menu Prog
            if (failed && !menuOpen && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                retryAll()
                return true
            }
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_GUIDE) {
                if (menuOpen) closeMenu() else openMenu()
                return true
            }
            // Films / séries : ← −10 s, → +10 s quand les commandes sont cachées
            if (!menuOpen && !live && !failed && !view.isControllerFullyVisible &&
                (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                val p = exo
                if (p != null) {
                    val d = if (k == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                    val dur = p.duration
                    var t = p.currentPosition + d
                    if (dur > 0) t = minOf(t, dur - 1000L)
                    p.seekTo(maxOf(0L, t))
                    flashMsg((if (d < 0) "−10 s" else "+10 s") + "   " + fmtPos(maxOf(0L, t)))
                    return true
                }
            }
            if (!menuOpen && live && Zap.items.isNotEmpty()) {
                val d = digitOf(k)
                if (d >= 0) {
                    if (numBuf.length < 4) numBuf.append(d)
                    flashMsg("Chaîne : $numBuf")
                    handler.removeCallbacks(numTask)
                    handler.postDelayed(numTask, 1800)
                    return true
                }
                if (numBuf.isNotEmpty() && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                    commitNumber()
                    return true
                }
                when (k) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                }
                if (!view.isControllerFullyVisible) {
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_UP -> { zap(1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); return true }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStart() {
        super.onStart()
        exo?.playWhenReady = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 1000)
    }

    override fun onStop() {
        saveProgress()
        handler.removeCallbacks(watchdog)
        exo?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        Zap.playing = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        val p = exo
        if (p != null && p === Preview.player) {
            // l'aperçu continue (muet) quand on revient à la liste des chaînes
            view.player = null
            listener?.let { p.removeListener(it) }
            Preview.muteAudio(p, true)
            p.playWhenReady = true
            Preview.lent = false
        } else {
            p?.release()
        }
        exo = null
        super.onDestroy()
    }
}

@Composable
fun PlayerMenu(entries: List<MenuEntry>, pinned: MenuEntry.Row?, title: String, sub: String, onClose: () -> Unit) {
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
                "≡  Prog", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC9C9D2),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)
            )
            if (title.isNotBlank()) {
                Text(
                    title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            if (sub.isNotBlank()) {
                Text(
                    sub, fontSize = 13.sp, color = MENU_ACCENT, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                )
            }
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
