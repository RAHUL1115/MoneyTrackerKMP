package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Flow
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.Screen
import app.moneytracker.ui.byMainCategory
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.flattenCategories
import app.moneytracker.ui.label
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberPeriod
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Budgets of one month: the effective amounts, the lines with what's spent, and the whole month's spending. */
internal class MonthBudget(val budgets: Map<String, Long>, val lines: List<BudgetLine>, val spent: Long) {
    val total get() = lines.firstOrNull { it.categoryId == TOTAL_BUDGET }
    val categoryLines get() = lines.filter { it.categoryId != TOTAL_BUDGET }
}

@Composable
internal fun rememberMonthBudget(ym: YearMonth): MonthBudget {
    val repo = LocalRepo.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val range = ps.month(ym)
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val expenseRows = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == Flow.EXPENSE }
    val spent = byMainCategory(expenseRows, catById, Flow.EXPENSE)
    val spentBySub = expenseRows.filter { r -> r.categoryId?.let { catById[it]?.parentId } != null }
        .groupBy { it.categoryId!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val budgets = effectiveBudgets(entries, ym)
    return MonthBudget(budgets, budgetLines(budgets, spent, spentBySub) { id -> catById[id]?.parentId != null }, spent.values.sum())
}

/** The budget card on Insights: how much of the month's budget is used, which categories are over. */
@Composable
internal fun BudgetSummaryCard(ym: YearMonth) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val catById by LocalRepo.current.categoryById.collectAsState(emptyMap())
    val b = rememberMonthBudget(ym)
    val total = b.total
    if (total == null) {
        FCard(onClick = { nav.push(Screen.Budget) }, padding = 4.dp) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                FRow(tr("Set a monthly budget"), tr("See what's left of it here and on Home"), onClick = { nav.push(Screen.Budget) })
            }
        }
        return
    }
    val over = b.categoryLines.filter { it.remaining < 0 }
    FCard(onClick = { nav.push(Screen.Budget) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PercentRing((total.ratio * 100).roundToInt(), total.ratio > 1f, 52.dp)
            HGap(14.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("{0} budget", ym.label()), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val left = if (total.remaining >= 0) tr("{0} left of {1}", currency.format(total.remaining), currency.format(total.budget))
                    else tr("{0} over {1}", currency.format(-total.remaining), currency.format(total.budget))
                val overText = when (over.size) {
                    0 -> null
                    1 -> tr("{0} is over", catById[over[0].categoryId]?.name ?: "")
                    else -> tr("{0} categories over", over.size)
                }
                Text(listOfNotNull(left, overText).joinToString(" · "), color = F.muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(tr("Budget ›"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Budget screen: total and per-category budgets of a month, set from that month onward. */
@Composable
internal fun BudgetBody() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var periodKey by rememberPeriod()
    val ym = YearMonth.fromKey(periodKey)
    val b = rememberMonthBudget(ym)
    val before = rememberMonthBudget(ym.plus(-1))
    val catById by repo.categoryById.collectAsState(emptyMap())
    val categories by repo.categories.collectAsState(emptyList())
    val useSubcategories by repo.useSubcategories.collectAsState(false)
    var monthPicker by remember { mutableStateOf(false) }
    var totalOpen by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val from = monthShort(ym.month)

    fun nameOf(id: String) = if (id == TOTAL_BUDGET) tr("Total") else categoryPath(id, catById)
    fun set(key: String, amount: Long) {
        val previous = b.budgets[key] ?: 0L
        scope.launch { withContext(repo.io) { repo.setBudget(key, ym, amount) } }
        val name = nameOf(key)
        val msg = when {
            amount <= 0 -> tr("{0} budget removed", name)
            previous == 0L -> tr("{0} budget added", name)
            else -> tr("{0} budget set to {1}", name, currency.format(amount))
        }
        toaster.show(msg) { repo.moneyIo { repo.setBudget(key, ym, previous) } }
    }

    if (monthPicker) MonthPickerDialog(ym, ps.labelFor(repo.today()), { monthPicker = false }) { periodKey = it.key; monthPicker = false }

    SubScreen(tr("Budget · {0}", from), nav::pop, actions = {
        Pill(tr("+ Category"), selected = false, onClick = { adding = true })
    }) {
        PeriodStepper(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, onLabel = { monthPicker = true })

        // Total budget.
        val total = b.total
        val explicit = b.budgets[TOTAL_BUDGET]
        FCard {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { totalOpen = !totalOpen }, verticalAlignment = Alignment.CenterVertically) {
                PercentRing(total?.let { (it.ratio * 100).roundToInt() } ?: 0, (total?.ratio ?: 0f) > 1f, 60.dp)
                HGap(14.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (total != null) tr("Total budget {0}", currency.format(total.budget)) else tr("No total budget"),
                            Modifier.weight(1f, fill = false), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(" · " + tr("Edit"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (total != null) Text(
                        if (total.remaining >= 0) tr("{0} left", currency.format(total.remaining)) else tr("{0} over", currency.format(-total.remaining)),
                        color = if (total.remaining >= 0) F.ink else F.expense, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1,
                    ) else Text(tr("Set one for {0}", ym.label()), color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(tr("Spent {0} · {1} was {2}", currency.format(b.spent), monthShort(ym.plus(-1).month), currency.format(before.spent)),
                        color = F.muted, fontSize = 12.5.sp, maxLines = 1)
                    if (total != null && explicit == null) Text(tr("Sum of the category budgets"), color = F.muted, fontSize = 12.5.sp)
                }
            }
            if (totalOpen) {
                Gap(12.dp)
                AmountEditor(tr("Total per month · from {0} onward", from), explicit ?: total?.budget, onRemove = if (explicit != null) ({ totalOpen = false; set(TOTAL_BUDGET, 0) }) else null) {
                    totalOpen = false; set(TOTAL_BUDGET, it)
                }
            }
        }

        // Category budgets.
        val lines = b.categoryLines
        if (lines.isEmpty()) EmptyCard(tr("No category budgets for {0}. Add one to keep an eye on a single kind of spending.", ym.label()), tr("Add a category budget")) { adding = true }
        else FCard {
            lines.forEachIndexed { i, line ->
                if (i > 0) Divider()
                val name = nameOf(line.categoryId)
                val isOpen = open == line.categoryId
                val over = line.remaining < 0
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = if (isOpen) null else line.categoryId }.padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mark(catById[line.categoryId]?.name ?: name, 34.dp)
                        HGap(12.dp)
                        Text(name, Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (over) tr("{0} over", currency.format(-line.remaining)) else tr("{0} left", currency.format(line.remaining)),
                            color = if (over) F.expense else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = F.money, maxLines = 1)
                    }
                    Gap(8.dp)
                    Bar(line.ratio, Modifier.padding(start = 46.dp), fill = if (over) F.warn else if (line.ratio > 0.9f) F.accent.copy(alpha = 0.55f) else F.accent, height = 6.dp)
                    Text(tr("{0} of {1}", currency.format(line.spent), currency.format(line.budget)), Modifier.padding(start = 46.dp, top = 6.dp),
                        color = F.muted, fontSize = 12.5.sp, style = F.money)
                }
                if (isOpen) Column(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(F.fill).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AmountEditor(tr("Monthly amount · from {0} onward", from), line.budget, onRemove = { open = null; set(line.categoryId, 0) }) {
                        open = null; set(line.categoryId, it)
                    }
                    val cat = catById[line.categoryId]
                    if (cat != null) {
                        val main = cat.parentId ?: cat.id
                        val range = ps.month(ym)
                        Text(tr("See {0} entries ›", catById[main]?.name ?: name),
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.push(Screen.StatsDetail(main, false, range.start, range.endInclusive, catById[main]?.name ?: name)) }
                                .padding(vertical = 6.dp), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (lines.isNotEmpty()) Text(
            tr("Category budgets add up to {0}{1}. Tap a category to change its amount or see its entries. Budgets apply from the selected month onward.",
                currency.format(lines.filter { catById[it.categoryId]?.parentId == null }.sumOf { it.budget }),
                if (explicit != null) " " + tr("of your {0} total", currency.format(explicit)) else ""),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.5.sp,
        )
        FCard(padding = 4.dp) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                FRow(tr("Month overview"), tr("Every budget for {0} with what's left", ym.label()), onClick = { nav.push(Screen.BudgetDetail(ym.key)) })
            }
        }
    }

    if (adding) {
        val options = flattenCategories(categories.filter { it.kind == CategoryKind.EXPENSE })
            .filter { (useSubcategories || it.parentId == null) && it.id !in b.budgets }
        var picked by remember { mutableStateOf<String?>(null) }
        var amount by remember { mutableStateOf("") }
        val value = currency.parse(amount)?.takeIf { it > 0 }
        Sheet(tr("Add a category budget"), { adding = false }, bottom = {
            PrimaryButton(
                when { picked == null -> tr("Pick a category"); value == null -> tr("Enter an amount"); else -> tr("Add {0} budget", nameOf(picked!!)) },
                { val k = picked; if (k != null && value != null) { adding = false; set(k, value) } },
                enabled = picked != null && value != null,
            )
        }) {
            Text(tr("Applies from {0} onward.", ym.label()), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            if (options.isEmpty()) Text(tr("Every category already has a budget."), color = F.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            options.forEach { c ->
                SheetOption(categoryPath(c.id, catById), picked == c.id, leading = { Mark(c.name, 32.dp) }) { picked = c.id }
            }
            Gap(8.dp)
            MoneyTextField(tr("Monthly amount"), amount, { amount = it }, placeholder = currency.format(0), keyboard = KeyboardType.Decimal)
        }
    }
}

/** Amount box with Save, and Remove when there is something to remove. */
@Composable
private fun AmountEditor(label: String, initial: Long?, onRemove: (() -> Unit)?, onSave: (Long) -> Unit) {
    val currency = LocalCurrency.current
    var text by remember(initial) { mutableStateOf(initial?.let { currency.toInput(it) }.orEmpty()) }
    val value = if (text.isBlank()) 0L else currency.parse(text)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MoneyTextField(label, text, { text = it }, keyboard = KeyboardType.Decimal, placeholder = currency.format(0))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRemove != null) SecondaryButton(tr("Remove budget"), onRemove, Modifier.weight(1f), danger = true)
            PrimaryButton(tr("Save"), { if (value != null) { if (value == 0L) onRemove?.invoke() else onSave(value) } }, Modifier.weight(1f),
                enabled = value != null && (value > 0 || onRemove != null))
        }
    }
}
