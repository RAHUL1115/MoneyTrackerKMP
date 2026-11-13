package app.moneytracker

import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A transfer's fee is the expense row in a transfer group; nothing else counts as one. */
class TransferFeeTest {
    private fun txn(kind: TxnKind, transferGroupId: String?) =
        Txn("t", kind, LocalDate(2026, 11, 1), 0L, "acc", amount = 100, transferGroupId = transferGroupId)

    @Test fun onlyTheExpenseInATransferGroupIsAFee() =
        assertEquals(setOf(TxnKind.EXPENSE), TxnKind.entries.filter { txn(it, "group").isTransferFee }.toSet())

    @Test fun anExpenseOutsideATransferIsNotAFee() = assertFalse(txn(TxnKind.EXPENSE, null).isTransferFee)
}
