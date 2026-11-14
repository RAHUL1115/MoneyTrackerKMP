package app.moneytracker.web

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.PeriodSettings
import app.moneytracker.data.Prefs
import app.moneytracker.data.ReadModels
import app.moneytracker.data.ReportStore
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnStore
import app.moneytracker.data.moneymanager.TabularImport
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Fx
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.YearMonth
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// The dashboard's read-only routes: what the page needs to start, the month summary, accounts and the CSV export.

/** /api/meta: currency, accounts, categories and tags, so the page can draw and fill its forms. */
internal class WebMeta(
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val accounts: AccountStore,
    private val categories: CategoryStore,
    private val tags: TagStore,
    private val today: LocalDate,
) {
    fun json(): Map<String, Any?> {
        val fmt = settings.currencyFormatNow()
        val groups = accounts.groupsNow()
        val groupType = groups.associate { it.id to it.type }
        return mapOf(
            "currency" to mapOf("symbol" to fmt.symbol, "decimals" to fmt.decimals, "after" to fmt.symbolAfter,
                "code" to (settings.settingNow(Prefs.CURRENCY_CODE) ?: "INR")),
            "currencies" to currencies.currenciesNow().map { mapOf("code" to it.code, "symbol" to it.symbol, "decimals" to it.decimals) },
            "today" to today.toString(),
            "month" to settings.periodSettingsNow().labelFor(today).key,
            "groups" to groups.map { mapOf("id" to it.id, "name" to it.name, "type" to it.type.name) },
            "accounts" to accounts.accountsNow().map {
                mapOf("id" to it.id, "name" to it.name, "groupId" to it.groupId, "hidden" to it.hidden, "currency" to it.currency,
                    "card" to (groupType[it.groupId]?.isCreditCard == true))
            },
            "categories" to categories.categoriesNow().map { mapOf("id" to it.id, "name" to it.name, "kind" to it.kind.name, "parentId" to it.parentId) } +
                mapOf("id" to MODIFIED_BALANCE_CATEGORY, "name" to MODIFIED_BALANCE_NAME, "kind" to "SYSTEM", "parentId" to null),
            "tags" to tags.tagsNow().map { mapOf("id" to it.id, "name" to it.name) },
            "subcategories" to settings.settingNow(Prefs.USE_SUBCATEGORIES),
        )
    }
}

/** /api/summary: six months of totals and the month's budgets, the same numbers as the phone. */
internal class WebSummary(
    private val settings: SettingsStore,
    private val reports: ReportStore,
    private val categories: CategoryStore,
    private val views: ReadModels,
    private val today: LocalDate,
) {
    fun json(query: Map<String, String>): Map<String, Any?> {
        val ps = settings.periodSettingsNow()
        val ym = requestedMonth(query, ps, today)
        val trend = (5 downTo 0).map { back ->
            val m = ym.plus(-back)
            val t = reports.totalsNow(ps.month(m))
            mapOf("ym" to m.key, "income" to t.income, "expense" to t.expense)
        }
        val range = ps.month(ym)
        val catById = categories.categoryByIdNow()
        val lines = views.budgetNow(ym).lines
        return mapOf(
            "ym" to ym.key, "from" to range.start.toString(), "to" to range.endInclusive.toString(),
            "trend" to trend, "daysSoFar" to daysSoFar(range, today), "days" to range.days(),
            "budgets" to lines.map { l ->
                mapOf("category" to l.categoryId.ifEmpty { null }, "name" to if (l.categoryId == TOTAL_BUDGET) "All spending" else catById[l.categoryId]?.name ?: "Category",
                    "budget" to l.budget, "spent" to l.spent)
            },
        )
    }
}

/** /api/accounts: net worth and the visible accounts by group, the same summary as the phone. */
internal class WebAccounts(
    private val accounts: AccountStore,
    private val currencies: CurrencyStore,
    private val settings: SettingsStore,
    private val views: ReadModels,
    private val today: LocalDate,
) {
    fun json(): Map<String, Any?> {
        val groups = accounts.groupsNow()
        val all = currencies.currenciesNow()
        val mainDecimals = settings.currencyFormatNow().decimals
        val (lines, worth) = views.accountsNow()
        val byGroup = lines.filter { !it.account.hidden }.groupBy { it.group.id }
        return mapOf(
            "today" to today.toString(),
            "assets" to worth.assets, "liabilities" to worth.liabilities, "net" to worth.net,
            "groups" to groups.mapNotNull { g ->
                val list = byGroup[g.id] ?: return@mapNotNull null
                mapOf(
                    "name" to g.name, "type" to g.type.name, "liability" to g.type.isLiability, "debit" to g.type.isDebitCard,
                    "total" to list.filter { it.account.includeInTotals && !g.type.isDebitCard }
                        .sumOf { Fx.toMain(it.effectiveBalance, all.firstOrNull { c -> c.code == it.account.currency }, mainDecimals) },
                    "accounts" to list.map(::account),
                )
            },
        )
    }

    private fun account(l: AccountLine): Map<String, Any?> = mapOf(
        "id" to l.account.id, "name" to l.account.name, "memo" to l.account.memo,
        "balance" to l.effectiveBalance, "currency" to l.account.currency, "inTotals" to l.account.includeInTotals,
        "card" to l.card?.let { mapOf("due" to (it.statementDue + it.overdue), "outstanding" to it.outstanding, "payDay" to l.account.cardPaymentDay, "closeDay" to l.account.cardSettlementDay) },
    )
}

/** /api/export.csv: the entries of a period as the app's own CSV, optionally for one account. */
internal class WebExport(
    private val txns: TxnStore,
    private val accounts: AccountStore,
    private val categories: CategoryStore,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val tags: TagStore,
) {
    fun csv(range: DateRange, account: String?): HttpResponse {
        val chosen = txns.txnsNow(range).filter { account == null || it.accountId == account || it.toAccountId == account }
        val rows = TabularImport.exportRows(chosen, accounts.accountsNow(), categories.categoriesNow(), settings.currencyFormatNow(), currencies.currenciesNow(), tags.txnTagNamesNow())
        val name = "MoneyTracker-${range.start}-to-${range.endInclusive}.csv"
        return HttpResponse.text(200, "﻿" + TabularImport.toCsv(rows), "text/csv; charset=utf-8",
            listOf("Content-Disposition" to "attachment; filename=\"$name\""))
    }
}

// ───────────────────────── periods asked for in the query

/** ?ym=202611, or the period that holds [today]. */
internal fun requestedMonth(query: Map<String, String>, ps: PeriodSettings, today: LocalDate): YearMonth =
    query["ym"]?.toIntOrNull()?.takeIf { it % 100 in 1..12 }?.let(YearMonth::fromKey) ?: ps.labelFor(today)

/** ?period=month|3m|year&ym=202611, or ?period=custom&from=2026-11-01&to=2026-11-30; with the period's name. */
internal fun requestedRange(query: Map<String, String>, ps: PeriodSettings, today: LocalDate): Pair<DateRange, String> {
    val ym = requestedMonth(query, ps, today)
    return when (query["period"]) {
        "3m" -> DateRange(ps.month(ym.plus(-2)).start, ps.month(ym).endInclusive) to "3m"
        "year" -> ps.year(ym.year) to "year"
        "custom" -> customRange(query) to "custom"
        else -> ps.month(ym) to "month"
    }
}

/** From and to as picked, up to 10 years apart. */
private fun customRange(query: Map<String, String>): DateRange {
    val from = queryDate(query, "from", "Pick a start date")
    val to = queryDate(query, "to", "Pick an end date")
    if (to < from) throw ApiError(400, "The end date is before the start date")
    if (from.plus(DatePeriod(years = 10)) < to) throw ApiError(400, "Pick 10 years or less")
    return DateRange(from, to)
}

private fun queryDate(query: Map<String, String>, key: String, missing: String): LocalDate =
    query[key]?.let(LocalDate.Formats.ISO::parseOrNull) ?: throw ApiError(400, missing)

/** Days of [range] up to and including [today]: 0 before it starts, all of them once it is over. */
internal fun daysSoFar(range: DateRange, today: LocalDate) = when {
    today < range.start -> 0
    today > range.endInclusive -> range.days()
    else -> (today.toEpochDays() - range.start.toEpochDays() + 1)
}

private fun DateRange.days() = endInclusive.toEpochDays() - start.toEpochDays() + 1
