package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatTiming
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Fx
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isRefund

/** Classic repeat settings: the timing, then the rules grouped by Income / Expenses / Transfer with group totals. */
@Composable
fun RecurringScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) {
        RepeatsModel(repo.repeatStore, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, repo.today, scope)
    }
    val state by model.state.collectAsState()
    RecurringContent(
        state, onBack = nav::pop, onAdd = { nav.push(Screen.EditTxn(preset = model.newRepeatDraft())) },
        onTiming = model::setTiming, onSave = model::saveRule, onStop = model::stopRule,
    )
}

/** The classic Repeat Setting screen drawn from [state]; the rule editor and timing choice open as dialogs. */
@Composable
private fun RecurringContent(
    state: RepeatsState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onTiming: (RepeatTiming) -> Unit,
    onSave: (RecurringRule) -> Unit,
    onStop: (RecurringRule) -> Unit,
) {
    val timing = choiceSpec(Prefs.REPEAT_TIMING, newLayout = false)
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var timingDialog by remember { mutableStateOf(false) }

    editing?.let { r -> RuleEditDialog(r, state.lookups.accounts, state.lookups.categories, onDismiss = { editing = null }) { updated ->
        editing = null
        onSave(updated)
    } }
    if (timingDialog) {
        ChoiceDialog(tr("Timing of reflection"), timing.options.map { (v, label) -> v.name to label }, { timingDialog = false }) { v ->
            timingDialog = false
            onTiming(RepeatTiming.valueOf(v))
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Repeat Setting"), onBack) {
            if (state.rules.isNotEmpty()) IconButton(onClick = { deleting = !deleting }) {
                Icon(Icons.Outlined.Delete, tr("Delete"), tint = if (deleting) DangerColor else LocalContentColor.current)
            }
            IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, tr("Add")) }
        }
        HorizontalDivider(color = DividerColor)
        ConfigRow(tr("Timing of reflection"), timing.label(state.timing)) { timingDialog = true }
        if (state.rules.isEmpty()) EmptyState(tr("No repeating transactions. Use “Rep/Inst.” when adding one."), Icons.Outlined.Repeat)
        else RuleList(state.groups, state.lookups, deleting, onEdit = { editing = it }, onStop = onStop)
    }
}

private fun groupTitle(kind: TxnKind) = when (kind) {
    TxnKind.INCOME -> tr("Income")
    TxnKind.EXPENSE -> tr("Expenses")
    TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr("Transfer")
}

@Composable
private fun RuleList(
    groups: List<Pair<TxnKind, List<RecurringRule>>>,
    lookups: RuleLookups,
    deleting: Boolean,
    onEdit: (RecurringRule) -> Unit,
    onStop: (RecurringRule) -> Unit,
) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    fun main(r: RecurringRule) = Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (kind, list) ->
            val title = groupTitle(kind)
            item(key = "g$title") { RuleGroupHeader(title, kind, currency.format(list.sumOf { main(it) })) }
            items(list, key = { it.id }) { r ->
                RuleLine(r, main(r), deleting, lookups.accountNames, lookups.categoryById, onEdit = { onEdit(r) }, onStop = { onStop(r) })
                HorizontalDivider(color = DividerColor)
            }
        }
        item {
            Text(tr("Tap a rule to change it. Stopping a rule keeps the transactions it already created."), Modifier.padding(20.dp), fontSize = 13.sp, color = SubtleText)
        }
    }
}

@Composable
private fun RuleGroupHeader(title: String, kind: TxnKind, total: String) {
    Row(Modifier.fillMaxWidth().background(PageBackground).padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Text(title, Modifier.weight(1f), color = SubtleText)
        Text(total, color = when (kind) {
            TxnKind.INCOME -> IncomeColor; TxnKind.EXPENSE -> ExpenseColor
            TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> Ink
        })
    }
    HorizontalDivider(color = DividerColor)
}

/** One rule: next date and how often, what it adds, and its amount in the main currency ([mainAmount]). */
@Composable
private fun RuleLine(
    r: RecurringRule,
    mainAmount: Long,
    deleting: Boolean,
    accountName: Map<String, String>,
    catById: Map<String, Category>,
    onEdit: () -> Unit,
    onStop: () -> Unit,
) {
    val currency = LocalCurrency.current
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onEdit).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (deleting) IconButton(onClick = onStop) { Icon(Icons.Outlined.RemoveCircleOutline, tr("Stop this rule"), tint = DangerColor) }
        Column(Modifier.width(96.dp)) {
            Text(r.nextDate.let { "${it.monthNumber}/${it.dayOfMonth}/${it.year % 100}" }, fontSize = 13.sp, color = SubtleText)
            Text(tr(r.repeat.label), fontSize = 12.sp, color = SubtleText)
        }
        RuleDetails(r, accountName, catById, Modifier.weight(1f).padding(end = 8.dp))
        val refund = isRefund(r.kind, r.amount)
        Text(currency.format(if (refund) -mainAmount else mainAmount, showSign = refund), color = when {
            r.kind == TxnKind.INCOME || refund -> IncomeColor; r.kind == TxnKind.EXPENSE -> ExpenseColor; else -> Ink
        })
    }
}

@Composable
private fun RuleDetails(r: RecurringRule, accountName: Map<String, String>, catById: Map<String, Category>, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The note keeps its room; the description takes what's left.
            Text(r.note.ifBlank { categoryPath(r.categoryId, catById) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.memo.isNotBlank()) Text("  " + r.memo, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = SubtleText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(when (r.kind) {
            TxnKind.TRANSFER_OUT -> "${accountName[r.accountId]} → ${accountName[r.toAccountId]}"
            TxnKind.INCOME, TxnKind.EXPENSE, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN ->
                "${accountName[r.accountId].orEmpty()}  ${categoryPath(r.categoryId, catById)}"
        }, fontSize = 13.sp, color = SubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        r.endDate?.let { Text(tr("Until: {0}", "${it.monthNumber}/${it.dayOfMonth}/${it.year % 100}"), fontSize = 12.sp, color = SubtleText) }
    }
}

/** What the repeat editor is changing; starts from the rule as it is. */
private class RuleForm(rule: RecurringRule, amountText: String) {
    var amount by mutableStateOf(amountText)
    var note by mutableStateOf(rule.note)
    var memo by mutableStateOf(rule.memo)
    var accountId by mutableStateOf(rule.accountId)
    var toAccountId by mutableStateOf(rule.toAccountId)
    var categoryId by mutableStateOf(rule.categoryId)
    var repeat by mutableStateOf(rule.repeat)
    var next by mutableStateOf(rule.nextDate)
    var end by mutableStateOf(rule.endDate)
}

/** Change a repeating rule: what it adds and when. Entries already added stay as they are. */
@Composable
private fun RuleEditDialog(
    rule: RecurringRule,
    accounts: List<Account>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (RecurringRule) -> Unit,
) {
    val fmt = LocalCurrencies.current[rule.currency]?.format ?: LocalCurrency.current
    // A repeating refund is stored negative; it is edited as a positive amount like in the entry editor.
    val sign = if (isRefund(rule.kind, rule.amount)) -1 else 1
    val f = remember { RuleForm(rule, fmt.toInput(rule.amount * sign)) }
    var picking by remember { mutableStateOf<String?>(null) }
    val transfer = rule.kind == TxnKind.TRANSFER_OUT
    val parsed = fmt.parse(f.amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit repeat")) },
        text = { RuleFields(rule, f, accounts, categories) { picking = it } },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed > 0 && (!transfer || f.toAccountId != null), onClick = {
                onSave(rule.copy(amount = parsed?.let { it * sign } ?: rule.amount, note = f.note.trim(), memo = f.memo.trim(), accountId = f.accountId,
                    toAccountId = if (transfer) f.toAccountId else null, categoryId = if (transfer) null else f.categoryId,
                    repeat = f.repeat, nextDate = f.next, endDate = f.end))
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    DatePickerHost(picking == "next", f.next, { picking = null }, { d -> d?.let { f.next = it } })
    DatePickerHost(picking == "end", f.end ?: f.next, { picking = null }, { f.end = it }, clearable = true)
}

@Composable
private fun RuleFields(rule: RecurringRule, f: RuleForm, accounts: List<Account>, categories: List<Category>, onPickDate: (String) -> Unit) {
    val transfer = rule.kind == TxnKind.TRANSFER_OUT
    val catKind = if (rule.kind == TxnKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val visible = accounts.filter { !it.hidden || it.id == f.accountId || it.id == f.toAccountId }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(f.amount, { f.amount = it }, label = { Text(tr("Amount")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Picker(if (transfer) tr("From") else tr("Account"), visible, visible.firstOrNull { it.id == f.accountId }, { it.name }, { f.accountId = it.id }, Modifier.fillMaxWidth())
        if (transfer) {
            val targets = visible.filter { it.id != f.accountId }
            Picker(tr("To"), targets, targets.firstOrNull { it.id == f.toAccountId }, { it.name }, { f.toAccountId = it.id }, Modifier.fillMaxWidth())
        } else {
            val options = flattenCategories(categories.filter { it.kind == catKind })
            Picker(tr("Category"), options, options.firstOrNull { it.id == f.categoryId }, { it.name }, { f.categoryId = it.id }, Modifier.fillMaxWidth())
        }
        OutlinedTextField(f.note, { f.note = it }, label = { Text(tr("Note")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(f.memo, { f.memo = it }, label = { Text(tr("Description")) }, modifier = Modifier.fillMaxWidth())
        Picker(tr("Repeat"), RepeatType.entries, f.repeat, { tr(it.label) }, { f.repeat = it }, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Next: {0} {1}", f.next.short(), f.next.year), Modifier.weight(1f))
            TextButton(onClick = { onPickDate("next") }) { Text(tr("Change")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Until: {0}", f.end?.let { "${it.short()} ${it.year}" } ?: tr("no end")), Modifier.weight(1f))
            TextButton(onClick = { onPickDate("end") }) { Text(tr("Change")) }
        }
    }
}
