package app.moneytracker.ui

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.DraftType
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatStore
import app.moneytracker.data.RepeatTiming
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** Accounts and categories as a repeat rule shows and edits them. */
data class RuleLookups(
    val accounts: List<Account>,
    val categories: List<Category>,
    val accountNames: Map<String, String>,
    val categoryById: Map<String, Category>,
)

/** Everything the Repeat entries screen draws, in both layouts. */
data class RepeatsState(val rules: List<RecurringRule>, val timing: RepeatTiming, val lookups: RuleLookups) {
    /** Rules grouped as both layouts list them: income, spending, transfers; empty groups left out. */
    val groups: List<Pair<TxnKind, List<RecurringRule>>>
        get() = listOf(TxnKind.INCOME, TxnKind.EXPENSE, TxnKind.TRANSFER_OUT)
            .map { kind -> kind to rules.filter { it.kind == kind } }
            .filter { it.second.isNotEmpty() }
}

/**
 * The Repeat entries screen: the active rules, when repeats are added, and changing or stopping a rule.
 * Every change runs the repeats again, so entries that became due are added at once.
 */
class RepeatsModel(
    private val repeats: RepeatStore,
    private val settings: SettingsStore,
    accounts: AccountStore,
    categories: CategoryStore,
    private val io: CoroutineDispatcher,
    private val today: () -> LocalDate,
    private val scope: CoroutineScope,
) {
    private val lookups = combine(accounts.accounts, categories.categories, accounts.accountNames, categories.categoryById, ::RuleLookups)

    val state: StateFlow<RepeatsState> = combine(repeats.rules, settings.setting(Prefs.REPEAT_TIMING), lookups, ::RepeatsState)
        .stateIn(
            scope, SharingStarted.Eagerly,
            RepeatsState(
                repeats.rules.current, Prefs.REPEAT_TIMING.default,
                RuleLookups(accounts.accounts.current, categories.categories.current, accounts.accountNames.current, categories.categoryById.current),
            ),
        )

    /** A blank monthly expense for the entry editor, the way to start a new repeat. */
    fun newRepeatDraft() = TxnDraft(DraftType.EXPENSE, today(), "", amount = 0, repeat = RepeatType.MONTHLY)

    fun saveRule(rule: RecurringRule) = write { repeats.saveRule(rule); repeats.generateRecurring() }

    /** Stops the rule; the entries it already created stay. */
    fun stopRule(rule: RecurringRule) = write { repeats.deleteRule(rule.id) }

    /** Undo for [stopRule]. */
    fun restoreRule(rule: RecurringRule) = write { repeats.saveRule(rule) }

    fun setTiming(timing: RepeatTiming) = write { settings.putSetting(Prefs.REPEAT_TIMING, timing); repeats.generateRecurring() }

    private fun write(block: () -> Unit) {
        scope.launch { withContext(io) { block() } }
    }
}
