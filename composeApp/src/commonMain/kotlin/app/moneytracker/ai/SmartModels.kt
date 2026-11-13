package app.moneytracker.ai

import kotlin.math.exp
import kotlin.math.sqrt

/** Unicode NFKC normalisation (full-width digits, ligatures…), used before splitting words into knowledge pieces. */
internal expect fun nfkc(s: String): String

/**
 * An 8-bit table from the model files: value = byte × scale[column]. Hashed tables keep only the rows training used
 * ([index] = sorted bucket ids); a missing bucket reads as zeros.
 */
internal class QTable(val rows: Int, val cols: Int, private val index: IntArray?, private val scale: FloatArray, private val q: ByteArray) {
    companion object {
        fun read(bytes: ByteArray): QTable {
            var p = 0
            fun u32(): Int { val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 3].toInt() and 0xFF) shl 24); p += 4; return v }
            require(bytes.size > 16 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == 'Q'.code.toByte() && bytes[3] == '1'.code.toByte()) { "Not a Smart entry table" }
            p = 4
            val rows = u32(); val cols = u32(); val n = u32()
            val index = if (n > 0) IntArray(n) { u32() } else null
            val scale = FloatArray(cols) { Float.fromBits(u32()) }
            val q = bytes.copyOfRange(p, p + rows * cols)
            return QTable(rows, cols, index, scale, q)
        }
    }

    /** Row position for a bucket id, or -1 when the bucket has no stored row. */
    fun rowOf(bucket: Int): Int = if (index == null) bucket else index.binarySearch(bucket).let { if (it >= 0) it else -1 }

    /** acc += row(r); r from [rowOf]. */
    fun addRow(r: Int, acc: FloatArray) {
        if (r < 0) return
        val base = r * cols
        for (c in 0 until cols) acc[c] += q[base + c] * scale[c]
    }

    fun get(r: Int, c: Int): Float = q[r * cols + c] * scale[c]
}

internal fun softmax(z: FloatArray): FloatArray {
    val m = z.max(); val e = FloatArray(z.size) { exp((z[it] - m).toDouble()).toFloat() }; val s = e.sum()
    for (i in e.indices) e[i] /= s
    return e
}

internal fun normalize(v: FloatArray, eps: Float = 0f): FloatArray {
    var n = 0.0; for (x in v) n += x * x
    val d = (sqrt(n) + eps).toFloat()
    if (d == 0f) return v
    return FloatArray(v.size) { v[it] / d }
}

internal fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

/** Splits text into the knowledge table's word pieces: best-scoring split per word (Viterbi), uncovered characters skipped. */
internal class PieceSplitter(pieces: List<Pair<String, Float>>) {
    private val ids = HashMap<String, Int>(pieces.size * 2)
    private val scores = FloatArray(pieces.size)
    private val maxLen: Int

    init {
        var m = 1
        pieces.forEachIndexed { k, (p, s) -> ids[p] = k; scores[k] = s; if (p.length > m) m = p.length }
        maxLen = m
    }

    fun split(text: String): IntArray {
        val out = ArrayList<Int>()
        for (word in nfkc(text.lowercase()).split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }) out.addAll(splitWord(word))
        return out.toIntArray()
    }

    private fun splitWord(word: String): List<Int> {
        val s = "▁$word"
        val lattice = Lattice(s.length)
        for (i in s.indices) {
            if (!lattice.reached(i)) continue
            for (j in i + 1..minOf(s.length, i + maxLen)) {
                val id = ids[s.substring(i, j)] ?: continue
                lattice.offer(from = i, to = j, piece = id, score = lattice.best[i] + scores[id])
            }
            if (!lattice.reached(i + 1)) lattice.skip(i)
        }
        return lattice.path()
    }

    /** The best split found so far for every prefix of one word. */
    private class Lattice(n: Int) {
        val best = DoubleArray(n + 1) { -1e18 }.also { it[0] = 0.0 }
        private val from = IntArray(n + 1) { -1 }
        private val piece = IntArray(n + 1) { -1 }

        fun reached(i: Int) = best[i] > -1e17

        fun offer(from: Int, to: Int, piece: Int, score: Double) {
            if (score > best[to]) { best[to] = score; this.from[to] = from; this.piece[to] = piece }
        }

        /** Skips the character at [i] with a penalty, so a word with an unknown character still splits. */
        fun skip(i: Int) {
            best[i + 1] = best[i] - 20.0; from[i + 1] = i; piece[i + 1] = -1
        }

        /** Piece ids along the best path, in reading order; skipped characters left out. */
        fun path(): List<Int> {
            val reversed = ArrayList<Int>()
            var j = piece.size - 1
            while (j > 0) {
                if (piece[j] >= 0) reversed.add(piece[j])
                j = from[j]
            }
            return reversed.asReversed()
        }
    }
}
