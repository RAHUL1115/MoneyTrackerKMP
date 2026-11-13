package app.moneytracker.data

import app.moneytracker.domain.AccountType
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Currency
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.db.BudgetAmount as BudgetRow
import app.moneytracker.db.Note as NoteRow

data class LegacyResult(
    val transactions: Int, val accounts: Int, val deletedAccounts: Int, val categories: Int,
    val recurring: Int, val budgets: Int, val skipped: List<String>,
)

/**
 * Moves the whole book to and from [MoneyManagerBackup.Data], the shape the Money Manager (.mmbak) importer and
 * exporter work in. It reads and writes every table, so it sits beside the stores rather than inside one.
 */
class MoneyManagerBridge(
    private val db: Db,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val tags: TagStore,
    private val learning: LearningStore,
    private val repeats: RepeatStore,
    private val writer: EntryWriter,
    private val txns: TxnStore,
) {
    private val q get() = db.q

    /** Everything a Money Manager backup can hold, in the importer's shape, for [MoneyManagerExport]. Deleted accounts and categories are kept so old entries keep their names. */
    fun moneyManagerData(): MoneyManagerBackup.Data {
        val keys = Prefs.carriedByMoneyManager.map { it.key }
        val mainCode = settings.settingNow(Prefs.CURRENCY_CODE)
        return MoneyManagerBackup.Data(
            mainCode = mainCode, mainSymbol = settings.settingNow(Prefs.CURRENCY_SYMBOL), mainDecimals = settings.mainDecimals(),
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
            txns = txns.allTxns().let { all ->
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
            settings = keys.mapNotNull { k -> settings.settingNow(k)?.let { k to it } }.toMap(),
            skipped = emptyList(),
        )
    }

    /**
     * Replaces everything with the contents of a Money Manager backup, in one database transaction
     * (any failure leaves current data untouched). Settings like passcode are kept.
     */
    fun replaceWithMoneyManager(d: MoneyManagerBackup.Data): LegacyResult = db.transactionWithResult {
        listOf(q::wipeTxn, q::wipeAccount, q::wipeAccountGroup, q::wipeCategory, q::wipeBudget, q::wipeRules, q::wipeTags,
            q::wipeTxnTags, q::wipeCurrencies, q::wipeNotes, q::wipePhotos, q::wipeTemplates, q::wipeLearning, q::wipeLearnLabels, q::wipeCaptures)
            .forEach { it() }
        val ts = db.now()
        restoreSettingsAndCurrencies(d)
        restoreAccountsAndCategories(d, ts)
        restoreEntries(d, ts)
        // Teach the suggestion model from history: note words → category & account.
        d.txns.filter { it.kind.isIncomeOrExpense && it.note.isNotBlank() }
            .forEach { learning.learn(SmsParser.words(it.note), it.categoryId, it.accountId) }
        LegacyResult(
            d.txns.size, d.accounts.count { !it.deleted }, d.accounts.count { it.deleted }, d.categories.count { !it.deleted },
            d.rules.size, d.budgets.size, d.skipped,
        )
    }

    private fun restoreSettingsAndCurrencies(d: MoneyManagerBackup.Data) {
        settings.putSetting(Prefs.CURRENCY_SYMBOL, d.mainSymbol)
        settings.putSetting(Prefs.CURRENCY_DECIMALS, d.mainDecimals)
        settings.putSetting(Prefs.CURRENCY_CODE, d.mainCode)
        d.settings.forEach { (k, v) -> settings.putSetting(k, v) }
        d.currencies.forEachIndexed { i, c -> currencies.saveCurrency(Currency(c.code, c.symbol, c.decimals, c.rate, i)) }
    }

    private fun restoreAccountsAndCategories(d: MoneyManagerBackup.Data, ts: Long) {
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
    }

    /** Entries, repeats, budgets, tags and memos, in that order (tags before the links that point at them). */
    private fun restoreEntries(d: MoneyManagerBackup.Data, ts: Long) {
        d.txns.forEach { t ->
            writer.insert(Txn(t.id, t.kind, t.date, t.createdAt, t.accountId, t.toAccountId, t.categoryId, t.amountMain, t.note, t.memo,
                t.transferGroupId, t.installmentGroupId, t.installmentLabel, t.recurringRuleId, t.currency, t.originalAmount, t.accountAmount, t.lat, t.lng,
                t.timeOfDay, t.origin))
        }
        d.rules.forEach { r ->
            repeats.saveRule(RecurringRule(r.id, r.kind, r.accountId, r.toAccountId, r.categoryId, r.amount, r.note, r.repeat, r.nextDate, r.nextDate, r.endDate, r.currency, r.memo))
        }
        d.budgets.forEach { b -> q.upsertBudgetAmount(BudgetRow(b.categoryId, b.period.toLong(), b.amount, ts)) }
        d.tags.forEach { tags.saveTag(Tag(it.id, it.name, it.sortOrder)) }
        tags.ensureSystemTags()
        d.txnTags.forEach { (txn, tag) -> q.addTxnTag(txn, tag) }
        d.memos.forEach { m -> q.upsertNote(NoteRow(m.id, m.title, m.content, 0, m.pinned.toLong(), m.time, ts, 0)) }
    }
}
