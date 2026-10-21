package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Note
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test

/**
 * Builds a made-up dataset for store screenshots (no real accounts or numbers). Skipped unless DEMO_DB_OUT is set:
 *   DEMO_DB_OUT=/tmp/demo.db DEMO_TODAY=2026-10-21 ./gradlew :composeApp:testDebugUnitTest --tests '*DemoData*'
 * Then restore the file in the app (More › Backup › Restore file).
 */
class DemoData {
    @Test fun writeDemoDatabase() {
        val out = System.getenv("DEMO_DB_OUT") ?: return
        val today = LocalDate.parse(System.getenv("DEMO_TODAY") ?: "2026-10-21")
        File(out).delete()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$out", Properties(), MoneyDatabase.Schema)
        val repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
        val rnd = Random(7)
        var clock = 0
        fun add(type: DraftType, date: LocalDate, account: String, amount: Long, cat: String? = null, note: String = "", to: String? = null,
                repeat: RepeatType? = null) {
            clock++
            repo.create(TxnDraft(type, date, account, to, cat, amount * 100, note = note, repeat = repeat,
                timeOfDay = 8 * 60 + (clock * 47) % (13 * 60)))
        }

        val start = YearMonth.of(today).plus(-2)
        // Monthly basics (rent and salary repeat, so future months show the upcoming "Repeat" row).
        add(DraftType.INCOME, LocalDate(start.year, start.month, 1), "acc_bank", 85_000, "cat_in_0", "Salary", repeat = RepeatType.MONTHLY)
        add(DraftType.EXPENSE, LocalDate(start.year, start.month, 3), "acc_bank", 22_000, "cat_ex_2_0", "Rent", repeat = RepeatType.MONTHLY)
        var d = LocalDate(start.year, start.month, 1)
        while (d <= today) {
            val day = d.dayOfMonth
            if (day == 5) add(DraftType.TRANSFER, d, "acc_bank", 10_000, note = "Monthly saving", to = "acc_savings")
            if (day == 8) add(DraftType.EXPENSE, d, "acc_bank", 1_850 + rnd.nextInt(400).toLong(), "cat_ex_2_1", "Electricity")
            if (day == 12) add(DraftType.EXPENSE, d, "acc_card", 799, "cat_ex_5", "Streaming")
            if (day == 15) add(DraftType.TRANSFER, d, "acc_bank", 3_000, note = "ATM", to = "acc_cash")
            if (day == 20) add(DraftType.TRANSFER, d, "acc_bank", 6_500, note = "Card bill", to = "acc_card")
            if (day % 3 == 0) add(DraftType.EXPENSE, d, "acc_card", 600 + rnd.nextInt(1_400).toLong(), "cat_ex_0_0", listOf("Supermarket", "Vegetables", "Groceries").random(rnd))
            if (day % 4 == 1) add(DraftType.EXPENSE, d, "acc_cash", 120 + rnd.nextInt(180).toLong(), "cat_ex_0_2", "Coffee")
            if (day % 5 == 2) add(DraftType.EXPENSE, d, "acc_card", 450 + rnd.nextInt(900).toLong(), "cat_ex_0_1", listOf("Dinner", "Lunch with team", "Pizza").random(rnd))
            if (day % 7 == 4) add(DraftType.EXPENSE, d, "acc_card", 1_500 + rnd.nextInt(700).toLong(), "cat_ex_1_0", "Fuel")
            if (day % 6 == 3) add(DraftType.EXPENSE, d, "acc_cash", 80 + rnd.nextInt(220).toLong(), "cat_ex_1_2", "Taxi")
            if (day == 18) add(DraftType.EXPENSE, d, "acc_card", 2_000 + rnd.nextInt(3_000).toLong(), "cat_ex_3_0", "Clothes")
            if (day == 24 && d.monthNumber % 2 == 0) add(DraftType.EXPENSE, d, "acc_cash", 650, "cat_ex_4_1", "Doctor")
            d = d.plus(DatePeriod(days = 1))
        }
        add(DraftType.INCOME, today.minus(DatePeriod(days = 9)), "acc_bank", 4_500, "cat_in_3", "Interest")
        add(DraftType.EXPENSE, today.minus(DatePeriod(days = 6)), "acc_card", 3_200, "cat_ex_7", "Birthday gift")

        // Budgets, a note and a detected bank message waiting for review.
        val ym = YearMonth.of(today)
        repo.setBudget("", ym.plus(-2), 45_000 * 100)
        repo.setBudget("cat_ex_0", ym.plus(-2), 15_000 * 100)
        repo.setBudget("cat_ex_1", ym.plus(-2), 8_000 * 100)
        repo.setBudget("cat_ex_5", ym.plus(-2), 2_000 * 100)
        val noon = today.toEpochDays() * 86_400_000L
        repo.saveNote(Note("note_demo", "Trip to Goa", "Flights booked. Keep ₹15,000 aside for hotel and food.", 2, true, noon, noon))
        repo.ingestMessage("SMS", "AX-HDFCBK", "Rs.1,240.00 spent on HDFC Bank Card x4821 at BIG BAZAAR on ${today}.", noon + 10 * 3_600_000L)
        driver.close()
        println("Demo database written to $out")
    }
}

private fun LocalDate.plus(p: DatePeriod) = kotlinx.datetime.LocalDate.fromEpochDays(this.toEpochDays() + p.days)
