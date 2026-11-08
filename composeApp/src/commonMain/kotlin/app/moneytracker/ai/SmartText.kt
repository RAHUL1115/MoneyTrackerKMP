package app.moneytracker.ai

/**
 * Text clean-up shared by every Smart entry model. Mirrors the training code exactly (same tokens → same hashes),
 * so change it only together with the model files.
 */
internal object SmartText {
    /** Lower-case, drop emoji/symbols (Devanagari vowel signs kept), "1,250" → "1250", lone dots/commas → spaces. */
    fun norm(input: String): String {
        val lowered = input.lowercase().replace("₹", " rs ").replace('।', ' ').replace('॥', ' ')
        val kept = StringBuilder(lowered.length)
        for (ch in lowered) {
            val keep = ch.isLetterOrDigit() || ch == '_' || ch.isWhitespace() || ch in "&'.," || ch in 'ऀ'..'ॿ'
            kept.append(if (keep) ch else ' ')
        }
        val s = kept.toString()
        // Commas between digits disappear; any other '.' or ',' not touching a digit on the right side becomes a space.
        val noThousands = StringBuilder(s.length)
        for (i in s.indices) {
            val ch = s[i]
            if (ch == ',' && i > 0 && i + 1 < s.length && s[i - 1].isDigit() && s[i + 1].isDigit()) continue
            noThousands.append(ch)
        }
        val t = noThousands.toString()
        val out = StringBuilder(t.length)
        for (i in t.indices) {
            val ch = t[i]
            if (ch == '.' || ch == ',') {
                val prevDigit = i > 0 && t[i - 1].isDigit()
                val nextDigit = i + 1 < t.length && t[i + 1].isDigit()
                out.append(if (!prevDigit || !nextDigit) ' ' else ch)
            } else out.append(ch)
        }
        return out.toString().trim().split(WS).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val WS = Regex("\\s+")

    private val CONS = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh",
        'स' to "s", 'ह' to "h", 'ळ' to "l", 'ड़' to "d", 'ढ़' to "dh")
    private val NUKTA = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "d", 'ढ' to "dh", 'फ' to "f")
    private val VOW = mapOf('अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o",
        'औ' to "au", 'ऋ' to "ri", 'ऑ' to "o", 'ऍ' to "e")
    private val MATRA = mapOf('ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ृ' to "ri", 'ॉ' to "o", 'ॅ' to "e")
    private const val VIRAMA = '्'
    private const val NUK = '़'

    private class Unit(val c: String, var v: String, val explicit: Boolean)

    /** Devanagari word → Hinglish spelling ("पेट्रोल" → "petrol"); anything else is returned unchanged. */
    fun translit(word: String): String {
        if (word.none { it in 'ऀ'..'ॿ' }) return word
        val w = buildString { for (ch in word) append(if (ch in '०'..'९') ('0' + (ch - '०')) else ch) }
        val units = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            val ch = w[i]
            val cons = CONS[ch]
            when {
                cons != null -> {
                    var r = cons
                    if (i + 1 < w.length && w[i + 1] == NUK) { r = NUKTA[ch] ?: r; i++ }
                    var v = "a"; var explicit = false
                    if (i + 1 < w.length && MATRA.containsKey(w[i + 1])) { v = MATRA.getValue(w[i + 1]); explicit = true; i++ }
                    else if (i + 1 < w.length && w[i + 1] == VIRAMA) { v = ""; explicit = true; i++ }
                    units.add(Unit(r, v, explicit))
                }
                VOW.containsKey(ch) -> units.add(Unit("", VOW.getValue(ch), true))
                ch == 'ं' || ch == 'ँ' -> units.add(Unit("n", "", true))
                ch == 'ः' -> units.add(Unit("h", "", true))
                ch == NUK -> {}
                else -> units.add(Unit(ch.toString(), "", true))
            }
            i++
        }
        // Schwa deletion: drop the final inherent 'a', and a medial one between a vowel and a consonant+vowel.
        val n = units.size
        for (k in 0 until n) {
            val u = units[k]
            if (u.v == "a" && !u.explicit && u.c.isNotEmpty()) {
                if (k == n - 1 && n > 1) u.v = ""
                else if (k in 1 until n - 1) {
                    val prevV = units[k - 1].v != ""
                    val nxt = units[k + 1]
                    val a = prevV && nxt.c.isNotEmpty() && nxt.c != "n" && nxt.v != "" && k + 1 != n - 1
                    val b = prevV && nxt.c.isNotEmpty() && nxt.explicit && nxt.v != ""
                    if (a || b) u.v = ""
                }
            }
        }
        return units.joinToString("") { it.c + it.v }
    }

    private val DOUBLE = Regex("([a-z])\\1+")

    /** Spelling fold so Hinglish and misheard variants meet: aa→a, ee→i, oo→u, w→v, ph→f, z→j, q→k, doubled letters → one. */
    fun fold(w: String): String {
        if (w.isNotEmpty() && w.all { it.isDigit() }) return w
        val s = w.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace("w", "v").replace("ph", "f").replace("z", "j").replace("q", "k")
        return DOUBLE.replace(s, "$1")
    }

    /** Model tokens for [text]: normalised words, transliterated, folded, then English spellings for Hindi-script loanwords. */
    fun toks(text: String, aliases: Map<String, String>): List<String> =
        norm(text).split(' ').filter { it.isNotEmpty() }.map { fold(translit(it)) }.map { aliases[it] ?: it }

    /** Category names: symbols and emoji removed ("🍔 Food" → "food"). */
    fun cleanCategory(name: String): String {
        val s = buildString { for (ch in name) append(if (ch.isLetterOrDigit() || ch == '_' || ch.isWhitespace() || ch == '&') ch else ' ') }.trim().lowercase()
        return s.ifEmpty { name.lowercase() }
    }

    /** 32-bit FNV-1a over UTF-8, as an unsigned value. */
    fun fnv1a(s: String): Long {
        var h = 0x811C9DC5L
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return h
    }
}
