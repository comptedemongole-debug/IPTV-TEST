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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = Color(0xFF0B0B0F)
private val TXT = Color(0xFFF2F2F5)
private val MUTED = Color(0xFFC9C9D2)
private val ACCENT = Color(0xFFE5484D)
private val PILL = Color(0xFF34343F)

private val TAB_NAMES = listOf("TV", "Guide", "Films", "Séries")
private fun kindOf(tab: Int) = when (tab) { 0, 1 -> "live"; 2 -> "vod"; else -> "series" }

data class Shelf(val title: String, val items: List<Item>)
data class Data(val rows: List<Shelf>, val all: List<Item>, val cats: List<Cat>)

private fun buildData(cats: List<Cat>, all: List<Item>): Data {
    val by = all.groupBy { it.cat }
    val rows = mutableListOf<Shelf>()
    val recent = all.sortedByDescending { it.added }.take(30)
    if (recent.isNotEmpty() && recent.first().added > 0) rows += Shelf("Récemment ajoutés", recent)
    cats.forEach { c -> by[c.id]?.let { rows += Shelf(c.name, it.take(40)) } }
    return Data(rows, all, cats)
}

private fun play(ctx: Context, url: String, title: String) {
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title))
}

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT)) {
                Surface(Modifier.fillMaxSize(), color = BG) { App() }
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
        Home(a) { prefs.edit().clear().apply(); api = null }
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
    var tab by remember { mutableStateOf(2) }
    val cache = remember { mutableStateMapOf<String, Data>() }
    var error by remember { mutableStateOf<String?>(null) }
    var episodes by remember { mutableStateOf<List<Ep>?>(null) }
    var seriesName by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<Item?>(null) }
    var info by remember { mutableStateOf<Info?>(null) }
    var now by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    val kind = kindOf(tab)
    val data = cache[kind]

    BackHandler(enabled = episodes != null) { episodes = null }
    BackHandler(enabled = searching && episodes == null) { searching = false; query = "" }

    LaunchedEffect(kind) {
        focused = null; info = null; now = null
        if (cache[kind] == null) {
            error = null
            try {
                val d = withContext(Dispatchers.IO) { buildData(api.cats(kind), api.items(kind)) }
                cache[kind] = d
            } catch (e: Exception) {
                error = "Erreur : ${e.message}"
            }
        }
    }

    val shown = focused ?: data?.rows?.firstOrNull()?.items?.firstOrNull()
    LaunchedEffect(shown?.id, kind) {
        info = null; now = null
        val cur = shown ?: return@LaunchedEffect
        delay(350)
        if (kind == "vod") info = api.vodInfo(cur.id)
        else if (kind == "live") now = api.epg(cur.id).firstOrNull()?.title
    }
    val heroInfo = if (kind == "series") shown?.let { Info(it.plot, it.year, "", it.genre, it.backdrop) } else info

    val open: (Item) -> Unit = { item ->
        when (kind) {
            "series" -> scope.launch {
                seriesName = item.name
                episodes = try { api.episodes(item.id) } catch (e: Exception) { emptyList() }
            }
            "vod" -> play(ctx, api.vodUrl(item.id, item.ext), item.name)
            else -> play(ctx, api.liveUrl(item.id), item.name)
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
                    FocusRow("S${ep.season}  ·  ${ep.title}") { play(ctx, api.epUrl(ep.id, ep.ext), ep.title) }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(BG)) {
            if (!searching && tab != 1 && kind != "live") {
                Backdrop(heroInfo?.backdrop ?: shown?.backdrop ?: shown?.img)
            }
            Column(Modifier.fillMaxSize()) {
                TopBar(tab, { tab = it; searching = false; query = "" }, { searching = true }, { showLogout = true })
                when {
                    error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(error ?: "", color = TXT) }
                    data == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    searching -> SearchView(data.all, kind == "live", query, { query = it }, open)
                    tab == 1 -> GuideView(api, data) { open(it) }
                    else -> Browse(
                        data, kind, shown, heroInfo, now,
                        onPlay = { shown?.let { open(it) } },
                        onInfo = { showInfo = true },
                        onFocus = { focused = it },
                        onOpen = open
                    )
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Fermer") } },
            title = { Text(shown?.name ?: "") },
            text = {
                val meta = listOf(heroInfo?.year, heroInfo?.duration, heroInfo?.genre)
                    .filter { !it.isNullOrBlank() }.joinToString("  •  ")
                Text(meta + (if (meta.isNotEmpty()) "\n\n" else "") + (heroInfo?.plot?.ifBlank { "Pas de synopsis." } ?: "Chargement…"))
            }
        )
    }
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            confirmButton = { TextButton(onClick = { showLogout = false; logout() }) { Text("Se déconnecter") } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("Annuler") } },
            title = { Text("Changer de compte ?") }
        )
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
fun TopBar(tab: Int, onTab: (Int) -> Unit, onSearch: () -> Unit, onAccount: () -> Unit) {
    var time by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { time = clock(); delay(20_000) } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp),
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
            Pill("Rechercher", false, onSearch)
            Spacer(Modifier.width(4.dp))
            Pill("Compte", false, onAccount)
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
            .padding(horizontal = 20.dp, vertical = 8.dp)
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

@Composable
fun Browse(
    data: Data, kind: String, item: Item?, info: Info?, now: String?,
    onPlay: () -> Unit, onInfo: () -> Unit, onFocus: (Item) -> Unit, onOpen: (Item) -> Unit
) {
    val live = kind == "live"
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth(0.55f).height(210.dp).padding(start = 32.dp, top = 6.dp)) {
            Text(
                item?.name ?: "", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                if (live) "En direct" else null,
                item?.rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let { "★ $it" },
                info?.year?.takeIf { it.isNotBlank() },
                info?.duration?.takeIf { it.isNotBlank() },
                info?.genre?.takeIf { it.isNotBlank() }
            )
            Spacer(Modifier.height(6.dp))
            Text(meta.joinToString("  •  "), fontSize = 13.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val syn = if (live) (now?.let { "Maintenant : $it" } ?: "") else info?.plot.orEmpty()
            Text(syn, fontSize = 13.sp, lineHeight = 18.sp, color = MUTED, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("▶ Lecture", true, onPlay)
                if (!live) ActionButton("Plus d'infos", false, onInfo)
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
                    items(shelf.items, key = { it.id }) { it ->
                        Poster(it, if (live) 1f else 2f / 3f, live, onFocus) { onOpen(it) }
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
    }
}

@Composable
fun SearchView(all: List<Item>, live: Boolean, query: String, onQuery: (String) -> Unit, onOpen: (Item) -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    val res = remember(query, all) {
        if (query.length < 2) emptyList() else all.filter { it.name.contains(query, ignoreCase = true) }.take(150)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        OutlinedTextField(
            query, onQuery, singleLine = true, placeholder = { Text("Rechercher (2 lettres minimum)") },
            modifier = Modifier.fillMaxWidth(0.5f).focusRequester(fr)
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(res, key = { it.id }) { it ->
                Poster(it, if (live) 1f else 2f / 3f, live, {}) { onOpen(it) }
            }
        }
    }
}

@Composable
fun GuideView(api: Xtream, data: Data, onPlay: (Item) -> Unit) {
    var catId by remember { mutableStateOf(data.cats.firstOrNull()?.id) }
    val list = remember(catId) { data.all.filter { it.cat == catId }.take(300) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(data.cats) { c -> Pill(c.name, c.id == catId) { catId = c.id } }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
            items(list, key = { it.id }) { ch -> GuideRow(api, ch) { onPlay(ch) } }
        }
    }
}

@Composable
fun GuideRow(api: Xtream, ch: Item, onClick: () -> Unit) {
    var progs by remember(ch.id) { mutableStateOf<List<Prog>>(emptyList()) }
    LaunchedEffect(ch.id) { progs = api.epg(ch.id) }
    var f by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .background(if (f) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = ch.img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Text(ch.name, color = TXT, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
        Column(Modifier.weight(1f)) {
            val p = progs.firstOrNull()
            Text(
                if (p != null) "${fmt.format(Date(p.start * 1000))}  ${p.title}" else "Programme indisponible",
                color = TXT, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            progs.getOrNull(1)?.let {
                Text("${fmt.format(Date(it.start * 1000))}  ${it.title}", color = MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FocusRow(text: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TXT, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { f = it.isFocused }
            .background(if (f) Color(0x44FFFFFF) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    )
}
