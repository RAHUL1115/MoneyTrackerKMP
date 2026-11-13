package app.moneytracker.data

import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.LocalDate

/** Input from the transaction editor. [amount] and [fee] are minor units of [currency] (null = main). */
data class TxnDraft(
    val type: DraftType,
    val date: LocalDate,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amount: Long,
    val fee: Long = 0,
    val note: String = "",
    val memo: String = "",
    val installments: Int = 1,
    val repeat: RepeatType? = null,
    val repeatEnd: LocalDate? = null,
    val currency: String? = null,
    val tagIds: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
    val timeOfDay: Int? = null,
    val origin: String = "",
    /** Exchange rate to use for [currency]; null = today's. Set when editing so an entry keeps the rate it was made with. */
    val rate: Double? = null,
)

/** [REFUND] is money back into an expense category; it is saved as an expense with a negative amount. */
enum class DraftType { INCOME, EXPENSE, TRANSFER, REFUND }

internal fun DraftType.primaryKind() = when (this) {
    DraftType.INCOME -> TxnKind.INCOME
    DraftType.EXPENSE, DraftType.REFUND -> TxnKind.EXPENSE
    DraftType.TRANSFER -> TxnKind.TRANSFER_OUT
}

/** Stored amounts are negative for refunds; the editor always works with positive amounts. */
val DraftType.sign: Int get() = if (this == DraftType.REFUND) -1 else 1
