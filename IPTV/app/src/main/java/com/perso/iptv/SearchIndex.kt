package com.perso.iptv

import java.util.BitSet
import kotlin.math.abs
import kotlin.math.min

/**
 * Index de recherche local, construit une seule fois (en arrière-plan) puis interrogé à chaque lettre.
 *
 * - Les mots de tous les titres sont mis dans un vocabulaire trié ; chaque mot pointe vers les titres qui le contiennent.
 *   Taper « ava » = une recherche dichotomique dans le vocabulaire (pas de parcours des milliers de titres).
 * - Sans accents ni majuscules, ponctuation ignorée.
 * - Si peu de résultats : tolérance aux fautes de frappe (distance d'édition bornée), puis mots collés / séparés.
 * - Les doublons (même film en plusieurs langues / qualités) sont regroupés dans la liste des résultats.
 */
class SearchIndex private constructor(
    private val items: List<Item>,
    private val norm: Array<String>,
    private val compact: Array<String?>,
    private val vocab: Array<String>,
    private val post: Array<IntArray>,
    private val rate: ByteArray,
    private val withVersions: Boolean,
    private val maxTok: Int
) {
    val size: Int get() = items.size

    private class Ver(val years: Array<String>, val byTitle: HashMap<String, IntArray>, val dkey: Array<String>)

    /** Données « doublons » : calculées seulement à la première demande (fiche d'un film), pas au démarrage. */
    private val ver: Lazy<Ver?> = lazy(LazyThreadSafetyMode.PUBLICATION) { if (withVersions) computeVer() else null }

    private fun computeVer(): Ver {
        val n = items.size
        val titles = Array(n) { "" }
        val years = Array(n) { "" }
        val yearsOf = HashMap<String, HashSet<String>>()
        for (i in 0 until n) {
            val k = Versions.key(items[i].name, items[i].year)
            titles[i] = k.title
            years[i] = k.year
            if (k.year.isNotEmpty()) yearsOf.getOrPut(k.title) { HashSet() }.add(k.year)
        }
        val dkey = Array(n) { i ->
            val ys = yearsOf[titles[i]]
            if (ys == null || ys.size <= 1) titles[i] else titles[i] + "|" + years[i]
        }
        val bt = HashMap<String, IntBuf>()
        for (i in 0 until n) {
            var b = bt[titles[i]]
            if (b == null) {
                b = IntBuf()
                bt[titles[i]] = b
            }
            b.add(i)
        }
        val fin = HashMap<String, IntArray>(bt.size * 2)
        for ((k, v) in bt) fin[k] = v.toArray()
        return Ver(years, fin, dkey)
    }

    private class IntBuf {
        var a = IntArray(2)
        var n = 0
        fun add(v: Int) {
            if (n > 0 && a[n - 1] == v) return
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }

    private class Scratch(n: Int) {
        val a = IntArray(n + 2)
        val b = IntArray(n + 2)
        val c = IntArray(n + 2)
    }

    companion object {
        private const val MAX_FUZZY_TOKEN = 32

        fun build(items: List<Item>, withVersions: Boolean): SearchIndex {
            val n = items.size
            val norm = Array(n) { Txt.fold(items[it].name) }
            val compact = arrayOfNulls<String>(n)
            val map = HashMap<String, IntBuf>(n * 2 + 16)
            var maxTok = 0
            for (i in 0 until n) {
                val s = norm[i]
                var start = 0
                var nt = 0
                for (j in 0..s.length) {
                    if (j == s.length || s[j] == ' ') {
                        if (j > start) {
                            val tok = s.substring(start, j)
                            var b = map[tok]
                            if (b == null) {
                                b = IntBuf()
                                map[tok] = b
                            }
                            b.add(i)
                            nt++
                            if (tok.length > maxTok && tok.length <= MAX_FUZZY_TOKEN) maxTok = tok.length
                        }
                        start = j + 1
                    }
                }
                if (nt > 1) compact[i] = s.replace(" ", "")
            }
            val vocab = map.keys.toTypedArray()
            vocab.sort()
            val post = Array(vocab.size) { map[vocab[it]]!!.toArray() }

            val rate = ByteArray(n)
            for (i in 0 until n) {
                val r = items[i].rating.toDoubleOrNull()
                if (r != null) rate[i] = (r.coerceIn(0.0, 10.0) * 3).toInt().toByte()
            }

            return SearchIndex(items, norm, compact, vocab, post, rate, withVersions, maxTok)
        }
    }

    // ------------------------------------------------------------------ recherche
    private fun prefixSet(t: String): BitSet {
        val bs = BitSet(items.size)
        val r = java.util.Arrays.binarySearch(vocab, t)
        var i = if (r >= 0) r else -(r + 1)
        while (i < vocab.size && vocab[i].startsWith(t)) {
            for (id in post[i]) bs.set(id)
            i++
        }
        return bs
    }

    private fun langBonus(l: String): Int = when (l) {
        "FR" -> 15
        "MULTI" -> 10
        else -> 0
    }

    private fun score(id: Int, qn: String, qt: List<String>): Int {
        val s = norm[id]
        var sc = 1000
        if (s == qn) sc += 5000
        else if (s.startsWith(qn)) sc += 3000
        else if (s.contains(" $qn")) sc += 1800
        else if (s.contains(qn)) sc += 1000
        for (t in qt) {
            if (s == t || s.startsWith("$t ") || s.endsWith(" $t") || s.contains(" $t ")) sc += 200
        }
        sc -= min(s.length, 150)
        val it = items[id]
        if (it.img != null) sc += 20
        sc += rate[id].toInt() + langBonus(it.lang)
        return sc
    }

    /** Distance d'édition (avec transposition) bornée par k ; renvoie k+1 si c'est plus loin. */
    private fun osa(a: String, b: String, bl: Int, k: Int, sc: Scratch): Int {
        val al = a.length
        if (abs(al - bl) > k) return k + 1
        var p2 = sc.a
        var p1 = sc.b
        var cur = sc.c
        for (j in 0..bl) p1[j] = j
        for (i in 1..al) {
            cur[0] = i
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..bl) {
                val cost = if (ca == b[j - 1]) 0 else 1
                var v = min(min(p1[j] + 1, cur[j - 1] + 1), p1[j - 1] + cost)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, p2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > k) return k + 1
            val t = p2
            p2 = p1
            p1 = cur
            cur = t
        }
        return p1[bl]
    }

    /** Titres contenant un mot proche de t (faute de frappe) ou dont le début du mot est proche (saisie incomplète). */
    private fun fuzzySet(t: String, sc: Scratch): BitSet? {
        if (t.length < 4 || t.length > MAX_FUZZY_TOKEN) return null
        val k = if (t.length >= 8) 2 else 1
        val bs = BitSet(items.size)
        var any = false
        for (vi in vocab.indices) {
            val w = vocab[vi]
            val lw = w.length
            if (lw < t.length - k || lw > MAX_FUZZY_TOKEN) continue
            var ok = abs(lw - t.length) <= k && osa(t, w, lw, k, sc) <= k
            if (!ok && lw > t.length) ok = osa(t, w, t.length, k, sc) <= k
            if (ok) {
                for (id in post[vi]) bs.set(id)
                any = true
            }
        }
        return if (any) bs else null
    }

    private fun ranked(cand: BitSet, qn: String, qt: List<String>, base: Int, skip: BitSet?): LongArray {
        var cnt = 0
        var id = cand.nextSetBit(0)
        val tmp = LongArray(cand.cardinality())
        while (id >= 0) {
            if (skip == null || !skip.get(id)) {
                tmp[cnt++] = ((score(id, qn, qt) + base).toLong() shl 32) or id.toLong()
            }
            id = cand.nextSetBit(id + 1)
        }
        val out = tmp.copyOf(cnt)
        out.sort()
        return out
    }

    /**
     * Résultats classés par pertinence, sans doublons. [accept] permet d'écarter par exemple les éléments masqués.
     */
    fun search(q: String, limit: Int, accept: (Item) -> Boolean): List<Item> {
        val qn = Txt.fold(q)
        if (qn.isEmpty() || items.isEmpty()) return emptyList()
        val qt = qn.split(' ')
        val order = ArrayList<LongArray>(3)

        // 1) tous les mots saisis sont le début d'un mot du titre
        val strict = prefixSet(qt[0])
        for (k in 1 until qt.size) {
            strict.and(prefixSet(qt[k]))
            if (strict.isEmpty) break
        }
        order.add(ranked(strict, qn, qt, 0, null))

        // 1b) comme avant : le texte saisi apparaît n'importe où dans le titre (classé après les meilleurs résultats)
        run {
            val sub = BitSet(items.size)
            for (i in items.indices) {
                if (!strict.get(i) && norm[i].contains(qn)) sub.set(i)
            }
            if (!sub.isEmpty) order.add(ranked(sub, qn, qt, -1000, null))
            strict.or(sub)
        }

        // 2) peu de résultats : mots collés / séparés, puis fautes de frappe
        if (strict.cardinality() < 12) {
            val qc = qn.replace(" ", "")
            if (qc.length >= 4) {
                val comp = BitSet(items.size)
                for (i in items.indices) {
                    if (!strict.get(i) && (compact[i] ?: norm[i]).contains(qc)) comp.set(i)
                }
                if (!comp.isEmpty) order.add(ranked(comp, qn, qt, -2000, null))
                strict.or(comp)
            }
            if (qn.length >= 4) {
                val sc = Scratch(maxTok + 2)
                var fz: BitSet? = null
                var valid = true
                for (t in qt) {
                    val own = prefixSet(t)
                    val f = fuzzySet(t, sc)
                    if (f != null) own.or(f)
                    if (own.isEmpty) {
                        valid = false
                        break
                    }
                    if (fz == null) fz = own else fz.and(own)
                }
                val fzz = fz
                if (valid && fzz != null && !fzz.isEmpty) order.add(ranked(fzz, qn, qt, -3500, strict))
            }
        }

        val out = ArrayList<Item>()
        val used = BitSet(items.size)
        for (arr in order) {
            var i = arr.size - 1
            while (i >= 0 && out.size < limit) {
                val id = (arr[i] and 0xFFFFFFFFL).toInt()
                i--
                if (used.get(id)) continue
                used.set(id)
                val it = items[id]
                if (!accept(it)) continue
                out.add(it)
            }
            if (out.size >= limit) break
        }
        return out
    }

    // ------------------------------------------------------------------ versions et acteurs
    /** Les autres versions du même film / de la même série (jamais deux années différentes). */
    fun versionsOf(item: Item): List<Item> {
        val v = ver.value ?: return listOf(item)
        val bt = v.byTitle
        val k = Versions.key(item.name, item.year)
        val ids = bt[k.title] ?: return listOf(item)
        if (ids.size <= 1) return listOf(item)
        val ys = v.years
        val years = HashSet<String>()
        for (id in ids) if (ys[id].isNotEmpty()) years.add(ys[id])
        val out = ArrayList<Item>()
        for (id in ids) {
            val cy = ys[id]
            val ok = when {
                k.year.isNotEmpty() && cy.isNotEmpty() -> k.year == cy
                k.year.isEmpty() && cy.isEmpty() -> true
                else -> years.size <= 1
            }
            if (ok) out.add(items[id])
        }
        if (out.none { it.id == item.id }) out.add(item)
        return if (out.size <= 1) out else Versions.order(item, out)
    }

    /** Titres locaux correspondant à une filmographie TMDB (recherche par acteur / réalisateur). */
    fun matchCredits(credits: List<TmCredit>, tv: Boolean, accept: (Item) -> Boolean): List<Item> {
        val v = ver.value ?: return emptyList()
        val bt = v.byTitle
        val ys = v.years
        val dkey = v.dkey
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (c in credits) {
            if (c.tv != tv) continue
            for (t in listOf(c.title, c.original)) {
                if (t.isBlank()) continue
                val ids = bt[Versions.key(t, "").title] ?: continue
                for (id in ids) {
                    val ly = ys[id]
                    if (c.year.length == 4 && ly.length == 4) {
                        val d = (c.year.toIntOrNull() ?: 0) - (ly.toIntOrNull() ?: 0)
                        if (abs(d) > 1) continue
                    }
                    val it = items[id]
                    if (accept(it) && seen.add(dkey[id])) out.add(it)
                }
            }
        }
        return out
    }
}
