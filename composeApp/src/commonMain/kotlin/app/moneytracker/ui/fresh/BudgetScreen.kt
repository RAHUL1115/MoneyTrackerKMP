package app.moneytracker.ui.fresh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.tr
import app.moneytracker.data.MonthBudget
import app.moneytracker.domain.BudgetLine
import app.moneytracker.domain.Category
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.YearMonth
import app.moneytracker.ui.BudgetActions
import app.moneytracker.ui.BudgetChange
import app.moneytracker.ui.BudgetState
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.MonthPickerDialog
import app.moneytracker.ui.Screen
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.label
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.rememberBudgetModel
import kotlin.math.roundToInt

@Composable internal fun FreshBudget() = BudgetBody()

/** Side padding of the single link rows (Set a monthly budget, Month overview) inside their cards. */
private val LINK_INSET = 16.dp

/** The card's padding a category row takes inside its highlight: on top of the first row, under the last. */
private class RowSpacing(val top: Dp, val bottom: Dp)

/** The budget card on Insights: how much of the month's budget is used, which categories are over. */
@Composable
internal fun BudgetSummaryCard(ym: YearMonth, b: MonthBudget, catById: Map<String, Category>) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val total = b.total
    if (total == null) {
        // One tap target: the row spans the card.
        FListCard {
            FRow(tr("Set a monthly budget"), tr("See what's left of it here and on Home"), onClick = { nav.push(Screen.Budget) },
                inset = LINK_INSET, extraTop = 4.dp, extraBottom = 4.dp)
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
    val model = rememberBudgetModel()
    val state by model.state.collectAsState()
    val toaster = LocalToaster.current
    val currency = LocalCurrency.current
    BudgetContent(state, model.actions) { key, amount ->
        val change = model.save(key, amount)
        toaster.show(changeMessage(change, budgetName(key, state.catById), currency.format(amount))) { model.undo(change) }
    }
}

private fun budgetName(id: String, catById: Map<String, Category>) = if (id == TOTAL_BUDGET) tr("Total") else categoryPath(id, catById)

private fun changeMessage(change: BudgetChange, name: String, amount: String) = when {
    change.removed -> tr("{0} budget removed", name)
    change.added -> tr("{0} budget added", name)
    else -> tr("{0} budget set to {1}", name, amount)
}

@Composable
private fun BudgetContent(state: BudgetState, actions: BudgetActions, onSave: (key: String, amount: Long) -> Unit) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    var monthPicker by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val ym = state.month
    val explicit = state.budget.budgets[TOTAL_BUDGET]

    if (monthPicker) MonthPickerDialog(ym, state.thisMonth, { monthPicker = false }) { actions.pickMonth(it); monthPicker = false }

    SubScreen(tr("Budget · {0}", monthShort(ym.month)), nav::pop, actions = {
        HeaderTextTile(tr("+ Category")) { adding = true }
    }) {
        PeriodStepper(ym.label(), { actions.step(-1) }, { actions.step(1) }, onLabel = { monthPicker = true })
        TotalBudgetCard(state, onSave)
        val lines = state.budget.categoryLines
        if (lines.isEmpty()) EmptyCard(tr("No category budgets for {0}. Add one to keep an eye on a single kind of spending.", ym.label()), tr("Add a category budget")) { adding = true }
        else CategoryBudgetsCard(state, lines, onSave)
        if (lines.isNotEmpty()) Text(
            tr("Category budgets add up to {0}{1}. Tap a category to change its amount or see its entries. Budgets apply from the selected month onward.",
                currency.format(lines.filter { state.catById[it.categoryId]?.parentId == null }.sumOf { it.budget }),
                if (explicit != null) " " + tr("of your {0} total", currency.format(explicit)) else ""),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
        )
        FListCard {
            FRow(tr("Month overview"), tr("Every budget for {0} with what's left", ym.label()), onClick = { nav.push(Screen.BudgetDetail(ym.key)) },
                inset = LINK_INSET, extraTop = 4.dp, extraBottom = 4.dp)
        }
    }

    if (adding) AddBudgetSheet(state, { budgetName(it, state.catById) }, onDismiss = { adding = false }) { key, amount -> adding = false; onSave(key, amount) }
}

/** The total budget: ring, what's left, last month's spending; a tap opens its amount editor. */
@Composable
private fun TotalBudgetCard(state: BudgetState, onSave: (key: String, amount: Long) -> Unit) {
    val currency = LocalCurrency.current
    var totalOpen by remember { mutableStateOf(false) }
    val total = state.budget.total
    val explicit = state.budget.budgets[TOTAL_BUDGET]
    val from = monthShort(state.month.month)
    // The tappable part keeps the card's padding inside it, so its highlight fills the card.
    val pad = F.cardPadding
    FListCard {
        Row(
            Modifier.fillMaxWidth().expandState(totalOpen).clickable { totalOpen = !totalOpen }
                .padding(start = pad, end = pad, top = pad, bottom = if (totalOpen) 0.dp else pad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PercentRing(total?.let { (it.ratio * 100).roundToInt() } ?: 0, (total?.ratio ?: 0f) > 1f, 84.dp, textSize = 60 * 0.24f)
            HGap(16.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (total != null) tr("Total budget {0}", currency.format(total.budget)) else tr("No total budget"),
                        Modifier.weight(1f, fill = false), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(" · " + tr("Edit"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                TotalBudgetFigures(state, total, explicit)
            }
        }
        if (totalOpen) Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = pad)) {
            Gap(12.dp)
            AmountEditor(tr("Total per month · from {0} onward", from), explicit ?: total?.budget, onRemove = if (explicit != null) ({ totalOpen = false; onSave(TOTAL_BUDGET, 0) }) else null) {
                totalOpen = false; onSave(TOTAL_BUDGET, it)
            }
        }
    }
}

@Composable
private fun TotalBudgetFigures(state: BudgetState, total: BudgetLine?, explicit: Long?) {
    val currency = LocalCurrency.current
    val ym = state.month
    if (total != null) Text(
        if (total.remaining >= 0) tr("{0} left", currency.format(total.remaining)) else tr("{0} over", currency.format(-total.remaining)),
        color = if (total.remaining >= 0) F.ink else F.expense, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1,
    ) else Text(tr("Set one for {0}", ym.label()), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(tr("Spent {0} · {1} was {2}", currency.format(state.budget.spent), monthShort(ym.plus(-1).month), currency.format(state.before.spent)),
        color = F.muted, fontSize = 12.sp, maxLines = 1)
    if (total != null && explicit == null) Text(tr("Sum of the category budgets"), color = F.muted, fontSize = 12.sp)
}

/** Category budgets; a tapped one opens its amount editor and a link to its entries. */
@Composable
private fun CategoryBudgetsCard(state: BudgetState, lines: List<BudgetLine>, onSave: (key: String, amount: Long) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    // Rows pad themselves (the first and last also with the card's top and bottom padding) so their highlight spans the card.
    val pad = F.cardPadding
    FListCard {
        lines.forEachIndexed { i, line ->
            if (i > 0) Divider(pad)
            val isOpen = open == line.categoryId
            val last = i == lines.lastIndex
            CategoryBudgetRow(line, budgetName(line.categoryId, state.catById), state.catById, isOpen,
                RowSpacing(top = if (i == 0) pad else 0.dp, bottom = if (last && !isOpen) pad else 0.dp)) { open = if (isOpen) null else line.categoryId }
            // Editor sits under the name, in line with the bar.
            if (isOpen) Column(Modifier.fillMaxWidth().padding(start = pad + 38.dp, end = pad, bottom = 14.dp + if (last) pad else 0.dp)) {
                AmountEditor(tr("Monthly amount · from {0} onward", monthShort(state.month.month)), line.budget,
                    onRemove = { open = null; onSave(line.categoryId, 0) }, link = { CategoryEntriesLink(state, line) }) {
                    open = null; onSave(line.categoryId, it)
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetRow(line: BudgetLine, name: String, catById: Map<String, Category>, open: Boolean, spacing: RowSpacing, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val over = line.remaining < 0
    Column(
        Modifier.fillMaxWidth().expandState(open).clickable(onClick = onClick).padding(top = spacing.top, bottom = spacing.bottom)
            .padding(horizontal = F.cardPadding, vertical = 12.dp),
    ) {
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
}

/** "See Food entries ›" under an open category budget, while the category exists. */
@Composable
private fun CategoryEntriesLink(state: BudgetState, line: BudgetLine) {
    val nav = LocalNavigator.current
    val cat = state.catById[line.categoryId] ?: return
    val main = cat.parentId ?: cat.id
    val name = state.catById[main]?.name ?: budgetName(line.categoryId, state.catById)
    Text(tr("See {0} entries ›", name),
        Modifier.clip(F.tagShape).clickable(role = Role.Button) { nav.push(Screen.StatsDetail(main, false, state.range.start, state.range.endInclusive, name)) }
            .padding(vertical = 6.dp), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
