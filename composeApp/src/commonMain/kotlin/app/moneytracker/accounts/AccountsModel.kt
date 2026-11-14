package app.moneytracker.accounts

import app.moneytracker.data.AccountStore
import app.moneytracker.data.AccountSummary
import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.ReadModels
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TxnStore
import app.moneytracker.domain.Account
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/** Net worth at one month's end, in the main currency, over the accounts counted in totals. */
data class NetWorthPoint(val month: YearMonth, val assets: Long, val liabilities: Long) {
    val net: Long get() = assets - liabilities
}

/** The accounts tab of both layouts: every account's balance and card statement, net worth, and its trend. */
class AccountsModel(
    private val views: ReadModels,
    private val accounts: AccountStore,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val txns: TxnStore,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    /**
     * Starts from the last summary worked out, so a reopened tab draws at once; null until there is one.
     * Worked out only once asked for, so the trend screen doesn't pay for it.
     */
    val summary: StateFlow<AccountSummary?> by lazy { views.accounts.stateIn(scope, SharingStarted.Eagerly, views.latestAccounts) }

    /** Net worth at the end of each of the last [months] months, worked out again when accounts, periods or currencies change. */
    fun netWorthTrend(months: Int): Flow<List<NetWorthPoint>> =
        combine(accounts.accounts, settings.periodSettings, currencies.currencies) { a, ps, c -> Triple(a, ps, c) }
            .distinctUntilChanged()
            .map { (a, ps, _) -> netWorthTrendNow(a, ps, months) }
            .flowOn(io)

    /** Blocking. Each account's balance at a month's end counts as an asset when above zero, a liability below it. */
    private fun netWorthTrendNow(accountList: List<Account>, ps: PeriodSettings, months: Int): List<NetWorthPoint> {
        val all = txns.allTxns()
        val included = accountList.filter { it.includeInTotals }.map { it.id }.toSet()
        val current = ps.labelFor(today())
        return (months - 1 downTo 0).map { back ->
            val ym = current.plus(-back)
            val end = ps.month(ym).endInclusive
            val perAccount = all.filter { it.date <= end && it.accountId in included }
                .groupBy { it.accountId }.mapValues { (_, v) -> v.sumOf { it.signedAmount } }
            NetWorthPoint(ym, perAccount.values.filter { it > 0 }.sum(), -perAccount.values.filter { it < 0 }.sum())
        }
    }
}
