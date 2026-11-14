package app.moneytracker.domain.ai

/**
 * Text clean-up shared by every Smart entry model. Mirrors the training code exactly (same tokens → same hashes),
 * so change it only together with the model files.
 */
internal object SmartText {
    /** Lower-case, drop emoji/symbols (Devanagari vowel signs kept), "1,250" → "1250", lone dots/commas → spaces. */
    fun norm(input: String): String {
        val lowered = input.lowercase().replace("₹", " rs ").replace('।', ' ').replace('॥', ' ')
        val kept = buildString { for (ch in lowered) append(if (isWordChar(ch) || ch in "&'.," || isDevanagari(ch)) ch else ' ') }
        return spaceLoneSeparators(dropThousandsCommas(kept)).trim().split(WS).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Commas between digits disappear: "1,250" → "1250". */
    private fun dropThousandsCommas(s: String) = buildString {
        for (i in s.indices) if (!(s[i] == ',' && digitAt(s, i - 1) && digitAt(s, i + 1))) append(s[i])
    }

    /** Any '.' or ',' without a digit on both sides becomes a space. */
    private fun spaceLoneSeparators(t: String) = buildString {
        for (i in t.indices) {
            val ch = t[i]
            val separator = ch == '.' || ch == ','
            append(if (separator && !(digitAt(t, i - 1) && digitAt(t, i + 1))) ' ' else ch)
        }
    }

    private fun digitAt(s: String, i: Int) = s.getOrNull(i)?.isDigit() == true
    private fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch.isWhitespace()
    private fun isDevanagari(ch: Char) = ch in 'ऀ'..'ॿ'

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

    /** One written sound: a consonant (or a standalone vowel, nasal or other mark) and the vowel after it. */
    private class Syllable(val consonant: String, var vowel: String, val explicit: Boolean)

    /** Devanagari word → Hinglish spelling ("पेट्रोल" → "petrol"); anything else is returned unchanged. */
    fun translit(word: String): String {
        if (word.none(::isDevanagari)) return word
        val w = buildString { for (ch in word) append(if (ch in '०'..'९') ('0' + (ch - '०')) else ch) }
        val syllables = syllablesOf(w)
        // Schwa deletion: drop the final inherent 'a', and a medial one between a vowel and a consonant+vowel.
        for (k in syllables.indices) if (dropsSchwa(syllables, k)) syllables[k].vowel = ""
        return syllables.joinToString("") { it.consonant + it.vowel }
    }

    private fun syllablesOf(w: String): List<Syllable> {
        val out = ArrayList<Syllable>()
        var i = 0
        while (i < w.length) {
            if (w[i] in CONS) i = readConsonant(w, i, out) else otherSyllable(w[i])?.let(out::add)
            i++
        }
        return out
    }

    /** Reads the consonant at [at] with its nukta and vowel sign into [out]; returns the index of the last character used. */
    private fun readConsonant(w: String, at: Int, out: MutableList<Syllable>): Int {
        var i = at
        var sound = CONS.getValue(w[at])
        if (w.getOrNull(i + 1) == NUK) { sound = NUKTA[w[at]] ?: sound; i++ }
        val next = w.getOrNull(i + 1)
        val matra = next?.let { MATRA[it] }
        when {
            matra != null -> { out.add(Syllable(sound, matra, explicit = true)); i++ }
            next == VIRAMA -> { out.add(Syllable(sound, "", explicit = true)); i++ }
            else -> out.add(Syllable(sound, "a", explicit = false))
        }
        return i
    }

    /** A standalone vowel, a nasal or visarga mark, or any other character as itself; null for a stray nukta. */
    private fun otherSyllable(ch: Char): Syllable? {
        val vowel = VOW[ch]
        return when {
            vowel != null -> Syllable("", vowel, true)
            ch == 'ं' || ch == 'ँ' -> Syllable("n", "", true)
            ch == 'ः' -> Syllable("h", "", true)
            ch == NUK -> null
            else -> Syllable(ch.toString(), "", true)
        }
    }

    private fun dropsSchwa(s: List<Syllable>, k: Int): Boolean {
        val u = s[k]
        val n = s.size
        if (u.vowel != "a" || u.explicit || u.consonant.isEmpty()) return false
        if (k == n - 1) return n > 1
        if (k == 0) return false
        val next = s[k + 1]
        val betweenVowels = s[k - 1].vowel != "" && next.consonant.isNotEmpty() && next.vowel != ""
        return betweenVowels && ((next.consonant != "n" && k + 1 != n - 1) || next.explicit)
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
        val s = buildString { for (ch in name) append(if (isWordChar(ch) || ch == '&') ch else ' ') }.trim().lowercase()
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
