package app.moneytracker.domain

import app.moneytracker.domain.TxnKind.ADJUST_DOWN
import app.moneytracker.domain.TxnKind.ADJUST_UP
import app.moneytracker.domain.TxnKind.EXPENSE
import app.moneytracker.domain.TxnKind.INCOME
import app.moneytracker.domain.TxnKind.TRANSFER_IN
import app.moneytracker.domain.TxnKind.TRANSFER_OUT
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each question a caller asks about a kind, answered for every kind so a new kind has to be placed. */
class TxnKindTest {
    private fun kindsWhere(question: (TxnKind) -> Boolean) = TxnKind.entries.filter(question).toSet()

    @Test fun signRaisesForIncomeTransferInAndAdjustUp() {
        assertEquals(
            mapOf(INCOME to 1, EXPENSE to -1, TRANSFER_OUT to -1, TRANSFER_IN to 1, ADJUST_UP to 1, ADJUST_DOWN to -1),
            TxnKind.entries.associateWith { it.sign },
        )
    }

    @Test fun transferIsEitherLeg() = assertEquals(setOf(TRANSFER_OUT, TRANSFER_IN), kindsWhere { it.isTransfer })

    @Test fun adjustmentIsEitherDirection() = assertEquals(setOf(ADJUST_UP, ADJUST_DOWN), kindsWhere { it.isAdjustment })

    @Test fun incomeOrExpenseIsOnlyThoseTwo() = assertEquals(setOf(INCOME, EXPENSE), kindsWhere { it.isIncomeOrExpense })

    @Test fun everyKindButTheIncomingTransferLegIsListed() =
        assertEquals(setOf(INCOME, EXPENSE, TRANSFER_OUT, ADJUST_UP, ADJUST_DOWN), kindsWhere { it.isListed })

    @Test fun raisesBalanceIsMoneyIn() = assertEquals(setOf(INCOME, TRANSFER_IN, ADJUST_UP), kindsWhere { it.raisesBalance })

    @Test fun lowersBalanceIsMoneyOut() = assertEquals(setOf(EXPENSE, TRANSFER_OUT, ADJUST_DOWN), kindsWhere { it.lowersBalance })
}
