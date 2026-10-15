package app.moneytracker.ui

import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CurrencyFormat
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Turns a spoken phrase into an editor draft: "spent 250 on lunch", "received 5000 salary yesterday",
 * "transfer 1000". The first number is the amount; the rest (minus filler words) becomes the note.
 */
object VoiceParser {
    private val incomeWords = listOf("received", "receive", "got", "earned", "income", "salary", "credited", "refund")
    private val transferWords = listOf("transfer", "transferred", "moved")
    private val filler = setOf("spent", "spend", "paid", "pay", "for", "on", "at", "rupees", "rs", "inr", "dollars", "bucks",
        "received", "receive", "got", "earned", "income", "credited", "transfer", "transferred", "moved", "yesterday", "today",
        "a", "an", "the", "of", "rupee", "₹")

    fun parse(text: String, today: LocalDate, currency: CurrencyFormat): TxnDraft {
        val lower = text.lowercase()
        val number = Regex("""\d[\d,]*(\.\d+)?""").find(lower)?.value?.replace(",", "")
        val amount = number?.let { currency.parse(it.take(15)) } ?: 0L
        val type = when {
            transferWords.any { lower.contains(it) } -> DraftType.TRANSFER
            incomeWords.any { lower.contains(it) } -> DraftType.INCOME
            else -> DraftType.EXPENSE
        }
        val date = when {
            "day before yesterday" in lower -> today.minus(DatePeriod(days = 2))
            "yesterday" in lower -> today.minus(DatePeriod(days = 1))
            else -> today
        }
        val note = lower.replace(Regex("""\d[\d,]*(\.\d+)?"""), " ")
            .split(Regex("""\s+""")).filter { it.isNotBlank() && it !in filler && it != "day" && it != "before" }
            .joinToString(" ").replaceFirstChar { it.uppercase() }
        return TxnDraft(type, date, "", amount = amount, note = note)
    }
}
