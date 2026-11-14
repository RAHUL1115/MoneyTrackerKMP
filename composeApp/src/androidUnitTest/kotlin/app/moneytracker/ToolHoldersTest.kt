package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.app.Screen
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.RatesUpdate
import app.moneytracker.data.Template
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Currency
import app.moneytracker.domain.Tag
import app.moneytracker.insights.classic.StatsDetailModel
import app.moneytracker.lists.TagsModel
import app.moneytracker.lists.classic.BookmarksModel
import app.moneytracker.settings.CurrenciesModel
import app.moneytracker.settings.classic.pressCalcKey
import app.moneytracker.settings.currencyInputValid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The holders behind the tool screens both layouts share: currencies, tags, bookmarks, the stats drill-down, the calculator. */
class ToolHoldersTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun currencies(reply: String? = null) =
        CurrenciesModel(repo.currencyStore, repo.settingsStore, { reply }, Dispatchers.Unconfined, scope)

    @Test fun currenciesAreSavedAndDeleted() = runTest {
        val model = currencies()
        model.save(Currency("USD", "$", 2, 83.0, 0))
        assertEquals(listOf("USD"), model.state.first { it.currencies.isNotEmpty() }.currencies.map { it.code })
        model.delete("USD")
        assertTrue(model.state.first { it.currencies.isEmpty() }.currencies.isEmpty())
    }

    @Test fun rateUpdateReportsWhatHappened() = runTest {
        val model = currencies(reply = null)
        model.updateRates()
        assertEquals(RatesUpdate.NothingToUpdate, model.state.value.lastUpdate)
        model.save(Currency("USD", "$", 2, 83.0, 0))
        model.updateRates()
        val done = model.state.first { it.lastUpdate == RatesUpdate.Failed }
        assertFalse(done.updating)
    }

    @Test fun turningAutoUpdateOnSavesItAndUpdatesNow() {
        val model = currencies()
        model.setAutoUpdate(false)
        assertFalse(repo.settingsStore.settingNow(Prefs.RATES_AUTO))
        model.setAutoUpdate(true)
        assertTrue(repo.settingsStore.settingNow(Prefs.RATES_AUTO))
        assertEquals(RatesUpdate.NothingToUpdate, model.state.value.lastUpdate)
    }

    @Test fun currencyInputNeedsCodeSymbolDecimalsAndRate() {
        assertTrue(currencyInputValid(" usd ", "$", "2", "83.5"))
        assertFalse(currencyInputValid("US", "$", "2", "83.5"))
        assertFalse(currencyInputValid("USD", "$", "4", "83.5"))
        assertFalse(currencyInputValid("USD", "$", "2", "0"))
    }

    @Test fun tagsAreCreatedRenamedAndDeleted() = runTest {
        val model = TagsModel(repo.tagStore, Dispatchers.Unconfined, scope)
        model.save(Tag("", "", 0), "Goa trip")
        val tag = model.tags.first { tags -> tags.any { it.name == "Goa trip" } }.single { it.name == "Goa trip" }
        assertTrue(tag.id.isNotEmpty())
        var picked: String? = null
        model.tagNamed("Goa trip") { picked = it }
        assertEquals(tag.id, picked)
        model.delete(tag.id)
        assertTrue(model.tags.first { tags -> tags.none { it.id == tag.id } }.none { it.id == tag.id })
    }

    @Test fun aBookmarkBecomesAnEntryDatedToday() = runTest {
        repo.bookmarkStore.saveTemplate(Template("b1", "Lunch", DraftType.EXPENSE, "acc_cash", null, "cat_ex_0", 25_000, note = "Thali"))
        val model = BookmarksModel(repo.bookmarkStore, repo.categoryStore, repo.accountStore, { today }, Dispatchers.Unconfined, scope)
        val b = model.state.first { it.bookmarks.isNotEmpty() }.bookmarks.single()
        assertEquals(TxnDraft(DraftType.EXPENSE, today, "acc_cash", null, "cat_ex_0", 25_000, note = "Thali"), model.draftOf(b))
        model.delete("b1")
        assertTrue(model.state.first { it.bookmarks.isEmpty() }.bookmarks.isEmpty())
    }

    @Test fun statsDetailSplitsByCategoryAndTracksEightMonths() = runTest {
        val sub = repo.categoryStore.categoriesNow().first { it.parentId == "cat_ex_0" }.id
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0", amount = 1_000))
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = sub, amount = 3_000))
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, LocalDate.parse("2026-01-10"), "acc_cash", categoryId = sub, amount = 500))
        repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_1", amount = 9_999))
        val screen = Screen.StatsDetail("cat_ex_0", income = false, from = LocalDate.parse("2026-03-01"), to = LocalDate.parse("2026-03-31"), title = "Food")
        val model = StatsDetailModel(screen, repo.txnStore, repo.categoryStore, repo.accountStore, repo.settingsStore.periodSettingsNow(), scope)
        val s = model.state.value
        assertEquals(mapOf("cat_ex_0" to 1_000L, sub to 3_000L), s.bySub)
        assertEquals(4_000L, s.total)
        assertEquals(2, s.txns.size)
        assertEquals(8, s.months.size)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 500L, 0L, 4_000L), s.trend)
    }

    @Test fun calculatorKeysBuildAnExpression() {
        fun type(vararg keys: String) = keys.fold("") { e, k -> pressCalcKey(e, k, 2) }
        assertEquals("12+", type("1", "2", "×", "+"))   // an operator replaces a trailing one
        assertEquals("−", type("−"))                     // only minus can start an expression
        assertEquals("", type("+"))
        assertEquals("1.5", type("1", ".", ".", "5"))
        assertEquals("1", type("1", "2", "⌫"))
        assertEquals("", type("1", "2", "C"))
    }
}
