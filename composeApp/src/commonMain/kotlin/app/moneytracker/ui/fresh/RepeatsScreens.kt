package app.moneytracker.ui.fresh

import app.moneytracker.ui.rememberSetting
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.Prefs
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Fx
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isRefund
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.flattenCategories
import app.moneytracker.ui.long
import app.moneytracker.ui.mainCategoryName
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun timingOptions() = app.moneytracker.ui.choiceSpec(Prefs.REPEAT_TIMING, newLayout = true)

@Composable
internal fun FreshRepeats() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val rules by repo.rules.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val timing by rememberSetting(Prefs.REPEAT_TIMING)
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var stopping by remember { mutableStateOf<RecurringRule?>(null) }
    var timingSheet by remember { mutableStateOf(false) }

    fun write(block: () -> Unit) = scope.launch { withContext(repo.io) { block() } }
    fun main(r: RecurringRule) = Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
    val addNew = {
        nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, repo.today(), "", amount = 0, repeat = RepeatType.MONTHLY)))
    }
    val timingLabel = timingOptions().label(timing)

    SubScreen(tr("Repeat entries"), nav::pop, add = tr("New repeat") to addNew) {
        // The summary is the screen's hero, so it gets the hero's rounder corners.
        Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(18.dp)) {
            val next = rules.minOfOrNull { it.nextDate }
            Text(next?.let { tr("Next run · {0}", it.long()) } ?: tr("Nothing scheduled"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(tr("Spending"), currency.format(rules.filter { it.kind == TxnKind.EXPENSE }.sumOf { main(it) }), Modifier.weight(1f))
                Stat(tr("Income"), currency.format(rules.filter { it.kind == TxnKind.INCOME }.sumOf { main(it) }), Modifier.weight(1f))
                Stat(tr("Active rules"), rules.size.toString(), Modifier.weight(1f))
            }
            Divider()
            FRow(tr("Repeat entries are added"), value = timingLabel, valueColor = F.accent, onClick = { timingSheet = true })
        }
        if (rules.isEmpty()) {
            EmptyCard(tr("No repeating entries. Make an entry and set Repeat to add one."), tr("Add a repeating entry"), addNew)
        } else {
            listOf(
                tr("Income") to rules.filter { it.kind == TxnKind.INCOME },
                tr("Spending") to rules.filter { it.kind == TxnKind.EXPENSE },
                tr("Transfers") to rules.filter { it.kind == TxnKind.TRANSFER_OUT },
            ).filter { it.second.isNotEmpty() }.forEach { (title, list) ->
                FCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        // The group's title sits inside its card, above a hairline.
                        Text("$title · ${currency.format(list.sumOf { main(it) })}".uppercase(), Modifier.padding(top = 14.dp, bottom = 8.dp),
                            color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
                        list.forEach { r ->
                            Divider()
                            RuleRow(r, main(r), accountName, catById) { editing = r }
                        }
                    }
                }
            }
        }
        Text(tr("Tap a rule to change it. Stopping a rule keeps the entries it already created. To add one, make an entry and set Repeat."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp)
    }

    editing?.let { r ->
        RuleSheet(r, accounts, categories, accountName, catById, onDismiss = { editing = null }, onStop = { stopping = r }) { updated ->
            editing = null
            write { repo.saveRule(updated); repo.generateRecurring() }
            toaster.show(tr("{0} updated", ruleName(updated, catById)))
        }
    }
    stopping?.let { r ->
        Confirm(tr("Stop “{0}”?", ruleName(r, catById)),
            tr("No new entries will be added. The entries it already created stay in your history."), tr("Stop rule"), { stopping = null }) {
            editing = null
            write { repo.deleteRule(r.id) }
            toaster.show(tr("{0} stopped", ruleName(r, catById))) { write { repo.saveRule(r) } }
        }
    }
    if (timingSheet) {
        Sheet(tr("Repeat entries are added"), { timingSheet = false }) {
            timingOptions().options.forEach { (value, text) ->
                SheetOption(text, selected = value == timing) {
                    timingSheet = false
                    write { repo.putSetting(Prefs.REPEAT_TIMING, value); repo.generateRecurring() }
                }
            }
        }
    }
}

private fun ruleName(r: RecurringRule, catById: Map<String, Category>) = r.note.ifBlank { categoryPath(r.categoryId, catById) }

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label, color = F.muted, fontSize = 12.sp, maxLines = 1)
    Text(value, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun routeOf(r: RecurringRule, accountName: Map<String, String>, catById: Map<String, Category>): String = when (r.kind) {
    TxnKind.TRANSFER_OUT -> "${accountName[r.accountId].orEmpty()} → ${accountName[r.toAccountId].orEmpty()}"
    else -> "${categoryPath(r.categoryId, catById)} · ${accountName[r.accountId].orEmpty()}"
}

@Composable
private fun RuleRow(r: RecurringRule, mainAmount: Long, accountName: Map<String, String>, catById: Map<String, Category>, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val transfer = r.kind == TxnKind.TRANSFER_OUT
    val refund = isRefund(r.kind, r.amount)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (transfer) Mark(tr("Transfer"), 38.dp, icon = Glyph.Swap) else Mark(mainCategoryName(r.categoryId, catById), 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ruleName(r, catById), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.memo.isNotBlank()) Text("  " + r.memo, Modifier.weight(1f, fill = false), color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${routeOf(r, accountName, catById)} · ${tr(r.repeat.label).lowercase()}", color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("Next {0}", r.nextDate.long()) + (r.endDate?.let { " · " + tr("until {0}", "${it.short()} ${it.year}") } ?: ""),
                color = F.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            currency.format(if (refund) -mainAmount else mainAmount, showSign = refund), style = F.money, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = when { r.kind == TxnKind.INCOME || refund -> F.income; r.kind == TxnKind.EXPENSE -> F.expense; else -> F.ink },
        )
    }
}

/** Change a repeating rule: what it adds and when. Entries already added stay as they are. */
@Composable
private fun RuleSheet(
    rule: RecurringRule,
    accounts: List<Account>,
    categories: List<Category>,
    accountName: Map<String, String>,
    catById: Map<String, Category>,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onSave: (RecurringRule) -> Unit,
) {
    val fmt = LocalCurrencies.current[rule.currency]?.format ?: LocalCurrency.current
    // A repeating refund is stored negative; it is edited as a positive amount like in the entry editor.
    val sign = if (isRefund(rule.kind, rule.amount)) -1 else 1
    var amount by remember(rule) { mutableStateOf(fmt.toInput(rule.amount * sign)) }
    var note by remember(rule) { mutableStateOf(rule.note) }
    var memo by remember(rule) { mutableStateOf(rule.memo) }
    var accountId by remember(rule) { mutableStateOf(rule.accountId) }
    var toAccountId by remember(rule) { mutableStateOf(rule.toAccountId) }
    var categoryId by remember(rule) { mutableStateOf(rule.categoryId) }
    var repeat by remember(rule) { mutableStateOf(rule.repeat) }
    var next by remember(rule) { mutableStateOf(rule.nextDate) }
    var end by remember(rule) { mutableStateOf(rule.endDate) }
    var picking by remember { mutableStateOf<String?>(null) }
    val transfer = rule.kind == TxnKind.TRANSFER_OUT
    val catKind = if (rule.kind == TxnKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val visible = accounts.filter { !it.hidden || it.id == accountId || it.id == toAccountId }
    val parsed = fmt.parse(amount)
    val ok = parsed != null && parsed > 0 && (!transfer || toAccountId != null)
    val kindLabel = when (rule.kind) { TxnKind.INCOME -> tr("Income"); TxnKind.EXPENSE -> tr("Expense"); else -> tr("Transfer") }

    Sheet(ruleName(rule, catById), onDismiss, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Stopping is destructive but secondary: red text on a soft red, only as wide as its words.
            Box(
                Modifier.height(52.dp).clip(F.buttonShape).background(F.dangerTint).clickable(onClick = onStop).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Stop rule"), color = F.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            PrimaryButton(tr("Save changes"), enabled = ok, modifier = Modifier.weight(1f), onClick = {
                onSave(rule.copy(amount = parsed?.let { it * sign } ?: rule.amount, note = note.trim(), memo = memo.trim(), accountId = accountId,
                    toAccountId = if (transfer) toAccountId else null, categoryId = if (transfer) null else categoryId,
                    repeat = repeat, nextDate = next, endDate = end))
            })
        }
    }) {
        Text("$kindLabel · ${routeOf(rule, accountName, catById)}", color = F.muted, fontSize = 13.sp)
        StgField(amount, { amount = it }, label = tr("Amount ({0})", fmt.symbol), number = true, error = !ok && amount.isNotBlank())
        StgLabel(tr("Repeats"))
        StgChips(RepeatType.entries, repeat, { tr(it.label) }) { repeat = it }
        Divider()
        FRow(tr("Next date"), value = "${next.short()} ${next.year}", valueColor = F.accent, onClick = { picking = "next" })
        FRow(tr("Until (optional)"), value = end?.let { "${it.short()} ${it.year}" } ?: tr("No end"), valueColor = F.accent, onClick = { picking = "end" })
        StgLabel(if (transfer) tr("From") else tr("Account"))
        StgChips(visible, visible.firstOrNull { it.id == accountId }, { it.name }) { accountId = it.id }
        if (transfer) {
            StgLabel(tr("To"))
            val targets = visible.filter { it.id != accountId }
            StgChips(targets, targets.firstOrNull { it.id == toAccountId }, { it.name }) { toAccountId = it.id }
        } else {
            StgLabel(tr("Category"))
            val options = flattenCategories(categories.filter { it.kind == catKind })
            StgChips(options, options.firstOrNull { it.id == categoryId }, { categoryPath(it.id, catById) }) { categoryId = it.id }
        }
        StgField(note, { note = it }, label = tr("Note"))
        StgField(memo, { memo = it }, label = tr("Description"), singleLine = false)
        Text(
            if (parsed != null && parsed > 0) tr("{0} {1}, next on {2}", fmt.format(parsed), tr(repeat.label).lowercase(), next.long()) +
                (end?.let { ", " + tr("last by {0}", "${it.short()} ${it.year}") } ?: ", " + tr("with no end date")) + "."
            else tr("Enter an amount."),
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(F.page).padding(12.dp), color = F.muted, fontSize = 13.sp,
        )
    }
    DatePickerHost(picking == "next", next, { picking = null }, { d -> d?.let { next = it } })
    DatePickerHost(picking == "end", end ?: next, { picking = null }, { end = it }, clearable = true)
}
