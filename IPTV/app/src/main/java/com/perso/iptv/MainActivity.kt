package com.perso.iptv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
private val TXT = Color(0xFFF2F2F5)
private val MUTED = Color(0xFFC9C9D2)
private val ACCENT = Color(0xFFE5484D)
private val PILL = Color(0xFF34343F)

private val TAB_NAMES = listOf("TV", "Films", "Séries", "Recherche", "Masqués", "Réglages")

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
private fun fmtDate(ts: Long): String =
    if (ts == 0L) "jamais" else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))

private fun play(ctx: Context, url: String, title: String, live: Boolean) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("title", title).putExtra("live", live)
    )
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
                        App()
                    }
                }
            }
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
        Text("Connexion IPTV", style = MaterialTheme.typography.headlineLarge)
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
    var episodes by remember { mutableStateOf<List<Ep>?>(null) }
    var seriesName by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<Item?>(null) }
    var info by remember { mutableStateOf<Info?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    BackHandler(enabled = episodes != null) { episodes = null }

    LaunchedEffect(repo) {
        try {
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
            "series" -> scope.launch {
                seriesName = item.name
                episodes = try { api.episodes(item.id) } catch (e: Exception) { emptyList() }
            }
            "vod" -> play(ctx, api.vodUrl(item.id, item.ext), item.name, false)
            else -> {
                Zap.items = sib; Zap.index = idx; Zap.api = api; Zap.repo = repo
                play(ctx, api.liveUrl(item.id), item.name, true)
            }
        }
    }

    val eps = episodes
    if (eps != null) {
        Column(Modifier.fillMaxSize().background(BG).padding(32.dp)) {
            Text(seriesName, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TXT)
            Spacer(Modifier.height(12.dp))
            if (eps.isEmpty()) Text("Aucun épisode trouvé.", color = MUTED)
            LazyColumn {
                items(eps) { ep ->
                    FocusRow("S${ep.season}  ·  ${ep.title}") { play(ctx, api.epUrl(ep.id, ep.ext), ep.title, false) }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(BG)) {
            if (tab == 1 || tab == 2) Backdrop(heroInfo?.backdrop ?: shown?.backdrop ?: shown?.img)
            Column(Modifier.fillMaxSize()) {
                TopBar(tab, { tab = it }, repo.status)
                when {
                    tab == 3 -> SearchScreen(repo, openItem)
                    tab == 4 -> HiddenScreen(repo)
                    tab == 5 -> SettingsScreen(repo, scope, logout)
                    vis == null -> LoadingView(repo, cacheLoaded) { scope.launch { repo.refreshContent(true) } }
                    tab == 0 -> LiveView(
                        repo, vis,
                        onHide = { repo.hideItem("live", it) },
                        onHideCat = { repo.toggleCat("live", it) }
                    ) { ch, list, idx ->
                        openItem("live", ch, list, idx)
                    }
                    else -> Browse(
                        vis, kind, shown, heroInfo,
                        onPlay = { shown?.let { openItem(kind, it, emptyList(), 0) } },
                        onInfo = { showInfo = true },
                        onFocus = { focused = it },
                        onOpen = { openItem(kind, it, emptyList(), 0) }
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Fermer") } },
            dismissButton = {
                TextButton(onClick = { shown?.let { repo.hideItem(kind, it) }; showInfo = false; focused = null }) {
                    Text(if (kind == "series") "Masquer cette série" else "Masquer ce film")
                }
            },
            title = { Text(shown?.name ?: "") },
            text = {
                val meta = listOf(shown?.lang, heroInfo?.year, heroInfo?.duration, heroInfo?.genre)
                    .filter { !it.isNullOrBlank() }.joinToString("  •  ")
                Text(meta + (if (meta.isNotEmpty()) "\n\n" else "") + (heroInfo?.plot?.ifBlank { "Pas de synopsis." } ?: "Chargement…"))
            }
        )
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
                CircularProgressIndicator()
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
        if (url != null) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).fillMaxHeight(0.62f)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(colorStops = arrayOf(0.30f to BG, 0.65f to BG.copy(alpha = 0f)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colorStops = arrayOf(0.35f to BG.copy(alpha = 0f), 0.62f to BG))
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
        Text("Mon IPTV", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TXT, modifier = Modifier.weight(1f))
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
    onPlay: () -> Unit, onInfo: () -> Unit, onFocus: (Item) -> Unit, onOpen: (Item) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth(0.55f).height(210.dp).padding(start = 32.dp, top = 6.dp)) {
            Text(
                item?.name ?: "", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                item?.lang?.takeIf { it.isNotBlank() },
                item?.rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let { "★ $it" },
                info?.year?.takeIf { it.isNotBlank() },
                info?.duration?.takeIf { it.isNotBlank() },
                info?.genre?.takeIf { it.isNotBlank() }
            )
            Spacer(Modifier.height(6.dp))
            Text(meta.joinToString("  •  "), fontSize = 13.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(info?.plot.orEmpty(), fontSize = 13.sp, lineHeight = 18.sp, color = MUTED, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(if (kind == "series") "▶ Épisodes" else "▶ Lecture", true, onPlay)
                ActionButton("Plus d'infos", false, onInfo)
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(data.rows) { shelf ->
                Text(
                    shelf.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT,
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
fun Poster(item: Item, ratio: Float, fit: Boolean, onFocus: (Item) -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.06f else 1f, label = "scale")
    Box(
        Modifier.width(118.dp).scale(sc)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus(item) }
            .border(3.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(3.dp)
    ) {
        AsyncImage(
            model = item.img, contentDescription = item.name,
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

// ---------------------------------------------------------------- TV en direct
@Composable
fun LiveView(repo: Repo, data: Data, onHide: (Item) -> Unit, onHideCat: (Cat) -> Unit, onPlay: (Item, List<Item>, Int) -> Unit) {
    val ev = repo.epgVersion
    var catId by remember { mutableStateOf(data.cats.firstOrNull()?.id) }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    val list = remember(catId, data) { data.byCat[catId] ?: emptyList() }
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LazyColumn(Modifier.width(200.dp).fillMaxHeight()) {
            items(data.cats) { c -> FocusRow(c.name, c.id == catId, onFocus = { catId = c.id }) { catId = c.id } }
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
    var res by remember {
        mutableStateOf(Triple<List<Item>, List<Item>, List<Item>>(emptyList(), emptyList(), emptyList()))
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(q) {
        delay(200)
        res = repo.search(q)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, singleLine = true,
            placeholder = { Text("Rechercher une chaîne, un film, une série…") },
            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(0.6f).focusRequester(fr)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item { ResultRow("TV", res.first, true) { i, idx -> open("live", i, res.first, idx) } }
            item { ResultRow("Films", res.second, false) { i, idx -> open("vod", i, res.second, idx) } }
            item { ResultRow("Séries", res.third, false) { i, idx -> open("series", i, res.third, idx) } }
        }
    }
}

@Composable
fun ResultRow(title: String, list: List<Item>, live: Boolean, onOpen: (Item, Int) -> Unit) {
    if (list.isEmpty()) return
    Text(
        "$title (${list.size})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT,
        modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 6.dp)
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(list) { idx, it ->
            Poster(it, if (live) 1f else 2f / 3f, live, {}) { onOpen(it, idx) }
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
            opts.forEach { (d, l) -> Pill(l, repo.freqContent == d) { repo.setFreqContent(d) } }
        }
        ActionButton("Mettre à jour maintenant", false) { scope.launch { repo.refreshContent(true) } }
        Spacer(Modifier.height(8.dp))
        Text("Guide TV (programmes)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TXT)
        Text("Dernière mise à jour : ${fmtDate(repo.lastEpgTs)}", fontSize = 13.sp, color = MUTED)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (d, l) -> Pill(l, repo.freqEpg == d) { repo.setFreqEpg(d) } }
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
