package com.perso.iptv

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit

/** Normalisation de texte : minuscules, sans accents, lettres et chiffres séparés par un seul espace. */
object Txt {
    fun fold(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        var lastSpace = true
        for (c0 in d) {
            if (Character.getType(c0) == Character.NON_SPACING_MARK.toInt()) continue
            var c = c0.lowercaseChar()
            when (c) {
                'ı' -> c = 'i'
                'ø' -> c = 'o'
                'ł' -> c = 'l'
                'đ' -> c = 'd'
                'ß' -> { sb.append("ss"); lastSpace = false; continue }
                'œ' -> { sb.append("oe"); lastSpace = false; continue }
                'æ' -> { sb.append("ae"); lastSpace = false; continue }
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(c)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }
}

/** Clé d'identité d'un film / d'une série : titre « nettoyé » + année si elle est connue. */
class VKey(val title: String, val year: String)

class VersionDetail(val item: Item, val line: String, val sub: String)

class AltSource(val url: String, val label: String, val item: Item? = null)

/**
 * Détection des doublons (même film / même série présent plusieurs fois : langues, qualités, catégories).
 * Principe de prudence : on ne regroupe que des titres identiques une fois nettoyés des étiquettes (FR, 4K, HEVC…),
 * et deux années différentes ne sont JAMAIS regroupées (Dune 1984 ≠ Dune 2021).
 */
object Versions {
    /** Étiquettes qui ne peuvent pas faire partie d'un vrai titre : retirées partout. */
    private val safeTags = hashSetOf(
        "vf", "vff", "vfq", "vfi", "vostfr", "stfr", "multi", "truefrench", "vost",
        "4k", "uhd", "fhd", "2160p", "1080p", "1080i", "720p", "576p", "480p",
        "hevc", "h265", "h264", "x265", "x264", "hdr", "hdr10", "hdrip", "bluray", "bdrip", "brrip",
        "remux", "webrip", "webdl", "hdtv", "dvdrip", "10bit", "atmos"
    )

    /** Étiquettes plus ambiguës : retirées seulement quand elles terminent le titre. */
    private val endTags: HashSet<String> = HashSet<String>(safeTags).apply {
        addAll(
            listOf(
                "hd", "sd", "fr", "en", "vo", "french", "english", "eng", "dv", "dts", "ac3", "eac3", "aac",
                "mkv", "mp4", "hq", "web", "dl", "blu", "ray", "dvd", "imax", "extended", "uncut", "remastered"
            )
        )
    }

    private val rYear = Regex("^(19|20)\\d{2}$")
    private val rBracket = Regex("[\\[({]([^\\])}]*)[\\])}]")

    fun key(name: String, year: String): VKey {
        var yr = year.trim().take(4)
        if (!rYear.matches(yr)) yr = ""
        val stripped = rBracket.replace(name) { m ->
            val inner = Txt.fold(m.groupValues[1])
            if (inner.isEmpty()) {
                " "
            } else {
                val ts = inner.split(' ')
                if (ts.size == 1 && rYear.matches(ts[0])) {
                    if (yr.isEmpty()) yr = ts[0]
                    " "
                } else if (ts.all { it in endTags || rYear.matches(it) }) {
                    " "
                } else {
                    " $inner "
                }
            }
        }
        val toks = Txt.fold(stripped).split(' ').filter { it.isNotEmpty() && it !in safeTags }.toMutableList()
        while (toks.size > 1 && toks[toks.size - 1] in endTags) toks.removeAt(toks.size - 1)
        val title = if (toks.isEmpty()) Txt.fold(name) else toks.joinToString(" ")
        return VKey(title, yr)
    }

    private val tagLabels = listOf(
        "4k" to "4K", "uhd" to "UHD", "2160p" to "2160p", "1080p" to "1080p", "fhd" to "FHD", "720p" to "720p",
        "hevc" to "HEVC", "x265" to "HEVC", "h265" to "HEVC", "x264" to "H.264", "h264" to "H.264",
        "hdr" to "HDR", "hdr10" to "HDR10", "remux" to "Remux", "bluray" to "BluRay", "webrip" to "WebRip"
    )

    /** Étiquettes de qualité écrites dans le titre (indication du fournisseur, non vérifiée). */
    fun nameTags(name: String): List<String> {
        val ts = Txt.fold(name).split(' ').toSet()
        val out = ArrayList<String>()
        for ((k, label) in tagLabels) if (k in ts && label !in out) out.add(label)
        if ("web" in ts && "dl" in ts) out.add("WEB-DL")
        return out
    }

    private fun langRank(l: String): Int = when (l) {
        "FR" -> 0
        "MULTI" -> 1
        "VOSTFR" -> 2
        "" -> 3
        else -> 4
    }

    /** Version « principale » en premier, puis par langue. */
    fun order(item: Item, list: List<Item>): List<Item> =
        list.sortedWith(compareBy<Item>({ it.id != item.id }, { langRank(it.lang) }, { -it.added }))

    fun label(i: Item): String {
        val parts = ArrayList<String>()
        if (i.lang.isNotBlank()) parts.add(i.lang)
        parts.add(i.ext.uppercase())
        parts.addAll(nameTags(i.name))
        return parts.joinToString(" · ")
    }

    private fun channelsLabel(n: Int): String = when (n) {
        0 -> ""
        1 -> " mono"
        2 -> " stéréo"
        6 -> " 5.1"
        8 -> " 7.1"
        else -> " ${n}c"
    }

    private fun bitrateLabel(kbps: Int): String =
        if (kbps >= 1000) String.format(java.util.Locale.US, "%.1f Mb/s", kbps / 1000.0) else "$kbps kb/s"

    /**
     * Fiche d'une version : uniquement des informations réellement lues (conteneur, métadonnées du serveur,
     * test de disponibilité). Rien n'est supposé : une donnée absente n'est pas affichée.
     */
    private val gate = kotlinx.coroutines.sync.Semaphore(1)

    suspend fun describe(api: Xtream, kind: String, item: Item, catName: String): VersionDetail =
        gate.withPermit { describeNow(api, kind, item, catName) }

    private suspend fun describeNow(api: Xtream, kind: String, item: Item, catName: String): VersionDetail {
        val parts = ArrayList<String>()
        val sub = ArrayList<String>()
        if (item.lang.isNotBlank()) parts.add(item.lang)
        if (kind == "vod") {
            parts.add(item.ext.uppercase())
            val inf = api.vodInfo(item.id)
            if (inf != null) {
                if (inf.width > 0 && inf.height > 0) parts.add("${inf.width}×${inf.height}")
                if (inf.vcodec.isNotBlank()) parts.add(inf.vcodec.uppercase())
                if (inf.acodec.isNotBlank()) parts.add(inf.acodec.uppercase() + channelsLabel(inf.channels))
                if (inf.bitrate > 0) parts.add(bitrateLabel(inf.bitrate))
            }
            val code = api.probe(api.vodUrl(item.id, item.ext))
            sub.add(
                when {
                    code in 200..206 -> "✓ flux disponible"
                    code < 0 -> "✕ serveur injoignable"
                    else -> "✕ indisponible (code $code)"
                }
            )
        } else {
            try {
                val eps = api.seriesDetail(item.id).second
                val ns = eps.map { it.season }.distinct().size
                parts.add(if (ns > 1) "$ns saisons" else "$ns saison")
                parts.add("${eps.size} épisodes")
                sub.add(if (eps.isEmpty()) "✕ aucun épisode" else "✓ disponible")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                sub.add("✕ indisponible")
            }
        }
        if (catName.isNotBlank()) sub.add("Catégorie : $catName")
        val tags = nameTags(item.name)
        if (tags.isNotEmpty()) sub.add("dans le titre : " + tags.joinToString(" "))
        return VersionDetail(item, parts.joinToString(" · ").ifBlank { item.name }, sub.joinToString("  ·  "))
    }
}

/**
 * Sources de remplacement pour un même contenu. Une source n'est proposée que si elle est
 * (1) confirmée identique (même identifiant TMDB, ou même titre ET même année connue),
 * (2) réellement joignable (test de disponibilité).
 */
object Alternatives {
    private fun up(code: Int) = code in 200..206

    suspend fun vod(repo: Repo, api: Xtream, cur: Item?, tried: Set<String>): List<AltSource> {
        if (cur == null) return emptyList()
        val sibs = repo.versionsOf("vod", cur).filter { it.id != cur.id }.take(6)
        if (sibs.isEmpty()) return emptyList()
        val curYear = Versions.key(cur.name, cur.year).year
        val curTmdb = api.vodInfo(cur.id)?.tmdb ?: ""
        val out = ArrayList<AltSource>()
        for (s in sibs) {
            val url = api.vodUrl(s.id, s.ext)
            if (url in tried) continue
            val sTmdb = api.vodInfo(s.id)?.tmdb ?: ""
            val same = if (curTmdb.isNotBlank() && sTmdb.isNotBlank()) {
                curTmdb == sTmdb
            } else {
                curYear.isNotEmpty() && curYear == Versions.key(s.name, s.year).year
            }
            if (same && up(api.probe(url))) out.add(AltSource(url, Versions.label(s), s))
        }
        return out
    }

    suspend fun episode(repo: Repo, api: Xtream, e: EpEntry?, tried: Set<String>): List<AltSource> {
        if (e == null || e.seriesId.isBlank() || e.num.isBlank()) return emptyList()
        val series = repo.data["series"]?.all?.firstOrNull { it.id == e.seriesId } ?: return emptyList()
        val sibs = repo.versionsOf("series", series).filter { it.id != series.id }.take(4)
        if (sibs.isEmpty()) return emptyList()
        val curTmdb = try { api.seriesDetail(series.id).first.tmdb } catch (x: CancellationException) { throw x } catch (x: Throwable) { "" }
        val curYear = Versions.key(series.name, series.year).year
        val out = ArrayList<AltSource>()
        for (s in sibs) {
            try {
                val (inf, eps) = api.seriesDetail(s.id)
                val same = if (curTmdb.isNotBlank() && inf.tmdb.isNotBlank()) {
                    curTmdb == inf.tmdb
                } else {
                    curYear.isNotEmpty() && curYear == Versions.key(s.name, s.year).year
                }
                if (!same) continue
                val ep = eps.firstOrNull { it.season == e.season && it.num == e.num } ?: continue
                val url = api.epUrl(ep.id, ep.ext)
                if (url in tried) continue
                if (up(api.probe(url))) out.add(AltSource(url, Versions.label(s)))
            } catch (x: CancellationException) {
                throw x
            } catch (x: Throwable) {
                /* cette source ne répond pas : on passe à la suivante */
            }
        }
        return out
    }

    /** Chaînes : seulement si le guide TV (epg_channel_id) confirme que c'est la même chaîne. */
    suspend fun live(repo: Repo, api: Xtream, cur: Item?, tried: Set<String>): List<AltSource> {
        if (cur == null || cur.epgId.isBlank()) return emptyList()
        val all = repo.data["live"]?.all ?: return emptyList()
        val out = ArrayList<AltSource>()
        for (s in all.filter { it.epgId == cur.epgId && it.id != cur.id }.take(5)) {
            val url = api.liveUrl(s.id)
            if (url in tried) continue
            if (up(api.probe(url))) out.add(AltSource(url, s.name, s))
        }
        return out
    }
}
