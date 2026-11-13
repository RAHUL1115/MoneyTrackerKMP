package app.moneytracker.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Keypad calculator: "12.5+3×4−1÷2" with the usual precedence (× ÷ before + −).
 * Works on decimal strings and returns a plain decimal string rounded to [decimals], or null when the
 * expression is incomplete or invalid (division by zero, dangling operator, negative result).
 */
object Calc {
    const val OPS = "+−×÷"

    fun hasOperator(text: String) = text.drop(1).any { it in OPS }

    fun evaluate(text: String, decimals: Int): String? {
        val sums = multiplyOut(terms(text) ?: return null) ?: return null
        val result = addUp(sums)
        if (result < 0 || result.isNaN() || result.isInfinite()) return null
        return format(result, decimals)
    }

    /** Numbers with the operators between them: [operators] has one fewer entry than [numbers]. */
    private class Expression(val numbers: List<Double>, val operators: List<Char>)

    /** "12+3×4" → 12, 3, 4 with +, ×; a leading − is a minus sign. Null when empty or a number is incomplete. */
    private fun terms(text: String): Expression? {
        if (text.isEmpty()) return null
        val parts = mutableListOf<String>()
        val ops = mutableListOf<Char>()
        var cur = StringBuilder()
        text.forEachIndexed { i, c ->
            if (c in OPS && !(i == 0 && c == '−')) {
                parts += cur.toString()
                ops += c; cur = StringBuilder()
            } else cur.append(c)
        }
        parts += cur.toString()
        return Expression(parts.map { it.replace('−', '-').toDoubleOrNull() ?: return null }, ops)
    }

    /** × ÷ first, folding into the left operand; null on division by zero. */
    private fun multiplyOut(e: Expression): Expression? {
        val n2 = mutableListOf(e.numbers[0])
        val o2 = mutableListOf<Char>()
        e.operators.forEachIndexed { i, op ->
            val rhs = e.numbers[i + 1]
            when (op) {
                '×' -> n2[n2.lastIndex] = n2.last() * rhs
                '÷' -> {
                    if (rhs == 0.0) return null
                    n2[n2.lastIndex] = n2.last() / rhs
                }
                else -> { o2 += op; n2 += rhs }
            }
        }
        return Expression(n2, o2)
    }

    private fun addUp(e: Expression): Double {
        var result = e.numbers[0]
        e.operators.forEachIndexed { i, op -> result = if (op == '+') result + e.numbers[i + 1] else result - e.numbers[i + 1] }
        return result
    }

    private fun format(v: Double, decimals: Int): String {
        val scale = 10.0.pow(decimals)
        val minor = (v * scale).roundToLong()
        if (decimals == 0) return minor.toString()
        val whole = minor / scale.toLong()
        val frac = abs(minor % scale.toLong()).toString().padStart(decimals, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }
}
