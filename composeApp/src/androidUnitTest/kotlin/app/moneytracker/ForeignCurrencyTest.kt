package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.Currency
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.totals
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Other currencies: exchange rates, the rate an entry keeps, and accounts kept in a foreign currency. */
class ForeignCurrencyTest {
    private val today = LocalDate.parse("2026-03-15")
    private lateinit var repo: MoneyRepository
    private val march = Periods.month(YearMonth(2026, 3), 1)

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.freshStart.seedIfEmpty()
    }

    @Test fun exchangeRatesParseAndInvert() {
        val q = app.moneytracker.data.ExchangeRates.parse("""{"amount":1.0,"base":"INR","date":"2026-11-03","rates":{"EUR":0.01025,"USD":0.0119}}""")!!
        assertEquals("2026-11-03", q.date)
        assertEquals(0.0119, q.perMain.getValue("USD"))
        assertEquals(84.0336, app.moneytracker.data.ExchangeRates.mainPerUnit(0.0119))    // 1 USD in rupees
        assertEquals(0.011905, app.moneytracker.data.ExchangeRates.mainPerUnit(84.0))      // under 1: six decimals
        assertEquals(null, app.moneytracker.data.ExchangeRates.parse("""{"message":"not found"}"""))
        assertEquals("https://api.frankfurter.dev/v1/latest?base=INR&symbols=USD,EUR",
            app.moneytracker.data.ExchangeRates.url("inr", listOf("usd", "EUR")))
    }

    @Test fun updateRatesSavesNewRatesAndReportsMissing() = runTest {
        repo.settingsStore.putSetting(Prefs.CURRENCY_CODE.key, "INR")
        repo.currencyStore.saveCurrency(Currency("USD", "$", 2, 80.0))
        repo.currencyStore.saveCurrency(Currency("AED", "د.إ", 2, 22.0))
        var asked = ""
        val r = repo.currencyStore.updateRates { url -> asked = url; """{"amount":1.0,"base":"INR","date":"2026-11-03","rates":{"USD":0.0119}}""" }
        assertTrue(asked.contains("base=INR") && asked.contains("USD") && asked.contains("AED"))
        assertEquals(app.moneytracker.data.RatesUpdate.Done("2026-11-03", listOf("USD"), listOf("AED")), r)
        assertEquals(84.0336, repo.currencyStore.currencies.first().first { it.code == "USD" }.rate)
        assertEquals(22.0, repo.currencyStore.currencies.first().first { it.code == "AED" }.rate)                // no ECB rate: unchanged
        assertEquals("2026-11-03", repo.settingsStore.settingNow(Prefs.RATES_DATE.key))
        assertEquals(app.moneytracker.data.RatesUpdate.Failed, repo.currencyStore.updateRates { null })           // offline
    }

    @Test fun entryKeepsItsRateWhenRatesChangeAndItIsEdited() = runTest {
        repo.currencyStore.saveCurrency(Currency("USD", "$", 2, 80.0))
        val id = repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 1_000, currency = "USD", note = "Lunch"))  // $10.00
        val made = repo.txnStore.txn(id)!!
        assertEquals(80.0, made.rate)
        assertEquals(80_000L, made.amount)                                                        // ₹800.00
        repo.currencyStore.saveCurrency(Currency("USD", "$", 2, 95.0))                                          // rates update later
        val edited = repo.txnStore.txn(repo.txnStore.update(made, repo.txnStore.draftFrom(made).copy(note = "Team lunch")))!!
        assertEquals(80.0, edited.rate)                                                           // edit keeps the old rate
        assertEquals(80_000L, edited.amount)
        val fresh = repo.txnStore.txn(repo.txnStore.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0_1", amount = 1_000, currency = "USD")))!!
        assertEquals(95.0, fresh.rate)                                                            // new entry: today's rate
        assertEquals(95_000L, fresh.amount)
        // An entry from before v7 (no stored rate) keeps the rate implied by its two amounts.
        assertEquals(80.0, repo.entryWriter.rateOf(made.copy(rate = null)))
    }

    @Test fun foreignCurrencyAccountKeepsOwnBalanceAndReportsInMain() = runTest {
        repo.currencyStore.saveCurrency(Currency("USD", "$", 2, 80.0))
        repo.accountStore.saveAccount(Account("acc_usd", "grp_bank", "USD Account", currency = "USD"))
        repo.txnStore.create(TxnDraft(DraftType.INCOME, today, "acc_usd", amount = 10_000, currency = "USD"))     // $100.00
        assertEquals(10_000L, repo.accountStore.balances.first()["acc_usd"])                                         // account currency
        assertEquals(800_000L, totals(repo.reportStore.reportRows(march).first()).income)                            // ₹8,000.00
        repo.txnStore.create(TxnDraft(DraftType.TRANSFER, today, "acc_usd", toAccountId = "acc_bank", amount = 5_000, currency = "USD"))
        assertEquals(5_000L, repo.accountStore.balances.first()["acc_usd"])
        assertEquals(400_000L, repo.accountStore.balances.first()["acc_bank"])                                       // ₹4,000 arrived
    }
}
