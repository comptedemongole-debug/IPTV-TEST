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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.load
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

    /** Série en cours de lecture : sert à retrouver les vrais titres d'épisodes (TMDB) de toutes les saisons. */
    var seriesName: String = ""
    var tmdbId: String = ""

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
    private lateinit var gearBtn: TextView
    private lateinit var introBtn: TextView          // « Passer l'intro » (séries)
    private lateinit var nextCard: LinearLayout      // carte « épisode suivant » (séries, 15 s avant la fin)
    private lateinit var nextImg: ImageView
    private lateinit var nextTitle: TextView
    private lateinit var nextCount: TextView
    private var introDismissed = false
    private var nextDismissed = false
    private var noAutoNext = false                   // « Retour » sur la carte : pas de lecture auto pour cet épisode
    private var swallowBackUp = false
    private var nextShownFor = -1
    private var seekStreak = 0
    private var lastSeekAt = 0L
    private var lastSeekDir = 0
    private val overlayTask = object : Runnable {
        override fun run() {
            overlayTick()
            handler.postDelayed(this, 500)
        }
    }
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
    private var menuMode by mutableStateOf(0)   // 0 = Prog (épisodes / chaînes / films), 1 = Réglages (roue crantée)
    private var menuEntries by mutableStateOf<List<MenuEntry>>(emptyList())
    private var menuPinned by mutableStateOf<MenuEntry.Row?>(null)
    private var menuTitle by mutableStateOf("")
    private var menuSub by mutableStateOf("")
    private var menuSeasons by mutableStateOf<List<String>>(emptyList())   // Prog des séries : liste des saisons
    private var menuSeason by mutableStateOf("")                          // saison affichée

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
                if (menuOpen) PlayerMenu(
                        menuEntries, menuPinned, menuTitle, menuSub, if (menuMode == 1) "Réglages" else "≡  Prog", menuMode == 1,
                        if (menuMode == 0) menuSeasons else emptyList(), menuSeason,
                        { sn -> menuSeason = sn; menuEntries = buildMenu() }
                    ) { closeMenu() }
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
        gearBtn = TextView(this)
        gearBtn.text = "⚙"
        gearBtn.textSize = 20f
        gearBtn.setTextColor(AColor.WHITE)
        gearBtn.gravity = Gravity.CENTER
        gearBtn.setPadding(26, 18, 26, 18)
        gearBtn.isFocusable = true
        gearBtn.isFocusableInTouchMode = true
        gearBtn.isClickable = true
        gearBtn.contentDescription = "Réglages du lecteur"
        gearBtn.background = progBg(false)
        gearBtn.visibility = View.GONE
        try {
            val ic = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_settings)
            if (ic != null) {
                val sz = (30 * resources.displayMetrics.density).toInt()
                ic.setBounds(0, 0, sz, sz)
                gearBtn.text = ""
                gearBtn.setCompoundDrawables(ic, null, null, null)
            }
        } catch (e: Exception) { /* repli : le caractère ⚙ reste affiché */ }
        gearBtn.setOnClickListener { openMenu(true) }
        gearBtn.setOnFocusChangeListener { _, hasFocus ->
            gearBtn.background = progBg(hasFocus)
            if (hasFocus) view.showController()
        }
        subSizeIdx = try {
            getSharedPreferences("kineo_player", MODE_PRIVATE).getInt("sub_size", 1).coerceIn(0, subSizes.size - 1)
        } catch (e: Exception) { 1 }
        applySubSize()
        view.controllerShowTimeoutMs = 3000
        view.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                val vis = if (visibility == View.VISIBLE && !menuOpen) View.VISIBLE else View.GONE
                progBtn.visibility = vis
                gearBtn.visibility = vis
            }
        })

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(menuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.setMargins(0, 48, 200, 0)
        root.addView(info, lp)
        val lp2 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.gravity = Gravity.TOP or Gravity.START
        lp2.setMargins(48, 48, 0, 0)
        root.addView(progBtn, lp2)
        val lp3 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp3.gravity = Gravity.TOP or Gravity.END
        lp3.setMargins(0, 48, 48, 0)
        root.addView(gearBtn, lp3)

        // --- bouton « Passer l'intro » (↓ pour le sélectionner, OK pour sauter)
        introBtn = TextView(this)
        introBtn.text = "↓  Passer l'intro"
        introBtn.textSize = 18f
        introBtn.typeface = android.graphics.Typeface.DEFAULT_BOLD
        introBtn.setTextColor(AColor.WHITE)
        introBtn.gravity = Gravity.CENTER
        introBtn.setPadding(dpx(26), dpx(14), dpx(26), dpx(14))
        introBtn.isFocusable = true
        introBtn.isFocusableInTouchMode = true
        introBtn.isClickable = true
        introBtn.background = overlayBg(false, 8)
        introBtn.visibility = View.GONE
        introBtn.setOnClickListener { skipIntro() }
        introBtn.setOnFocusChangeListener { _, f ->
            introBtn.background = overlayBg(f, 8)
            introBtn.setTextColor(if (f) AColor.BLACK else AColor.WHITE)
            introBtn.text = if (f) "Passer l'intro  ›" else "↓  Passer l'intro"
        }
        val lp4 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp4.gravity = Gravity.BOTTOM or Gravity.END
        lp4.setMargins(0, 0, dpx(32), dpx(120))
        root.addView(introBtn, lp4)

        // --- carte « épisode suivant »
        nextCard = LinearLayout(this)
        nextCard.orientation = LinearLayout.VERTICAL
        nextCard.setPadding(dpx(8), dpx(8), dpx(8), dpx(10))
        nextCard.isFocusable = true
        nextCard.isFocusableInTouchMode = true
        nextCard.isClickable = true
        nextCard.background = overlayBg(false, 12)
        nextCard.visibility = View.GONE
        nextImg = ImageView(this)
        nextImg.scaleType = ImageView.ScaleType.CENTER_CROP
        nextImg.setBackgroundColor(0xFF222230.toInt())
        nextCard.addView(nextImg, LinearLayout.LayoutParams(dpx(260), dpx(146)))
        val nextTag = TextView(this)
        nextTag.text = "ÉPISODE SUIVANT"
        nextTag.textSize = 11f
        nextTag.setTextColor(0xFFC9C9D2.toInt())
        nextTag.setPadding(dpx(2), dpx(8), 0, 0)
        nextCard.addView(nextTag)
        nextTitle = TextView(this)
        nextTitle.textSize = 15f
        nextTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        nextTitle.setTextColor(AColor.WHITE)
        nextTitle.maxLines = 2
        nextTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        nextTitle.setPadding(dpx(2), dpx(2), dpx(2), 0)
        nextCard.addView(nextTitle, LinearLayout.LayoutParams(dpx(260), ViewGroup.LayoutParams.WRAP_CONTENT))
        nextCount = TextView(this)
        nextCount.textSize = 13f
        nextCount.setTextColor(0xFFFF6B6F.toInt())
        nextCount.setPadding(dpx(2), dpx(4), dpx(2), 0)
        nextCard.addView(nextCount)
        nextCard.setOnClickListener { playNextNow() }
        nextCard.setOnFocusChangeListener { _, f -> nextCard.background = overlayBg(f, 12) }
        val lp5 = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp5.gravity = Gravity.BOTTOM or Gravity.END
        lp5.setMargins(0, 0, dpx(32), dpx(110))
        root.addView(nextCard, lp5)
        setContentView(root)
        view.requestFocus()

        if (!borrowed) start(url, startMs)
        handler.postDelayed(watchdog, 1000)
        if (live && Zap.items.isNotEmpty()) {
            flash()
        } else if (!live) {
            enrichEpisodeTitles()
            flashMsg(
                "OK puis ↑ : « Prog » (épisodes) à gauche, réglages ⚙ à droite\nLecture automatique : " +
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
                            ".\nRoue ⚙ (en haut à droite) > piste audio, ou Réglages > Son."
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
                        if (!live && Math.abs(p.playbackParameters.speed - speeds[speedIdx]) > 0.001f) p.setPlaybackSpeed(speeds[speedIdx])
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
        introDismissed = false
        nextDismissed = false
        noAutoNext = false
        nextShownFor = -1
        hideIntro()
        hideNext()
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
            if (auto && !noAutoNext && Zap.epIndex + 1 < Zap.eps.size) playEpisode(Zap.epIndex + 1)
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
        menuEntries = if (menuMode == 1) buildSettings() else buildMenu()
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
                flashMsg("Vitesse : " + speedLabel(speeds[speedIdx]))
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

    /** Réglages du lecteur (roue crantée) : lecture (vitesse, intro, minuteur), piste audio, sous-titres. */
    /** ← / → servent aux sauts en film / série, commandes affichées ou non (sauf sur les boutons Prog et ⚙ du haut). */
    private fun arrowsSeek(): Boolean =
        !menuOpen && !live && !failed && exo != null && !progBtn.hasFocus() && !gearBtn.hasFocus()

    /** ← / → ne doivent JAMAIS réveiller le lecteur : seuls ↑ / ↓ le font (sauf sur les boutons Prog / ⚙ où ← → servent à naviguer). */
    private fun arrowsMute(): Boolean = !menuOpen && !progBtn.hasFocus() && !gearBtn.hasFocus()

    private fun dpx(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun overlayBg(focused: Boolean, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dpx(radiusDp).toFloat()
        g.setColor(if (focused && radiusDp == 8) AColor.WHITE else 0xE6101018.toInt())
        g.setStroke(dpx(if (focused) 3 else 1), if (focused) AColor.WHITE else 0x66FFFFFF)
        return g
    }

    private fun hideIntro() {
        if (!::introBtn.isInitialized) return
        if (introBtn.visibility == View.VISIBLE) {
            if (introBtn.hasFocus()) view.requestFocus()
            introBtn.visibility = View.GONE
        }
    }

    private fun hideNext() {
        if (!::nextCard.isInitialized) return
        if (nextCard.visibility == View.VISIBLE) {
            if (nextCard.hasFocus()) view.requestFocus()
            nextCard.visibility = View.GONE
        }
    }

    private fun skipIntro() {
        val p = exo ?: return
        val dur = p.duration
        var t = p.currentPosition + 90_000L
        if (dur > 0) t = minOf(t, dur - 1000L)
        t = maxOf(0L, t)
        p.seekTo(t)
        introDismissed = true
        hideIntro()
        flashMsg("Intro passée   " + fmtPos(t))
    }

    private fun playNextNow() {
        val i = Zap.epIndex + 1
        if (i < Zap.eps.size) {
            hideNext()
            playEpisode(i)
        }
    }

    /** Toutes les 500 ms : affiche « Passer l'intro » au début d'un épisode et la carte « épisode suivant » 15 s avant la fin. */
    private fun overlayTick() {
        val p = exo
        if (p == null || live || failed || menuOpen || !episodeContext()) {
            hideIntro()
            hideNext()
            return
        }
        val dur = p.duration
        val pos = p.currentPosition
        val known = dur > 60_000L
        val remaining = if (known) dur - pos else Long.MAX_VALUE

        val introOn = !introDismissed && pos in 3_000L..180_000L && (!known || remaining > 20_000L)
        if (introOn) {
            if (introBtn.visibility != View.VISIBLE) introBtn.visibility = View.VISIBLE
        } else hideIntro()

        if (known && remaining > 16_000L) nextDismissed = false
        val hasNext = Zap.epIndex + 1 < Zap.eps.size
        if (hasNext && known && remaining in 0L..15_000L && !nextDismissed) {
            val nx = Zap.eps[Zap.epIndex + 1]
            if (nextShownFor != Zap.epIndex) {
                nextShownFor = Zap.epIndex
                nextTitle.text = nx.label
                val img = nx.img
                if (!img.isNullOrBlank()) nextImg.load(img) else nextImg.setImageDrawable(null)
            }
            val auto = Zap.repo?.autoNext == true
            val sec = ((remaining + 999L) / 1000L).toInt()
            nextCount.text = if (auto && !noAutoNext) "Lecture dans $sec s  ·  OK pour lancer" else "OK pour lancer"
            if (nextCard.visibility != View.VISIBLE) {
                hideIntro()
                nextCard.visibility = View.VISIBLE
                nextCard.requestFocus()
            }
        } else hideNext()
    }

    /** Remplace les titres d'épisodes de TOUTES les saisons par ceux de TMDB (la fiche série n'en chargeait qu'une). */
    private fun enrichEpisodeTitles() {
        val key = Zap.repo?.tmdbKey ?: return
        val snapshot = Zap.eps
        if (key.isBlank() || snapshot.isEmpty() || Zap.seriesName.isBlank()) return
        val name = Zap.seriesName
        scope.launch {
            try {
                var id = Zap.tmdbId
                if (id.isBlank()) id = Tmdb.findTv(key, name) ?: return@launch
                val maps = HashMap<String, Map<Int, TmEp>>()
                for (s in snapshot.map { it.season }.distinct()) maps[s] = Tmdb.season(key, id, s)
                if (Zap.eps !== snapshot) return@launch      // une autre série a pris la place entre-temps
                Zap.eps = snapshot.map { e ->
                    val t = maps[e.season]?.get(e.num.toIntOrNull() ?: -1)
                    if (t == null || t.name.isBlank()) e
                    else e.copy(label = "S${e.season} E${e.num.ifBlank { "?" }} · ${t.name}", img = t.still ?: e.img)
                }
                if (menuOpen && menuMode == 0) refreshMenu()
            } catch (e: Exception) { /* on garde les titres actuels */ }
        }
    }

    private fun buildSettings(): List<MenuEntry> {
        val out = ArrayList<MenuEntry>()
        out.addAll(playbackEntries())
        out.addAll(audioEntries())
        out.addAll(subtitleEntries())
        return out
    }

    /** Menu Prog : uniquement la liste (chaînes, épisodes de la série, films de la catégorie / playlist). */
    private fun buildMenu(): List<MenuEntry> {
        val out = ArrayList<MenuEntry>()
        if (live) {
            out.add(MenuEntry.Header("Chaînes"))
            Zap.items.forEachIndexed { i, ch ->
                val prog = Zap.repo?.progsFor(ch)?.firstOrNull()?.title ?: ""
                out.add(MenuEntry.Row("${i + 1}   ${ch.name}", prog, i == Zap.index) { zapTo(i); closeMenu() })
            }
        } else if (episodeContext()) {
            val shown = menuSeason.ifBlank { Zap.eps.getOrNull(Zap.epIndex)?.season ?: Zap.eps.first().season }
            Zap.eps.forEachIndexed { i, e ->
                if (e.season == shown) out.add(MenuEntry.Row(e.label, "", i == Zap.epIndex) { playEpisode(i); closeMenu() })
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

    private fun openMenu(settings: Boolean = false) {
        menuMode = if (settings) 1 else 0
        if (!settings && !live && episodeContext()) {
            menuSeasons = Zap.eps.map { it.season }.distinct()
            menuSeason = Zap.eps.getOrNull(Zap.epIndex)?.season ?: menuSeasons.firstOrNull() ?: ""
        } else {
            menuSeasons = emptyList()
            menuSeason = ""
        }
        menuEntries = if (settings) buildSettings() else buildMenu()
        menuPinned = if (!live && !settings) autoToggleRow() else null
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
        if (settings) menuSub = "Réglages du lecteur"
        menuOpen = true
        hideIntro()
        hideNext()
        progBtn.visibility = View.GONE
        gearBtn.visibility = View.GONE
        menuView.requestFocus()
    }

    private fun closeMenu() {
        menuOpen = false
        view.requestFocus()
        if (view.isControllerFullyVisible) {
            progBtn.visibility = View.VISIBLE
            gearBtn.visibility = View.VISIBLE
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_UP) {
            val ku = e.keyCode
            if (ku == KeyEvent.KEYCODE_BACK && swallowBackUp) {
                swallowBackUp = false
                return true
            }
            // Le relâchement de ← / → réveillait les commandes du lecteur : on l'absorbe, donc on peut spammer les sauts
            if ((ku == KeyEvent.KEYCODE_DPAD_LEFT || ku == KeyEvent.KEYCODE_DPAD_RIGHT) && arrowsMute()) return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            // Touche « ≡ » de la télécommande (si la TV la transmet) : ouvre / ferme le menu Prog
            if (failed && !menuOpen && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                retryAll()
                return true
            }
            if (k == KeyEvent.KEYCODE_SETTINGS) {
                if (menuOpen) closeMenu() else openMenu(true)
                return true
            }
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_GUIDE) {
                if (menuOpen) closeMenu() else openMenu()
                return true
            }
            // « Retour » sur la carte « épisode suivant » : on l'annule (et pas de lecture auto pour cet épisode)
            if (k == KeyEvent.KEYCODE_BACK && !menuOpen && nextCard.visibility == View.VISIBLE) {
                nextDismissed = true
                noAutoNext = true
                swallowBackUp = true
                hideNext()
                flashMsg("Lecture automatique annulée pour cet épisode")
                return true
            }
            // ↑ / ↓ depuis le bouton « Passer l'intro » ou la carte « épisode suivant » : on réveille le lecteur
            if ((k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && !menuOpen &&
                (introBtn.hasFocus() || nextCard.hasFocus())
            ) {
                view.requestFocus()
                view.showController()
                return true
            }
            // ↓ : sélectionne le bouton « Passer l'intro » (OK pour sauter)
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && !menuOpen && !live && !failed && !view.isControllerFullyVisible &&
                introBtn.visibility == View.VISIBLE && !introBtn.hasFocus()
            ) {
                introBtn.requestFocus()
                return true
            }
            // Films / séries : ← −10 s, → +10 s (puis 30 s, 60 s si on enchaîne), sans afficher les commandes
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && arrowsSeek()) {
                val p = exo
                if (p != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (e.repeatCount > 0 && now - lastSeekAt < 150L) return true   // touche maintenue : on limite le rythme
                    val dir = if (k == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    seekStreak = if (dir == lastSeekDir && now - lastSeekAt < 700L) seekStreak + 1 else 1
                    lastSeekDir = dir
                    lastSeekAt = now
                    val step = if (seekStreak < 6) 10_000L else if (seekStreak < 14) 30_000L else 60_000L
                    val d = dir * step
                    val dur = p.duration
                    var t = p.currentPosition + d
                    if (dur > 0) t = minOf(t, dur - 1000L)
                    t = maxOf(0L, t)
                    p.seekTo(t)
                    flashMsg((if (d < 0) "−" else "+") + (step / 1000L) + " s   " + fmtPos(t))
                    return true
                }
            }
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && arrowsMute()) return true
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
        handler.removeCallbacks(overlayTask)
        handler.postDelayed(overlayTask, 500)
    }

    override fun onStop() {
        saveProgress()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(overlayTask)
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
fun PlayerMenu(
    entries: List<MenuEntry>, pinned: MenuEntry.Row?, title: String, sub: String, heading: String, settings: Boolean,
    seasons: List<String>, season: String, onSeason: (String) -> Unit, onClose: () -> Unit
) {
    // Prog : on se place sur l'élément en cours ; Réglages : on commence tout en haut
    val sel = if (settings) entries.indexOfFirst { it is MenuEntry.Row }.coerceAtLeast(0)
        else entries.indexOfLast { it is MenuEntry.Row && it.selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = if (settings) 0 else maxOf(0, sel - 3))
    val fr = remember { FocusRequester() }
    val chipFr = remember { FocusRequester() }
    var chipsFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        delay(120)
        try { fr.requestFocus() } catch (e: Exception) { /* ignoré */ }
    }
    // Quand on change de saison, la liste repart en haut
    var firstSeason by remember { mutableStateOf(true) }
    LaunchedEffect(season) {
        if (firstSeason) firstSeason = false else state.scrollToItem(0)
    }
    // Retour : d'abord on remonte sur les saisons, ensuite seulement on ferme
    BackHandler {
        if (seasons.isNotEmpty() && !chipsFocused) {
            scope.launch {
                state.scrollToItem(0)
                try { chipFr.requestFocus() } catch (e: Exception) { /* ignoré */ }
            }
        } else onClose()
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(400.dp).fillMaxHeight().background(MENU_BG).padding(vertical = 16.dp)) {
            Text(
                heading, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC9C9D2),
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
            if (seasons.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().onFocusChanged { chipsFocused = it.hasFocus },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons) { sn ->
                        SeasonChip("Saison $sn", sn == season, if (sn == season) Modifier.focusRequester(chipFr) else Modifier) { onSeason(sn) }
                    }
                }
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
fun SeasonChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        color = if (selected) Color.White else Color(0xFFC9C9D2),
        modifier = modifier
            .onFocusChanged { f = it.isFocused }
            .background(
                if (selected) MENU_ACCENT else if (f) Color(0x55FFFFFF) else Color(0x22FFFFFF),
                RoundedCornerShape(99.dp)
            )
            .then(if (f) Modifier.border(2.dp, Color.White, RoundedCornerShape(99.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
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
