package app.moneytracker.ui.fresh

import app.moneytracker.data.MonthBudget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.moneytracker.ui.collectAsState
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

/** One month's budget from the shared read model (same numbers as the web dashboard and the classic screens). */
@Composable
internal fun rememberMonthBudget(ym: YearMonth): MonthBudget {
    val repo = LocalRepo.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val range = ps.month(ym)
    return remember(ym, range) { repo.views.budget(ym) }.collectAsState().value
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
            PercentRing((total.ratio * 100).roundToInt(), total.ratio > 1f, 40.dp, textSize = 52 * 0.24f)
            HGap(14.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("{0} budget", ym.label()), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                val left = if (total.remaining >= 0) tr("{0} left of {1}", currency.format(total.remaining), currency.format(total.budget))
                    else tr("{0} over {1}", currency.format(-total.remaining), currency.format(total.budget))
                val overText = when (over.size) {
                    0 -> null
                    1 -> tr("{0} is over", catById[over[0].categoryId]?.name ?: "")
                    else -> tr("{0} categories over", over.size)
                }
                Text(listOfNotNull(left, overText).joinToString(" · "), color = F.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(tr("Budget ›"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
        HeaderTextTile(tr("+ Category")) { adding = true }
    }) {
        PeriodStepper(ym.label(), { periodKey = ym.plus(-1).key }, { periodKey = ym.plus(1).key }, onLabel = { monthPicker = true })

        // Total budget.
        val total = b.total
        val explicit = b.budgets[TOTAL_BUDGET]
        FCard {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { totalOpen = !totalOpen }, verticalAlignment = Alignment.CenterVertically) {
                PercentRing(total?.let { (it.ratio * 100).roundToInt() } ?: 0, (total?.ratio ?: 0f) > 1f, 84.dp, textSize = 60 * 0.24f)
                HGap(16.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (total != null) tr("Total budget {0}", currency.format(total.budget)) else tr("No total budget"),
                            Modifier.weight(1f, fill = false), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(" · " + tr("Edit"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    if (total != null) Text(
                        if (total.remaining >= 0) tr("{0} left", currency.format(total.remaining)) else tr("{0} over", currency.format(-total.remaining)),
                        color = if (total.remaining >= 0) F.ink else F.expense, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1,
                    ) else Text(tr("Set one for {0}", ym.label()), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Spent {0} · {1} was {2}", currency.format(b.spent), monthShort(ym.plus(-1).month), currency.format(before.spent)),
                        color = F.muted, fontSize = 12.sp, maxLines = 1)
                    if (total != null && explicit == null) Text(tr("Sum of the category budgets"), color = F.muted, fontSize = 12.sp)
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
                        Mark(catById[line.categoryId]?.name ?: name, 28.dp)
                        HGap(10.dp)
                        Text(name, Modifier.weight(1f), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (over) tr("{0} over", currency.format(-line.remaining)) else tr("{0} left", currency.format(line.remaining)),
                            color = if (over) F.expense else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                    }
                    Gap(8.dp)
                    Bar(line.ratio, Modifier.padding(start = 38.dp), fill = if (over) F.danger else if (line.ratio > 0.9f) F.accent.copy(alpha = 0.55f) else F.accent, height = 8.dp)
                    Text(tr("{0} of {1}", currency.format(line.spent), currency.format(line.budget)), Modifier.padding(start = 38.dp, top = 6.dp),
                        color = F.muted, fontSize = 12.sp, style = F.money)
                }
                // Editor sits under the name, in line with the bar.
                if (isOpen) Column(Modifier.fillMaxWidth().padding(start = 38.dp, bottom = 14.dp)) {
                    AmountEditor(tr("Monthly amount · from {0} onward", from), line.budget, onRemove = { open = null; set(line.categoryId, 0) }, link = {
                        val cat = catById[line.categoryId]
                        if (cat != null) {
                            val main = cat.parentId ?: cat.id
                            val range = ps.month(ym)
                            Text(tr("See {0} entries ›", catById[main]?.name ?: name),
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.push(Screen.StatsDetail(main, false, range.start, range.endInclusive, catById[main]?.name ?: name)) }
                                    .padding(vertical = 6.dp), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }) {
                        open = null; set(line.categoryId, it)
                    }
                }
            }
        }
        if (lines.isNotEmpty()) Text(
            tr("Category budgets add up to {0}{1}. Tap a category to change its amount or see its entries. Budgets apply from the selected month onward.",
                currency.format(lines.filter { catById[it.categoryId]?.parentId == null }.sumOf { it.budget }),
                if (explicit != null) " " + tr("of your {0} total", currency.format(explicit)) else ""),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
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
            // Three columns of tiles; the picked one gets an accent outline.
            options.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> CategoryTile(categoryPath(c.id, catById), c.name, picked == c.id, Modifier.weight(1f)) { picked = c.id } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Gap(8.dp)
            MoneyTextField(tr("Monthly amount"), amount, { amount = it }, placeholder = currency.format(0), keyboard = KeyboardType.Decimal)
        }
    }
}

/** Amount box with a compact Save beside it; [link] on the left and Remove (red text) on the right below. */
@Composable
private fun AmountEditor(label: String, initial: Long?, onRemove: (() -> Unit)?, link: (@Composable () -> Unit)? = null, onSave: (Long) -> Unit) {
    val currency = LocalCurrency.current
    var text by remember(initial) { mutableStateOf(initial?.let { currency.toInput(it) }.orEmpty()) }
    val value = if (text.isBlank()) 0L else currency.parse(text)
    val enabled = value != null && (value > 0 || onRemove != null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyTextField(label, text, { text = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, placeholder = currency.format(0))
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) F.accent else F.fill)
                    .clickable(enabled = enabled) { if (value != null) { if (value == 0L) onRemove?.invoke() else onSave(value) } }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Save"), color = if (enabled) F.onAccent else F.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (link != null || onRemove != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { link?.invoke() }
            if (onRemove != null) Text(tr("Remove budget"),
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRemove).padding(horizontal = 10.dp, vertical = 8.dp),
                color = F.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A category in the add-budget sheet: mark and name in a white tile, outlined in the accent when picked. */
@Composable
private fun CategoryTile(label: String, markName: String, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(48.dp).clip(shape).background(if (picked) F.accentTint else F.card)
            .border(if (picked) 2.dp else 1.dp, if (picked) F.accent else F.border, shape).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mark(markName, 26.dp)
        Text(label, color = if (picked) F.accent else F.ink, fontSize = 14.sp, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
