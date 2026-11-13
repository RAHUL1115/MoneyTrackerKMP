package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.moneytracker.Log
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Fx
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Entries: reading them, creating, editing and deleting them (with transfer legs and installment plans), bulk edits and search. */
class TxnStore(
    private val db: Db,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val accounts: AccountStore,
    private val tags: TagStore,
    private val writer: EntryWriter,
    private val repeats: RepeatStore,
) {
    private val q get() = db.q

    fun txnsBetween(range: DateRange): CachedFlow<List<Txn>> = db.rangeCache.get("txns:$range") { txnsIn(range) }
    /** A fresh read for background threads; doesn't touch the per-range cache. */
    fun txnsNow(range: DateRange): List<Txn> = txnsIn(range).now()
    private fun txnsIn(range: DateRange): CachedFlow<List<Txn>> = run {
        val from = range.start.toEpochDays().toLong(); val to = range.endInclusive.toEpochDays().toLong()
        CachedFlow(combine(q.txnsBetween(from, to).asFlow().mapToList(db.io), settings.setting(Prefs.TIME_SORT)) { rows, sort ->
            sortWithinDays(rows.map { it.toDomain() }, ascending = sort == TimeSort.ASC)
        }) { sortWithinDays(q.txnsBetween(from, to).executeAsList().map { it.toDomain() }, ascending = settings.settingNow(Prefs.TIME_SORT) == TimeSort.ASC) }
    }

    /** Newest day first; within a day by recorded time (entries without one by entry order), newest first unless [ascending]. */
    fun sortWithinDays(txns: List<Txn>, ascending: Boolean = false): List<Txn> {
        val inDay = compareBy<Txn>({ it.timeOfDay ?: -1 }, { it.createdAt })
        return txns.sortedWith(compareByDescending<Txn> { it.date }.then(if (ascending) inDay else inDay.reversed()))
    }

    fun accountTxns(accountId: String): List<Txn> = q.txnsForAccount(accountId).executeAsList().map { it.toDomain() }

    fun accountTxnsFlow(accountId: String, range: DateRange): Flow<List<Txn>> =
        q.txnsForAccountBetween(accountId, range.start.toEpochDays().toLong(), range.endInclusive.toEpochDays().toLong())
            .asFlow().mapToList(db.io).map { rows -> rows.map { it.toDomain() } }

    fun allTxns(): List<Txn> = q.allTxns().executeAsList().map { it.toDomain() }

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
        val tagged = tagIds.flatMap { tags.txnIdsWithTag(it) }.toSet()
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
            repeats.saveRule(rule)
            repeats.generateRecurring()
            Log.d("Txn") { "repeat rule ${rule.id} created (${d.type}, ${d.repeat})" }
            return@transactionWithResult rule.id
        }
        writer.writeDraft(d, recurringRuleId = null, fixedId = null).also { Log.d("Txn") { "created $it (${d.type})" } }
    }

    /** Replaces an existing transaction (and its transfer pair / fee / installment group) with [d]. */
    fun update(existing: Txn, d: TxnDraft): String = db.transactionWithResult {
        // Same currency as before: keep the rate the entry was made with, so a later rate update doesn't change it.
        val rate = d.rate ?: if (d.currency != null && d.currency == existing.currency) writer.rateOf(existing) else null
        delete(existing, wholeInstallmentPlan = true)
        writer.writeDraft(d.copy(repeat = null, rate = rate), recurringRuleId = existing.recurringRuleId, fixedId = null)
            .also { Log.d("Txn") { "updated ${existing.id} → $it (${d.type})" } }
    }

    fun delete(t: Txn, wholeInstallmentPlan: Boolean = false) = db.transaction {
        Log.d("Txn") { "deleted ${t.id}${if (t.transferGroupId != null) " (transfer pair)" else if (t.installmentGroupId != null && wholeInstallmentPlan) " (installment plan)" else ""}" }
        val ts = db.now()
        when {
            t.transferGroupId != null -> q.softDeleteTransferGroup(ts, t.transferGroupId)
            t.installmentGroupId != null && wholeInstallmentPlan -> q.softDeleteInstallmentGroup(ts, t.installmentGroupId)
            else -> q.softDeleteTxn(ts, t.id)
        }
    }

    /** The parts of a draft that depend on what kind of entry [draftFrom] is looking at. */
    private data class DraftShape(val type: DraftType, val from: String, val to: String?, val fee: Long, val categoryId: String?)

    /** Rebuilds an editor draft from a stored transaction (for copy / templates). Amount is in the entered currency. */
    fun draftFrom(t: Txn): TxnDraft {
        val shape = if (t.transferGroupId != null) {
            val group = transferGroup(t.transferGroupId)
            val out = group.firstOrNull { it.kind == TxnKind.TRANSFER_OUT } ?: t
            val feeRow = group.firstOrNull { it.kind == TxnKind.EXPENSE }
            DraftShape(DraftType.TRANSFER, out.accountId, out.toAccountId, feeRow?.enteredAmount ?: 0L, feeRow?.categoryId)
        } else {
            // A transfer leg that lost its group can't reopen as a transfer, so it opens as a plain expense.
            val type = if (t.kind.isTransfer) DraftType.EXPENSE else t.kind.toDraftType(t.amount)
            DraftShape(type, t.accountId, null, 0L, t.categoryId)
        }
        val signed = if (t.installmentGroupId != null) installmentGroup(t.installmentGroupId).sumOf { it.enteredAmount } else t.enteredAmount
        val amount = signed * shape.type.sign
        return TxnDraft(shape.type, t.date, shape.from, shape.to, shape.categoryId, amount, shape.fee, t.note, t.memo,
            installments = t.installmentGroupId?.let { installmentGroup(it).size } ?: 1, currency = t.currency, tagIds = tags.tagsOf(t.id),
            timeOfDay = t.timeOfDay, origin = t.origin)
    }

    // ───────────────────────── balance adjustments

    /**
     * Records the difference to [target] (account currency) under "Modified Bal." with the note "Difference": by
     * default as a neutral adjustment that only the account's own page shows, or with [asIncomeExpense] as a regular
     * income or expense entry that lists and stats count.
     */
    fun adjustBalance(accountId: String, target: Long, asIncomeExpense: Boolean = false) {
        val current = q.balanceOf(accountId, db.today().toEpochDays().toLong()).executeAsOne().toLong()
        val diff = target - current
        if (diff == 0L) return
        val cur = currencies.currencyNow(accounts.accountNow(accountId)?.currency)
        val abs = kotlin.math.abs(diff)
        val kind = when {
            asIncomeExpense -> if (diff > 0) TxnKind.INCOME else TxnKind.EXPENSE
            else -> if (diff > 0) TxnKind.ADJUST_UP else TxnKind.ADJUST_DOWN
        }
        writer.insert(Txn(newId(), kind, db.today(), db.now(), accountId, categoryId = MODIFIED_BALANCE_CATEGORY,
            amount = Fx.toMain(abs, cur, settings.mainDecimals()), note = "Difference",
            currency = cur?.code, originalAmount = cur?.let { abs }, accountAmount = cur?.let { abs }, rate = cur?.rate))
    }

    /** Edits an adjustment row in place. [amount] is in the account's currency; [up] = increase. */
    fun updateAdjustment(t: Txn, date: LocalDate, amount: Long, accountId: String, up: Boolean, note: String, memo: String = t.memo, timeOfDay: Int? = t.timeOfDay) {
        val today = currencies.currencyNow(accounts.accountNow(accountId)?.currency)
        // Same currency: keep the adjustment's own rate.
        val cur = today?.let { c -> (if (c.code == t.currency) writer.rateOf(t) else null)?.let { c.copy(rate = it) } ?: c }
        writer.insert(t.copy(
            kind = if (up) TxnKind.ADJUST_UP else TxnKind.ADJUST_DOWN, date = date, accountId = accountId, note = note, memo = memo, timeOfDay = timeOfDay,
            amount = Fx.toMain(amount, cur, settings.mainDecimals()), currency = cur?.code, originalAmount = cur?.let { amount }, accountAmount = cur?.let { amount },
            rate = cur?.rate,
        ))
    }

    // ───────────────────────── bulk edits (multi-select)

    /** Deletes each entry the way the editor would (transfer legs and fees together). */
    fun deleteMany(ids: Collection<String>) = db.transaction { ids.mapNotNull { txn(it) }.forEach { delete(it) } }

    fun setDateMany(ids: Collection<String>, date: LocalDate) = db.transaction {
        ids.mapNotNull { txn(it) }.forEach { t -> q.setTxnDate(date.toEpochDays().toLong(), db.now(), t.id, t.transferGroupId ?: "") }
    }

    /** Only income/expense rows of the matching kind take the category. Returns how many changed. */
    fun setCategoryMany(ids: Collection<String>, categoryId: String): Int = db.transactionWithResult {
        val kind = q.allCategories().executeAsList().firstOrNull { it.id == categoryId }?.kind ?: return@transactionWithResult 0
        val wanted = if (kind == CategoryKind.INCOME.name) TxnKind.INCOME else TxnKind.EXPENSE
        ids.mapNotNull { txn(it) }.filter { it.kind == wanted && it.transferGroupId == null }
            .onEach { q.setTxnCategory(categoryId, db.now(), it.id) }.size
    }

    /** Moves income/expense rows to another account (transfers keep their accounts). Returns how many changed. */
    fun setAccountMany(ids: Collection<String>, accountId: String): Int = db.transactionWithResult {
        ids.mapNotNull { txn(it) }.filter { it.kind.isIncomeOrExpense && it.transferGroupId == null }
            .onEach { q.setTxnAccount(accountId, db.now(), it.id) }.size
    }
}
