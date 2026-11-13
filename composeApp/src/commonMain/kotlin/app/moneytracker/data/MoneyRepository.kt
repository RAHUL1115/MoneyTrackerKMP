package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.moneytracker.Log
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.domain.Account
import app.moneytracker.domain.defaultAccountFor
import app.moneytracker.domain.AccountPredictor
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TAG_USAGE_EXCEPTION
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.BudgetEntry
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.isRefund
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Fx
import app.moneytracker.domain.Note
import app.moneytracker.domain.Periods
import app.moneytracker.domain.Photo
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.MessageRules
import app.moneytracker.domain.TinyBayes
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.ReportRules
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.splitInstallments
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import app.moneytracker.db.Account as AccountRow
import app.moneytracker.db.AccountGroup as GroupRow
import app.moneytracker.db.Capture as CaptureRow
import app.moneytracker.db.Category as CategoryRow
import app.moneytracker.db.Currency as CurrencyRow
import app.moneytracker.db.Note as NoteRow
import app.moneytracker.db.Photo as PhotoRow
import app.moneytracker.db.RecurringRule as RuleRow
import app.moneytracker.db.Tag as TagRow
import app.moneytracker.db.Template as TemplateRow
import app.moneytracker.db.Txn as TxnRow


class MoneyRepository(
    database: MoneyDatabase,
    val io: CoroutineDispatcher,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val db = Db(database, io, now, today)
    private val q get() = db.q
    private fun now() = db.now()
    private fun <R : Any, T> cached(query: () -> app.cash.sqldelight.Query<R>, map: (List<R>) -> T): CachedFlow<T> = db.cached(query, map)
    private val rangeCache get() = db.rangeCache

    val settingsStore = SettingsStore(db)
    val currencyStore = CurrencyStore(db, settingsStore)
    val accountStore = AccountStore(db)
    val categoryStore = CategoryStore(db)
    val tagStore = TagStore(db)
    val photoStore = PhotoStore(db)
    val noteStore = NoteStore(db)
    val bookmarkStore = BookmarkStore(db)
    val captureStore = CaptureStore(db)

    /** Finished views (accounts, budgets, month totals) for screens, the web dashboard and widgets. */
    val views: ReadModels by lazy { ReadModels(this) }

    // ───────────────────────── forwarding
    // Temporary: one-line forwards to the stores so existing callers keep compiling. Callers will move to the
    // stores directly, after which these go.

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
    private fun currencyNow(code: String?): Currency? = currencyStore.currencyNow(code)
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


    fun accountTxns(accountId: String): List<Txn> = q.txnsForAccount(accountId).executeAsList().map { it.toDomain() }

    fun accountTxnsFlow(accountId: String, range: DateRange): Flow<List<Txn>> =
        q.txnsForAccountBetween(accountId, range.start.toEpochDays().toLong(), range.endInclusive.toEpochDays().toLong())
            .asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    /**
     * Records the difference to [target] (account currency) under "Modified Bal.", note "Difference", as in
     * A neutral adjustment by default (only visible on the account's own page, not in the
     * Trans. tab or stats), or with [asIncomeExpense] a regular income/expense entry.
     */
    fun adjustBalance(accountId: String, target: Long, asIncomeExpense: Boolean = false) {
        val current = q.balanceOf(accountId, today().toEpochDays().toLong()).executeAsOne().toLong()
        val diff = target - current
        if (diff == 0L) return
        val cur = currencyNow(accountNow(accountId)?.currency)
        val abs = kotlin.math.abs(diff)
        val kind = when {
            asIncomeExpense -> if (diff > 0) TxnKind.INCOME else TxnKind.EXPENSE
            else -> if (diff > 0) TxnKind.ADJUST_UP else TxnKind.ADJUST_DOWN
        }
        insert(Txn(newId(), kind, today(), now(), accountId, categoryId = app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY,
            amount = Fx.toMain(abs, cur, mainDecimals()), note = "Difference",
            currency = cur?.code, originalAmount = cur?.let { abs }, accountAmount = cur?.let { abs }, rate = cur?.rate))
    }

    /** Edits an adjustment row in place. [amount] is in the account's currency; [up] = increase. */
    fun updateAdjustment(t: Txn, date: LocalDate, amount: Long, accountId: String, up: Boolean, note: String, memo: String = t.memo, timeOfDay: Int? = t.timeOfDay) {
        val today = currencyNow(accountNow(accountId)?.currency)
        // Same currency: keep the adjustment's own rate.
        val cur = today?.let { c -> (if (c.code == t.currency) rateOf(t) else null)?.let { c.copy(rate = it) } ?: c }
        insert(t.copy(
            kind = if (up) TxnKind.ADJUST_UP else TxnKind.ADJUST_DOWN, date = date, accountId = accountId, note = note, memo = memo, timeOfDay = timeOfDay,
            amount = Fx.toMain(amount, cur, mainDecimals()), currency = cur?.code, originalAmount = cur?.let { amount }, accountAmount = cur?.let { amount },
            rate = cur?.rate,
        ))
    }

    // ───────────────────────── transactions

    fun txnsBetween(range: DateRange): CachedFlow<List<Txn>> = rangeCache.get("txns:$range") { txnsIn(range) }
    private fun txnsIn(range: DateRange): CachedFlow<List<Txn>> = run {
        val from = range.start.toEpochDays().toLong(); val to = range.endInclusive.toEpochDays().toLong()
        CachedFlow(combine(q.txnsBetween(from, to).asFlow().mapToList(io), setting(Prefs.TIME_SORT)) { rows, sort ->
            sortWithinDays(rows.map { it.toDomain() }, ascending = sort == TimeSort.ASC)
        }) { sortWithinDays(q.txnsBetween(from, to).executeAsList().map { it.toDomain() }, ascending = settingNow(Prefs.TIME_SORT) == TimeSort.ASC) }
    }

    // ───────────────────────── synchronous reads for background threads (web dashboard, widgets)
    // Each is the matching flow's own mapping read once ([CachedFlow.now]); none touches the per-range cache.
    fun txnsNow(range: DateRange): List<Txn> = txnsIn(range).now()
    fun reportRowsNow(range: DateRange): List<ReportRow> = reportRowsIn(range).now()
    fun budgetEntriesNow(upTo: YearMonth): List<BudgetEntry> = budgetEntriesUpTo(upTo).now()

    /** Newest day first; within a day by recorded time (entries without one by entry order), newest first unless [ascending]. */
    fun sortWithinDays(txns: List<Txn>, ascending: Boolean = false): List<Txn> {
        val inDay = compareBy<Txn>({ it.timeOfDay ?: -1 }, { it.createdAt })
        return txns.sortedWith(compareByDescending<Txn> { it.date }.then(if (ascending) inDay else inDay.reversed()))
    }

    // ───────────────────────── autocomplete

    /** [amount] is in minor units of [currency] (null = main), as it was entered. */
    data class NoteSuggestion(val note: String, val categoryId: String?, val accountId: String?, val kind: TxnKind, val amount: Long, val currency: String?)

    /** Past notes starting with [prefix], most recently used first, with the category/account/amount used last time. */
    fun noteSuggestions(prefix: String): List<NoteSuggestion> {
        if (prefix.isBlank() || !settingNow(Prefs.AUTOCOMPLETE)) return emptyList()
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val since = settingNow(Prefs.AUTOCOMPLETE_SINCE)
        return q.noteSuggestions(escaped, since).executeAsList()
            .filter { !it.note.equals(prefix, ignoreCase = false) }
            .map { NoteSuggestion(it.note, it.categoryId, it.accountId, TxnKind.valueOf(it.kind), it.amount ?: 0L, it.currency) }
    }

    /**
     * The account this entry probably goes in, from the person's recent entries of the same [kind]: its category,
     * note words and amount, and the accounts used just before (see [AccountPredictor]). Only live, visible accounts
     * (and only [allowed] ones when given); null when none was used in the last year or the setting is off.
     * A category's own default account is the caller's to prefer.
     */
    fun habitAccount(kind: TxnKind, categoryId: String?, allowed: Set<String>? = null, note: String = "", amount: Long? = null): String? {
        if (!settingNow(Prefs.HABIT_ACCOUNT)) return null
        val usable = q.allAccounts().executeAsList().filter { it.hidden == 0L && (allowed == null || it.id in allowed) }.map { it.id }.toSet()
        val today = today().toEpochDays().toLong()
        val history = q.accountHistory(kind.name, today - AccountPredictor.WINDOW_DAYS).executeAsList().asReversed().map {
            AccountPredictor.Past(it.accountId, it.categoryId, it.parentId ?: it.categoryId, it.note, it.amount, it.date)
        }
        val main = categoryId?.let { id -> q.allCategories().executeAsList().firstOrNull { it.id == id }?.parentId } ?: categoryId
        return AccountPredictor.rank(history, AccountPredictor.Ask(categoryId, main, note, amount, today), usable).firstOrNull()?.first
    }

    fun resetAutocomplete() = putSetting(Prefs.AUTOCOMPLETE_SINCE, now())

    // ───────────────────────── bulk edits (multi-select)

    /** Deletes each entry the way the editor would (transfer legs and fees together). */
    fun deleteMany(ids: Collection<String>) = db.transaction { ids.mapNotNull { txn(it) }.forEach { delete(it) } }

    fun setDateMany(ids: Collection<String>, date: LocalDate) = db.transaction {
        ids.mapNotNull { txn(it) }.forEach { t -> q.setTxnDate(date.toEpochDays().toLong(), now(), t.id, t.transferGroupId ?: "") }
    }

    /** Only income/expense rows of the matching kind take the category. Returns how many changed. */
    fun setCategoryMany(ids: Collection<String>, categoryId: String): Int = db.transactionWithResult {
        val kind = q.allCategories().executeAsList().firstOrNull { it.id == categoryId }?.kind ?: return@transactionWithResult 0
        val wanted = if (kind == CategoryKind.INCOME.name) TxnKind.INCOME else TxnKind.EXPENSE
        ids.mapNotNull { txn(it) }.filter { it.kind == wanted && it.transferGroupId == null }
            .onEach { q.setTxnCategory(categoryId, now(), it.id) }.size
    }

    /** Moves income/expense rows to another account (transfers keep their accounts). Returns how many changed. */
    fun setAccountMany(ids: Collection<String>, accountId: String): Int = db.transactionWithResult {
        ids.mapNotNull { txn(it) }.filter { (it.kind == TxnKind.INCOME || it.kind == TxnKind.EXPENSE) && it.transferGroupId == null }
            .onEach { q.setTxnAccount(accountId, now(), it.id) }.size
    }

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

    fun reportRows(range: DateRange): CachedFlow<List<ReportRow>> = rangeCache.get("report:$range") { reportRowsIn(range) }
    private fun reportRowsIn(range: DateRange): CachedFlow<List<ReportRow>> = run {
        val from = range.start.toEpochDays().toLong(); val to = range.endInclusive.toEpochDays().toLong()
        CachedFlow(combine(q.reportRows(from, to).asFlow().mapToList(io), setting(Prefs.CARD_AT_PAYMENT)) { rows, cardAtPayment ->
            rows.map { it.toReportRow(cardAtPayment) }
        }) { q.reportRows(from, to).executeAsList().map { it.toReportRow(settingNow(Prefs.CARD_AT_PAYMENT)) } }
    }

    /** How to build report rows from entries; same rules as [reportRows]. */
    val reportRules: CachedFlow<ReportRules> = CachedFlow(combine(accounts, groups, setting(Prefs.CARD_AT_PAYMENT)) { a, g, card ->
        ReportRules.of(a, g, card)
    }) { ReportRules.of(accounts.current, groups.current, settingNow(Prefs.CARD_AT_PAYMENT)) }

    private fun app.moneytracker.db.ReportRows.toReportRow(cardAtPayment: Boolean) = ReportRow(
        TxnKind.valueOf(kind), amount, categoryId, srcTransferExpense != 0L, dstTransferExpense != 0L,
        LocalDate.fromEpochDays(date.toInt()),
        srcIsCard = srcType == AccountType.CREDIT_CARD.name, dstIsCard = dstType == AccountType.CREDIT_CARD.name,
        cardAtPayment = cardAtPayment, txnId = id, accountId = accountId, note = note,
    )

    /** Synchronous totals for widgets / notifications (same rows as [reportRows]). */
    fun totalsNow(range: DateRange): app.moneytracker.domain.Totals = app.moneytracker.domain.totals(reportRowsNow(range))

    /**
     * Text matches note, memo, category, either account or tag names; text starting with # matches tag names only.
     * With [tagIds], the entry must carry at least one of them. Amounts are main-currency minor units.
     */
    fun search(text: String, range: DateRange, minAmount: Long?, maxAmount: Long?, tagIds: Set<String> = emptySet()): List<Txn> {
        val trimmed = text.trim()
        val tagsOnly = trimmed.startsWith("#")
        val words = trimmed.removePrefix("#").trim().replace("%", "").replace("_", "")
        val found = q.searchTxns(
            range.start.toEpochDays().toLong(), range.endInclusive.toEpochDays().toLong(),
            if (tagsOnly) 1L else 0L, "%$words%",
            minAmount ?: 0L, maxAmount ?: Long.MAX_VALUE,
        ).executeAsList().map { it.toDomain() }
        if (tagIds.isEmpty()) return found
        val tagged = tagIds.flatMap { txnIdsWithTag(it) }.toSet()
        return found.filter { it.id in tagged }
    }

    /** Runs [block] as one database transaction (nested repository writes join it). */
    fun <T> inTransaction(block: () -> T): T = db.transactionWithResult { block() }

    fun txn(id: String): Txn? = q.txnById(id).executeAsOneOrNull()?.toDomain()
    /** Like [txn], but null once the entry is deleted. */
    fun liveTxn(id: String): Txn? = q.txnById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDomain()
    fun installmentGroup(groupId: String): List<Txn> = q.txnsByInstallmentGroup(groupId).executeAsList().map { it.toDomain() }
    fun transferGroup(groupId: String): List<Txn> = q.txnsByTransferGroup(groupId).executeAsList().map { it.toDomain() }

    /** Creates the rows for a draft. Returns the id of the primary row (or of the recurring rule). */
    fun create(d: TxnDraft): String = db.transactionWithResult {
        if (d.repeat != null) {
            val rule = RecurringRule(newId(), d.type.primaryKind(), d.accountId, d.toAccountId, d.categoryId, d.amount * d.type.sign,
                d.note, d.repeat, d.date, d.date, d.repeatEnd, d.currency, d.memo)
            saveRule(rule)
            generateRecurring()
            Log.d("Txn") { "repeat rule ${rule.id} created (${d.type}, ${d.repeat})" }
            return@transactionWithResult rule.id
        }
        writeDraft(d, recurringRuleId = null, fixedId = null).also { Log.d("Txn") { "created $it (${d.type})" } }
    }

    /** Replaces an existing transaction (and its transfer pair / fee / installment group) with [d]. */
    fun update(existing: Txn, d: TxnDraft): String = db.transactionWithResult {
        // Same currency as before: keep the rate the entry was made with, so a later rate update doesn't change it.
        val rate = d.rate ?: if (d.currency != null && d.currency == existing.currency) rateOf(existing) else null
        delete(existing, wholeInstallmentPlan = true)
        writeDraft(d.copy(repeat = null, rate = rate), recurringRuleId = existing.recurringRuleId, fixedId = null)
            .also { Log.d("Txn") { "updated ${existing.id} → $it (${d.type})" } }
    }

    /** The rate [t] was made with: stored since v7, worked out from its two amounts for older entries. */
    fun rateOf(t: Txn): Double? {
        t.rate?.let { return it }
        val original = t.originalAmount?.takeIf { it != 0L } ?: return null
        val dec = currencyNow(t.currency)?.decimals ?: return null
        return (t.amount.toDouble() / CurrencyFormat.pow10(mainDecimals())) / (original.toDouble() / CurrencyFormat.pow10(dec))
    }

    fun delete(t: Txn, wholeInstallmentPlan: Boolean = false) = db.transaction {
        Log.d("Txn") { "deleted ${t.id}${if (t.transferGroupId != null) " (transfer pair)" else if (t.installmentGroupId != null && wholeInstallmentPlan) " (installment plan)" else ""}" }
        val ts = now()
        when {
            t.transferGroupId != null -> q.softDeleteTransferGroup(ts, t.transferGroupId)
            t.installmentGroupId != null && wholeInstallmentPlan -> q.softDeleteInstallmentGroup(ts, t.installmentGroupId)
            else -> q.softDeleteTxn(ts, t.id)
        }
    }

    /** Converts a draft amount into the three stored figures: main currency, entered currency, account currency. */
    private class Money(val main: Long, val currency: String?, val original: Long?, val account: Long?, val rate: Double?)

    private fun money(amount: Long, entered: Currency?, accountCurrency: Currency?, mainDec: Int): Money {
        val main = Fx.toMain(amount, entered, mainDec)
        val inAccount = if (accountCurrency == null) null else Fx.convert(amount, entered, accountCurrency, mainDec)
        return Money(main, entered?.code, entered?.let { amount }, inAccount, entered?.rate)
    }

    private fun writeDraft(d: TxnDraft, recurringRuleId: String?, fixedId: String?): String {
        val created = now()
        val mainDec = mainDecimals()
        // The entry's own rate when editing (d.rate), otherwise today's.
        val entered = currencyNow(d.currency)?.let { c -> d.rate?.let { c.copy(rate = it) } ?: c }
        val srcCur = currencyNow(accountNow(d.accountId)?.currency)
        val ids = mutableListOf<String>()
        val primary = when (d.type) {
            DraftType.INCOME, DraftType.EXPENSE, DraftType.REFUND -> {
                val kind = d.type.primaryKind()
                if (d.type == DraftType.EXPENSE && d.installments > 1) {
                    val group = newId()
                    splitInstallments(d.amount, d.installments).mapIndexed { i, part ->
                        val m = money(part, entered, srcCur, mainDec)
                        val t = Txn(newId(), kind, d.date.plus(DatePeriod(months = i)), created + i, d.accountId,
                            categoryId = d.categoryId, amount = m.main, note = d.note, memo = d.memo,
                            installmentGroupId = group, installmentLabel = "(${i + 1}/${d.installments})",
                            currency = m.currency, originalAmount = m.original, accountAmount = m.account, rate = m.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay,
                            origin = d.origin)
                        insert(t); ids += t.id; t.id
                    }.first()
                } else {
                    val m = money(d.amount * d.type.sign, entered, srcCur, mainDec)
                    val t = Txn(fixedId ?: newId(), kind, d.date, created, d.accountId, categoryId = d.categoryId,
                        amount = m.main, note = d.note, memo = d.memo, recurringRuleId = recurringRuleId,
                        currency = m.currency, originalAmount = m.original, accountAmount = m.account, rate = m.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay,
                        origin = d.origin)
                    insert(t, ignoreIfExists = fixedId != null); ids += t.id; t.id
                }
            }
            DraftType.TRANSFER -> {
                val to = requireNotNull(d.toAccountId) { "Transfer needs a destination" }
                val dstCur = currencyNow(accountNow(to)?.currency)
                val group = newId()
                val outId = fixedId ?: newId()
                val ignore = fixedId != null
                val out = money(d.amount, entered, srcCur, mainDec)
                val inn = money(d.amount, entered, dstCur, mainDec)
                insert(Txn(outId, TxnKind.TRANSFER_OUT, d.date, created, d.accountId, to, amount = out.main,
                    note = d.note, memo = d.memo, transferGroupId = group, recurringRuleId = recurringRuleId,
                    currency = out.currency, originalAmount = out.original, accountAmount = out.account, rate = out.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay), ignore)
                insert(Txn(if (ignore) "${outId}_in" else newId(), TxnKind.TRANSFER_IN, d.date, created, to, d.accountId,
                    amount = inn.main, note = d.note, memo = d.memo, transferGroupId = group, recurringRuleId = recurringRuleId,
                    currency = inn.currency, originalAmount = inn.original, accountAmount = inn.account, rate = inn.rate, timeOfDay = d.timeOfDay), ignore)
                if (d.fee > 0) {
                    val f = money(d.fee, entered, srcCur, mainDec)
                    insert(Txn(if (ignore) "${outId}_fee" else newId(), TxnKind.EXPENSE, d.date, created + 1, d.accountId,
                        categoryId = d.categoryId, amount = f.main, note = "Transfer fee", transferGroupId = group,
                        currency = f.currency, originalAmount = f.original, accountAmount = f.account, rate = f.rate, timeOfDay = d.timeOfDay), ignore)
                }
                ids += outId
                outId
            }
        }
        if (d.tagIds.isNotEmpty()) ids.forEach { setTags(it, d.tagIds) }
        return primary
    }

    private fun insert(t: Txn, ignoreIfExists: Boolean = false) {
        val row = TxnRow(t.id, t.kind.name, t.date.toEpochDays().toLong(), t.createdAt, t.accountId, t.toAccountId,
            t.categoryId, t.amount, t.note, t.memo, t.transferGroupId, t.installmentGroupId, t.installmentLabel,
            t.recurringRuleId, 0, now(), t.currency, t.originalAmount, t.accountAmount, t.lat, t.lng, t.timeOfDay?.toLong(), t.origin, t.rate)
        if (ignoreIfExists) q.insertTxnIfAbsent(row) else q.upsertTxn(row)
    }

    fun allTxns(): List<Txn> = q.allTxns().executeAsList().map { it.toDomain() }

    /** Rebuilds an editor draft from a stored transaction (for copy / templates). Amount is in the entered currency. */
    fun draftFrom(t: Txn): TxnDraft {
        val (type, from, to, fee, cat) = when {
            t.transferGroupId != null -> {
                val group = transferGroup(t.transferGroupId)
                val out = group.firstOrNull { it.kind == TxnKind.TRANSFER_OUT } ?: t
                val feeRow = group.firstOrNull { it.kind == TxnKind.EXPENSE }
                Quint(DraftType.TRANSFER, out.accountId, out.toAccountId, feeRow?.enteredAmount ?: 0L, feeRow?.categoryId)
            }
            t.kind == TxnKind.INCOME -> Quint(DraftType.INCOME, t.accountId, null, 0L, t.categoryId)
            t.isRefund -> Quint(DraftType.REFUND, t.accountId, null, 0L, t.categoryId)
            else -> Quint(DraftType.EXPENSE, t.accountId, null, 0L, t.categoryId)
        }
        val signed = if (t.installmentGroupId != null) installmentGroup(t.installmentGroupId).sumOf { it.enteredAmount } else t.enteredAmount
        val amount = signed * type.sign
        return TxnDraft(type, t.date, from, to, cat, amount, fee, t.note, t.memo,
            installments = t.installmentGroupId?.let { installmentGroup(it).size } ?: 1, currency = t.currency, tagIds = tagsOf(t.id),
            timeOfDay = t.timeOfDay, origin = t.origin)
    }

    private data class Quint(val a: DraftType, val b: String, val c: String?, val d: Long, val e: String?)

    // ───────────────────────── learning (tiny Naive Bayes) and message inbox

    /** Remembers that these words went with this category / account. Cheap: a few row updates. */
    fun learn(tokens: List<String>, categoryId: String?, accountId: String?) = db.transaction {
        val labels = listOfNotNull(categoryId?.let { "cat:$it" }, accountId?.let { "acc:$it" })
        for (label in labels) {
            q.seedLabel(label); q.bumpLabel(label)
            for (t in tokens.distinct().take(12)) { q.seedToken(t, label); q.bumpToken(t, label) }
        }
    }

    data class Suggestion(val categoryId: String?, val accountId: String?, val categoryConfidence: Double, val accountConfidence: Double)

    fun suggest(tokens: List<String>): Suggestion {
        if (tokens.isEmpty()) return Suggestion(null, null, 0.0, 0.0)
        val tc = q.tokenCounts(tokens.distinct()).executeAsList().associate { (it.token to it.label) to it.count }
        val lc = q.labelCounts().executeAsList().associate { it.label to it.count }
        val liveCats = q.allCategories().executeAsList().map { "cat:" + it.id }.toSet()
        val liveAccs = q.allAccounts().executeAsList().map { "acc:" + it.id }.toSet()
        val cat = TinyBayes.predict(tokens, tc, lc.filterKeys { it in liveCats }, "cat:")
        val acc = TinyBayes.predict(tokens, tc, lc.filterKeys { it in liveAccs }, "acc:")
        var catId = cat?.label?.removePrefix("cat:")
        // With sub-categories off, suggest the main category even if history learned a sub-category.
        if (catId != null && !settingNow(Prefs.USE_SUBCATEGORIES)) {
            catId = q.allCategories().executeAsList().firstOrNull { it.id == catId }?.parentId ?: catId
        }
        return Suggestion(catId, acc?.label?.removePrefix("acc:"), cat?.confidence ?: 0.0, acc?.confidence ?: 0.0)
    }

    /** Learns from a manually saved transaction: note words → category, plus account. */
    fun learnFromDraft(d: TxnDraft) {
        if (d.type == DraftType.TRANSFER) return
        val words = SmsParser.words(d.note)
        if (words.isNotEmpty()) learn(words, d.categoryId, d.accountId)
    }

    /**
     * Turns an incoming SMS into a pending suggestion. Returns null when the text
     * isn't a money movement or is a duplicate. Explicit rules win; otherwise match the payment
     * instrument and digits before learning / a unique bank name. Ambiguous accounts need review.
     */
    fun ingestMessage(source: String, sender: String, text: String, receivedAt: Long): Capture? {
        val from = Log.sender(sender)
        if (MessageRules.isBlocked(sender, text, MessageRules.lines(settingNow(Prefs.SMS_BLOCKED_SENDERS)),
                MessageRules.lines(settingNow(Prefs.SMS_BLOCKED_PHRASES)))) return null.also { Log.d("Sms") { "$source from $from: blocked by your rules" } }
        val p = SmsParser.parse(sender, cleanMessage(text), mainDecimals())
            ?: return null.also { Log.d("Sms") { "$source from $from: not a transaction (${text.length} chars)" } }
        val tokens = SmsParser.tokens(p)
        val s = suggest(tokens)
        val accountId = MessageRules.suggestAccount(accountsNow(), groupsNow(), sender, text, p, s.accountId)
        val vendor = p.vendor.orEmpty()
        val capture = Capture(
            newId(), receivedAt, source, sender, text,
            // Refunds are kept like stored entries: an expense with a negative amount.
            amount = if (p.isRefund) -p.amount else p.amount,
            kind = if (p.isCredit && !p.isRefund) TxnKind.INCOME else TxnKind.EXPENSE,
            merchant = listOfNotNull(vendor.ifBlank { null }, p.bank?.let { "· $it" }).joinToString(" "),
            accountId = accountId,
            status = CaptureStatus.PENDING,
        )
        if (!addCapture(capture)) return null.also { Log.d("Sms") { "$source from $from: already seen" } }
        // "Auto-save": straight into the books when the account is known.
        if (settingNow(Prefs.SMS_AUTO_SAVE) && capture.accountId != null && (capture.amount ?: 0) != 0L) {
            val d = draftFromCapture(capture)
            // Only complete entries are saved on their own: never without an account or a category.
            if (d.categoryId != null && d.accountId.isNotBlank()) {
                completeCapture(capture, d, create(d))
                Log.i("Sms") { "$source from $from: ${capture.kind} saved automatically (${capture.id})" }
                return capture.copy(status = CaptureStatus.SAVED)
            }
        }
        Log.i("Sms") { "$source from $from: ${capture.kind} waiting for review (${capture.id}); account ${if (accountId != null) "matched" else "unknown"}, category ${if (s.categoryId != null) "learned" else "unknown"}${if (p.isRefund) ", refund" else ""}" }
        return capture
    }

    private fun cleanMessage(text: String) = MessageRules.clean(text, MessageRules.lines(settingNow(Prefs.SMS_CONTENT_FILTER)))

    /** Builds an editor draft from a pending capture, filling category/account from what was learned. */
    fun draftFromCapture(c: Capture): TxnDraft {
        val p = SmsParser.parse(c.sender, cleanMessage(c.text), mainDecimals())
        val s = p?.let { suggest(SmsParser.tokens(it)) }
        val date = Instant.fromEpochMilliseconds(c.receivedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val knownCurrency = p?.currencyCode?.let { code -> currencyNow(code)?.code }
        val type = when {
            c.kind == TxnKind.INCOME -> DraftType.INCOME
            isRefund(c.kind, c.amount) -> DraftType.REFUND
            else -> DraftType.EXPENSE
        }
        // A learned category of the other kind (an income category for a refund, say) doesn't fit.
        val wantedKind = if (type == DraftType.INCOME) CategoryKind.INCOME.name else CategoryKind.EXPENSE.name
        val fits = { id: String -> q.allCategories().executeAsList().any { it.id == id && it.kind == wantedKind } }
        // What this bank/merchant was filed under before; failing that, what the person's own notes with these words
        // were filed under (the same suggestion the editor shows), so a one-tap Add lands in the right category.
        val category = s?.categoryId?.takeIf(fits)
            ?: p?.vendor?.let { SmsParser.words(it) }?.takeIf { it.isNotEmpty() }?.let { suggest(it).categoryId }?.takeIf(fits)
        val kind = if (type == DraftType.INCOME) TxnKind.INCOME else TxnKind.EXPENSE
        return TxnDraft(
            type, date,
            c.accountId ?: p?.let { MessageRules.suggestAccount(accountsNow(), groupsNow(), c.sender, c.text, it, s?.accountId) }
                ?: usualSmsAccount(p, kind, category).orEmpty(),
            categoryId = category, amount = (c.amount ?: 0) * type.sign,
            note = p?.vendor.orEmpty(), currency = knownCurrency,
            origin = if (settingNow(Prefs.SMS_SHOW_ORIGINAL) == SmsOriginal.OFF) "" else c.text,
        )
    }

    /**
     * When the message doesn't say which account, pre-fill the usual one so the entry is one tap
     * from done: the category's default account, then the account most used for this category,
     * then for this kind of entry, then the only account that fits. Only accounts that fit what
     * the message does say (credit card, bank, …) are considered. It's a pre-fill for review,
     * so auto-save still waits for a confident match.
     */
    private fun usualSmsAccount(p: SmsParser.Parsed?, kind: TxnKind, categoryId: String?): String? {
        val fits = MessageRules.candidates(accountsNow().filter { !it.hidden }, groupsNow(), p)
        // "… via slice" with "Slice account" and "HDFC card": the slice ones are the likelier pool.
        val pool = p?.bank?.let { bank -> fits.filter { it.name.contains(bank.substringBefore(' '), ignoreCase = true) } }
            ?.takeIf { it.isNotEmpty() } ?: fits
        val ids = pool.map { it.id }.toSet()
        if (ids.isEmpty()) return null
        return defaultAccountFor(categoryId, categoryById.now())?.takeIf { it in ids }
            ?: habitAccount(kind, categoryId, ids, p?.vendor.orEmpty(), p?.amount)
            ?: ids.singleOrNull()
    }

    /** Called when the user saves a capture: marks it and teaches the model vendor/bank/digits → category & account. */
    fun completeCapture(c: Capture, saved: TxnDraft, txnId: String) {
        Log.d("Sms") { "capture ${c.id} saved as $txnId; learning category=${saved.categoryId != null} account=${saved.accountId.isNotBlank()}" }
        setCaptureStatus(c.id, CaptureStatus.SAVED, txnId)
        SmsParser.parse(c.sender, cleanMessage(c.text), mainDecimals())?.let { learn(SmsParser.tokens(it), saved.categoryId, saved.accountId) }
    }

    // ───────────────────────── recurring

    val rules: CachedFlow<List<RecurringRule>> = cached({ q.activeRules() }) { rows -> rows.map { it.toDomain() } }

    fun saveRule(r: RecurringRule) = q.upsertRule(
        RuleRow(r.id, r.kind.name, r.accountId, r.toAccountId, r.categoryId, r.amount, r.note, r.repeat.name,
            r.startDate.toEpochDays().toLong(), r.nextDate.toEpochDays().toLong(), r.endDate?.toEpochDays()?.toLong(), 0, now(), r.currency, r.memo)
    )

    fun deleteRule(id: String) = q.softDeleteRule(now(), id)

    /**
     * Materialises every due occurrence up to today. Ids are deterministic ("<rule>_<epochDay>"),
     * so running this repeatedly never duplicates. Ended rules are soft-deleted.
     */
    fun generateRecurring(): Int = db.transactionWithResult {
        var created = 0
        // "On the first day of every month": the whole month's repeats are added as soon as it starts.
        val horizon = if (settingNow(Prefs.REPEAT_TIMING) == RepeatTiming.MONTH_START) {
            val ps = periodSettingsNow()
            ps.month(ps.labelFor(today())).endInclusive
        } else today()
        for (row in q.activeRules().executeAsList()) {
            val rule = row.toDomain()
            val (dates, next) = Recurrence.due(rule, horizon)
            for (date in dates) {
                val type = when {
                    rule.kind == TxnKind.INCOME -> DraftType.INCOME
                    rule.kind == TxnKind.TRANSFER_OUT -> DraftType.TRANSFER
                    isRefund(rule.kind, rule.amount) -> DraftType.REFUND
                    else -> DraftType.EXPENSE
                }
                writeDraft(TxnDraft(type, date, rule.accountId, rule.toAccountId, rule.categoryId, rule.amount * type.sign, note = rule.note, memo = rule.memo, currency = rule.currency),
                    recurringRuleId = rule.id, fixedId = "${rule.id}_${date.toEpochDays()}")
                created++
            }
            if (next == null) q.softDeleteRule(now(), rule.id)
            else if (dates.isNotEmpty()) saveRule(rule.copy(nextDate = next))
        }
        if (created > 0) Log.i("Repeat") { "added $created repeat entr${if (created == 1) "y" else "ies"}" }
        created
    }

    // ───────────────────────── budgets

    fun budgetEntries(upTo: YearMonth): CachedFlow<List<BudgetEntry>> = rangeCache.get("budget:${upTo.key}") { budgetEntriesUpTo(upTo) }
    private fun budgetEntriesUpTo(upTo: YearMonth) =
        cached({ q.budgetAmountsUpTo(upTo.key.toLong()) }) { rows -> rows.map { BudgetEntry(it.categoryId, it.period.toInt(), it.amount) } }

    /** Sets the budget from [from] onward (later explicit changes are replaced). 0 removes it. */
    fun setBudget(categoryId: String, from: YearMonth, amount: Long) = db.transaction {
        q.deleteBudgetFrom(categoryId, from.key.toLong())
        q.upsertBudgetAmount(app.moneytracker.db.BudgetAmount(categoryId, from.key.toLong(), amount, now()))
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
            writeDraft(draft, recurringRuleId = null, fixedId = null)
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
            insert(Txn(t.id, t.kind, t.date, t.createdAt, t.accountId, t.toAccountId, t.categoryId, t.amountMain, t.note, t.memo,
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
