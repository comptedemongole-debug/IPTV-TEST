package com.perso.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = Color(0xFF0B0B0F)
private val BG_TOP = Color(0xFF1A1F4A)
private val SCRIM = Color(0xFF101225)
private val TXT = Color(0xFFF2F2F5)
private val MUTED = Color(0xFFC9C9D2)
private val ACCENT = Color(0xFFE5484D)
private val PILL = Color(0xFF34343F)

val LocalPerf = compositionLocalOf { false }

private val rTmdbSize = Regex("/t/p/[^/]+/")

/** Réduit la taille des images TMDB (souvent en pleine résolution) : beaucoup moins à télécharger et à décoder. */
private fun lightImg(url: String?, size: String): String? =
    if (url != null && url.contains("image.tmdb.org/t/p/")) rTmdbSize.replace(url, "/t/p/$size/") else url

private fun openSystem(ctx: Context, action: String): Boolean = try {
    ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

private val TAB_ORDER = listOf(0 to "TV", 1 to "Films", 2 to "Séries", 6 to "Playlists", 4 to "Masqués", 5 to "Réglages")

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
private fun fmtDate(ts: Long): String =
    if (ts == 0L) "jamais" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))

private fun play(
    ctx: Context, url: String, title: String, live: Boolean,
    img: String? = null, kind: String = "vod", start: Long = 0L, handoffId: String? = null
) {
    if (kind != "series") Zap.eps = emptyList()
    // sans transmission, on libère l'aperçu avant de lancer (une seule connexion chez le fournisseur)
    if (handoffId == null) Preview.release()
    // la vidéo a besoin de la mémoire : on libère les images de l'appli (rechargées depuis le disque au retour)
    if (Zap.repo?.autoBoost == true) Booster.trimOwnCaches(ctx.applicationContext)
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("title", title).putExtra("live", live)
            .putExtra("img", img).putExtra("kind", kind).putExtra("start", start).putExtra("handoffId", handoffId)
    )
}

private fun setZapEpisodes(api: Xtream, series: Item, list: List<Ep>, current: Ep, tm: Map<String, Map<Int, TmEp>>, srcId: String = series.id) {
    Zap.eps = list.map { e ->
        val num = e.num.ifBlank { "?" }
        val tmName = tm[e.season]?.get(e.num.toIntOrNull() ?: -1)?.name
        val name = if (!tmName.isNullOrBlank()) tmName else cleanEpTitle(e.title, series.name, e.num)
        EpEntry(api.epUrl(e.id, e.ext), "S${e.season} E$num · $name", "${series.name} · S${e.season} E$num", e.img ?: series.img, e.season, srcId, e.num)
    }
    Zap.epIndex = list.indexOf(current).coerceAtLeast(0)
}

private fun setVodQueue(repo: Repo, item: Item) {
    val list = repo.data["vod"]?.byCat?.get(item.cat)?.take(500) ?: emptyList()
    val i = list.indexOfFirst { it.id == item.id }
    if (i >= 0) {
        Zap.vodQueue = list
        Zap.vodIndex = i
    } else {
        Zap.vodQueue = listOf(item)
        Zap.vodIndex = 0
    }
    Zap.queueIsPlaylist = false
    Zap.queueName = null
}

private fun fmtMs(ms: Long): String {
    val sec = ms / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
}

private fun langColor(l: String): Color = when (l) {
    "FR" -> Color(0xFF2D5BFF)
    "EN", "VO" -> Color(0xFFE5484D)
    "VOSTFR" -> Color(0xFF8E4EC6)
    "MULTI" -> Color(0xFF2E9E6B)
    else -> Color(0xFF555566)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("crash", Context.MODE_PRIVATE)
        val last = sp.getString("trace", null)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            sp.edit().putString("trace", e.stackTraceToString().take(1800)).commit()
            old?.uncaughtException(t, e)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT)) {
                Surface(Modifier.fillMaxSize(), color = BG) {
                    var crash by remember { mutableStateOf(last) }
                    val c = crash
                    if (c != null) {
                        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("L'appli a planté. Prends cet écran en photo :", color = TXT)
                            Text(c, fontSize = 11.sp, color = MUTED)
                            Button(onClick = { sp.edit().remove("trace").apply(); crash = null }) { Text("Continuer") }
                        }
                    } else {
                        var splash by remember { mutableStateOf(true) }
                        LaunchedEffect(Unit) { delay(1500); splash = false }
                        Box(Modifier.fillMaxSize()) {
                            App()
                            AnimatedVisibility(visible = splash, enter = fadeIn(), exit = fadeOut(tween(500))) { Splash() }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!Preview.lent) Preview.release()
        super.onStop()
    }
}

@Composable
fun Logo(boxSize: Dp) {
    Canvas(Modifier.size(boxSize)) {
        val k = size.width / 120f
        drawRoundRect(color = ACCENT, cornerRadius = CornerRadius(28f * k, 28f * k))
        val p = Path().apply {
            moveTo(42f * k, 30f * k); lineTo(42f * k, 90f * k)
            moveTo(86f * k, 30f * k); lineTo(50f * k, 60f * k); lineTo(86f * k, 90f * k)
        }
        drawPath(p, Color.White, style = Stroke(width = 12f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun Splash() {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val a by animateFloatAsState(if (go) 1f else 0f, tween(800), label = "splash")
    Box(Modifier.fillMaxSize().background(BG), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(a).scale(0.7f + 0.3f * a)
        ) {
            Logo(110.dp)
            Spacer(Modifier.height(16.dp))
            Text("Kinéo", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = TXT)
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    var api by remember {
        mutableStateOf(
            prefs.getString("server", null)?.let {
                Xtream(it, prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
            }
        )
    }
    val a = api
    if (a == null) {
        Login { s, u, p ->
            prefs.edit().putString("server", s).putString("user", u).putString("pass", p).apply()
            api = Xtream(s, u, p)
        }
    } else {
        Home(a) {
            Repo.wipe(ctx)
            prefs.edit().clear().apply()
            api = null
        }
    }
}

@Composable
fun Login(onOk: (String, String, String) -> Unit) {
    var s by remember { mutableStateOf("http://") }
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun submit() {
        if (busy) return
        var server = s.trim()
        var user = u.trim()
        var pass = p.trim()
        // URL complète collée (http://hote:port/get.php?username=...&password=...) : on en extrait tout
        val mu = Regex("username=([^&\\s]+)").find(server)
        val mp = Regex("password=([^&\\s]+)").find(server)
        if (mu != null && mp != null) {
            user = mu.groupValues[1]
            pass = mp.groupValues[1]
            server = server.substringBefore("/get.php").substringBefore("/player_api.php").substringBefore("/xmltv.php")
        }
        if (server.isNotBlank() && !server.startsWith("http")) server = "http://$server"
        if (server == "http://" || user.isBlank() || pass.isBlank()) {
            msg = "Remplis le serveur, l'identifiant et le mot de passe."
            return
        }
        focus.clearFocus()
        busy = true
        msg = "Connexion en cours…"
        scope.launch {
            val r = Xtream(server, user, pass).check()
            busy = false
            if (r == null) onOk(server, user, pass) else msg = r
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Logo(56.dp)
        Text("Kinéo", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TXT)
        Text("Connexion à ton service IPTV", color = MUTED, fontSize = 14.sp)
        OutlinedTextField(
            s, { s = it }, singleLine = true,
            label = { Text("Serveur (http://adresse:port)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        OutlinedTextField(
            u, { u = it }, singleLine = true,
            label = { Text("Identifiant") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        OutlinedTextField(
            p, { p = it }, singleLine = true,
            label = { Text("Mot de passe") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        ActionButton(if (busy) "Connexion…" else "Se connecter", true) { submit() }
        if (msg.isNotBlank()) {
            Text(msg, color = if (busy) MUTED else ACCENT, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Text(
            "Sur le clavier : « Suivant » passe au champ d'après, « OK » sur le mot de passe se connecte. " +
                "Tu peux aussi coller ton lien complet (get.php?username=…&password=…) dans « Serveur ».",
            color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.7f)
        )
    }
}

@Composable
fun Home(api: Xtream, logout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(api) { Repo.obtain(ctx.applicationContext, api) }
    var tab by remember { mutableStateOf(1) }
    var cacheLoaded by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, Item>?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    // Nouveau jour : mise à jour de la playlist à la première ouverture / reprise de l'appli de la journée
    val lifeOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val dayScope = rememberCoroutineScope()
    DisposableEffect(lifeOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_START && cacheLoaded) {
                dayScope.launch {
                    try {
                        repo.refreshContent(false)
                        repo.syncEpgIfDue()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) { /* sera retenté */ }
                }
            }
        }
        lifeOwner.lifecycle.addObserver(obs)
        onDispose { lifeOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(repo) {
        try {
            Zap.repo = repo
            Zap.api = api
            repo.loadCache()
            cacheLoaded = true
            val fullJob = launch { repo.loadFull() }
            launch { repo.syncCloud() }
            repo.refreshContent(false)
            fullJob.join()
            repo.syncEpgIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            repo.status = "Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    val kind = when (tab) { 0 -> "live"; 1 -> "vod"; 2 -> "series"; else -> "" }
    val data = if (kind.isEmpty()) null else repo.data[kind]
    val visPair by produceState(Pair(kind, data), kind, data, repo.hiddenVersion) {
        value = Pair(kind, if (data == null) null else withContext(Dispatchers.Default) { repo.visible(kind, data) })
    }
    val vis = if (visPair.first == kind && visPair.second != null) visPair.second else data

    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        if (repo.perf) {
            pageAlpha.snapTo(1f)
        } else {
            pageAlpha.snapTo(0f)
            pageAlpha.animateTo(1f, tween(350))
        }
    }
    val hero = remember(kind) { HeroState() }
    if (tab == 1 || tab == 2) {
        HeroEffects(hero, kind, api, vis?.rows?.firstOrNull()?.items?.firstOrNull())
    }

    val openItem: (String, Item, List<Item>, Int) -> Unit = { k, item, sib, idx ->
        when (k) {
            "series", "vod" -> detail = k to item
            else -> {
                Zap.items = sib; Zap.index = idx; Zap.api = api; Zap.repo = repo
                repo.addRecent(item)
                val handoff = Preview.player != null && Preview.item?.id == item.id
                play(ctx, api.liveUrl(item.id), item.name, true, item.img, "live", 0L, if (handoff) item.id else null)
            }
        }
    }

    CompositionLocalProvider(LocalPerf provides repo.perf) {
    val det = detail
    if (det != null) {
        DetailScreen(repo, api, det.first, det.second, onBack = { detail = null }) { url, title, img, k, start ->
            play(ctx, url, title, false, img, k, start)
        }
    } else {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG_TOP, BG)))) {
            if (tab == 1 || tab == 2) HeroBackdrop(hero)
            Column(Modifier.fillMaxSize()) {
                TopBar(tab, { tab = it }) { repo.status }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha.value }) {
                when {
                    tab == 3 -> SearchScreen(repo, openItem)
                    tab == 4 -> HiddenScreen(repo)
                    tab == 6 -> PlaylistsScreen(repo) { pl, idx ->
                        val m = pl.items.getOrNull(idx)
                        if (m != null) {
                            Zap.vodQueue = pl.items
                            Zap.vodIndex = idx
                            Zap.queueIsPlaylist = true
                            Zap.queueName = pl.name
                            val u = api.vodUrl(m.id, m.ext)
                            play(ctx, u, m.name, false, m.img, "vod", repo.resumeFor(u)?.pos ?: 0L)
                        }
                    }
                    tab == 5 -> SettingsScreen(repo, scope, logout)
                    vis == null -> LoadingView(repo, cacheLoaded) { scope.launch { repo.refreshContent(true) } }
                    vis.cats.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "Toutes les catégories sont masquées.\nVa dans l’onglet Masqués pour en cocher.",
                            color = MUTED, textAlign = TextAlign.Center
                        )
                    }
                    tab == 0 -> LiveView(
                        repo, vis,
                        onHide = { repo.hideItem("live", it) },
                        onHideCat = { repo.toggleCat("live", it) }
                    ) { ch, list, idx ->
                        openItem("live", ch, list, idx)
                    }
                    else -> Browse(
                        vis, kind, hero,
                        favs = repo.favItems(kind),
                        resumes = repo.resumes.filter { it.kind == kind },
                        onPlay = { sh ->
                            if (kind == "vod") {
                                setVodQueue(repo, sh)
                                val u = api.vodUrl(sh.id, sh.ext)
                                play(ctx, u, sh.name, false, sh.img, "vod", repo.resumeFor(u)?.pos ?: 0L)
                            } else {
                                openItem(kind, sh, emptyList(), 0)
                            }
                        },
                        onInfo = { sh -> detail = kind to sh },
                        onResume = { r ->
                            if (r.kind == "vod") Zap.vodQueue = emptyList()
                            play(ctx, r.url, r.title, false, r.img, r.kind, r.pos)
                        },
                        onOpen = { openItem(kind, it, emptyList(), 0) }
                    )
                }
                }
            }
        }
    }
    }
}

@Composable
fun LoadingView(repo: Repo, cacheLoaded: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (repo.status.startsWith("Erreur")) {
                Text(repo.status, color = TXT)
                ActionButton("Réessayer", true, onRetry)
            } else {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val pa by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pa")
                Box(Modifier.alpha(pa)) { Logo(72.dp) }
                val msg = when {
                    !cacheLoaded -> "Chargement…"
                    repo.status.isBlank() -> "Chargement…"
                    else -> repo.status + "\nLa première synchronisation peut prendre un moment, ensuite tout sera instantané."
                }
                Text(msg, color = MUTED, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun Backdrop(url: String?) {
    val perf = LocalPerf.current
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = if (perf) null else url, animationSpec = tween(400), label = "backdrop") { u ->
            Box(Modifier.fillMaxSize()) {
                if (u != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(lightImg(u, "w780")).size(1280, 720).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.8f).fillMaxHeight(0.72f)
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(colorStops = arrayOf(0.22f to SCRIM, 0.62f to SCRIM.copy(alpha = 0f)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colorStops = arrayOf(0.40f to SCRIM.copy(alpha = 0f), 0.80f to SCRIM))
            )
        )
    }
}

@Composable
fun TopBar(tab: Int, onTab: (Int) -> Unit, statusText: () -> String) {
    val status = statusText()
    var time by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { time = clock(); delay(20_000) } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Logo(32.dp)
            Spacer(Modifier.width(10.dp))
            Text("Kinéo", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TXT)
        }
        Row(
            Modifier.background(BG.copy(alpha = 0.8f), CircleShape)
                .border(1.dp, Color(0xFF3A3A45), CircleShape).padding(4.dp)
        ) {
            TAB_ORDER.forEach { (i, n) -> Pill(n, tab == i) { onTab(i) } }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (status.isNotBlank()) {
                Text(
                    "↻ $status", fontSize = 11.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)
                )
            }
            SearchPill(tab == 3) { onTab(3) }
            Spacer(Modifier.width(12.dp))
            Text(time, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TXT)
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, color = TXT, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier
            .onFocusChanged { f = it.isFocused }
            .clip(CircleShape)
            .background(if (selected) PILL else Color.Transparent)
            .border(2.dp, if (f) Color.White else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
fun ActionButton(text: String, primary: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        color = if (primary) BG else TXT,
        modifier = Modifier
            .onFocusChanged { f = it.isFocused }
            .clip(CircleShape)
            .background(if (primary) Color.White else PILL)
            .border(3.dp, if (f) ACCENT else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp)
    )
}

// ---------------------------------------------------------------- Films / Séries
/** État de la bannière : isolé pour que bouger dans les affiches ne redessine pas toute la page. */
class HeroState {
    var item by mutableStateOf<Item?>(null)
    var shown by mutableStateOf<Item?>(null)
    var info by mutableStateOf<Info?>(null)
}

@Composable
fun HeroEffects(hero: HeroState, kind: String, api: Xtream, first: Item?) {
    LaunchedEffect(first?.id) {
        if (hero.shown == null) {
            hero.item = first
            hero.shown = first
        }
    }
    val raw = hero.item
    LaunchedEffect(raw?.id) {
        delay(250)
        hero.shown = hero.item
    }
    val cur = hero.shown
    LaunchedEffect(cur?.id, kind) {
        hero.info = if (kind == "series" && cur != null) Info(cur.plot, cur.year, "", cur.genre, cur.backdrop) else null
        if (kind == "vod" && cur != null) {
            delay(400)
            hero.info = api.vodInfo(cur.id)
        }
    }
}

@Composable
fun HeroBackdrop(hero: HeroState) {
    val sh = hero.shown
    Backdrop(hero.info?.backdrop ?: sh?.backdrop ?: sh?.img)
}

@Composable
fun HeroPanel(hero: HeroState, kind: String, focus: FocusRequester, onPlay: (Item) -> Unit, onInfo: (Item) -> Unit) {
    val perf = LocalPerf.current
    val item = hero.shown
    val info = hero.info
    Column(Modifier.fillMaxWidth(0.6f).height(230.dp).padding(start = 32.dp, top = 6.dp)) {
        AnimatedContent(
            targetState = item?.id,
            transitionSpec = {
                if (perf) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(300)) togetherWith fadeOut(tween(150))
            },
            label = "hero"
        ) { _ ->
            Column {
                Text(
                    item?.name ?: "", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    item?.rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let { "★ $it" },
                    info?.year?.takeIf { it.isNotBlank() },
                    info?.duration?.takeIf { it.isNotBlank() },
                    info?.genre?.takeIf { it.isNotBlank() }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item?.lang?.takeIf { it.isNotBlank() }?.let { l ->
                        Text(
                            l, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(langColor(l), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 2.dp)
                        )
                    }
                    Text(meta.joinToString("  •  "), fontSize = 13.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text(info?.plot.orEmpty(), fontSize = 13.sp, lineHeight = 18.sp, color = MUTED, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.focusRequester(focus)) {
                ActionButton(if (kind == "series") "▶ Épisodes" else "▶ Lecture", true) { item?.let(onPlay) }
            }
            ActionButton("Plus d'infos", false) { item?.let(onInfo) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// Largeur des affiches dans les rangées (taille d'origine)
private val BROWSE_W = 124.dp

// Catégorie complète ouverte (type, titre, éléments) : retrouvée au retour d'une fiche détaillée
private var lastCollection: Triple<String, String, List<Item>>? = null

@Composable
fun Browse(
    data: Data, kind: String, hero: HeroState,
    favs: List<Item>, resumes: List<Resume>,
    onPlay: (Item) -> Unit, onInfo: (Item) -> Unit,
    onResume: (Resume) -> Unit, onOpen: (Item) -> Unit
) {
    // « Voir toute la collection » : titre de la catégorie + tous ses éléments
    var full by remember(kind) {
        mutableStateOf<Pair<String, List<Item>>?>(lastCollection?.takeIf { it.first == kind }?.let { it.second to it.third })
    }
    val f = full
    if (f != null) {
        BackHandler { full = null; lastCollection = null }
        CollectionGrid(f.first, f.second, onOpen) { full = null; lastCollection = null }
    } else {
        BrowseHome(data, kind, hero, favs, resumes, onPlay, onInfo, onResume, onOpen) { t, l -> full = t to l; lastCollection = Triple(kind, t, l) }
    }
}

@Composable
fun BrowseHome(
    data: Data, kind: String, hero: HeroState,
    favs: List<Item>, resumes: List<Resume>,
    onPlay: (Item) -> Unit, onInfo: (Item) -> Unit,
    onResume: (Resume) -> Unit, onOpen: (Item) -> Unit,
    onSeeAll: (String, List<Item>) -> Unit
) {
    val top = remember(data) { data.rows.firstOrNull()?.items?.take(10) ?: emptyList() }
    val onFocus: (Item) -> Unit = remember(hero) { { i: Item -> hero.item = i } }
    val listState = rememberLazyListState()
    val heroFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // Touche Retour : remonte tout en haut de la page (au lieu de quitter)
    BackHandler(enabled = !atTop) {
        scope.launch {
            listState.scrollToItem(0)
            try { heroFocus.requestFocus() } catch (e: Exception) { /* ignoré */ }
        }
    }
    Column(Modifier.fillMaxSize()) {
        HeroPanel(hero, kind, heroFocus, onPlay, onInfo)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (resumes.isNotEmpty()) {
                item {
                    ShelfTitle("Continuer à regarder")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(resumes) { r ->
                            Poster(
                                Item(id = r.url, name = r.title, img = r.img, cat = "", ext = ""),
                                2f / 3f, false, onFocus = {},
                                progress = if (r.dur > 0) r.pos.toFloat() / r.dur else 0f, w = BROWSE_W
                            ) { onResume(r) }
                        }
                    }
                }
            }
            if (favs.isNotEmpty()) {
                item {
                    ShelfTitle("Ma liste")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(favs) { it ->
                            Poster(it, 2f / 3f, false, onFocus, w = BROWSE_W) { onOpen(it) }
                        }
                    }
                }
            }
            if (top.isNotEmpty()) {
                item {
                    ShelfTitle("Top 10 des nouveautés")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(top) { idx, it ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${idx + 1}", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color(0xFF55557A))
                                Poster(it, 2f / 3f, false, onFocus, w = BROWSE_W) { onOpen(it) }
                            }
                        }
                    }
                }
            }
            items(data.rows) { shelf ->
                val whole = remember(data, shelf) {
                    val c = data.cats.firstOrNull { it.name == shelf.title }
                    when {
                        c != null -> data.byCat[c.id] ?: shelf.items
                        shelf.title == "Récemment ajoutés" -> data.all.sortedByDescending { it.added }.take(500)
                        else -> shelf.items
                    }
                }
                ShelfTitle(shelf.title, whole.size)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        SeeAllCard(whole.size, BROWSE_W) { onSeeAll(shelf.title, whole) }
                    }
                    items(shelf.items) { it ->
                        Poster(it, 2f / 3f, false, onFocus, w = BROWSE_W) { onOpen(it) }
                    }
                }
            }
        }
    }
}

/** Titre de rangée (style d'origine) + nombre de titres. */
@Composable
fun ShelfTitle(text: String, count: Int = -1) {
    Row(Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count > 0) Text("   $count titres", fontSize = 13.sp, color = MUTED)
    }
}

/** Première tuile de chaque rangée : ouvre la catégorie complète (on scrolle à droite pour parcourir, OK pour tout voir). */
@Composable
fun SeeAllCard(total: Int, w: Dp, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        Modifier.padding(3.dp).width(w - 6.dp).aspectRatio(2f / 3f)
            .onFocusChanged { f = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (f) Color(0x66FFFFFF) else Color(0x22FFFFFF))
            .border(3.dp, if (f) Color.White else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            Text("›", fontSize = 44.sp, fontWeight = FontWeight.Black, color = if (f) Color.White else ACCENT)
            Text(
                "Voir toute la collection", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = TXT, textAlign = TextAlign.Center
            )
            Text("$total titres", fontSize = 11.sp, color = MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Tous les titres d'une catégorie, en grille. Retour = revient aux rangées. */
@Composable
fun CollectionGrid(title: String, list: List<Item>, onOpen: (Item) -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    var bf by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (e: Exception) { /* ignoré */ } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ Retour", color = TXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.focusRequester(first).onFocusChanged { bf = it.isFocused }
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .background(if (bf) Color(0x66FFFFFF) else Color(0x33FFFFFF))
                    .border(2.dp, if (bf) Color.White else Color.Transparent, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(title, color = TXT, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Text("${list.size} titres", color = MUTED, fontSize = 14.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(list) { _, it ->
                Poster(it, 2f / 3f, false, {}) { onOpen(it) }
            }
        }
    }
}

@Composable
fun Poster(item: Item, ratio: Float, fit: Boolean, onFocus: (Item) -> Unit, progress: Float = -1f, w: Dp = 124.dp, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val perf = LocalPerf.current
    val sc = animateFloatAsState(if (f && !perf) 1.1f else 1f, tween(180), label = "scale")
    val pctx = LocalContext.current
    val req = remember(item.img, perf) {
        ImageRequest.Builder(pctx).data(lightImg(item.img, if (perf) "w185" else "w342"))
            .size(if (perf) 220 else 300, if (perf) 330 else 450).crossfade(if (perf) 0 else 150)
            .allowRgb565(perf).build()
    }
    Box(
        Modifier.width(w).graphicsLayer { scaleX = sc.value; scaleY = sc.value }
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus(item) }
            .border(3.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(3.dp)
    ) {
        AsyncImage(
            model = req,
            contentDescription = item.name,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C24))
        )
        if (item.img == null) {
            Text(
                item.name, fontSize = 11.sp, color = TXT, textAlign = TextAlign.Center, maxLines = 4,
                modifier = Modifier.align(Alignment.Center).padding(6.dp)
            )
        }
        if (progress >= 0f) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)
                    .height(4.dp).background(Color(0x99000000), RoundedCornerShape(2.dp))
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(ACCENT, RoundedCornerShape(2.dp)))
            }
        }
        if (f) {
            Text(
                item.name, fontSize = 11.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                    .background(Color(0xCC000000)).padding(6.dp)
            )
        }
        val badges = remember(item.name) { Quality.badges(item.name) }
        if (!fit && badges.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = if (progress >= 0f) 18.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                badges.forEach { b ->
                    Text(
                        b, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (!fit && item.lang.isNotBlank()) {
            Text(
                item.lang, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(langColor(item.lang), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- Page de détail (film / série)
@Composable
fun DetailScreen(
    repo: Repo, api: Xtream, kind: String, item: Item, onBack: () -> Unit,
    onPlay: (String, String, String?, String, Long) -> Unit
) {
    var info by remember(item.id) { mutableStateOf<Info?>(null) }
    var eps by remember(item.id) { mutableStateOf<List<Ep>?>(null) }
    var season by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id) {
        try {
            if (kind == "series") {
                val (i, e) = api.seriesDetail(item.id)
                info = i
                eps = e
            } else {
                info = api.vodInfo(item.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (kind == "series") eps = emptyList()
        }
    }
    var tm by remember(item.id) { mutableStateOf<Map<String, Map<Int, TmEp>>>(emptyMap()) }
    var tmMv by remember(item.id) { mutableStateOf<TmMovie?>(null) }
    LaunchedEffect(item.id, info?.tmdb, repo.tmdbKey) {
        if (kind == "vod" && repo.tmdbKey.isNotBlank()) {
            delay(500)
            tmMv = Tmdb.movie(repo.tmdbKey, info?.tmdb ?: "", item.name, (info?.year ?: "").ifBlank { item.year })
        }
    }
    val inf = info
    val plot = (inf?.plot ?: "").ifBlank { item.plot }.ifBlank { tmMv?.overview ?: "" }
    val backdrop = inf?.backdrop ?: item.backdrop ?: tmMv?.backdrop ?: item.img
    val vodUrl = api.vodUrl(item.id, item.ext)
    val playVod: (Long) -> Unit = { st ->
        setVodQueue(repo, item)
        onPlay(vodUrl, item.name, item.img, "vod", st)
    }
    var showAdd by remember(item.id) { mutableStateOf(false) }
    if (showAdd) AddToPlaylistDialog(repo, item) { showAdd = false }
    val resume = if (kind == "vod") repo.resumeFor(vodUrl) else null
    val fav = repo.isFav(kind, item)
    val meta = listOfNotNull(
        item.rating.ifBlank { tmMv?.rating ?: "" }.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let { "★ $it" },
        (inf?.year ?: "").ifBlank { item.year }.ifBlank { tmMv?.year ?: "" }.takeIf { it.isNotBlank() },
        (inf?.duration ?: "").ifBlank { tmMv?.runtime ?: "" }.takeIf { it.isNotBlank() },
        (inf?.genre ?: "").ifBlank { item.genre }.ifBlank { tmMv?.genre ?: "" }.takeIf { it.isNotBlank() }
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG_TOP, BG)))) {
        Backdrop(backdrop)
        Column(Modifier.fillMaxSize().padding(start = 40.dp, top = 24.dp, end = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(26.dp)
                Spacer(Modifier.width(8.dp))
                Text("Kinéo", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TXT)
                Spacer(Modifier.weight(1f))
                Text("Retour : touche retour de la télécommande", fontSize = 12.sp, color = MUTED)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth(0.62f)) {
                Text(
                    item.name, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.lang.isNotBlank()) {
                        Text(
                            item.lang, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(langColor(item.lang), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 2.dp)
                        )
                    }
                    Text(meta.joinToString("  •  "), fontSize = 14.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (plot.isBlank() && inf == null) "Chargement…" else plot.ifBlank { "Pas de synopsis." },
                    fontSize = 14.sp, lineHeight = 20.sp, color = MUTED,
                    maxLines = if (kind == "series") 3 else 6, overflow = TextOverflow.Ellipsis
                )
                (inf?.cast ?: "").ifBlank { tmMv?.cast ?: "" }.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Avec : $it", fontSize = 13.sp, color = MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                (inf?.director ?: "").ifBlank { tmMv?.director ?: "" }.takeIf { it.isNotBlank() }?.let {
                    Text("De : $it", fontSize = 13.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind == "vod") {
                        if (resume != null) {
                            ActionButton("▶ Reprendre à ${fmtMs(resume.pos)}", true) {
                                playVod(resume.pos)
                            }
                            ActionButton("Depuis le début", false) { playVod(0L) }
                        } else {
                            ActionButton("▶ Lecture", true) { playVod(0L) }
                        }
                    } else {
                        eps?.firstOrNull()?.let { e1 ->
                            ActionButton("▶ Lecture S${e1.season} E${e1.num.ifBlank { "1" }}", true) {
                                eps?.let { setZapEpisodes(api, item, it, e1, tm) }
                                onPlay(
                                    api.epUrl(e1.id, e1.ext),
                                    "${item.name} · S${e1.season} E${e1.num.ifBlank { "1" }}",
                                    e1.img ?: item.img, "series",
                                    repo.resumeFor(api.epUrl(e1.id, e1.ext))?.pos ?: 0L
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(if (fav) "✓ Dans ma liste" else "+ Ma liste", false) { repo.toggleFav(kind, item) }
                    if (kind == "vod") ActionButton("+ Playlist", false) { showAdd = true }
                    ActionButton("Masquer", false) { repo.hideItem(kind, item); onBack() }
                }
            }
            if (kind == "series") {
                Spacer(Modifier.height(12.dp))
                val list = eps
                if (list == null) {
                    Text("Chargement des épisodes…", color = MUTED, fontSize = 14.sp)
                } else if (list.isEmpty()) {
                    Text("Aucun épisode trouvé.", color = MUTED, fontSize = 14.sp)
                } else {
                    val seasons = list.map { it.season }.distinct()
                    val sSel = season.ifBlank { seasons.first() }
                    LaunchedEffect(sSel, inf?.tmdb, repo.tmdbKey) {
                        val key = repo.tmdbKey
                        if (key.isNotBlank() && !tm.containsKey(sSel)) {
                            val id = (inf?.tmdb ?: "").ifBlank { Tmdb.findTv(key, item.name) ?: "" }
                            if (id.isNotBlank()) tm = tm + (sSel to Tmdb.season(key, id, sSel))
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { sn -> Pill("Saison $sn", sn == sSel) { season = sn } }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list.filter { it.season == sSel }) { ep ->
                            EpisodeRow(repo, api, item, ep, tm[sSel]?.get(ep.num.toIntOrNull() ?: -1)) { u, t, i, k, st ->
                                setZapEpisodes(api, item, list, ep, tm)
                                onPlay(u, t, i, k, st)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeRow(
    repo: Repo, api: Xtream, series: Item, ep: Ep, tm: TmEp?,
    onPlay: (String, String, String?, String, Long) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val url = api.epUrl(ep.id, ep.ext)
    val r = repo.resumeFor(url)
    val label = ep.num.ifBlank { "?" }
    val title = "${series.name} · S${ep.season} E$label"
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .background(if (f) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { onPlay(url, title, ep.img ?: tm?.still ?: series.img, "series", r?.pos ?: 0L) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C24))) {
            val ectx = LocalContext.current
            val epImg = ep.img ?: tm?.still ?: series.img
            val epReq = remember(epImg) { ImageRequest.Builder(ectx).data(epImg).size(384, 216).build() }
            AsyncImage(
                model = epReq, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (r != null && r.dur > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxWidth((r.pos.toFloat() / r.dur).coerceIn(0f, 1f)).fillMaxHeight().background(ACCENT))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val epName = tm?.name?.takeIf { it.isNotBlank() } ?: cleanEpTitle(ep.title, series.name, label)
            val epPlot = (tm?.overview ?: "").ifBlank { ep.plot }
            Text("$label.  $epName", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ep.duration.isNotBlank()) Text(ep.duration, fontSize = 12.sp, color = MUTED)
            if (epPlot.isNotBlank()) Text(epPlot, fontSize = 12.sp, color = MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- TV en direct
@Composable
fun LiveView(repo: Repo, data: Data, onHide: (Item) -> Unit, onHideCat: (Cat) -> Unit, onPlay: (Item, List<Item>, Int) -> Unit) {
    val ev = repo.epgVersion
    val appCtx = LocalContext.current.applicationContext
    var catId by remember {
        mutableStateOf<String?>(if (repo.recents.isNotEmpty()) "__recent" else data.cats.firstOrNull()?.id)
    }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    val favList = repo.favItems("live")
    // une chaîne masquée ou disparue de la playlist ne doit plus apparaître dans « Récemment regardées »
    val known = remember(data) { data.all.mapTo(HashSet()) { it.id } }
    val recentList = repo.recents.filter { it.id in known }
    val cats = buildList {
        if (recentList.isNotEmpty()) add(Cat("__recent", "Récemment regardées"))
        if (favList.isNotEmpty()) add(Cat("__fav", "★ Favoris"))
        addAll(data.cats)
    }
    val effId = if (cats.any { it.id == catId }) catId else cats.firstOrNull()?.id
    val list = when (effId) {
        "__recent" -> recentList
        "__fav" -> favList
        else -> data.byCat[effId] ?: emptyList()
    }
    val listName = cats.firstOrNull { it.id == effId }?.name ?: ""
    val pItem = Preview.item
    // on quitte l'onglet TV : on coupe l'aperçu (sauf s'il est en plein écran)
    DisposableEffect(Unit) { onDispose { if (!Preview.lent) Preview.release() } }
    val openFull: () -> Unit = {
        if (pItem != null) {
            val i = list.indexOfFirst { it.id == pItem.id }
            if (i >= 0) onPlay(pItem, list, i) else onPlay(pItem, listOf(pItem), 0)
        }
    }
    Column(Modifier.fillMaxSize()) {
        // catégories en haut, façon Free TV
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(cats) { c ->
                CatChip(c.name, c.id == effId, onFocus = { catId = c.id }) { catId = c.id }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    "$listName  ·  ${list.size} chaîne${if (list.size > 1) "s" else ""}",
                    color = MUTED, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp)
                )
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (list.isEmpty()) {
                        item {
                            Text(
                                if (effId == "__recent") "Aucune chaîne regardée pour l'instant." else "Aucune chaîne.",
                                color = MUTED, modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    itemsIndexed(list) { idx, ch ->
                        ChannelRow(repo, ch, idx + 1, ev, onFocus = { focusedCh = ch }) {
                            // 1er clic : aperçu dans le carré. 2e clic sur la même chaîne : plein écran.
                            if (!repo.preview || pItem?.id == ch.id) onPlay(ch, list, idx)
                            else Preview.play(appCtx, repo.api.liveUrl(ch.id), ch)
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            val cur = focusedCh ?: list.firstOrNull()
            val curCat = data.cats.firstOrNull { it.id == effId }
            ChannelPanel(
                repo, cur, curCat?.name, ev, Modifier.width(320.dp), pItem, openFull,
                onHideCat = {
                    curCat?.let { c ->
                        catId = cats.firstOrNull { it.id != c.id && !it.id.startsWith("__") }?.id
                        focusedCh = null
                        onHideCat(c)
                    }
                }
            ) {
                cur?.let { onHide(it) }
                focusedCh = null
            }
        }
    }
}

/** Pastille de catégorie (barre du haut de l'onglet TV). */
@Composable
fun CatChip(text: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, maxLines = 1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        color = if (f) BG else TXT,
        modifier = Modifier
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clip(CircleShape)
            .background(if (f) Color.White else if (selected) ACCENT else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
    )
}

@Composable
fun ChannelRow(repo: Repo, ch: Item, num: Int, ev: Int, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var fb by remember(ch.id) { mutableStateOf<List<Prog>>(emptyList()) }
    val cached = repo.progsFor(ch)
    val progs = if (cached.isNotEmpty()) cached else fb
    LaunchedEffect(ch.id, ev) {
        if (repo.progsFor(ch).isEmpty()) {
            val c = repo.cachedShortEpg(ch.id)
            if (c != null) fb = c else {
                delay(250)
                fb = repo.shortEpg(ch.id)
            }
        }
    }
    val nowS = System.currentTimeMillis() / 1000
    val p = progs.firstOrNull()
    val frac = if (p != null && p.stop > p.start) ((nowS - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .background(if (f) Color(0x44FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$num", color = MUTED, fontSize = 13.sp, modifier = Modifier.width(34.dp))
        val lctx = LocalContext.current
        val logo = remember(ch.img) { ImageRequest.Builder(lctx).data(ch.img).size(112, 112).build() }
        Box(
            Modifier.size(52.dp).background(Color(0x22FFFFFF), RoundedCornerShape(10.dp)).padding(6.dp),
            Alignment.Center
        ) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = TXT, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p?.title ?: "Programme indisponible", color = MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                LinearProgressIndicator(
                    progress = { frac }, color = ACCENT, trackColor = Color(0x33FFFFFF),
                    modifier = Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp)
                )
            }
        }
    }
}

@Composable
fun ChannelPanel(
    repo: Repo, ch: Item?, catName: String?, ev: Int, modifier: Modifier,
    previewItem: Item?, onPreviewClick: () -> Unit, onHideCat: () -> Unit, onHide: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var fb by remember(ch?.id) { mutableStateOf<List<Prog>>(emptyList()) }
    LaunchedEffect(ch?.id, ev) {
        if (ch != null && repo.progsFor(ch).isEmpty()) {
            val c = repo.cachedShortEpg(ch.id)
            if (c != null) fb = c else {
                delay(300)
                fb = repo.shortEpg(ch.id)
            }
        }
    }
    val cached = if (ch != null) repo.progsFor(ch) else emptyList()
    val progs = if (cached.isNotEmpty()) cached else fb
    Column(
        modifier.fillMaxHeight().padding(vertical = 8.dp)
            .background(Color(0xFF14141A), RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (repo.preview) PreviewBox(previewItem, onPreviewClick, Modifier.fillMaxWidth())
        if (ch == null) {
            Text("Aucune chaîne", color = MUTED)
        } else {
            Text(ch.name, color = TXT, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            progs.take(2).forEachIndexed { i, p ->
                Text(if (i == 0) "Maintenant" else "Ensuite", color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${fmt.format(Date(p.start * 1000))} – ${fmt.format(Date(p.stop * 1000))}   ${p.title}",
                    color = TXT, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (progs.isEmpty()) Text("Programme indisponible", color = MUTED, fontSize = 13.sp)
            ActionButton(if (repo.isFav("live", ch)) "★ Retirer des favoris" else "☆ Ajouter aux favoris", false) {
                repo.toggleFav("live", ch)
            }
            ActionButton("Masquer cette chaîne", false, onHide)
            if (catName != null) ActionButton("Masquer la catégorie « $catName »", false, onHideCat)
        }
    }
}

/** Petit carré d'aperçu : la chaîne en direct. OK dessus = plein écran, sans coupure (même lecteur). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PreviewBox(item: Item?, onClick: () -> Unit, modifier: Modifier) {
    var f by remember { mutableStateOf(false) }
    var pv by remember { mutableStateOf<PlayerView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) pv?.player = null
            if (ev == Lifecycle.Event.ON_START) pv?.player = Preview.player
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    Box(
        modifier.aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .onFocusChanged { f = it.isFocused }
            .border(3.dp, if (f) Color.White else Color(0xFF3A3A55), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        if (item != null) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { v ->
                    pv = v
                    v.player = Preview.player
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                item.name, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp)
            )
        } else {
            Text(
                "OK sur une chaîne : aperçu en direct", fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(10.dp)
            )
        }
    }
}

@Composable
fun SearchPill(selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.onFocusChanged { f = it.isFocused }
            .clip(CircleShape)
            .background(if (selected) PILL else Color(0x33FFFFFF))
            .border(2.dp, if (f) Color.White else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            drawCircle(Color.White, radius = w * 0.32f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(width = w * 0.1f))
            drawLine(Color.White, Offset(w * 0.65f, w * 0.65f), Offset(w * 0.92f, w * 0.92f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Text("Chaînes, films, séries…", color = MUTED, fontSize = 13.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Playlists
@Composable
fun PlaylistsScreen(repo: Repo, onPlay: (Playlist, Int) -> Unit) {
    var sel by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var itemSel by remember { mutableStateOf<Item?>(null) }
    val pls = repo.playlists
    val cur = pls.getOrNull(sel)
    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(300.dp)) {
            Text("Mes playlists", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TXT)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                itemsIndexed(pls) { i, p ->
                    FocusRow("${p.name}  (${p.items.size})", i == sel, onFocus = { sel = i }) { sel = i }
                }
                item { FocusRow("+  Nouvelle playlist") { dialog = "new" } }
            }
        }
        Column(Modifier.weight(1f)) {
            if (cur == null) {
                Text(
                    "Crée une playlist, puis ajoute-y des films depuis la page d'un film (bouton « + Playlist »). Ils s'enchaînent automatiquement.",
                    color = MUTED, fontSize = 14.sp
                )
            } else {
                Text(cur.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("▶ Lire", true) { if (cur.items.isNotEmpty()) onPlay(cur, 0) }
                    ActionButton("Renommer", false) { dialog = "rename" }
                    ActionButton("Supprimer", false) { dialog = "delete" }
                }
                Spacer(Modifier.height(8.dp))
                if (cur.items.isEmpty()) Text("Playlist vide.", color = MUTED, fontSize = 14.sp)
                LazyColumn {
                    itemsIndexed(cur.items) { i, m -> PlaylistItemRow(m, i + 1) { itemSel = m } }
                }
            }
        }
    }
    if (dialog == "new" || dialog == "rename") {
        var name by remember(dialog) { mutableStateOf(if (dialog == "rename") cur?.name ?: "" else "") }
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(if (dialog == "new") "Nouvelle playlist" else "Renommer la playlist") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nom de la playlist") })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        if (dialog == "new") sel = repo.createPlaylist(name) else repo.renamePlaylist(sel, name)
                    }
                    dialog = ""
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Annuler") } }
        )
    }
    if (dialog == "delete" && cur != null) {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Supprimer « ${cur.name} » ?") },
            confirmButton = {
                TextButton(onClick = { repo.deletePlaylist(sel); sel = 0; dialog = "" }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Annuler") } }
        )
    }
    val it0 = itemSel
    if (it0 != null && cur != null) {
        AlertDialog(
            onDismissRequest = { itemSel = null },
            title = { Text(it0.name) },
            confirmButton = {
                TextButton(onClick = {
                    val i = cur.items.indexOfFirst { x -> x.id == it0.id }
                    itemSel = null
                    if (i >= 0) onPlay(cur, i)
                }) { Text("Lire à partir d'ici") }
            },
            dismissButton = {
                TextButton(onClick = { repo.removeFromPlaylist(sel, it0.id); itemSel = null }) { Text("Retirer de la playlist") }
            }
        )
    }
}

@Composable
fun PlaylistItemRow(item: Item, n: Int, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .background(if (f) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$n", color = MUTED, fontSize = 13.sp, modifier = Modifier.width(32.dp))
        AsyncImage(
            model = lightImg(item.img, "w92"), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C24))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.lang.isNotBlank()) Text(item.lang, color = MUTED, fontSize = 12.sp)
        }
    }
}

@Composable
fun AddToPlaylistDialog(repo: Repo, item: Item, onDone: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Ajouter à une playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!creating) {
                    repo.playlists.forEachIndexed { i, p ->
                        FocusRow("${p.name}  (${p.items.size})") { repo.addToPlaylist(i, item); onDone() }
                    }
                    FocusRow("+  Nouvelle playlist…") { creating = true }
                } else {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nom de la playlist") })
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val i = repo.createPlaylist(name)
                        repo.addToPlaylist(i, item)
                        onDone()
                    }
                }) { Text("Créer et ajouter") }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Annuler") } }
    )
}

// ---------------------------------------------------------------- Recherche
@Composable
fun SearchScreen(repo: Repo, open: (String, Item, List<Item>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf("vod") }
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Recherche en direct : à chaque lettre, sans délai
    var res by remember {
        mutableStateOf(Triple<List<Item>, List<Item>, List<Item>>(emptyList(), emptyList(), emptyList()))
    }
    LaunchedEffect(q) { res = repo.search(q) }
    val lists = mapOf("live" to res.first, "vod" to res.second, "series" to res.third)
    LaunchedEffect(res) {
        if (lists[sel].isNullOrEmpty()) {
            listOf("vod", "live", "series").firstOrNull { !lists[it].isNullOrEmpty() }?.let { sel = it }
        }
    }
    val cur = lists[sel] ?: emptyList()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.moveFocus(FocusDirection.Down) }),
                placeholder = { Text("Rechercher une chaîne, un film, une série…", color = Color(0xFF666677)) },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedTextColor = Color(0xFF111111),
                    unfocusedTextColor = Color(0xFF111111),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color(0xFF111111)
                ),
                modifier = Modifier.weight(1f).focusRequester(fr)
            )
            Row(
                Modifier.background(Color(0x33FFFFFF), CircleShape)
                    .border(1.dp, Color(0xFF3A3A55), CircleShape).padding(4.dp)
            ) {
                Pill("TV ${res.first.size}", sel == "live") { sel = "live" }
                Pill("Films ${res.second.size}", sel == "vod") { sel = "vod" }
                Pill("Séries ${res.third.size}", sel == "series") { sel = "series" }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(cur) { idx, it ->
                Poster(it, if (sel == "live") 1f else 2f / 3f, sel == "live", {}) { open(sel, it, cur, idx) }
            }
        }
    }
}

// ---------------------------------------------------------------- Masqués
@Composable
fun HiddenScreen(repo: Repo) {
    val hv = repo.hiddenVersion
    var kind by remember { mutableStateOf("live") }
    var kw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val filtering = kw.isNotBlank()
    val cats = (repo.data[kind]?.cats ?: emptyList()).filter { !filtering || it.name.contains(kw.trim(), ignoreCase = true) }
    val hiddenList = repo.hiddenItems.entries
        .filter { it.key.startsWith("$kind:") && (!filtering || it.value.contains(kw.trim(), ignoreCase = true)) }
        .map { it.key to it.value }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("live" to "TV", "vod" to "Films", "series" to "Séries").forEach { (k, l) ->
                Pill(l, kind == k) { kind = k; msg = "" }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                kw, { kw = it }, singleLine = true,
                placeholder = { Text("Rechercher une catégorie : DAZN, TURK…") },
                modifier = Modifier.width(320.dp)
            )
            ActionButton(if (filtering) "✓ Cocher les ${cats.size} résultats" else "✓ Tout cocher", false) {
                if (filtering) repo.showCatsMatching(kind, kw) else repo.showAllCats(kind)
                msg = if (filtering) "Ces catégories sont affichées" else "Toutes les catégories sont affichées"
            }
            ActionButton(if (filtering) "✕ Décocher les ${cats.size} résultats" else "✕ Tout décocher", false) {
                if (filtering) repo.hideCatsMatching(kind, kw) else repo.hideAllCats(kind)
                msg = if (filtering) "Ces catégories sont masquées" else "Toutes les catégories sont masquées : coche celles que tu veux garder"
            }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = ACCENT)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Catégories (${cats.size}) : OK pour cocher ou décocher",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TXT
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn {
                    items(cats) { c ->
                        val h = repo.isCatHidden(kind, c.id)
                        FocusRow((if (h) "✕  " else "✓  ") + c.name, h) { repo.toggleCat(kind, c) }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Éléments masqués (OK pour réafficher)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TXT)
                Spacer(Modifier.height(6.dp))
                if (hiddenList.isEmpty() && hv >= 0) {
                    Text(
                        "Aucun pour l'instant. Pour masquer : page de détail d'un film ou d'une série, ou le panneau d'une chaîne dans l'onglet TV.",
                        fontSize = 13.sp, color = MUTED
                    )
                }
                LazyColumn {
                    items(hiddenList) { (key, name) -> FocusRow(name) { repo.showItem(key) } }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Réglages
@Composable
fun SettingsScreen(repo: Repo, scope: CoroutineScope, logout: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val opts = listOf(1 to "Chaque jour", 3 to "Tous les 3 jours", 7 to "Chaque semaine", 30 to "Chaque mois")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Réglages", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TXT)
        Text("Contenu (chaînes, films, séries)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        Text("Dernière mise à jour : ${fmtDate(repo.lastContentTs)}", fontSize = 13.sp, color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (d, l) -> Pill(l, repo.freqContent == d) { repo.updateFreqContent(d) } }
        }
        ActionButton("Mettre à jour maintenant", false) { scope.launch { repo.refreshContent(true) } }
        Spacer(Modifier.height(8.dp))
        Text("Guide TV (programmes)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        Text("Dernière mise à jour : ${fmtDate(repo.lastEpgTs)}", fontSize = 13.sp, color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (d, l) -> Pill(l, repo.freqEpg == d) { repo.updateFreqEpg(d) } }
        }
        ActionButton("Mettre à jour le guide maintenant", false) { scope.launch { repo.syncEpg() } }
        Text(
            "Les mises à jour se font à l'ouverture de l'appli, quand le délai choisi est dépassé.",
            fontSize = 12.sp, color = MUTED
        )
        Spacer(Modifier.height(8.dp))
        Text("Performance de la TV", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        val appCtx = LocalContext.current.applicationContext
        var sys by remember { mutableStateOf(Booster.info(appCtx)) }
        var perfMsg by remember { mutableStateOf("") }
        Text(
            "Mémoire libre : ${sys.freeRamMb} Mo sur ${sys.totalRamMb} Mo   ·   Stockage libre : ${sys.freeStorageMb / 1024} Go sur ${sys.totalStorageMb / 1024} Go",
            fontSize = 13.sp, color = MUTED
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Libérer la mémoire de l'appli", false) {
                val gain = Booster.trimOwnCaches(appCtx)
                sys = Booster.info(appCtx)
                perfMsg = "Mémoire de l'appli libérée : $gain Mo (les autres applis ne sont pas touchées)"
            }
            ActionButton("Vider le cache des images", false) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appCtx.imageLoader.memoryCache?.clear()
                        appCtx.imageLoader.diskCache?.clear()
                    }
                    perfMsg = "Cache des images vidé"
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill((if (repo.autoBoost) "✓ " else "✕ ") + "Libérer la mémoire de l'appli avant chaque lecture", repo.autoBoost) {
                repo.updateAutoBoost(!repo.autoBoost)
            }
            Pill((if (repo.perf) "✓ " else "✕ ") + "Mode performance", repo.perf) { repo.updatePerf(!repo.perf) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill((if (repo.autoNext) "✓ " else "✕ ") + "Lecture automatique (épisode ou film suivant)", repo.autoNext) {
                repo.updateAutoNext(!repo.autoNext)
            }
            Pill((if (repo.preview) "✓ " else "✕ ") + "Aperçu des chaînes", repo.preview) { repo.updatePreview(!repo.preview) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill((if (repo.pcmAudio) "✓ " else "✕ ") + "Son : forcer le décodage (si pas de son)", repo.pcmAudio) {
                repo.updatePcmAudio(!repo.pcmAudio)
            }
        }
        Text(
            "À activer seulement si tu n'as pas de son sur cet appareil (Chromecast…). Autre solution côté Chromecast : Paramètres, Affichage et son, Son surround, Jamais. Pendant la lecture : bouton Prog, puis « Piste audio ».",
            fontSize = 12.sp, color = MUTED
        )
        Text(
            "Mode performance : pas d'images de fond ni d'animations, images en basse consommation, tampon vidéo plus court, moins de mémoire utilisée. À activer si ça rame encore.",
            fontSize = 12.sp, color = MUTED
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("Applications de la TV", false) {
                if (!openSystem(appCtx, Settings.ACTION_APPLICATION_SETTINGS)) perfMsg = "Impossible d'ouvrir ce réglage sur cette TV"
            }
            ActionButton("Options pour les développeurs", false) {
                if (!openSystem(appCtx, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
                    perfMsg = "Active d'abord le mode développeur : Paramètres, Système, À propos, 7 clics sur « Build »"
                }
            }
        }
        if (perfMsg.isNotBlank()) Text(perfMsg, fontSize = 13.sp, color = ACCENT)
        Text(
            "Pour aller plus loin : désactive les applis inutiles dans « Applications », puis dans les options pour les développeurs mets les échelles d'animation sur 0,5x ou désactivées.",
            fontSize = 12.sp, color = MUTED
        )
        Spacer(Modifier.height(8.dp))
        Text("Synchronisation entre appareils", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        var codeIn by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill((if (repo.cloudOn) "✓ " else "✕ ") + "Synchroniser (TCL, Chromecast…)", repo.cloudOn) {
                repo.updateCloudOn(!repo.cloudOn)
            }
            ActionButton("Synchroniser maintenant", false) { scope.launch { repo.syncCloud() } }
        }
        Text(
            if (repo.cloudMsg.isNotBlank()) repo.cloudMsg
            else if (repo.cloudLast > 0) "Dernière synchro : ${fmtDate(repo.cloudLast)}"
            else "Pas encore synchronisé",
            fontSize = 13.sp, color = ACCENT
        )
        if (repo.cloudShort.isNotBlank()) {
            Text("Code de CET appareil (chaque appareil a le sien)", fontSize = 12.sp, color = MUTED)
            Text(repo.cloudShort, fontSize = 34.sp, fontWeight = FontWeight.Black, color = TXT, letterSpacing = 6.sp)
            Text("Pour lier un autre appareil : sur celui-ci, saisis le code ci-dessus dans « Code de l'autre appareil » puis « Utiliser ce code ». Les deux appareils partagent alors la synchro, et chacun garde son propre code.", fontSize = 12.sp, color = MUTED)
        } else if (repo.cloudId.isNotBlank()) {
            Text("Le code de cet appareil s'affichera après la prochaine synchronisation réussie.", fontSize = 12.sp, color = MUTED)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                codeIn, { codeIn = it }, singleLine = true,
                placeholder = { Text("Code de l'autre appareil (6 caractères)") },
                modifier = Modifier.width(300.dp)
            )
            ActionButton("Utiliser ce code", false) { scope.launch { repo.useCloudCode(codeIn) } }
        }
        Text(
            "Synchronisés : favoris, playlists, éléments masqués, reprises de lecture, mise à jour et lecture automatique. Stockés sur un service public (jsonblob.com), repérés par un identifiant secret. Tes identifiants IPTV ne sont jamais envoyés. L'aperçu, le mode performance et le son restent propres à chaque appareil.",
            fontSize = 12.sp, color = MUTED
        )
        Spacer(Modifier.height(8.dp))
        Text("Noms des épisodes (TMDB, facultatif)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        var tkey by remember { mutableStateOf(repo.tmdbKey) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                tkey, { tkey = it }, singleLine = true,
                placeholder = { Text("Clé API TMDB (v3)") },
                modifier = Modifier.width(380.dp)
            )
            ActionButton("Enregistrer", false) { repo.updateTmdbKey(tkey) }
        }
        Text(
            if (repo.tmdbKey.isBlank()) "Sans clé : les titres sont nettoyés (« Épisode 5 »)."
            else "Clé enregistrée : vrais noms d'épisodes, et fiches de films complétées (synopsis, acteurs, image). Données : TMDB.",
            fontSize = 12.sp, color = MUTED
        )
        if (repo.status.isNotBlank()) Text(repo.status, fontSize = 13.sp, color = ACCENT)
        Spacer(Modifier.height(8.dp))
        ActionButton("Changer de compte", false) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; logout() }) { Text("Se déconnecter") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
            title = { Text("Changer de compte ?") },
            text = { Text("Les données enregistrées seront effacées et rechargées avec le nouveau compte.") }
        )
    }
}

@Composable
fun FocusRow(text: String, selected: Boolean = false, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (selected) ACCENT else TXT, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus?.invoke() }
            .background(if (f) Color(0x44FFFFFF) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    )
}
