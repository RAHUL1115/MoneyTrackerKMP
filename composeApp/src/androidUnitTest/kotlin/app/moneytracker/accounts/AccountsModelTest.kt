package app.moneytracker.accounts

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The accounts tab and the screens that order, hide and restore accounts and groups. */
class AccountsModelTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun manage() = ManageAccountsModel(repo.accountStore, repo.io, scope)
    private fun accounts() = AccountsModel(repo.views, repo.accountStore, repo.settingsStore, repo.currencyStore, repo.txnStore, repo.today, repo.io, scope)
    private fun order() = repo.accountStore.accountsNow().map { it.id }
    private fun hidden(id: String) = repo.accountStore.accountsNow().first { it.id == id }.hidden

    @Test fun summaryShowsEveryAccountWithItsBalance() = runTest {
        repo.txnStore.create(TxnDraft(DraftType.INCOME, today, "acc_bank", categoryId = "cat_in_0", amount = 10_000))
        val summary = assertNotNull(accounts().summary.first { it != null })
        assertEquals(10_000L, summary.lines.first { it.account.id == "acc_bank" }.balance)
    }

    @Test fun netWorthTrendSplitsAssetsAndLiabilitiesAtEachMonthEnd() = runTest {
        repo.txnStore.create(TxnDraft(DraftType.INCOME, LocalDate(2026, 1, 10), "acc_bank", categoryId = "cat_in_0", amount = 10_000))
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, LocalDate(2026, 2, 5), "acc_card", categoryId = "cat_ex_0", amount = 3_000))
        // Accounts left out of totals don't count.
        repo.accountStore.saveAccount(repo.accountStore.accountsNow().first { it.id == "acc_cash" }.copy(includeInTotals = false))
        repo.txnStore.create(TxnDraft(DraftType.INCOME, LocalDate(2026, 3, 1), "acc_cash", categoryId = "cat_in_0", amount = 99))

        val points = accounts().netWorthTrend(3).first()

        assertEquals(
            listOf(
                NetWorthPoint(YearMonth(2026, 1), 10_000, 0),
                NetWorthPoint(YearMonth(2026, 2), 10_000, 3_000),
                NetWorthPoint(YearMonth(2026, 3), 10_000, 3_000),
            ),
            points,
        )
        assertEquals(7_000L, points.last().net)
    }

    @Test fun movingAnAccountSwapsItWithItsNeighbourInTheGroup() = runTest {
        repo.accountStore.saveAccount(Account("acc_bank2", "grp_bank", "Bank 2", sortOrder = 4))
        val model = manage()
        assertEquals(listOf("acc_cash", "acc_bank", "acc_card", "acc_savings", "acc_bank2"), order())

        model.moveAccount("acc_bank2", -1)
        assertEquals(listOf("acc_cash", "acc_bank2", "acc_card", "acc_savings", "acc_bank"), order())

        // Already first in its group: nothing moves.
        model.moveAccount("acc_bank2", -1)
        assertEquals(listOf("acc_cash", "acc_bank2", "acc_card", "acc_savings", "acc_bank"), order())
    }

    @Test fun movingAGroupStopsAtEitherEnd() = runTest {
        val model = manage()
        val before = repo.accountStore.groupsNow().map { it.id }
        model.moveGroup(0, -1)
        assertEquals(before, repo.accountStore.groupsNow().map { it.id })

        model.moveGroup(0, 1)
        assertEquals(listOf(before[1], before[0]) + before.drop(2), repo.accountStore.groupsNow().map { it.id })
    }

    @Test fun hidingAGroupCanBeUndone() = runTest {
        repo.accountStore.saveAccount(Account("acc_bank2", "grp_bank", "Bank 2", sortOrder = 4, hidden = true))
        val model = manage()

        val before = model.setGroupHidden("grp_bank", true)
        assertEquals(mapOf("acc_bank" to false, "acc_bank2" to true), before)
        assertTrue(hidden("acc_bank"))

        model.restoreHidden(before)
        assertFalse(hidden("acc_bank"))
        assertTrue(hidden("acc_bank2"))
    }

    @Test fun deletedAccountsCanBeRestored() = runTest {
        val model = manage()
        model.deleteAccount("acc_cash")
        assertEquals(listOf("acc_cash"), model.state.value.deletedAccounts.map { it.id })
        assertFalse("acc_cash" in order())

        model.restoreAccount("acc_cash")
        assertTrue(model.state.value.deletedAccounts.isEmpty())
        assertTrue("acc_cash" in order())
    }

    @Test fun onlyAnEmptyGroupCanBeDeleted() = runTest {
        val model = manage()
        var refused = false
        model.deleteGroup("grp_cash") { refused = true }
        assertTrue(refused)

        refused = false
        model.deleteGroup("grp_loan") { refused = true }
        assertFalse(refused)
        assertEquals(listOf("grp_loan"), model.state.value.deletedGroups.map { it.id })

        model.restoreGroup("grp_loan")
        assertTrue(repo.accountStore.groupsNow().any { it.id == "grp_loan" })
    }

    @Test fun aNewGroupGoesLastAndIsOnlyStoredOnSave() = runTest {
        val model = manage()
        val count = repo.accountStore.groupsNow().size
        val g = model.newGroup()
        assertEquals(AccountGroup(g.id, "", AccountType.OTHER, count), g)
        assertEquals(count, repo.accountStore.groupsNow().size)

        model.saveGroup(g.copy(name = "Wallets"))
        assertEquals("Wallets", repo.accountStore.groupsNow().last().name)
    }
}
