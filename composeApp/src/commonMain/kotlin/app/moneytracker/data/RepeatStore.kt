package app.moneytracker.data

import app.moneytracker.Log
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.RecurringRule
import app.moneytracker.db.RecurringRule as RuleRow

/** Repeats: rules that add the same entry on a schedule, and turning their due dates into real entries. */
class RepeatStore(private val db: Db, private val settings: SettingsStore, private val writer: EntryWriter) {
    private val q get() = db.q

    val rules: CachedFlow<List<RecurringRule>> = db.cached({ q.activeRules() }) { rows -> rows.map { it.toDomain() } }

    fun saveRule(r: RecurringRule) = q.upsertRule(
        RuleRow(r.id, r.kind.name, r.accountId, r.toAccountId, r.categoryId, r.amount, r.note, r.repeat.name,
            r.startDate.toEpochDays().toLong(), r.nextDate.toEpochDays().toLong(), r.endDate?.toEpochDays()?.toLong(), 0, db.now(), r.currency, r.memo)
    )

    fun deleteRule(id: String) = q.softDeleteRule(db.now(), id)

    /**
     * Materialises every due occurrence up to today. Ids are deterministic ("<rule>_<epochDay>"),
     * so running this repeatedly never duplicates. Ended rules are soft-deleted.
     */
    fun generateRecurring(): Int = db.transactionWithResult {
        var created = 0
        // "On the first day of every month": the whole month's repeats are added as soon as it starts.
        val horizon = if (settings.settingNow(Prefs.REPEAT_TIMING) == RepeatTiming.MONTH_START) {
            val ps = settings.periodSettingsNow()
            ps.month(ps.labelFor(db.today())).endInclusive
        } else db.today()
        for (row in q.activeRules().executeAsList()) {
            val rule = row.toDomain()
            val (dates, next) = Recurrence.due(rule, horizon)
            for (date in dates) {
                val type = rule.kind.toDraftType(rule.amount)
                writer.writeDraft(TxnDraft(type, date, rule.accountId, rule.toAccountId, rule.categoryId, rule.amount * type.sign, note = rule.note, memo = rule.memo, currency = rule.currency),
                    recurringRuleId = rule.id, fixedId = "${rule.id}_${date.toEpochDays()}")
                created++
            }
            if (next == null) q.softDeleteRule(db.now(), rule.id)
            else if (dates.isNotEmpty()) saveRule(rule.copy(nextDate = next))
        }
        if (created > 0) Log.i("Repeat") { "added $created repeat entr${if (created == 1) "y" else "ies"}" }
        created
    }
}
