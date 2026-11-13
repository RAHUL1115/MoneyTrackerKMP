package app.moneytracker.data

import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CategoryKind
import app.moneytracker.db.Account as AccountRow
import app.moneytracker.db.AccountGroup as GroupRow
import app.moneytracker.db.Category as CategoryRow

/** The starter book a new install gets (groups, a few accounts, categories), and wiping back to it. */
class FreshStart(private val db: Db, private val settings: SettingsStore, private val tags: TagStore) {
    private val q get() = db.q

    /**
     * [everything] = back to a fresh install (accounts, categories, settings except passcode/security);
     * otherwise only entries and what hangs off them (budgets, repeats, bookmarks, photos, detected SMS) go.
     */
    fun reset(everything: Boolean) = db.transaction {
        listOf(q::wipeTxn, q::wipeTxnTags, q::wipeBudget, q::wipeRules, q::wipePhotos, q::wipeTemplates).forEach { it() }
        q.wipeCaptures()
        if (everything) {
            listOf(q::wipeAccount, q::wipeAccountGroup, q::wipeCategory, q::wipeTags, q::wipeCurrencies, q::wipeLearning, q::wipeLearnLabels)
                .forEach { it() }
            settings.putSetting(Prefs.SEEDED, false)
            seedIfEmpty()
        }
    }

    fun seedIfEmpty() = db.transaction {
        tags.ensureSystemTags()
        if (settings.settingNow(Prefs.SEEDED)) return@transaction
        val ts = db.now()
        val groups = listOf(
            "Cash" to AccountType.CASH, "Bank Accounts" to AccountType.BANK, "Credit Cards" to AccountType.CREDIT_CARD,
            "Debit Cards" to AccountType.DEBIT_CARD, "Savings" to AccountType.SAVINGS, "Investments" to AccountType.INVESTMENT,
            "Loans" to AccountType.LOAN, "Others" to AccountType.OTHER,
        ).mapIndexed { i, (name, type) -> GroupRow("grp_${type.name.lowercase()}", name, type.name, i.toLong(), 0, ts) }
        groups.forEach(q::upsertGroup)
        q.upsertAccount(AccountRow("acc_cash", "grp_cash", "Cash", 1, 0, null, null, 0, 0, ts, null, null, 0, "", null, 0, null, 1, "", ""))
        q.upsertAccount(AccountRow("acc_bank", "grp_bank", "Bank Account", 1, 0, null, null, 1, 0, ts, null, null, 0, "", null, 0, null, 1, "", ""))
        q.upsertAccount(AccountRow("acc_card", "grp_credit_card", "Credit Card", 1, 0, 1, "acc_bank", 2, 0, ts, null, null, 0, "", null, 0, null, 1, "", ""))
        q.upsertAccount(AccountRow("acc_savings", "grp_savings", "Savings", 1, 1, null, null, 3, 0, ts, null, null, 0, "", null, 0, null, 1, "", ""))

        val income = listOf("💼 Salary", "🏆 Bonus", "🤲 Allowance", "🏦 Interest", "Other")
        val expense = linkedMapOf(
            "🍔 Food" to listOf("Groceries", "Eating out", "Coffee"),
            "🚌 Transport" to listOf("Fuel", "Public transport", "Taxi"),
            "🏠 Housing" to listOf("Rent", "Utilities", "Maintenance"),
            "🛍️ Shopping" to listOf("Clothing", "Electronics"),
            "💊 Health" to listOf("Medicine", "Doctor"),
            "🎬 Entertainment" to emptyList(), "📚 Education" to emptyList(), "🎁 Gifts" to emptyList(), "Other" to emptyList(),
        )
        income.forEachIndexed { i, n -> q.upsertCategory(CategoryRow("cat_in_$i", n, CategoryKind.INCOME.name, null, i.toLong(), 0, ts, null)) }
        expense.entries.forEachIndexed { i, (main, subs) ->
            val id = "cat_ex_$i"
            q.upsertCategory(CategoryRow(id, main, CategoryKind.EXPENSE.name, null, i.toLong(), 0, ts, null))
            subs.forEachIndexed { j, s -> q.upsertCategory(CategoryRow("${id}_$j", s, CategoryKind.EXPENSE.name, id, j.toLong(), 0, ts, null)) }
        }
        settings.putSetting(Prefs.SEEDED, true)
    }
}
