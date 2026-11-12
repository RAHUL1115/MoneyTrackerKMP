package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.ai.SmartParser
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.SmartEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Smart entry learns from the person's own saved entries: words the model can't know follow their past choices, on the phone. */
class SmartEntryLearningTest {
    private val today = LocalDate.parse("2026-11-08")
    private lateinit var repo: MoneyRepository
    private val parser by lazy { SmartParser.load { File("src/androidMain/assets/smart", it).readBytes() } }
    private val fmt = CurrencyFormat()

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
    }

    private fun guess(text: String) = runBlocking {
        SmartEntry.guess(parser, text, today, fmt, repo.categoriesNow(), repo.accountsNow(),
            learned = { repo.suggest(it) },
            usualAccount = { kind, category, note, amount -> repo.habitAccount(kind, category, note = note, amount = amount.takeIf { it > 0 }) })
    }

    @Test fun pastNotesSteerTheCategoryAndAccount() {
        val expense = repo.categoriesNow().filter { it.kind == CategoryKind.EXPENSE && it.parentId == null }
        val before = guess("zorbles 120")
        // Pick a category the model did NOT choose, and teach it the way the Add screen does on save.
        val mine = expense.first { it.id != before.draft.categoryId }
        val account = repo.accountsNow().first { !it.hidden }
        repeat(2) {
            val d = TxnDraft(DraftType.EXPENSE, today, account.id, categoryId = mine.id, amount = 12000, note = "zorbles")
            repo.create(d); repo.learnFromDraft(d)
        }
        val after = guess("zorbles 120")
        assertEquals(mine.id, after.draft.categoryId, "learned category: ${after.categoryName}")
        assertTrue(after.categoryLearned)
        assertEquals(account.id, after.draft.accountId, "learned account")
        assertEquals("Zorbles", after.draft.note)
        assertEquals(12000L, after.draft.amount)
    }

    @Test fun voiceEntryGetsTheCategorysUsualAccount() {
        // "500 for burger": Food is understood, and the account is where food spending usually goes, not the overall habit.
        val food = guess("500 for burger").draft.categoryId!!
        val foodMain = repo.categoriesNow().first { it.id == food }.let { it.parentId ?: it.id }
        val other = repo.categoriesNow().first { it.kind == CategoryKind.EXPENSE && it.parentId == null && it.id != foodMain }
        repeat(3) { repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = food, amount = 300_00, note = "pizza")) }
        repeat(6) { repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_bank", categoryId = other.id, amount = 900_00, note = "stuff")) }
        val g = guess("500 for burger")
        assertEquals(food, g.draft.categoryId)
        assertEquals("acc_card", g.draft.accountId)
        assertTrue(g.accountGuessed)
    }

    @Test fun anAccountSaidOutLoudBeatsHistoryAndNoHistoryMeansTheModel() {
        val plain = guess("50 for dahi puri")
        assertFalse(plain.categoryLearned)
        assertEquals("Dahi puri", plain.draft.note)
        assertEquals(5000L, plain.draft.amount)
    }
}
