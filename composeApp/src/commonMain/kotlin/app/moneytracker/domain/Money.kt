package app.moneytracker.domain

import kotlin.math.absoluteValue

/** Currency display settings; amounts are stored as Long minor units (10^decimals per major unit). */
data class CurrencyFormat(val symbol: String = "₹", val decimals: Int = 2, val symbolAfter: Boolean = false) {
    private val factor: Long get() = pow10(decimals)

    fun format(minor: Long, showSign: Boolean = false, withSymbol: Boolean = true): String {
        val sign = when {
            minor < 0 -> "-"
            showSign && minor > 0 -> "+"
            else -> ""
        }
        val abs = minor.absoluteValue
        val major = groupThousands((abs / factor).toString())
        val fraction = if (decimals > 0) "." + (abs % factor).toString().padStart(decimals, '0') else ""
        return when {
            !withSymbol -> "$sign$major$fraction"
            symbolAfter -> "$sign$major$fraction $symbol"
            else -> "$sign$symbol $major$fraction"
        }
    }

    /** Parses user input like "1,234.5" → 123450 (for 2 decimals). Returns null if invalid. */
    fun parse(input: String): Long? {
        val s = input.trim().replace(",", "").replace(symbol, "")
        if (s.isEmpty()) return null
        val regex = if (decimals > 0) Regex("""^\d+(\.\d{0,$decimals})?$""") else Regex("""^\d+$""")
        if (!regex.matches(s)) return null
        val parts = s.split('.')
        val major = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1).orEmpty().padEnd(decimals, '0').ifEmpty { "0" }.toLong()
        return major * factor + frac
    }

    /** Minor units → plain editable text without symbol or grouping. */
    fun toInput(minor: Long): String {
        if (decimals == 0) return minor.toString()
        val frac = (minor % factor).toString().padStart(decimals, '0').trimEnd('0')
        return (minor / factor).toString() + if (frac.isEmpty()) "" else ".$frac"
    }

    private fun groupThousands(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    companion object {
        fun pow10(n: Int): Long { var r = 1L; repeat(n) { r *= 10 }; return r }
    }
}

/**
 * Currency conversion between minor units. A null [Currency] means the main currency.
 * Rates are "main-currency units per 1 unit", captured on each transaction at entry time.
 */
object Fx {
    fun toMain(minor: Long, from: Currency?, mainDecimals: Int): Long {
        if (from == null) return minor
        val major = minor.toDouble() / CurrencyFormat.pow10(from.decimals)
        return kotlin.math.round(major * from.rate * CurrencyFormat.pow10(mainDecimals)).toLong()
    }

    fun fromMain(mainMinor: Long, to: Currency?, mainDecimals: Int): Long {
        if (to == null) return mainMinor
        val major = mainMinor.toDouble() / CurrencyFormat.pow10(mainDecimals)
        return kotlin.math.round(major / to.rate * CurrencyFormat.pow10(to.decimals)).toLong()
    }

    /** Converts between any two currencies through the main currency. */
    fun convert(minor: Long, from: Currency?, to: Currency?, mainDecimals: Int): Long =
        if (from?.code == to?.code) minor else fromMain(toMain(minor, from, mainDecimals), to, mainDecimals)
}
