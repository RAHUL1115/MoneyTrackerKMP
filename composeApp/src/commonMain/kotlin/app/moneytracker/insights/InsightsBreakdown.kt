package app.moneytracker.insights

import app.moneytracker.core.tr
import app.moneytracker.domain.Category
import app.moneytracker.domain.Flow as MoneyFlow
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.ReportRow
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.classify
import app.moneytracker.domain.isRefund

/** Breakdown key for money moved to savings or investments. */
const val TRANSFERS_KEY = "__transfers"

/** Breakdown key for entries without a category. */
const val UNCATEGORIZED_KEY = "__none"

/** Breakdown key for entries without a tag. */
const val NO_TAG_KEY = "__notag"

/** Where a breakdown part leads when tapped: its category's entries, its account, or nowhere. */
enum class BreakdownLink { CATEGORY_ENTRIES, ACCOUNT }

/**
 * One part of the breakdown. [amount] is net of refunds and [refunded] is how much came back (as a positive amount);
 * [previous] is the same part in the period before, and [entries] are the rows behind it.
 */
class BreakdownPart(
    val key: String,
    val group: InsightsGroup,
    val amount: Long,
    val previous: Long,
    val refunded: Long,
    val entries: List<ReportRow>,
    val link: BreakdownLink?,
)

/** Main-category key of a report row (transfers and balance changes get their own keys). */
fun mainCategoryKey(r: ReportRow, catById: Map<String, Category>): String = when {
    r.kind == TxnKind.TRANSFER_OUT -> TRANSFERS_KEY
    r.categoryId == MODIFIED_BALANCE_CATEGORY -> MODIFIED_BALANCE_CATEGORY
    else -> r.categoryId?.let { catById[it] }?.let { it.parentId ?: it.id } ?: UNCATEGORIZED_KEY
}

/** Amount per main category for the given flow (the web dashboard uses this too). */
fun byMainCategory(rows: List<ReportRow>, catById: Map<String, Category>, flow: MoneyFlow): Map<String, Long> =
    rows.filter { classify(it) == flow }.groupBy { mainCategoryKey(it, catById) }.mapValues { (_, v) -> v.sumOf { it.amount } }

/**
 * The rows of [flow] split by [groupBy], biggest first, each against the period before. A tagged entry counts once for
 * each of its tags. A part links to its category or account while that still exists.
 */
fun breakdownOf(
    groupBy: InsightsGroup,
    rows: List<ReportRow>,
    previousRows: List<ReportRow>,
    flow: MoneyFlow,
    catById: Map<String, Category>,
    accountName: Map<String, String>,
    tagNames: Map<String, List<String>>,
): List<BreakdownPart> {
    val keysOf: (ReportRow) -> List<String> = when (groupBy) {
        InsightsGroup.CATEGORY -> { r -> listOf(mainCategoryKey(r, catById)) }
        InsightsGroup.ACCOUNT -> { r -> listOf(r.accountId) }
        InsightsGroup.TAG -> { r -> tagNames[r.txnId].orEmpty().ifEmpty { listOf(NO_TAG_KEY) }.distinct() }
    }
    fun group(list: List<ReportRow>): Map<String, List<ReportRow>> {
        val m = LinkedHashMap<String, MutableList<ReportRow>>()
        list.filter { classify(it) == flow }.forEach { r -> keysOf(r).forEach { k -> m.getOrPut(k) { mutableListOf() } += r } }
        return m
    }
    val before = group(previousRows).mapValues { (_, v) -> v.sumOf { it.amount } }
    return group(rows).map { (key, list) ->
        val link = when {
            groupBy == InsightsGroup.CATEGORY && catById[key] != null -> BreakdownLink.CATEGORY_ENTRIES
            groupBy == InsightsGroup.ACCOUNT && accountName[key] != null -> BreakdownLink.ACCOUNT
            else -> null
        }
        val refunded = -list.filter { isRefund(it.kind, it.amount) }.sumOf { it.amount }
        BreakdownPart(key, groupBy, list.sumOf { it.amount }, before[key] ?: 0L, refunded, list, link)
    }.sortedByDescending { it.amount }
}

/** The five biggest single entries of [flow]. */
fun topEntriesOf(rows: List<ReportRow>, flow: MoneyFlow): List<ReportRow> =
    rows.filter { classify(it) == flow && it.amount > 0 }.sortedByDescending { it.amount }.take(5)

/** What a part is called. [categoryName] names a category key, since each layout words the special keys itself. */
fun breakdownName(part: BreakdownPart, accountName: Map<String, String>, categoryName: (String) -> String): String = when (part.group) {
    InsightsGroup.CATEGORY -> categoryName(part.key)
    InsightsGroup.ACCOUNT -> accountName[part.key] ?: tr("Deleted account")
    InsightsGroup.TAG -> if (part.key == NO_TAG_KEY) tr("No tag") else part.key
}
