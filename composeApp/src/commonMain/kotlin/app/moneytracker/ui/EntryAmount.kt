package app.moneytracker.ui

import app.moneytracker.domain.Calc
import app.moneytracker.domain.CurrencyFormat

// The calculator rules behind both layouts' amount keypads.

/** The amount text after pressing [k] on the calculator keypad. */
internal fun pressKey(text: String, k: String, currency: CurrencyFormat): String {
    val ops = Calc.OPS
    // The number being typed is whatever follows the last operator.
    val head = text.dropLastWhile { it !in ops }
    val num = text.substring(head.length)
    return when (k) {
        "⌫" -> text.dropLast(1)
        "C" -> ""
        "=" -> Calc.evaluate(text, currency.decimals) ?: text
        "+", "−", "×", "÷" -> when {
            text.isEmpty() -> text
            text.last() in ops -> text.dropLast(1) + k          // change the operator
            text.last() == '.' -> text.dropLast(1) + k
            else -> text + k
        }
        "." -> if (currency.decimals == 0 || num.contains('.')) text else text + (if (num.isEmpty()) "0." else ".")
        else -> typeDigit(text, head, num, k, currency)
    }
}

private fun typeDigit(text: String, head: String, num: String, k: String, currency: CurrencyFormat): String {
    val candidate = if (num == "0") head + k else text + k
    val newNum = candidate.substring(head.length)
    val frac = newNum.substringAfter('.', "")
    return if (newNum.contains('.') && frac.length > currency.decimals) text
    else if (newNum.substringBefore('.').length > 12) text else candidate
}

/** A half-typed calculation ("120+30") counts as its result. */
internal fun settleAmount(text: String, currency: CurrencyFormat): String =
    if (Calc.hasOperator(text)) Calc.evaluate(text, currency.decimals) ?: text else text
