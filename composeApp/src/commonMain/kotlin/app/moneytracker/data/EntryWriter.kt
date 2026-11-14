package app.moneytracker.data

import app.moneytracker.db.Txn as TxnRow
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Fx
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.splitInstallments
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

/**
 * Turns an editor draft into stored entry rows: one row, an installment plan, or a transfer's legs and fee, each
 * carrying its amount in main, entered and account currency. Shared by new entries, repeats and imports.
 */
class EntryWriter(
    private val db: Db,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val accounts: AccountStore,
    private val tags: TagStore,
) {
    private val q get() = db.q

    /** The rate [t] was made with: stored since v7, worked out from its two amounts for older entries. */
    fun rateOf(t: Txn): Double? {
        t.rate?.let { return it }
        val original = t.originalAmount?.takeIf { it != 0L } ?: return null
        val dec = currencies.currencyNow(t.currency)?.decimals ?: return null
        return (t.amount.toDouble() / CurrencyFormat.pow10(settings.mainDecimals())) / (original.toDouble() / CurrencyFormat.pow10(dec))
    }

    /** Converts a draft amount into the three stored figures: main currency, entered currency, account currency. */
    private class Money(val main: Long, val currency: String?, val original: Long?, val account: Long?, val rate: Double?)

    private fun money(amount: Long, entered: Currency?, accountCurrency: Currency?, mainDec: Int): Money {
        val main = Fx.toMain(amount, entered, mainDec)
        val inAccount = if (accountCurrency == null) null else Fx.convert(amount, entered, accountCurrency, mainDec)
        return Money(main, entered?.code, entered?.let { amount }, inAccount, entered?.rate)
    }

    /** What every row written for one draft shares; [ids] collects the rows that get the draft's tags. */
    private class Batch(val d: TxnDraft, val created: Long, val mainDec: Int, val entered: Currency?, val srcCur: Currency?) {
        val ids = mutableListOf<String>()
    }

    /** Writes the rows for [d]. Returns the id of the primary row. A [fixedId] (repeats) makes the write a no-op if it exists. */
    internal fun writeDraft(d: TxnDraft, recurringRuleId: String?, fixedId: String?): String {
        val created = db.now()
        val mainDec = settings.mainDecimals()
        // The entry's own rate when editing (d.rate), otherwise today's.
        val entered = currencies.currencyNow(d.currency)?.let { c -> d.rate?.let { c.copy(rate = it) } ?: c }
        val srcCur = currencies.currencyNow(accounts.accountNow(d.accountId)?.currency)
        val batch = Batch(d, created, mainDec, entered, srcCur)
        val primary = when (d.type) {
            DraftType.INCOME, DraftType.EXPENSE, DraftType.REFUND ->
                if (d.type == DraftType.EXPENSE && d.installments > 1) writeInstallments(batch)
                else writeSingle(batch, recurringRuleId, fixedId)
            DraftType.TRANSFER -> writeTransfer(batch, recurringRuleId, fixedId)
        }
        if (d.tagIds.isNotEmpty()) batch.ids.forEach { tags.setTags(it, d.tagIds) }
        return primary
    }

    private fun writeInstallments(b: Batch): String {
        val d = b.d
        val kind = d.type.primaryKind()
        val group = newId()
        return splitInstallments(d.amount, d.installments).mapIndexed { i, part ->
            val m = money(part, b.entered, b.srcCur, b.mainDec)
            val t = Txn(newId(), kind, d.date.plus(DatePeriod(months = i)), b.created + i, d.accountId,
                categoryId = d.categoryId, amount = m.main, note = d.note, memo = d.memo,
                installmentGroupId = group, installmentLabel = "(${i + 1}/${d.installments})",
                currency = m.currency, originalAmount = m.original, accountAmount = m.account, rate = m.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay,
                origin = d.origin)
            insert(t); b.ids += t.id; t.id
        }.first()
    }

    private fun writeSingle(b: Batch, recurringRuleId: String?, fixedId: String?): String {
        val d = b.d
        val m = money(d.amount * d.type.sign, b.entered, b.srcCur, b.mainDec)
        val t = Txn(fixedId ?: newId(), d.type.primaryKind(), d.date, b.created, d.accountId, categoryId = d.categoryId,
            amount = m.main, note = d.note, memo = d.memo, recurringRuleId = recurringRuleId,
            currency = m.currency, originalAmount = m.original, accountAmount = m.account, rate = m.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay,
            origin = d.origin)
        insert(t, ignoreIfExists = fixedId != null); b.ids += t.id
        return t.id
    }

    private fun writeTransfer(b: Batch, recurringRuleId: String?, fixedId: String?): String {
        val d = b.d
        val to = requireNotNull(d.toAccountId) { "Transfer needs a destination" }
        val dstCur = currencies.currencyNow(accounts.accountNow(to)?.currency)
        val group = newId()
        val outId = fixedId ?: newId()
        val ignore = fixedId != null
        val out = money(d.amount, b.entered, b.srcCur, b.mainDec)
        val inn = money(d.amount, b.entered, dstCur, b.mainDec)
        insert(Txn(outId, TxnKind.TRANSFER_OUT, d.date, b.created, d.accountId, to, amount = out.main,
            note = d.note, memo = d.memo, transferGroupId = group, recurringRuleId = recurringRuleId,
            currency = out.currency, originalAmount = out.original, accountAmount = out.account, rate = out.rate, lat = d.lat, lng = d.lng, timeOfDay = d.timeOfDay), ignore)
        insert(Txn(if (ignore) "${outId}_in" else newId(), TxnKind.TRANSFER_IN, d.date, b.created, to, d.accountId,
            amount = inn.main, note = d.note, memo = d.memo, transferGroupId = group, recurringRuleId = recurringRuleId,
            currency = inn.currency, originalAmount = inn.original, accountAmount = inn.account, rate = inn.rate, timeOfDay = d.timeOfDay), ignore)
        if (d.fee > 0) {
            val f = money(d.fee, b.entered, b.srcCur, b.mainDec)
            insert(Txn(if (ignore) "${outId}_fee" else newId(), TxnKind.EXPENSE, d.date, b.created + 1, d.accountId,
                categoryId = d.categoryId, amount = f.main, note = "Transfer fee", transferGroupId = group,
                currency = f.currency, originalAmount = f.original, accountAmount = f.account, rate = f.rate, timeOfDay = d.timeOfDay), ignore)
        }
        b.ids += outId
        return outId
    }

    /** Stores [t] as it is (replacing a row with the same id unless [ignoreIfExists]). */
    internal fun insert(t: Txn, ignoreIfExists: Boolean = false) {
        val row = TxnRow(t.id, t.kind.name, t.date.toEpochDays().toLong(), t.createdAt, t.accountId, t.toAccountId,
            t.categoryId, t.amount, t.note, t.memo, t.transferGroupId, t.installmentGroupId, t.installmentLabel,
            t.recurringRuleId, 0, db.now(), t.currency, t.originalAmount, t.accountAmount, t.lat, t.lng, t.timeOfDay?.toLong(), t.origin, t.rate)
        if (ignoreIfExists) q.insertTxnIfAbsent(row) else q.upsertTxn(row)
    }
}
