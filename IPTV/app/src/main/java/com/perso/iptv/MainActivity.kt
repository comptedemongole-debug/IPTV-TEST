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
import coil.request.ImageRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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

private val TAB_NAMES = listOf("TV", "Films", "Séries", "Recherche", "Masqués", "Réglages")

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
private fun fmtDate(ts: Long): String =
    if (ts == 0L) "jamais" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))

private fun play(
    ctx: Context, url: String, title: String, live: Boolean,
    img: String? = null, kind: String = "vod", start: Long = 0L
) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("title", title).putExtra("live", live)
            .putExtra("img", img).putExtra("kind", kind).putExtra("start", start)
    )
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
            Text("Ta télé. Ton rythme.", fontSize = 14.sp, color = MUTED)
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
    Column(
        Modifier.fillMaxSize().padding(60.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Logo(80.dp)
        Text("Kinéo", style = MaterialTheme.typography.headlineLarge)
        Text("Connexion à ton service IPTV", color = MUTED)
        OutlinedTextField(s, { s = it }, label = { Text("Serveur (http://adresse:port)") }, singleLine = true)
        OutlinedTextField(u, { u = it }, label = { Text("Identifiant") }, singleLine = true)
        OutlinedTextField(p, { p = it }, label = { Text("Mot de passe") }, singleLine = true)
        Button(onClick = { onOk(s.trim(), u.trim(), p.trim()) }) { Text("Se connecter") }
    }
}

@Composable
fun Home(api: Xtream, logout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(api) { Repo(ctx.applicationContext, api) }
    var tab by remember { mutableStateOf(1) }
    var cacheLoaded by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, Item>?>(null) }
    var focused by remember { mutableStateOf<Item?>(null) }
    var info by remember { mutableStateOf<Info?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    LaunchedEffect(repo) {
        try {
            Zap.repo = repo
            Zap.api = api
            repo.loadCache()
            cacheLoaded = true
            repo.refreshContent(false)
            repo.syncEpgIfDue()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            repo.status = "Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    val kind = when (tab) { 0 -> "live"; 1 -> "vod"; 2 -> "series"; else -> "" }
    val data = if (kind.isEmpty()) null else repo.data[kind]
    val vis = data?.let { d -> remember(d, repo.hiddenVersion) { repo.visible(kind, d) } }

    LaunchedEffect(tab) { focused = null; info = null }
    val pageAlpha = remember { Animatable(1f) }
    LaunchedEffect(tab) {
        pageAlpha.snapTo(0f)
        pageAlpha.animateTo(1f, tween(350))
    }
    val shown = focused ?: vis?.rows?.firstOrNull()?.items?.firstOrNull()
    LaunchedEffect(shown?.id, kind) {
        info = null
        val cur = shown ?: return@LaunchedEffect
        if (kind == "vod") {
            delay(350)
            info = api.vodInfo(cur.id)
        }
    }
    val heroInfo = if (kind == "series") shown?.let { Info(it.plot, it.year, "", it.genre, it.backdrop) } else info

    val openItem: (String, Item, List<Item>, Int) -> Unit = { k, item, sib, idx ->
        when (k) {
            "series", "vod" -> detail = k to item
            else -> {
                Zap.items = sib; Zap.index = idx; Zap.api = api; Zap.repo = repo
                play(ctx, api.liveUrl(item.id), item.name, true, item.img, "live", 0L)
            }
        }
    }

    val det = detail
    if (det != null) {
        DetailScreen(repo, api, det.first, det.second, onBack = { detail = null }) { url, title, img, k, start ->
            play(ctx, url, title, false, img, k, start)
        }
    } else {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG_TOP, BG)))) {
            if (tab == 1 || tab == 2) Backdrop(heroInfo?.backdrop ?: shown?.backdrop ?: shown?.img)
            Column(Modifier.fillMaxSize()) {
                TopBar(tab, { tab = it }, repo.status)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha.value }) {
                when {
                    tab == 3 -> SearchScreen(repo, openItem)
                    tab == 4 -> HiddenScreen(repo)
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
                        vis, kind, shown, heroInfo,
                        favs = repo.favItems(kind),
                        resumes = repo.resumes.filter { it.kind == kind },
                        onPlay = {
                            shown?.let { sh ->
                                if (kind == "vod") {
                                    val u = api.vodUrl(sh.id, sh.ext)
                                    play(ctx, u, sh.name, false, sh.img, "vod", repo.resumeFor(u)?.pos ?: 0L)
                                } else {
                                    openItem(kind, sh, emptyList(), 0)
                                }
                            }
                        },
                        onInfo = { shown?.let { detail = kind to it } },
                        onFocus = { focused = it },
                        onResume = { r -> play(ctx, r.url, r.title, false, r.img, r.kind, r.pos) },
                        onOpen = { openItem(kind, it, emptyList(), 0) }
                    )
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
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(600), label = "backdrop") { u ->
            Box(Modifier.fillMaxSize()) {
                if (u != null) {
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop,
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
fun TopBar(tab: Int, onTab: (Int) -> Unit, status: String) {
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
            TAB_NAMES.forEachIndexed { i, n -> Pill(n, tab == i) { onTab(i) } }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (status.isNotBlank()) {
                Text(
                    "↻ $status", fontSize = 11.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)
                )
            }
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
@Composable
fun Browse(
    data: Data, kind: String, item: Item?, info: Info?,
    favs: List<Item>, resumes: List<Resume>,
    onPlay: () -> Unit, onInfo: () -> Unit, onFocus: (Item) -> Unit,
    onResume: (Resume) -> Unit, onOpen: (Item) -> Unit
) {
    val top = remember(data) { data.rows.firstOrNull()?.items?.take(10) ?: emptyList() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth(0.6f).height(230.dp).padding(start = 32.dp, top = 6.dp)) {
            AnimatedContent(
                targetState = item?.id,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
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
                ActionButton(if (kind == "series") "▶ Épisodes" else "▶ Lecture", true, onPlay)
                ActionButton("Plus d'infos", false, onInfo)
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (resumes.isNotEmpty()) {
                item {
                    Text(
                        "Reprendre la lecture", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TXT,
                        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(resumes) { r ->
                            Poster(
                                Item(id = r.url, name = r.title, img = r.img, cat = "", ext = ""),
                                2f / 3f, false, onFocus = {},
                                progress = if (r.dur > 0) r.pos.toFloat() / r.dur else 0f
                            ) { onResume(r) }
                        }
                    }
                }
            }
            if (favs.isNotEmpty()) {
                item {
                    Text(
                        "Ma liste", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TXT,
                        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(favs) { it ->
                            Poster(it, 2f / 3f, false, onFocus) { onOpen(it) }
                        }
                    }
                }
            }
            if (top.isNotEmpty()) {
                item {
                    Text(
                        "Top 10 des nouveautés", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TXT,
                        modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(top) { idx, it ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${idx + 1}", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color(0xFF55557A))
                                Poster(it, 2f / 3f, false, onFocus) { onOpen(it) }
                            }
                        }
                    }
                }
            }
            items(data.rows) { shelf ->
                Text(
                    shelf.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TXT,
                    modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 6.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shelf.items) { it ->
                        Poster(it, 2f / 3f, false, onFocus) { onOpen(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun Poster(item: Item, ratio: Float, fit: Boolean, onFocus: (Item) -> Unit, progress: Float = -1f, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.1f else 1f, tween(180), label = "scale")
    Box(
        Modifier.width(124.dp).scale(sc)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus(item) }
            .border(3.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(3.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.img).crossfade(400).build(),
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
    val inf = info
    val plot = (inf?.plot ?: "").ifBlank { item.plot }
    val backdrop = inf?.backdrop ?: item.backdrop ?: item.img
    val vodUrl = api.vodUrl(item.id, item.ext)
    val resume = if (kind == "vod") repo.resumeFor(vodUrl) else null
    val fav = repo.isFav(kind, item)
    val meta = listOfNotNull(
        item.rating.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let { "★ $it" },
        (inf?.year ?: "").ifBlank { item.year }.takeIf { it.isNotBlank() },
        inf?.duration?.takeIf { it.isNotBlank() },
        (inf?.genre ?: "").ifBlank { item.genre }.takeIf { it.isNotBlank() }
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
                inf?.cast?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Avec : $it", fontSize = 13.sp, color = MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                inf?.director?.takeIf { it.isNotBlank() }?.let {
                    Text("De : $it", fontSize = 13.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (kind == "vod") {
                        if (resume != null) {
                            ActionButton("▶ Reprendre à ${fmtMs(resume.pos)}", true) {
                                onPlay(vodUrl, item.name, item.img, "vod", resume.pos)
                            }
                            ActionButton("Depuis le début", false) { onPlay(vodUrl, item.name, item.img, "vod", 0L) }
                        } else {
                            ActionButton("▶ Lecture", true) { onPlay(vodUrl, item.name, item.img, "vod", 0L) }
                        }
                    } else {
                        eps?.firstOrNull()?.let { e1 ->
                            ActionButton("▶ Lecture S${e1.season} E${e1.num.ifBlank { "1" }}", true) {
                                onPlay(
                                    api.epUrl(e1.id, e1.ext),
                                    "${item.name} · S${e1.season} E${e1.num.ifBlank { "1" }}",
                                    e1.img ?: item.img, "series",
                                    repo.resumeFor(api.epUrl(e1.id, e1.ext))?.pos ?: 0L
                                )
                            }
                        }
                    }
                    ActionButton(if (fav) "✓ Dans ma liste" else "+ Ma liste", false) { repo.toggleFav(kind, item) }
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { sn -> Pill("Saison $sn", sn == sSel) { season = sn } }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list.filter { it.season == sSel }) { ep -> EpisodeRow(repo, api, item, ep, onPlay) }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeRow(
    repo: Repo, api: Xtream, series: Item, ep: Ep,
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
            .clickable { onPlay(url, title, ep.img ?: series.img, "series", r?.pos ?: 0L) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C24))) {
            AsyncImage(
                model = ep.img ?: series.img, contentDescription = null,
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
            Text("$label.  ${ep.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ep.duration.isNotBlank()) Text(ep.duration, fontSize = 12.sp, color = MUTED)
            if (ep.plot.isNotBlank()) Text(ep.plot, fontSize = 12.sp, color = MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- TV en direct
@Composable
fun LiveView(repo: Repo, data: Data, onHide: (Item) -> Unit, onHideCat: (Cat) -> Unit, onPlay: (Item, List<Item>, Int) -> Unit) {
    val ev = repo.epgVersion
    var catId by remember { mutableStateOf(data.cats.firstOrNull()?.id) }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    val favList = repo.favItems("live")
    val cats = if (favList.isNotEmpty()) listOf(Cat("__fav", "★ Favoris")) + data.cats else data.cats
    val list = if (catId == "__fav") favList else (data.byCat[catId] ?: emptyList())
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LazyColumn(Modifier.width(200.dp).fillMaxHeight()) {
            items(cats) { c -> FocusRow(c.name, c.id == catId, onFocus = { catId = c.id }) { catId = c.id } }
        }
        Spacer(Modifier.width(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
            itemsIndexed(list) { idx, ch ->
                ChannelRow(repo, ch, idx + 1, ev, onFocus = { focusedCh = ch }) { onPlay(ch, list, idx) }
            }
        }
        Spacer(Modifier.width(12.dp))
        val cur = focusedCh ?: list.firstOrNull()
        val curCat = data.cats.firstOrNull { it.id == catId }
        ChannelPanel(repo, cur, curCat?.name, ev, Modifier.width(300.dp), onHideCat = {
            curCat?.let { c ->
                catId = data.cats.firstOrNull { it.id != c.id }?.id
                focusedCh = null
                onHideCat(c)
            }
        }) {
            cur?.let { onHide(it) }
            focusedCh = null
        }
    }
}

@Composable
fun ChannelRow(repo: Repo, ch: Item, num: Int, ev: Int, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var fb by remember(ch.id) { mutableStateOf<List<Prog>>(emptyList()) }
    val cached = repo.progsFor(ch)
    val progs = if (cached.isNotEmpty()) cached else fb
    LaunchedEffect(ch.id, ev) {
        if (repo.progsFor(ch).isEmpty()) {
            delay(250)
            fb = repo.api.epg(ch.id)
        }
    }
    val nowS = System.currentTimeMillis() / 1000
    val p = progs.firstOrNull()
    val frac = if (p != null && p.stop > p.start) ((nowS - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .background(if (f) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$num", color = MUTED, fontSize = 13.sp, modifier = Modifier.width(36.dp))
        AsyncImage(model = ch.img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = TXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p?.title ?: "Programme indisponible", color = MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp))
            }
        }
    }
}

@Composable
fun ChannelPanel(repo: Repo, ch: Item?, catName: String?, ev: Int, modifier: Modifier, onHideCat: () -> Unit, onHide: () -> Unit) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var fb by remember(ch?.id) { mutableStateOf<List<Prog>>(emptyList()) }
    LaunchedEffect(ch?.id, ev) {
        if (ch != null && repo.progsFor(ch).isEmpty()) {
            delay(300)
            fb = repo.api.epg(ch.id)
        }
    }
    val cached = if (ch != null) repo.progsFor(ch) else emptyList()
    val progs = if (cached.isNotEmpty()) cached else fb
    Column(
        modifier.fillMaxHeight().padding(vertical = 8.dp)
            .background(Color(0xFF14141A), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (ch == null) {
            Text("Aucune chaîne", color = MUTED)
        } else {
            AsyncImage(model = ch.img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp))
            Text(ch.name, color = TXT, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            progs.take(3).forEachIndexed { i, p ->
                Text(if (i == 0) "Maintenant" else "Ensuite", color = ACCENT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${fmt.format(Date(p.start * 1000))} – ${fmt.format(Date(p.stop * 1000))}   ${p.title}",
                    color = TXT, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
            if (progs.isEmpty()) Text("Programme indisponible", color = MUTED, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            ActionButton(if (repo.isFav("live", ch)) "★ Retirer des favoris" else "☆ Ajouter aux favoris", false) {
                repo.toggleFav("live", ch)
            }
            ActionButton("Masquer cette chaîne", false, onHide)
            if (catName != null) ActionButton("Masquer la catégorie « $catName »", false, onHideCat)
            Text("OK : regarder\nPendant la lecture : haut / bas pour changer de chaîne", color = MUTED, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------- Recherche
@Composable
fun SearchScreen(repo: Repo, open: (String, Item, List<Item>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf("vod") }
    var res by remember {
        mutableStateOf(Triple<List<Item>, List<Item>, List<Item>>(emptyList(), emptyList(), emptyList()))
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(q) {
        delay(200)
        res = repo.search(q)
    }
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
    val cats = repo.data[kind]?.cats ?: emptyList()
    val hiddenList = repo.hiddenItems.entries.filter { it.key.startsWith("$kind:") }.map { it.key to it.value }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("live" to "TV", "vod" to "Films", "series" to "Séries").forEach { (k, l) ->
                Pill(l, kind == k) { kind = k }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                kw, { kw = it }, singleLine = true,
                placeholder = { Text("Mot du nom : DAZN, TURK, TR…") },
                modifier = Modifier.width(300.dp)
            )
            ActionButton("Masquer ces catégories", true) { msg = "${repo.hideCatsMatching(kind, kw)} catégorie(s) masquée(s)" }
            ActionButton("Réafficher", false) { msg = "${repo.showCatsMatching(kind, kw)} catégorie(s) réaffichée(s)" }
        }
        if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = ACCENT)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("✓ Tout cocher", false) {
                repo.showAllCats(kind); msg = "Toutes les catégories sont affichées"
            }
            ActionButton("✕ Tout décocher", false) {
                repo.hideAllCats(kind); msg = "Toutes les catégories sont masquées : coche celles que tu veux garder"
            }
            Text("Astuce : « Tout décocher », puis coche seulement tes catégories.", fontSize = 12.sp, color = MUTED)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Catégories (OK pour masquer ou afficher)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TXT)
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
                        "Aucun pour l'instant. Pour masquer : « Plus d'infos » sur un film ou une série, ou le bouton « Masquer cette chaîne » dans l'onglet TV.",
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
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp),
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
