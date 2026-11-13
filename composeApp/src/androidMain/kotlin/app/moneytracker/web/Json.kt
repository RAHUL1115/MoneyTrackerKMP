package app.moneytracker.web

/**
 * Just enough JSON for the web dashboard's API: objects become Map<String, Any?>, arrays List<Any?>,
 * numbers Double (or Long when written). Kept here instead of a library so the app gains no dependency.
 */
internal object Json {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(out: StringBuilder, v: Any?) {
        when (v) {
            null -> out.append("null")
            is String -> string(out, v)
            is Boolean -> out.append(v)
            is Int, is Long -> out.append(v.toString())
            is Double -> if (v.isFinite()) out.append(if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()) else out.append("null")
            is Number -> out.append(v.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) out.append(',')
                    first = false
                    string(out, k.toString()); out.append(':'); write(out, value)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in v) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            else -> string(out, v.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            // < and > escaped too, so a note can never close a <script> if the text ends up in HTML.
            c < ' ' || c == '<' || c == '>' || c == ' ' || c == ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
        out.append('"')
    }

    /** Parses [text]; throws [IllegalArgumentException] when it isn't valid JSON. */
    fun parse(text: String): Any? = Parser(text).run {
        val v = value()
        space()
        require(i == s.length) { "Trailing text" }
        v
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun space() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            space()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> {
                    require(c == '-' || c.isDigit()) { "Unexpected '$c'" }
                    num()
                }
            }
        }

        private inline fun <T> nested(block: () -> T): T {
            require(++depth <= 32) { "Too deep" }
            return block().also { depth-- }
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "Bad literal" }
            i += w.length
            return v
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number")
        }

        private fun str(): String {
            i++ // opening quote
            val b = StringBuilder()
            while (true) {
                require(i < s.length) { "Unclosed string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        require(i < s.length) { "Bad escape" }
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                            'u' -> {
                                require(i + 4 <= s.length) { "Bad escape" }
                                b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            space()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                space()
                require(i < s.length && s[i] == '"') { "Expected key" }
                val k = str()
                space()
                require(i < s.length && s[i] == ':') { "Expected :" }
                i++
                m[k] = value()
                space()
                require(i < s.length) { "Unclosed object" }
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw IllegalArgumentException("Expected , or }") }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            space()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                space()
                require(i < s.length) { "Unclosed array" }
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw IllegalArgumentException("Expected , or ]") }
            }
        }
    }
}
