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
    val sheetImport = SheetImport(db, settingsStore, categoryStore, tagStore, entryWriter)
    val moneyManagerBridge = MoneyManagerBridge(db, settingsStore, currencyStore, tagStore, learningStore, repeatStore, entryWriter, txnStore)
    val freshStart = FreshStart(db, settingsStore, tagStore)

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

    // import, export, reset
    fun importRows(rows: List<List<String>>): ImportResult = sheetImport.importRows(rows)
    fun parseRows(rows: List<List<String>>): TabularImport.Parsed = sheetImport.parseRows(rows)
    fun importParsed(parsed: TabularImport.Parsed): ImportResult = sheetImport.importParsed(parsed)
    fun moneyManagerData(): MoneyManagerBackup.Data = moneyManagerBridge.moneyManagerData()
    fun replaceWithMoneyManager(d: MoneyManagerBackup.Data): LegacyResult = moneyManagerBridge.replaceWithMoneyManager(d)
    fun reset(everything: Boolean) = freshStart.reset(everything)
    fun seedIfEmpty() = freshStart.seedIfEmpty()
}
