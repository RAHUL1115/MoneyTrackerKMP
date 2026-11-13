package app.moneytracker.data

import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.db.Account as AccountRow

data class ImportResult(val imported: Int, val skipped: Int, val createdAccounts: Int, val createdCategories: Int, val errors: List<String>)

/**
 * Imports tabular rows (first row = header). Columns are matched by header name, so both our own
 * export and simple spreadsheets work: Date, Type (Income/Expense/Transfer…), Account, To account,
 * Category, Subcategory, Amount, Note, Description/Memo, Currency, Tags. Missing accounts and
 * categories are created. Everything runs in one transaction.
 */
class SheetImport(
    private val db: Db,
    private val settings: SettingsStore,
    private val categories: CategoryStore,
    private val tags: TagStore,
    private val writer: EntryWriter,
) {
    private val q get() = db.q

    fun importRows(rows: List<List<String>>): ImportResult = importParsed(parseRows(rows))

    /** First step of an import: read the sheet without saving anything (for the preview). */
    fun parseRows(rows: List<List<String>>): TabularImport.Parsed =
        TabularImport.parse(rows, settings.mainDecimals(), q.allCurrencies().executeAsList().map { it.toDomain() })

    /** Second step: saves the rows the user kept. */
    fun importParsed(parsed: TabularImport.Parsed): ImportResult = db.transactionWithResult {
        val names = NameLookup()
        var imported = 0
        for (r in parsed.rows) {
            val kind = if (r.type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            val draft = TxnDraft(
                r.type, r.date, names.accountId(r.account),
                r.toAccount?.let { names.accountId(it) },
                if (r.type == DraftType.TRANSFER) null else names.categoryId(kind, r.category, r.subcategory),
                r.amount, note = r.note, memo = r.memo, currency = r.currency,
                tagIds = r.tags.map { tags.tagNamed(it) },
            )
            writer.writeDraft(draft, recurringRuleId = null, fixedId = null)
            imported++
        }
        ImportResult(imported, parsed.errors.size, names.newAccounts, names.newCats, parsed.errors.take(20))
    }

    /** Finds the import's accounts and categories by name, creating the missing ones and counting what it created. */
    private inner class NameLookup {
        private val accountsByName = q.allAccounts().executeAsList().associateBy { it.name.lowercase() }.toMutableMap()
        private val cats = q.allCategories().executeAsList().map { it.toDomain() }.toMutableList()
        var newAccounts = 0
        var newCats = 0

        fun accountId(name: String): String {
            accountsByName[name.lowercase()]?.let { return it.id }
            val id = newId()
            val row = AccountRow(id, "grp_other", name, 1, 0, null, null, accountsByName.size.toLong() + 100, 0, db.now(), null, null, 0, "", null, 0, null, 1, "", "")
            q.upsertAccount(row); accountsByName[name.lowercase()] = row; newAccounts++
            return id
        }

        fun categoryId(kind: CategoryKind, main: String, sub: String): String? {
            if (main.isBlank()) return null
            val m = cats.firstOrNull { it.kind == kind && it.parentId == null && it.name.equals(main, true) }
                ?: Category(newId(), main, kind, null, cats.size).also { categories.saveCategory(it); cats += it; newCats++ }
            if (sub.isBlank()) return m.id
            val s = cats.firstOrNull { it.parentId == m.id && it.name.equals(sub, true) }
                ?: Category(newId(), sub, kind, m.id, cats.size).also { categories.saveCategory(it); cats += it; newCats++ }
            return s.id
        }
    }
}
