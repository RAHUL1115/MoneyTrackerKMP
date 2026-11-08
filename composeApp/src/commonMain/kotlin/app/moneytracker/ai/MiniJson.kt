package app.moneytracker.ai

/** Tiny JSON reader for the Smart entry model metadata (objects → Map, arrays → List, numbers → Double). */
internal class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run { val v = value(); ws(); require(i == s.length) { "Trailing JSON at $i" }; v }
    }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> if (c == '-' || c.isDigit()) num() else error("Bad JSON at $i")
        }
    }

    private fun expect(word: String) { require(s.startsWith(word, i)) { "Expected $word at $i" }; i += word.length }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); require(s[i] == ':'); i++
            m[k] = value(); ws()
            if (s[i] == ',') { i++; continue }
            require(s[i] == '}'); i++; return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>(); i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value()); ws()
            if (s[i] == ',') { i++; continue }
            require(s[i] == ']'); i++; return l
        }
    }

    private fun str(): String {
        require(s[i] == '"'); i++
        val b = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }
}
