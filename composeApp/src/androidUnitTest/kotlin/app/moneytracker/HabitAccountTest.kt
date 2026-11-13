package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Currency
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Remember usual accounts" and the amount brought back by note autocomplete. */
class HabitAccountTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    private fun add(type: DraftType, account: String, category: String?, amount: Long = 100, daysAgo: Int = 0, note: String = "",
                    currency: String? = null, repeat: RepeatType? = null, installments: Int = 1) =
        repo.txnStore.create(TxnDraft(type, today.minus(DatePeriod(days = daysAgo)), account, categoryId = category, amount = amount, note = note,
            currency = currency, repeat = repeat, installments = installments))

    @Test fun categoryHabitBeatsKindHabitAndKindsAreSeparate() {
        repeat(3) { add(DraftType.EXPENSE, "acc_cash", "cat_ex_0") }
        add(DraftType.EXPENSE, "acc_card", "cat_ex_1")
        add(DraftType.INCOME, "acc_bank", "cat_in_0")

        assertEquals("acc_cash", repo.learningStore.habitAccount(TxnKind.EXPENSE, null))
        assertEquals("acc_card", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_1"))
        assertEquals("acc_cash", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_2"))   // no history with it: the kind's habit
        assertEquals("acc_bank", repo.learningStore.habitAccount(TxnKind.INCOME, null))
    }

    @Test fun olderHistoryFadesAndOverAYearGivesNothing() {
        assertNull(repo.learningStore.habitAccount(TxnKind.EXPENSE, null))
        add(DraftType.EXPENSE, "acc_bank", "cat_ex_3", daysAgo = 400)
        assertNull(repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_3"))               // only the last year counts
        add(DraftType.EXPENSE, "acc_card", "cat_ex_1", daysAgo = 60)
        assertEquals("acc_card", repo.learningStore.habitAccount(TxnKind.EXPENSE, null))       // someone who adds entries rarely still gets one
        repeat(2) { add(DraftType.EXPENSE, "acc_cash", "cat_ex_1", daysAgo = 200) }
        assertEquals("acc_card", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_1")) // one recent use outweighs two old ones
    }

    @Test fun noteAndCategoryPickTheAccountTogether() {
        // Food usually on the card, but fuel (also Transport-ish notes) on its own prepaid account.
        repeat(4) { add(DraftType.EXPENSE, "acc_card", "cat_ex_0", note = "burger", daysAgo = it + 1) }
        repeat(3) { add(DraftType.EXPENSE, "acc_cash", "cat_ex_1", note = "for fuel", daysAgo = it + 1) }
        add(DraftType.EXPENSE, "acc_card", "cat_ex_1", note = "taxi", daysAgo = 1)
        assertEquals("acc_card", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_0", note = "pizza"))
        assertEquals("acc_cash", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_1", note = "fuel"))
        assertEquals("acc_card", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_1", note = "taxi"))
    }

    @Test fun hiddenAccountsAreSkippedForTheNextOne() {
        repeat(3) { add(DraftType.EXPENSE, "acc_cash", "cat_ex_0") }
        add(DraftType.EXPENSE, "acc_bank", "cat_ex_0")
        repo.accountStore.setAccountHidden("acc_cash", true)
        assertEquals("acc_bank", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_0"))
    }

    @Test fun recurringInstallmentsAndRefundsAreNotHabits() {
        add(DraftType.EXPENSE, "acc_cash", "cat_ex_0")
        repeat(3) { add(DraftType.EXPENSE, "acc_bank", "cat_ex_0", repeat = RepeatType.MONTHLY) }
        add(DraftType.EXPENSE, "acc_card", "cat_ex_0", amount = 3_000, installments = 3)
        repeat(3) { add(DraftType.REFUND, "acc_savings", "cat_ex_0") }
        assertEquals("acc_cash", repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_0"))
    }

    @Test fun settingOffSuggestsNothing() {
        add(DraftType.EXPENSE, "acc_cash", "cat_ex_0")
        repo.settingsStore.putSetting(Prefs.HABIT_ACCOUNT.key, "0")
        assertNull(repo.learningStore.habitAccount(TxnKind.EXPENSE, "cat_ex_0"))
        assertNull(repo.learningStore.habitAccount(TxnKind.EXPENSE, null))
    }

    @Test fun noteSuggestionBringsBackTheLatestAmountAsEntered() {
        repo.currencyStore.saveCurrency(Currency("USD", "$", 2, 83.0))
        add(DraftType.EXPENSE, "acc_cash", "cat_ex_0", amount = 600, note = "Netflix")
        Thread.sleep(2)
        add(DraftType.EXPENSE, "acc_cash", "cat_ex_0", amount = 649, note = "Netflix")
        add(DraftType.EXPENSE, "acc_card", "cat_ex_1", amount = 12_00, note = "Spotify", currency = "USD")
        add(DraftType.REFUND, "acc_card", "cat_ex_1", amount = 250, note = "Shoe return")

        val s = repo.learningStore.noteSuggestions("N").single()
        assertEquals(649, s.amount); assertNull(s.currency)
        val usd = repo.learningStore.noteSuggestions("Sp").single()
        assertEquals(12_00, usd.amount); assertEquals("USD", usd.currency)
        assertEquals(250, repo.learningStore.noteSuggestions("Sh").single().amount)
    }
}
