package app.moneytracker.data

import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.Category
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Note
import app.moneytracker.domain.Photo
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.ReportRules
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Totals
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.SmsParser
import app.moneytracker.db.Account as AccountRow
import app.moneytracker.db.AccountGroup as GroupRow
import app.moneytracker.db.Category as CategoryRow
import app.moneytracker.db.Note as NoteRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The app's data, one store per area. Builds the stores over one shared [Db] and hands them out; the rules that
 * span areas live in [EntryWriter], [MessageIntake], [SheetImport], [MoneyManagerBridge] and [FreshStart].
 */
class MoneyRepository(
    database: MoneyDatabase,
    val io: CoroutineDispatcher,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val db = Db(database, io, now, today)
    private val q get() = db.q
    private fun now() = db.now()

    val settingsStore = SettingsStore(db)
    val currencyStore = CurrencyStore(db, settingsStore)
    val accountStore = AccountStore(db)
    val categoryStore = CategoryStore(db)
    val tagStore = TagStore(db)
    val photoStore = PhotoStore(db)
    val noteStore = NoteStore(db)
    val bookmarkStore = BookmarkStore(db)
    val captureStore = CaptureStore(db)
    val learningStore = LearningStore(db, settingsStore)
    val entryWriter = EntryWriter(db, settingsStore, currencyStore, accountStore, tagStore)
    val repeatStore = RepeatStore(db, settingsStore, entryWriter)
    val txnStore = TxnStore(db, settingsStore, currencyStore, accountStore, tagStore, entryWriter, repeatStore)
    val reportStore = ReportStore(db, settingsStore, accountStore)
    val messageIntake = MessageIntake(db, settingsStore, currencyStore, accountStore, categoryStore, learningStore, captureStore, txnStore)

    /** Finished views (accounts, budgets, month totals) for screens, the web dashboard and widgets. */
    val views: ReadModels by lazy { ReadModels(this) }

    /** [amount] is in minor units of [currency] (null = main), as it was entered. */
    data class NoteSuggestion(val note: String, val categoryId: String?, val accountId: String?, val kind: TxnKind, val amount: Long, val currency: String?)

    data class Suggestion(val categoryId: String?, val accountId: String?, val categoryConfidence: Double, val accountConfidence: Double)

    // ───────────────────────── forwarding
    // Temporary: one-line forwards to the stores so existing callers keep compiling. Callers will move to the
    // stores directly, after which these go (and the two suggestion types above move to LearningStore).

    // settings
    fun settingNow(key: String): String? = settingsStore.settingNow(key)
    fun putSetting(key: String, value: String) = settingsStore.putSetting(key, value)
    fun <T> setting(s: Setting<T>): Flow<T> = settingsStore.setting(s)
    fun <T> settingNow(s: Setting<T>): T = settingsStore.settingNow(s)
    fun <T> putSetting(s: Setting<T>, value: T) = settingsStore.putSetting(s, value)
    fun dropRetiredSettings() = settingsStore.dropRetiredSettings()
    val monthStartDay: Flow<Int> get() = settingsStore.monthStartDay
    val useSubcategories: Flow<Boolean> get() = settingsStore.useSubcategories
    val periodSettings: CachedFlow<PeriodSettings> get() = settingsStore.periodSettings
    fun periodSettingsNow() = settingsStore.periodSettingsNow()
    val currency: CachedFlow<CurrencyFormat> get() = settingsStore.currency
    fun currencyFormatNow() = settingsStore.currencyFormatNow()
    fun mainDecimals() = settingsStore.mainDecimals()

    // currencies
    val currencies: CachedFlow<List<Currency>> get() = currencyStore.currencies
    fun currenciesNow(): List<Currency> = currencyStore.currenciesNow()
    fun saveCurrency(c: Currency) = currencyStore.saveCurrency(c)
    fun deleteCurrency(code: String) = currencyStore.deleteCurrency(code)
    suspend fun updateRates(get: suspend (String) -> String?): RatesUpdate = currencyStore.updateRates(get)

    // accounts and groups
    val groups: CachedFlow<List<AccountGroup>> get() = accountStore.groups
    val deletedGroups: Flow<List<AccountGroup>> get() = accountStore.deletedGroups
    fun restoreGroup(id: String) = accountStore.restoreGroup(id)
    val accounts: CachedFlow<List<Account>> get() = accountStore.accounts
    val deletedAccounts: Flow<List<Account>> get() = accountStore.deletedAccounts
    fun accountNow(id: String?): Account? = accountStore.accountNow(id)
    fun accountsNow(): List<Account> = accountStore.accountsNow()
    fun groupsNow(): List<AccountGroup> = accountStore.groupsNow()
    val accountNames: CachedFlow<Map<String, String>> get() = accountStore.accountNames
    val balances: CachedFlow<Map<String, Long>> get() = accountStore.balances
    fun balancesNow(): Map<String, Long> = accountStore.balancesNow()
    fun saveAccount(a: Account) = accountStore.saveAccount(a)
    fun deleteAccount(id: String) = accountStore.deleteAccount(id)
    fun restoreAccount(id: String) = accountStore.restoreAccount(id)
    fun reorderAccounts(idsInOrder: List<String>) = accountStore.reorderAccounts(idsInOrder)
    fun reorderGroups(idsInOrder: List<String>) = accountStore.reorderGroups(idsInOrder)
    fun setAccountHidden(id: String, hidden: Boolean) = accountStore.setAccountHidden(id, hidden)
    fun saveGroup(g: AccountGroup) = accountStore.saveGroup(g)
    fun deleteGroup(id: String): Boolean = accountStore.deleteGroup(id)

    // categories
    val categories: CachedFlow<List<Category>> get() = categoryStore.categories
    val categoryById: CachedFlow<Map<String, Category>> get() = categoryStore.categoryById
    fun categoriesNow(): List<Category> = categoryStore.categoriesNow()
    fun categoryByIdNow(): Map<String, Category> = categoryStore.categoryByIdNow()
    fun saveCategory(c: Category) = categoryStore.saveCategory(c)
    fun deleteCategory(id: String) = categoryStore.deleteCategory(id)
    fun reorderCategories(idsInOrder: List<String>) = categoryStore.reorderCategories(idsInOrder)

    // entries
    fun txnsBetween(range: DateRange): CachedFlow<List<Txn>> = txnStore.txnsBetween(range)
    fun txnsNow(range: DateRange): List<Txn> = txnStore.txnsNow(range)
    fun sortWithinDays(txns: List<Txn>, ascending: Boolean = false): List<Txn> = txnStore.sortWithinDays(txns, ascending)
    fun accountTxns(accountId: String): List<Txn> = txnStore.accountTxns(accountId)
    fun accountTxnsFlow(accountId: String, range: DateRange): Flow<List<Txn>> = txnStore.accountTxnsFlow(accountId, range)
    fun allTxns(): List<Txn> = txnStore.allTxns()
    fun search(text: String, range: DateRange, minAmount: Long?, maxAmount: Long?, tagIds: Set<String> = emptySet()): List<Txn> =
        txnStore.search(text, range, minAmount, maxAmount, tagIds)
    fun <T> inTransaction(block: () -> T): T = txnStore.inTransaction(block)
    fun txn(id: String): Txn? = txnStore.txn(id)
    fun liveTxn(id: String): Txn? = txnStore.liveTxn(id)
    fun installmentGroup(groupId: String): List<Txn> = txnStore.installmentGroup(groupId)
    fun transferGroup(groupId: String): List<Txn> = txnStore.transferGroup(groupId)
    fun create(d: TxnDraft): String = txnStore.create(d)
    fun update(existing: Txn, d: TxnDraft): String = txnStore.update(existing, d)
    fun rateOf(t: Txn): Double? = entryWriter.rateOf(t)
    fun delete(t: Txn, wholeInstallmentPlan: Boolean = false) = txnStore.delete(t, wholeInstallmentPlan)
    fun draftFrom(t: Txn): TxnDraft = txnStore.draftFrom(t)
    fun adjustBalance(accountId: String, target: Long, asIncomeExpense: Boolean = false) = txnStore.adjustBalance(accountId, target, asIncomeExpense)
    fun updateAdjustment(t: Txn, date: LocalDate, amount: Long, accountId: String, up: Boolean, note: String, memo: String = t.memo, timeOfDay: Int? = t.timeOfDay) =
        txnStore.updateAdjustment(t, date, amount, accountId, up, note, memo, timeOfDay)
    fun deleteMany(ids: Collection<String>) = txnStore.deleteMany(ids)
    fun setDateMany(ids: Collection<String>, date: LocalDate) = txnStore.setDateMany(ids, date)
    fun setCategoryMany(ids: Collection<String>, categoryId: String): Int = txnStore.setCategoryMany(ids, categoryId)
    fun setAccountMany(ids: Collection<String>, accountId: String): Int = txnStore.setAccountMany(ids, accountId)

    // reports and budgets
    fun reportRows(range: DateRange): CachedFlow<List<ReportRow>> = reportStore.reportRows(range)
    fun reportRowsNow(range: DateRange): List<ReportRow> = reportStore.reportRowsNow(range)
    val reportRules: CachedFlow<ReportRules> get() = reportStore.reportRules
    fun totalsNow(range: DateRange): Totals = reportStore.totalsNow(range)
    fun budgetEntries(upTo: YearMonth): CachedFlow<List<BudgetEntry>> = reportStore.budgetEntries(upTo)
    fun budgetEntriesNow(upTo: YearMonth): List<BudgetEntry> = reportStore.budgetEntriesNow(upTo)
    fun setBudget(categoryId: String, from: YearMonth, amount: Long) = reportStore.setBudget(categoryId, from, amount)

    // tags
    val tags: CachedFlow<List<Tag>> get() = tagStore.tags
    val txnTagNames: CachedFlow<Map<String, List<String>>> get() = tagStore.txnTagNames
    fun tagsNow(): List<Tag> = tagStore.tagsNow()
    fun txnTagNamesNow(): Map<String, List<String>> = tagStore.txnTagNamesNow()
    fun saveTag(t: Tag) = tagStore.saveTag(t)
    fun txnIdsWithTag(tagId: String): Set<String> = tagStore.txnIdsWithTag(tagId)
    fun ensureSystemTags() = tagStore.ensureSystemTags()
    fun deleteTag(id: String) = tagStore.deleteTag(id)
    fun tagsOf(txnId: String): List<String> = tagStore.tagsOf(txnId)
    fun setTags(txnId: String, tagIds: List<String>) = tagStore.setTags(txnId, tagIds)
    fun tagNamed(name: String): String = tagStore.tagNamed(name)

    // learning and autocomplete
    fun noteSuggestions(prefix: String): List<NoteSuggestion> = learningStore.noteSuggestions(prefix)
    fun resetAutocomplete() = learningStore.resetAutocomplete()
    fun habitAccount(kind: TxnKind, categoryId: String?, allowed: Set<String>? = null, note: String = "", amount: Long? = null): String? =
        learningStore.habitAccount(kind, categoryId, allowed, note, amount)
    fun learn(tokens: List<String>, categoryId: String?, accountId: String?) = learningStore.learn(tokens, categoryId, accountId)
    fun suggest(tokens: List<String>): Suggestion = learningStore.suggest(tokens)
    fun learnFromDraft(d: TxnDraft) = learningStore.learnFromDraft(d)

    // photos, notes, bookmarks
    fun photos(txnId: String): Flow<List<Photo>> = photoStore.photos(txnId)
    fun photosNow(txnId: String): List<Photo> = photoStore.photosNow(txnId)
    val txnIdsWithPhotos: CachedFlow<Set<String>> get() = photoStore.txnIdsWithPhotos
    fun addPhoto(txnId: String, fileName: String) = photoStore.addPhoto(txnId, fileName)
    fun deletePhoto(id: String) = photoStore.deletePhoto(id)
    val notes: CachedFlow<List<Note>> get() = noteStore.notes
    fun saveNote(n: Note) = noteStore.saveNote(n)
    fun deleteNote(id: String) = noteStore.deleteNote(id)
    val templates: CachedFlow<List<Template>> get() = bookmarkStore.templates
    fun saveTemplate(t: Template) = bookmarkStore.saveTemplate(t)
    fun deleteTemplate(id: String) = bookmarkStore.deleteTemplate(id)

    // detected payments
    val pendingCaptures: CachedFlow<List<Capture>> get() = captureStore.pendingCaptures
    fun addCapture(c: Capture): Boolean = captureStore.addCapture(c)
    fun setCaptureStatus(id: String, status: CaptureStatus, txnId: String? = null) = captureStore.setCaptureStatus(id, status, txnId)
    fun ingestMessage(source: String, sender: String, text: String, receivedAt: Long): Capture? = messageIntake.ingestMessage(source, sender, text, receivedAt)
    fun draftFromCapture(c: Capture): TxnDraft = messageIntake.draftFromCapture(c)
    fun completeCapture(c: Capture, saved: TxnDraft, txnId: String) = messageIntake.completeCapture(c, saved, txnId)

    // repeats
    val rules: CachedFlow<List<RecurringRule>> get() = repeatStore.rules
    fun saveRule(r: RecurringRule) = repeatStore.saveRule(r)
    fun deleteRule(id: String) = repeatStore.deleteRule(id)
    fun generateRecurring(): Int = repeatStore.generateRecurring()

    // ───────────────────────── not split yet: import, export, reset

    // ───────────────────────── reset

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
            putSetting(Prefs.SEEDED, false)
            seedIfEmpty()
        }
    }

    // ───────────────────────── import (CSV / Excel rows)

    data class ImportResult(val imported: Int, val skipped: Int, val createdAccounts: Int, val createdCategories: Int, val errors: List<String>)

    /**
     * Imports tabular rows (first row = header). Columns are matched by header name, so both our own
     * export and simple spreadsheets work: Date, Type (Income/Expense/Transfer…), Account, To account,
     * Category, Subcategory, Amount, Note, Description/Memo, Currency, Tags. Missing accounts and
     * categories are created. Everything runs in one transaction.
     */
    fun importRows(rows: List<List<String>>): ImportResult = importParsed(parseRows(rows))

    /** First step of an import: read the sheet without saving anything (for the preview). */
    fun parseRows(rows: List<List<String>>): TabularImport.Parsed =
        TabularImport.parse(rows, mainDecimals(), q.allCurrencies().executeAsList().map { it.toDomain() })

    /** Second step: saves the rows the user kept. */
    fun importParsed(parsed: TabularImport.Parsed): ImportResult = db.transactionWithResult {
        val accountsByName = q.allAccounts().executeAsList().associateBy { it.name.lowercase() }.toMutableMap()
        val cats = q.allCategories().executeAsList().map { it.toDomain() }.toMutableList()
        var newAccounts = 0
        var newCats = 0
        fun accountId(name: String): String {
            accountsByName[name.lowercase()]?.let { return it.id }
            val id = newId()
            val row = AccountRow(id, "grp_other", name, 1, 0, null, null, accountsByName.size.toLong() + 100, 0, now(), null, null, 0, "", null, 0, null, 1, "", "")
            q.upsertAccount(row); accountsByName[name.lowercase()] = row; newAccounts++
            return id
        }
        fun categoryId(kind: CategoryKind, main: String, sub: String): String? {
            if (main.isBlank()) return null
            val m = cats.firstOrNull { it.kind == kind && it.parentId == null && it.name.equals(main, true) }
                ?: Category(newId(), main, kind, null, cats.size).also { saveCategory(it); cats += it; newCats++ }
            if (sub.isBlank()) return m.id
            val s = cats.firstOrNull { it.parentId == m.id && it.name.equals(sub, true) }
                ?: Category(newId(), sub, kind, m.id, cats.size).also { saveCategory(it); cats += it; newCats++ }
            return s.id
        }
        var imported = 0
        for (r in parsed.rows) {
            val kind = if (r.type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            val draft = TxnDraft(
                r.type, r.date, accountId(r.account),
                r.toAccount?.let { accountId(it) },
                if (r.type == DraftType.TRANSFER) null else categoryId(kind, r.category, r.subcategory),
                r.amount, note = r.note, memo = r.memo, currency = r.currency,
                tagIds = r.tags.map { tagNamed(it) },
            )
            entryWriter.writeDraft(draft, recurringRuleId = null, fixedId = null)
            imported++
        }
        ImportResult(imported, parsed.errors.size, newAccounts, newCats, parsed.errors.take(20))
    }

    // ───────────────────────── restore from Money Manager (.mmbak)

    data class LegacyResult(
        val transactions: Int, val accounts: Int, val deletedAccounts: Int, val categories: Int,
        val recurring: Int, val budgets: Int, val skipped: List<String>,
    )

    /**
     * Replaces everything with the contents of a Money Manager backup, in one database transaction
     * (any failure leaves current data untouched). Settings like passcode are kept.
     */
    /** Everything a Money Manager backup can hold, in the importer's shape, for [MoneyManagerExport]. Deleted accounts and categories are kept so old entries keep their names. */
    fun moneyManagerData(): MoneyManagerBackup.Data {
        val keys = Prefs.carriedByMoneyManager.map { it.key }
        val mainCode = settingNow(Prefs.CURRENCY_CODE)
        return MoneyManagerBackup.Data(
            mainCode = mainCode, mainSymbol = settingNow(Prefs.CURRENCY_SYMBOL), mainDecimals = mainDecimals(),
            currencies = q.allCurrencies().executeAsList().filter { it.code != mainCode }
                .map { MoneyManagerBackup.Currency(it.code, it.symbol, it.decimals.toInt(), it.rate) },
            groups = q.everyGroup().executeAsList().map { MoneyManagerBackup.Group(it.id, it.name, AccountType.valueOf(it.type), it.sortOrder.toInt(), it.deleted != 0L) },
            accounts = q.everyAccount().executeAsList().map { a ->
                MoneyManagerBackup.Account(a.id, a.groupId, a.name, a.includeInTotals != 0L, a.transferAsExpense != 0L, a.cardSettlementDay?.toInt(),
                    a.cardPaymentAccountId, a.sortOrder.toInt(), a.currency, a.deleted != 0L, a.hidden != 0L, a.memo, a.cardPaymentDay?.toInt(),
                    a.cardAutoPay != 0L, a.usageTarget, a.usageTargetType.toInt(), a.smsSender, a.smsMatch)
            },
            categories = q.everyCategory().executeAsList().map {
                MoneyManagerBackup.Category(it.id, it.name, it.kind == CategoryKind.INCOME.name, it.parentId, it.sortOrder.toInt(), it.deleted != 0L)
            },
            txns = allTxns().let { all ->
                val activeRules = q.activeRules().executeAsList().map { it.id }.toSet()
                all.map { t ->
                    MoneyManagerBackup.Txn(t.id, t.kind, t.date, t.createdAt, t.accountId, t.toAccountId, t.categoryId, t.amount, t.currency,
                        t.originalAmount, t.accountAmount, t.note, t.memo, t.transferGroupId, t.installmentGroupId, t.installmentLabel, t.lat, t.lng,
                        t.timeOfDay, t.origin, recurringRuleId = t.recurringRuleId?.takeIf { it in activeRules })
                }
            },
            rules = q.activeRules().executeAsList().map { it.toDomain() }.map { r ->
                MoneyManagerBackup.Rule(r.id, r.kind, r.accountId, r.toAccountId, r.categoryId, r.amount, r.note, r.repeat, r.nextDate, r.endDate, r.currency, r.memo)
            },
            budgets = q.allBudgetAmounts().executeAsList().map { MoneyManagerBackup.Budget(it.categoryId, it.period.toInt(), it.amount) },
            tags = q.allTags().executeAsList().map { MoneyManagerBackup.Tag(it.id, it.name, it.sortOrder.toInt()) },
            txnTags = q.allTxnTagIds().executeAsList().map { it.txnId to it.tagId },
            memos = q.allNotes().executeAsList().map { MoneyManagerBackup.Memo(it.id, it.title, it.content, it.pinned != 0L, it.createdAt) },
            settings = keys.mapNotNull { k -> settingNow(k)?.let { k to it } }.toMap(),
            skipped = emptyList(),
        )
    }

    fun replaceWithMoneyManager(d: MoneyManagerBackup.Data): LegacyResult = db.transactionWithResult {
        listOf(q::wipeTxn, q::wipeAccount, q::wipeAccountGroup, q::wipeCategory, q::wipeBudget, q::wipeRules, q::wipeTags,
            q::wipeTxnTags, q::wipeCurrencies, q::wipeNotes, q::wipePhotos, q::wipeTemplates, q::wipeLearning, q::wipeLearnLabels, q::wipeCaptures)
            .forEach { it() }
        val ts = now()
        putSetting(Prefs.CURRENCY_SYMBOL, d.mainSymbol)
        putSetting(Prefs.CURRENCY_DECIMALS, d.mainDecimals)
        putSetting(Prefs.CURRENCY_CODE, d.mainCode)
        d.settings.forEach { (k, v) -> putSetting(k, v) }
        d.currencies.forEachIndexed { i, c -> saveCurrency(Currency(c.code, c.symbol, c.decimals, c.rate, i)) }
        d.groups.forEach { g -> q.upsertGroupRaw(g.id, g.name, g.type.name, g.sortOrder.toLong(), g.deleted.toLong(), ts) }
        d.accounts.forEach { a ->
            q.upsertAccountRaw(a.id, a.groupId, a.name, a.includeInTotals.toLong(), a.transferAsExpense.toLong(), a.cardSettlementDay?.toLong(),
                a.cardPaymentAccountId, a.sortOrder.toLong(), a.deleted.toLong(), ts, a.currency, null, a.hidden.toLong(),
                a.memo, a.cardPaymentDay?.toLong(), a.cardAutoPay.toLong(), a.usageTarget, a.usageTargetType.toLong(), a.smsSender, a.smsMatch)
        }
        d.categories.forEach { c ->
            q.upsertCategoryRaw(c.id, c.name, (if (c.income) CategoryKind.INCOME else CategoryKind.EXPENSE).name, c.parentId,
                c.sortOrder.toLong(), c.deleted.toLong(), ts)
        }
        d.txns.forEach { t ->
            entryWriter.insert(Txn(t.id, t.kind, t.date, t.createdAt, t.accountId, t.toAccountId, t.categoryId, t.amountMain, t.note, t.memo,
                t.transferGroupId, t.installmentGroupId, t.installmentLabel, t.recurringRuleId, t.currency, t.originalAmount, t.accountAmount, t.lat, t.lng,
                t.timeOfDay, t.origin))
        }
        d.rules.forEach { r ->
            saveRule(RecurringRule(r.id, r.kind, r.accountId, r.toAccountId, r.categoryId, r.amount, r.note, r.repeat, r.nextDate, r.nextDate, r.endDate, r.currency, r.memo))
        }
        d.budgets.forEach { b -> q.upsertBudgetAmount(app.moneytracker.db.BudgetAmount(b.categoryId, b.period.toLong(), b.amount, ts)) }
        d.tags.forEach { saveTag(Tag(it.id, it.name, it.sortOrder)) }
        ensureSystemTags()
        d.txnTags.forEach { (txn, tag) -> q.addTxnTag(txn, tag) }
        d.memos.forEach { m -> q.upsertNote(NoteRow(m.id, m.title, m.content, 0, m.pinned.toLong(), m.time, ts, 0)) }
        // Teach the suggestion model from history: note words → category & account.
        d.txns.filter { (it.kind == TxnKind.INCOME || it.kind == TxnKind.EXPENSE) && it.note.isNotBlank() }
            .forEach { learn(SmsParser.words(it.note), it.categoryId, it.accountId) }
        LegacyResult(
            d.txns.size, d.accounts.count { !it.deleted }, d.accounts.count { it.deleted }, d.categories.count { !it.deleted },
            d.rules.size, d.budgets.size, d.skipped,
        )
    }


    // ───────────────────────── first-run seed

    fun seedIfEmpty() = db.transaction {
        ensureSystemTags()
        if (settingNow(Prefs.SEEDED)) return@transaction
        val ts = now()
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
        putSetting(Prefs.SEEDED, true)
    }
}
