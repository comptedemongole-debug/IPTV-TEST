package com.perso.iptv

/** Pastilles qualité lues UNIQUEMENT dans le titre (rien d'inventé). */
object Quality {
    private val r4k = Regex("(?i)(?<![a-z0-9])(4k|uhd|2160p?)(?![a-z0-9])")
    private val r1080 = Regex("(?i)(?<![a-z0-9])(1080p?|fhd|full\\s?hd)(?![a-z0-9])")
    private val r720 = Regex("(?i)(?<![a-z0-9])(720p?)(?![a-z0-9])")
    private val rDv = Regex("(?i)(?<![a-z0-9])(dolby\\s?vision|dovi|dv)(?![a-z0-9])")
    private val rHdr10p = Regex("(?i)hdr10\\+|hdr10plus")
    private val rHdr = Regex("(?i)(?<![a-z0-9])hdr(10)?(?![a-z0-9+])")

    fun badges(name: String): List<String> {
        val out = ArrayList<String>(3)
        when {
            r4k.containsMatchIn(name) -> out.add("4K")
            r1080.containsMatchIn(name) -> out.add("1080p")
            r720.containsMatchIn(name) -> out.add("720p")
        }
        when {
            rDv.containsMatchIn(name) -> out.add("Dolby Vision")
            rHdr10p.containsMatchIn(name) -> out.add("HDR10+")
            rHdr.containsMatchIn(name) -> out.add("HDR")
        }
        return out
    }
}
