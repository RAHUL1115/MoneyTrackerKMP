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
        if (text.isEmpty()) return null
        val nums = mutableListOf<Double>()
        val ops = mutableListOf<Char>()
        var cur = StringBuilder()
        text.forEachIndexed { i, c ->
            if (c in OPS && !(i == 0 && c == '−')) {
                nums += cur.toString().replace('−', '-').toDoubleOrNull() ?: return null
                ops += c; cur = StringBuilder()
            } else cur.append(c)
        }
        nums += cur.toString().replace('−', '-').toDoubleOrNull() ?: return null

        // × ÷ first, folding into the left operand.
        val n2 = mutableListOf(nums[0])
        val o2 = mutableListOf<Char>()
        ops.forEachIndexed { i, op ->
            val rhs = nums[i + 1]
            when (op) {
                '×' -> n2[n2.lastIndex] = n2.last() * rhs
                '÷' -> { if (rhs == 0.0) return null; n2[n2.lastIndex] = n2.last() / rhs }
                else -> { o2 += op; n2 += rhs }
            }
        }
        var result = n2[0]
        o2.forEachIndexed { i, op -> result = if (op == '+') result + n2[i + 1] else result - n2[i + 1] }
        if (result < 0 || result.isNaN() || result.isInfinite()) return null
        return format(result, decimals)
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
