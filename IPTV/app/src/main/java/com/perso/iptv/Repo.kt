package com.perso.iptv

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Shelf(val title: String, val items: List<Item>)
data class Data(
    val rows: List<Shelf>,
    val all: List<Item>,
    val cats: List<Cat>,
    val byCat: Map<String, List<Item>>
)

fun buildData(cats: List<Cat>, all: List<Item>): Data {
    val by = all.groupBy { it.cat }
    val rows = mutableListOf<Shelf>()
    val recent = all.sortedByDescending { it.added }.take(30)
    if (recent.isNotEmpty() && recent.first().added > 0) rows += Shelf("Récemment ajoutés", recent)
    cats.forEach { c -> by[c.id]?.let { rows += Shelf(c.name, it.take(40)) } }
    return Data(rows, all, cats, by)
}

/** Détection de la langue (FR, EN, VOSTFR, MULTI…) dans le titre ou la catégorie. */
object Lang {
    private val known = mapOf(
        "FR" to "FR", "FRENCH" to "FR", "VF" to "FR", "VFF" to "FR", "VFQ" to "FR", "TRUEFRENCH" to "FR",
        "VOSTFR" to "VOSTFR", "STFR" to "VOSTFR",
        "EN" to "EN", "ENG" to "EN", "ENGLISH" to "EN", "VO" to "VO", "VOST" to "VO",
        "MULTI" to "MULTI",
        "ES" to "ES", "SPA" to "ES", "DE" to "DE", "GER" to "DE", "IT" to "IT", "ITA" to "IT",
        "PT" to "PT", "AR" to "AR", "ARAB" to "AR", "TR" to "TR", "NL" to "NL", "PL" to "PL", "RU" to "RU"
    )
    private val rBr = Regex("""^\s*[\[(|]\s*([A-Za-z]{2,10})\s*[\])|]\s*[-:]?\s*""")
    private val rDash = Regex("""^\s*([A-Za-z]{2,10})\s*[-:|]\s+""")
    private val rSuf = Regex("""\s*[\[(]\s*([A-Za-z]{2,10})\s*[\])]\s*$""")
    private val rSplit = Regex("[^A-Za-z]+")

    fun detect(name: String, catName: String): Pair<String, String> {
        for (r in listOf(rBr, rDash)) {
            val m = r.find(name)
            if (m != null) {
                val l = known[m.groupValues[1].uppercase()]
                if (l != null) {
                    val rest = name.substring(m.range.last + 1).trim()
                    return l to (if (rest.isNotBlank()) rest else name)
                }
            }
        }
        val ms = rSuf.find(name)
        if (ms != null) {
            val l = known[ms.groupValues[1].uppercase()]
            if (l != null) {
                val rest = name.substring(0, ms.range.first).trim()
                return l to (if (rest.isNotBlank()) rest else name)
            }
        }
        for (t in catName.split(rSplit)) {
            val l = known[t.uppercase()]
            if (l != null) return l to name
        }
        return "" to name
    }
}

class Repo(private val ctx: Context, val api: Xtream) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    val data = mutableStateMapOf<String, Data>()
    var status by mutableStateOf("")
    var epgVersion by mutableStateOf(0)

    var freqContent by mutableStateOf(prefs.getInt("freq_content", 1))
        private set
    var freqEpg by mutableStateOf(prefs.getInt("freq_epg", 1))
        private set
    var lastContentTs by mutableStateOf(prefs.getLong("sync_ts", 0L))
        private set
    var lastEpgTs by mutableStateOf(prefs.getLong("epg_ts", 0L))
        private set

    private var epg: Map<String, List<Prog>> = emptyMap()
    private var busy = false
    private var busyEpg = false

    fun setFreqContent(d: Int) { freqContent = d; prefs.edit().putInt("freq_content", d).apply() }
    fun setFreqEpg(d: Int) { freqEpg = d; prefs.edit().putInt("freq_epg", d).apply() }

    companion object {
        fun wipe(ctx: Context) {
            ctx.filesDir.listFiles()?.forEach { if (it.name.endsWith(".tsv")) it.delete() }
        }
    }

    private fun f(name: String) = File(ctx.filesDir, name)
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    // ---------------- cache disque ----------------
    private fun save(kind: String, cats: List<Cat>, items: List<Item>) {
        val tc = f("cats_$kind.tmp")
        tc.bufferedWriter().use { w ->
            cats.forEach { w.write(clean(it.id) + "\t" + clean(it.name)); w.newLine() }
        }
        tc.renameTo(f("cats_$kind.tsv"))
        val ti = f("items_$kind.tmp")
        ti.bufferedWriter().use { w ->
            items.forEach { i ->
                w.write(
                    listOf(
                        i.id, i.name, i.img ?: "", i.cat, i.ext, i.added.toString(), i.rating, i.plot,
                        i.year, i.genre, i.backdrop ?: "", i.epgId, i.lang
                    ).joinToString("\t") { clean(it) }
                )
                w.newLine()
            }
        }
        ti.renameTo(f("items_$kind.tsv"))
    }

    private fun readCache(kind: String): Data? {
        val fi = f("items_$kind.tsv")
        val fc = f("cats_$kind.tsv")
        if (!fi.exists() || !fc.exists()) return null
        val cats = fc.readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size >= 2) Cat(p[0], p[1]) else null
        }
        val items = ArrayList<Item>()
        fi.bufferedReader().useLines { seq ->
            seq.forEach { l ->
                val p = l.split('\t')
                if (p.size >= 13) {
                    items.add(
                        Item(
                            id = p[0], name = p[1], img = p[2].ifBlank { null }, cat = p[3], ext = p[4],
                            added = p[5].toLongOrNull() ?: 0L, rating = p[6], plot = p[7], year = p[8],
                            genre = p[9], backdrop = p[10].ifBlank { null }, epgId = p[11], lang = p[12]
                        )
                    )
                }
            }
        }
        if (items.isEmpty()) return null
        return buildData(cats, items)
    }

    suspend fun loadCache() {
        val loaded = withContext(Dispatchers.IO) {
            val m = HashMap<String, Data>()
            for (k in listOf("vod", "series", "live")) {
                try { readCache(k)?.let { m[k] = it } } catch (e: Exception) { /* cache illisible : on resynchronise */ }
            }
            m
        }
        data.putAll(loaded)
        loadHidden()
        val e = withContext(Dispatchers.IO) { try { readEpg() } catch (e: Exception) { emptyMap() } }
        epg = e
        epgVersion++
    }

    // ---------------- synchronisation du contenu ----------------
    private suspend fun sync(kind: String) {
        status = "Mise à jour : " + when (kind) { "live" -> "chaînes TV"; "vod" -> "films"; else -> "séries" } + "…"
        val d = withContext(Dispatchers.IO) {
            val cats = api.cats(kind)
            val raw = api.items(kind)
            val names = cats.associate { it.id to it.name }
            val items = if (kind == "live") raw else raw.map {
                val (l, n) = Lang.detect(it.name, names[it.cat] ?: "")
                it.copy(name = n, lang = l)
            }
            save(kind, cats, items)
            buildData(cats, items)
        }
        data[kind] = d
        prefs.edit().putLong("sync_$kind", System.currentTimeMillis()).apply()
    }

    suspend fun refreshContent(force: Boolean) {
        if (busy) return
        busy = true
        var err: String? = null
        try {
            for (k in listOf("vod", "series", "live")) {
                val last = prefs.getLong("sync_$k", 0L)
                val due = data[k] == null || force || System.currentTimeMillis() - last > freqContent * 86_400_000L
                if (due) {
                    try {
                        sync(k)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        err = "Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}"
                    }
                }
            }
            if (err == null) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("sync_ts", now).apply()
                lastContentTs = now
            }
        } finally {
            busy = false
            status = err ?: ""
        }
    }

    // ---------------- guide TV (EPG) ----------------
    private val fz = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fn = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun parseX(s: String): Long = try {
        val t = s.trim()
        ((if (t.length > 14) fz.parse(t) else fn.parse(t))?.time ?: 0L) / 1000
    } catch (e: Exception) {
        0L
    }

    private fun downloadEpg(wanted: Set<String>): Map<String, List<Prog>> {
        val res = HashMap<String, ArrayList<Prog>>()
        val now = System.currentTimeMillis() / 1000
        val horizon = now + 48 * 3600
        api.xmltvStream().use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel") ?: ""
                    val st = parseX(p.getAttributeValue(null, "start") ?: "")
                    val sp = parseX(p.getAttributeValue(null, "stop") ?: "")
                    var title = ""
                    var done = false
                    while (!done) {
                        ev = p.next()
                        if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "programme")) {
                            done = true
                        } else if (ev == XmlPullParser.START_TAG && p.name == "title" && title.isEmpty()) {
                            title = p.nextText()
                        }
                    }
                    if (ch in wanted && sp > now && st < horizon) {
                        res.getOrPut(ch) { ArrayList() }.add(Prog(title, st, sp))
                    }
                    if (ev == XmlPullParser.END_DOCUMENT) break
                }
                ev = p.next()
            }
        }
        res.values.forEach { l -> l.sortBy { it.start } }
        saveEpg(res)
        return res
    }

    private fun saveEpg(m: Map<String, List<Prog>>) {
        val t = f("epg.tmp")
        t.bufferedWriter().use { w ->
            m.forEach { (ch, l) ->
                l.forEach { w.write(clean(ch) + "\t" + it.start + "\t" + it.stop + "\t" + clean(it.title)); w.newLine() }
            }
        }
        t.renameTo(f("epg.tsv"))
    }

    private fun readEpg(): Map<String, List<Prog>> {
        val fe = f("epg.tsv")
        if (!fe.exists()) return emptyMap()
        val m = HashMap<String, ArrayList<Prog>>()
        fe.bufferedReader().useLines { seq ->
            seq.forEach { l ->
                val p = l.split('\t')
                if (p.size >= 4) m.getOrPut(p[0]) { ArrayList() }.add(Prog(p[3], p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L))
            }
        }
        m.values.forEach { l -> l.sortBy { it.start } }
        return m
    }

    suspend fun syncEpg() {
        if (busyEpg) return
        val live = data["live"] ?: return
        busyEpg = true
        status = "Mise à jour du guide TV…"
        try {
            val wanted = live.all.mapNotNullTo(HashSet<String>()) { it.epgId.ifBlank { null } }
            val m = withContext(Dispatchers.IO) { downloadEpg(wanted) }
            epg = m
            epgVersion++
            val now = System.currentTimeMillis()
            prefs.edit().putLong("epg_ts", now).apply()
            lastEpgTs = now
            status = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            status = "Guide TV : mise à jour impossible"
            delay(4000)
            status = ""
        } finally {
            busyEpg = false
        }
    }

    suspend fun syncEpgIfDue() {
        val last = prefs.getLong("epg_ts", 0L)
        val age = System.currentTimeMillis() - last
        val due = age > freqEpg * 86_400_000L || (epg.isEmpty() && age > 6 * 3_600_000L)
        if (due) syncEpg()
    }

    /** Programmes en cours et à venir pour une chaîne (depuis le cache). */
    fun progsFor(item: Item): List<Prog> {
        val l = epg[item.epgId] ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val idx = l.indexOfFirst { it.stop > now }
        return if (idx < 0) emptyList() else l.subList(idx, minOf(idx + 3, l.size))
    }

    // ---------------- éléments masqués ----------------
    val hiddenItems = mutableStateMapOf<String, String>()
    val hiddenCats = mutableStateMapOf<String, String>()
    var hiddenVersion by mutableStateOf(0)
        private set
    private val setI = HashSet<String>()
    private val setC = HashSet<String>()

    private fun persistHidden() {
        val t = f("hidden.tmp")
        t.bufferedWriter().use { w ->
            hiddenItems.forEach { (k, n) -> w.write("I\t" + clean(k) + "\t" + clean(n)); w.newLine() }
            hiddenCats.forEach { (k, n) -> w.write("C\t" + clean(k) + "\t" + clean(n)); w.newLine() }
        }
        t.renameTo(f("hidden.tsv"))
    }

    private fun loadHidden() {
        val fh = f("hidden.tsv")
        if (!fh.exists()) return
        try {
            fh.readLines().forEach { l ->
                val p = l.split('\t')
                if (p.size >= 3) {
                    if (p[0] == "I") { setI.add(p[1]); hiddenItems[p[1]] = p[2] }
                    else if (p[0] == "C") { setC.add(p[1]); hiddenCats[p[1]] = p[2] }
                }
            }
        } catch (e: Exception) { /* fichier illisible : on ignore */ }
        hiddenVersion++
    }

    fun hideItem(kind: String, item: Item) {
        val k = "$kind:${item.id}"
        setI.add(k); hiddenItems[k] = item.name
        persistHidden(); hiddenVersion++
    }

    fun showItem(key: String) {
        setI.remove(key); hiddenItems.remove(key)
        persistHidden(); hiddenVersion++
    }

    fun toggleCat(kind: String, c: Cat) {
        val k = "$kind:${c.id}"
        if (setC.contains(k)) { setC.remove(k); hiddenCats.remove(k) } else { setC.add(k); hiddenCats[k] = c.name }
        persistHidden(); hiddenVersion++
    }

    /** Masque toutes les catégories dont le nom contient le mot saisi. Retourne le nombre touché. */
    fun hideCatsMatching(kind: String, kw: String): Int {
        val w = kw.trim()
        if (w.isEmpty()) return 0
        var n = 0
        data[kind]?.cats?.forEach { c ->
            if (c.name.contains(w, ignoreCase = true)) {
                val k = "$kind:${c.id}"
                if (!setC.contains(k)) { setC.add(k); hiddenCats[k] = c.name; n++ }
            }
        }
        if (n > 0) { persistHidden(); hiddenVersion++ }
        return n
    }

    fun showCatsMatching(kind: String, kw: String): Int {
        val w = kw.trim()
        if (w.isEmpty()) return 0
        val keys = hiddenCats.entries.filter { it.key.startsWith("$kind:") && it.value.contains(w, ignoreCase = true) }.map { it.key }
        keys.forEach { setC.remove(it); hiddenCats.remove(it) }
        if (keys.isNotEmpty()) { persistHidden(); hiddenVersion++ }
        return keys.size
    }

    fun isCatHidden(kind: String, id: String): Boolean = hiddenCats.containsKey("$kind:$id")

    private fun isHidden(kind: String, i: Item): Boolean =
        setI.contains("$kind:${i.id}") || setC.contains("$kind:${i.cat}")

    /** Données sans les catégories ni les éléments masqués. */
    fun visible(kind: String, d: Data): Data {
        if (setI.isEmpty() && setC.isEmpty()) return d
        val all = d.all.filter { !isHidden(kind, it) }
        val cats = d.cats.filter { !setC.contains("$kind:${it.id}") }
        return buildData(cats, all)
    }

    // ---------------- recherche ----------------
    suspend fun search(q: String): Triple<List<Item>, List<Item>, List<Item>> = withContext(Dispatchers.Default) {
        val t = q.trim()
        if (t.length < 2) {
            Triple(emptyList<Item>(), emptyList<Item>(), emptyList<Item>())
        } else {
            fun find(k: String): List<Item> =
                data[k]?.all?.filter { it.name.contains(t, ignoreCase = true) && !isHidden(k, it) }?.take(40) ?: emptyList()
            Triple(find("live"), find("vod"), find("series"))
        }
    }
}
