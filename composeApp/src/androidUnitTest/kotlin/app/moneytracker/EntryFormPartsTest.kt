package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.EntryChoices
import app.moneytracker.ui.EntryForm
import app.moneytracker.ui.EntryHints
import app.moneytracker.ui.ListenModel
import app.moneytracker.ui.ListenState
import app.moneytracker.ui.SmartModels
import app.moneytracker.ui.SpeechError
import app.moneytracker.ui.fresh.Step
import app.moneytracker.ui.fresh.accountTray
import app.moneytracker.ui.fresh.firstMissing
import app.moneytracker.ui.fresh.stepOrder
import app.moneytracker.ui.pressKey
import app.moneytracker.ui.suggestedCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What surrounds the entry form: hints while typing, the new layout's tray, the keypad and the listening sheet. */
class EntryFormPartsTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun hints() = EntryHints(repo.learningStore, repo.txnStore, repo.settingsStore, repo.today, Dispatchers.Unconfined, scope)

    private fun add(type: DraftType, note: String, category: String?, account: String = "acc_cash", to: String? = null) =
        repo.txnStore.create(TxnDraft(type, today, account, toAccountId = to, categoryId = category, amount = 1_000, note = note))

    // ───────────── hints

    @Test fun pastNotesComeFromEntriesOfTheSameKind() = runTest {
        add(DraftType.EXPENSE, "Coffee", "cat_ex_0")
        add(DraftType.EXPENSE, "Cola", "cat_ex_0")
        add(DraftType.INCOME, "Commission", "cat_in_0")
        val h = hints()
        assertEquals(setOf("Coffee", "Cola"), h.noteMatches("Co", DraftType.EXPENSE, 4).map { it.note }.toSet())
        assertEquals(listOf("Commission"), h.noteMatches("Co", DraftType.INCOME, 4).map { it.note })
        assertEquals(1, h.noteMatches("Co", DraftType.REFUND, 1).size)  // a refund looks among expenses
        assertTrue(h.noteMatches(" ", DraftType.EXPENSE, 4).isEmpty())
    }

    @Test fun learnedCategoryNeedsAFewLettersAndIsOfferedOnlyWhenItFits() = runTest {
        val h = hints()
        assertNull(h.learnedCategory("Pi", DraftType.EXPENSE))
        assertNull(h.learnedCategory("Pizza", DraftType.TRANSFER))
        val catById = repo.categoryStore.categoryById.current
        val blank = EntryForm(date = today)
        assertEquals("cat_ex_0", blank.suggestedCategory("cat_ex_0", catById))
        assertNull(blank.copy(categoryId = "cat_ex_1").suggestedCategory("cat_ex_0", catById))
        assertNull(blank.copy(type = DraftType.INCOME).suggestedCategory("cat_ex_0", catById))
    }

    // ───────────── the new layout's tray

    @Test fun trayFillsRowsInTheOrderOfTheEntry() {
        val f = EntryForm(date = today)
        assertEquals(listOf(Step.AMOUNT, Step.CATEGORY, Step.ACCOUNT, Step.NOTE), stepOrder(f, accountFirst = false))
        assertEquals(listOf(Step.ACCOUNT, Step.AMOUNT, Step.CATEGORY, Step.NOTE), stepOrder(f, accountFirst = true))
        assertEquals(listOf(Step.AMOUNT, Step.FROM, Step.TO, Step.NOTE), stepOrder(f.copy(type = DraftType.TRANSFER), accountFirst = false))
        assertEquals(Step.AMOUNT, firstMissing(f, null))
        assertEquals(Step.CATEGORY, firstMissing(f, 100))
        assertNull(firstMissing(f.copy(categoryId = "c", accountId = "a", note = "n"), 100))
    }

    @Test fun accountTrayListsRecentAccountsFirstAndNeverTheSourceAsDestination() {
        val choices = EntryChoices(repo.accountStore.accountsNow(), repo.accountStore.groupsNow(), emptyList(), emptyMap(), emptyList(), false)
        val recent = listOf(add(DraftType.EXPENSE, "a", "cat_ex_0", account = "acc_card")).mapNotNull { repo.txnStore.txn(it) }
        val f = EntryForm(date = today, accountId = "acc_bank")
        val from = accountTray(f, forTo = false, usual = "acc_cash", choices, recent, openTab = null)
        assertEquals("recent", from.tab)
        assertEquals(listOf("acc_cash", "acc_bank", "acc_card"), from.shown.map { it.id })
        val to = accountTray(f.copy(type = DraftType.TRANSFER), forTo = true, usual = null, choices, emptyList(), openTab = null)
        assertFalse(to.tabs.any { it.first == "recent" })
        assertTrue(to.shown.none { it.id == "acc_bank" })
    }

    @Test fun keypadKeepsOneOperatorAndTheCurrencysDecimals() {
        val f = CurrencyFormat()
        assertEquals("12+", pressKey("12−", "+", f))
        assertEquals("0.", pressKey("", ".", f))
        assertEquals("1.25", pressKey("1.25", "9", f))
        assertEquals("5", pressKey("0", "5", f))
        assertEquals("30", pressKey("12+18", "=", f).substringBefore('.'))
    }

    // ───────────── listening sheet

    private val noModels = object : SmartModels { override fun readSmartModel(name: String): ByteArray? = null }

    private fun listen(typing: Boolean = false) = ListenModel(
        repo.learningStore, repo.accountStore, repo.categoryStore, CurrencyFormat(), noModels, repo.today, Dispatchers.Unconfined, scope, typing,
    )

    @Test fun silenceIsAnErrorAndRetryStartsOver() {
        val m = listen()
        assertFalse(m.onFinal(""))
        assertEquals(ListenState.ERROR, m.progress.state)
        assertEquals(SpeechError.NO_MATCH, m.progress.error)
        m.retry()
        assertEquals(1, m.progress.attempt)
        m.listening()
        assertEquals(ListenState.LISTENING, m.progress.state)
        m.onPartial("200 fuel")
        m.onLevel(0.5f)
        assertEquals(0.5f, m.progress.level)
        assertTrue(m.onFinal(""))                                       // the partial words count
        assertEquals("200 fuel", m.progress.heard)
        assertEquals(ListenState.DONE, m.progress.state)
    }

    @Test fun typingIgnoresTheMicrophone() {
        val m = listen(typing = true)
        m.onPartial("noise")
        assertFalse(m.onFinal("noise"))
        assertEquals("", m.progress.heard)
        m.onTyped("1200 shoes")
        m.startSpeaking("1200 shoes")
        assertFalse(m.progress.typing)
        assertEquals("1200 shoes", m.progress.heard)
    }
}
