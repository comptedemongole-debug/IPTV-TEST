package com.perso.iptv

import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Multi-vue : 2 ou 4 chaînes en direct en même temps. Le son suit la fenêtre sélectionnée (cadre blanc).
 * ← ↑ ↓ → : choisir la fenêtre · OK : changer de chaîne · OK long : plein écran · Retour : quitter.
 * Chaque fenêtre ouvre sa propre connexion chez le fournisseur : il faut un abonnement à plusieurs connexions.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MultiViewActivity : ComponentActivity() {

    private class Tile(
        val frame: FrameLayout, val view: PlayerView,
        val name: TextView, val badge: TextView, val empty: TextView
    ) {
        var player: ExoPlayer? = null
        var item: Item? = null
        var url: String = ""
    }

    private val tiles = ArrayList<Tile>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var row2: LinearLayout
    private lateinit var toast: TextView
    private var count = 2
    private var maxCount = 4
    private var sel = 0
    private var longPressed = false
    private var menuOpen by mutableStateOf(false)
    private var pickCat by mutableStateOf<String?>(null)
    private var pickTile = 0
    private val hideToast = Runnable { toast.visibility = View.GONE }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Zap.playing = true
        root = FrameLayout(this)
        root.setBackgroundColor(AColor.BLACK)
        setContentView(root)

        val mc = intent.getIntExtra("max", 0)   // 0 = inconnu
        if (mc == 1 || Zap.api == null || Zap.items.isEmpty()) {
            val msg = TextView(this)
            msg.text = if (mc == 1)
                "Ton abonnement n'autorise qu'une seule connexion à la fois.\nLa multi-vue en demande au moins 2 : voir avec ton fournisseur."
            else "Aucune chaîne à afficher."
            msg.textSize = 18f
            msg.setTextColor(AColor.WHITE)
            msg.gravity = Gravity.CENTER
            msg.setPadding(dp(48), dp(48), dp(48), dp(48))
            root.addView(msg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            handler.postDelayed({ finish() }, 5000)
            return
        }

        maxCount = if (mc in 2..3) 2 else 4
        count = if (mc >= 4 && !Booster.isLowRam(this)) 4 else 2

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        for (r in 0..1) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0..1) {
                val t = makeTile()
                tiles.add(t)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(dp(2), dp(2), dp(2), dp(2))
                row.addView(t.frame, lp)
            }
            if (r == 1) row2 = row
        }
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row2.visibility = if (count > 2) View.VISIBLE else View.GONE

        // la chaîne d'où l'on vient dans la 1re fenêtre, les suivantes de la liste dans les autres
        val list = Zap.items
        for (i in 0 until 4) tiles[i].item = list.getOrNull(Zap.index + i)

        toast = TextView(this)
        toast.textSize = 15f
        toast.setTextColor(AColor.WHITE)
        toast.setPadding(dp(16), dp(8), dp(16), dp(8))
        toast.background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(10).toFloat() }
        toast.visibility = View.GONE
        val tlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        tlp.bottomMargin = dp(24)
        root.addView(toast, tlp)

        val menuView = ComposeView(this)
        menuView.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE5484D))) {
                if (menuOpen) {
                    key(pickCat) {
                        val entries = remember { buildPicker() }
                        val toggle = if (maxCount >= 4)
                            MenuEntry.Row(if (count == 4) "Afficher 2 fenêtres" else "Afficher 4 fenêtres", "", false) {
                                toggleLayout()
                                menuOpen = false
                            }
                        else null
                        PlayerMenu(
                            entries, toggle, "Choisir une chaîne", "Fenêtre ${pickTile + 1}", "Multi-vue", false,
                            emptyList(), "", {}
                        ) { menuOpen = false }
                    }
                }
            }
        }
        root.addView(menuView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        refreshTiles()
        flash("OK : changer de chaîne   ·   OK long : plein écran   ·   le son suit le cadre blanc", 7000)
    }

    private fun makeTile(): Tile {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(0xFF0B0B0F.toInt())
        val v = PlayerView(this)
        v.useController = false
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        v.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        v.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val name = TextView(this)
        name.textSize = 13f
        name.setTextColor(AColor.WHITE)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setBackgroundColor(0x99000000.toInt())
        name.setPadding(dp(8), dp(3), dp(8), dp(3))
        val nlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.gravity = Gravity.BOTTOM
        frame.addView(name, nlp)

        val badge = TextView(this)
        badge.text = "SON"
        badge.textSize = 11f
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setTextColor(AColor.WHITE)
        badge.setPadding(dp(8), dp(2), dp(8), dp(2))
        badge.background = GradientDrawable().apply { setColor(0xFFE5484D.toInt()); cornerRadius = dp(99).toFloat() }
        val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.gravity = Gravity.TOP or Gravity.END
        blp.setMargins(0, dp(8), dp(8), 0)
        frame.addView(badge, blp)

        val empty = TextView(this)
        empty.text = EMPTY_TXT
        empty.textSize = 14f
        empty.setTextColor(0xFFC9C9D2.toInt())
        empty.gravity = Gravity.CENTER
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return Tile(frame, v, name, badge, empty)
    }

    private fun border(selected: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(AColor.TRANSPARENT)
        g.setStroke(dp(if (selected) 3 else 1), if (selected) AColor.WHITE else 0x33FFFFFF)
        return g
    }

    private fun refreshTiles() {
        tiles.forEachIndexed { i, t ->
            val it = t.item
            t.name.text = it?.name ?: ""
            t.name.visibility = if (it != null) View.VISIBLE else View.GONE
            t.empty.visibility = if (it == null || t.empty.text.toString() != EMPTY_TXT) View.VISIBLE else View.GONE
            if (it == null) t.empty.text = EMPTY_TXT
            t.badge.visibility = if (i == sel && it != null) View.VISIBLE else View.GONE
            t.frame.foreground = border(i == sel)
        }
    }

    private fun flash(msg: String, ms: Long = 2500) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, ms)
    }

    // ---------------------------------------------------------------- lecteurs

    private fun newPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        // tampons courts : 2 à 4 flux en parallèle doivent rester légers pour la mémoire de la TV
        val lb = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 12_000, 1_500, 3_000)
            .setTargetBufferBytes(12 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(this, makeRenderers(this, Zap.repo?.pcmAudio == true))
            .setLoadControl(lb)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        // définition plafonnée : une fenêtre de 4 n'a aucun besoin de 1080p, et c'est le décodeur de la TV qui rame
        val maxW = if (count >= 3) 960 else 1280
        val maxH = if (count >= 3) 540 else 720
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(maxW, maxH).build()
        p.playWhenReady = true
        return p
    }

    private fun setChannel(i: Int, ch: Item) {
        val api = Zap.api ?: return
        val t = tiles[i]
        t.item = ch
        t.url = api.liveUrl(ch.id)
        t.empty.text = EMPTY_TXT
        var p = t.player
        if (p == null) {
            val np = newPlayer()
            np.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onTileError(t, np)
                }
            })
            t.player = np
            t.view.player = np
            p = np
        }
        Preview.muteAudio(p, i != sel)
        p.setMediaItem(MediaItem.fromUri(t.url))
        p.prepare()
        p.playWhenReady = true
        refreshTiles()
    }

    private fun onTileError(t: Tile, p: ExoPlayer) {
        if (t.url.endsWith(".m3u8")) {
            t.url = t.url.removeSuffix(".m3u8") + ".ts"
            p.setMediaItem(MediaItem.fromUri(t.url))
            p.prepare()
            p.play()
        } else {
            t.empty.text = "Chaîne indisponible\n(trop de connexions ?)\nOK : changer de chaîne"
            t.empty.visibility = View.VISIBLE
        }
    }

    private fun releaseTile(i: Int) {
        val t = tiles[i]
        try { t.view.player = null } catch (e: Exception) { /* ignoré */ }
        try { t.player?.release() } catch (e: Exception) { /* ignoré */ }
        t.player = null
    }

    private fun releaseAll() {
        for (i in tiles.indices) releaseTile(i)
    }

    private fun startAll() {
        for (i in 0 until count) {
            val it = tiles[i].item
            if (it != null && tiles[i].player == null) setChannel(i, it)
        }
    }

    private fun applyAudio() {
        tiles.forEachIndexed { i, t -> t.player?.let { Preview.muteAudio(it, i != sel) } }
    }

    private fun toggleLayout() {
        if (maxCount < 4) {
            flash("Ton abonnement n'autorise que $maxCount connexions : 2 fenêtres maximum")
            return
        }
        count = if (count == 4) 2 else 4
        if (sel >= count) sel = count - 1
        row2.visibility = if (count > 2) View.VISIBLE else View.GONE
        for (i in count until 4) releaseTile(i)
        // les lecteurs restants gardent leur plafond de définition : on les recrée pour appliquer le nouveau
        for (i in 0 until count) releaseTile(i)
        startAll()
        applyAudio()
        refreshTiles()
        flash("$count fenêtres")
    }

    override fun onStart() {
        super.onStart()
        if (tiles.isNotEmpty()) startAll()
    }

    override fun onStop() {
        releaseAll()
        super.onStop()
    }

    override fun onDestroy() {
        Zap.playing = false
        handler.removeCallbacksAndMessages(null)
        releaseAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- choix de chaîne

    private fun openPicker() {
        val data = Zap.repo?.data?.get("live")
        pickTile = sel
        pickCat = tiles[sel].item?.cat?.takeIf { data?.byCat?.containsKey(it) == true }
        menuOpen = true
    }

    private fun buildPicker(): List<MenuEntry> {
        val repo = Zap.repo ?: return emptyList()
        val data = repo.data["live"]
        val out = ArrayList<MenuEntry>()
        val cat = pickCat
        if (cat == null) {
            if (repo.recents.isNotEmpty()) out.add(MenuEntry.Row("Récemment regardées", "", false) { pickCat = "__recent" })
            if (repo.favItems("live").isNotEmpty()) out.add(MenuEntry.Row("★ Favoris", "", false) { pickCat = "__fav" })
            data?.cats?.forEach { c -> out.add(MenuEntry.Row(c.name, "", false) { pickCat = c.id }) }
        } else {
            out.add(MenuEntry.Row("‹  Catégories", "", false) { pickCat = null })
            val list = when (cat) {
                "__recent" -> repo.recents.toList()
                "__fav" -> repo.favItems("live")
                else -> data?.byCat?.get(cat) ?: emptyList()
            }
            val cur = tiles[pickTile].item?.id
            list.forEach { ch -> out.add(MenuEntry.Row(ch.name, "", ch.id == cur) { pickChannel(ch) }) }
        }
        return out
    }

    private fun pickChannel(ch: Item) {
        menuOpen = false
        Zap.repo?.addRecent(ch)
        setChannel(pickTile, ch)
    }

    private fun zapTile(delta: Int) {
        val list = Zap.items
        if (list.isEmpty()) return
        val cur = list.indexOfFirst { it.id == tiles[sel].item?.id }
        val n = if (cur < 0) 0 else (cur + delta + list.size) % list.size
        setChannel(sel, list[n])
    }

    private fun openFullscreen() {
        val ch = tiles[sel].item ?: return
        val api = Zap.api ?: return
        val idx = Zap.items.indexOfFirst { it.id == ch.id }
        if (idx >= 0) Zap.index = idx else { Zap.items = listOf(ch); Zap.index = 0 }
        Zap.eps = emptyList()
        Zap.repo?.addRecent(ch)
        releaseAll()   // une seule connexion chez le fournisseur pour le plein écran
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra("url", api.liveUrl(ch.id)).putExtra("title", ch.name).putExtra("live", true)
                .putExtra("img", ch.img).putExtra("kind", "live").putExtra("start", 0L)
                .putExtra("handoffId", null as String?)
        )
        finish()
    }

    // ---------------------------------------------------------------- télécommande

    private fun moveSel(key: Int) {
        val r = sel / 2
        val c = sel % 2
        val n = when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (c > 0) sel - 1 else sel
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (c < 1 && sel + 1 < count) sel + 1 else sel
            KeyEvent.KEYCODE_DPAD_UP -> if (r > 0) sel - 2 else sel
            KeyEvent.KEYCODE_DPAD_DOWN -> if (sel + 2 < count) sel + 2 else sel
            else -> sel
        }
        if (n != sel) {
            sel = n
            applyAudio()
            refreshTiles()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuOpen || tiles.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSel(keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    longPressed = false
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                toggleLayout()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zapTile(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapTile(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!menuOpen && tiles.isNotEmpty() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            longPressed = true
            openFullscreen()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!menuOpen && tiles.isNotEmpty() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!longPressed) openPicker()
            longPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val EMPTY_TXT = "+\nOK : choisir une chaîne"
    }
}
