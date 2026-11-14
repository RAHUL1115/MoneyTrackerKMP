package app.moneytracker.lists.fresh

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.tr
import app.moneytracker.data.Prefs
import app.moneytracker.data.RepeatTiming
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.designsystem.classic.flattenCategories
import app.moneytracker.designsystem.classic.long
import app.moneytracker.designsystem.classic.mainCategoryName
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.EmptyCard
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.Mark
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOption
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.StgChips
import app.moneytracker.designsystem.fresh.StgField
import app.moneytracker.designsystem.fresh.StgLabel
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Fx
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isRefund
import app.moneytracker.lists.RepeatsModel
import app.moneytracker.lists.RepeatsState
import app.moneytracker.lists.RuleLookups

private fun timingOptions() = app.moneytracker.settings.classic.choiceSpec(Prefs.REPEAT_TIMING, newLayout = true)

@Composable
internal fun FreshRepeats() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) {
        RepeatsModel(repo.repeatStore, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, repo.today, scope)
    }
    val state by model.state.collectAsState()
    val catById = state.lookups.categoryById
    RepeatsContent(
        state, onBack = nav::pop, onAdd = { nav.push(Screen.EditTxn(preset = model.newRepeatDraft())) }, onTiming = model::setTiming,
        onSave = { updated -> model.saveRule(updated); toaster.show(tr("{0} updated", ruleName(updated, catById))) },
        onStop = { r -> model.stopRule(r); toaster.show(tr("{0} stopped", ruleName(r, catById))) { model.restoreRule(r) } },
    )
}

/** The Repeat entries screen drawn from [state]; the rule sheet, stop confirmation and timing choice open on top. */
@Composable
private fun RepeatsContent(
    state: RepeatsState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onTiming: (RepeatTiming) -> Unit,
    onSave: (RecurringRule) -> Unit,
    onStop: (RecurringRule) -> Unit,
) {
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var stopping by remember { mutableStateOf<RecurringRule?>(null) }
    var timingSheet by remember { mutableStateOf(false) }
    val lookups = state.lookups
    val main = mainAmountOf()

    SubScreen(tr("Repeat entries"), onBack, add = tr("New repeat") to onAdd) {
        RepeatsSummary(state, main, onTiming = { timingSheet = true })
        if (state.rules.isEmpty()) {
            EmptyCard(tr("No repeating entries. Make an entry and set Repeat to add one."), tr("Add a repeating entry"), onAdd)
        } else {
            state.groups.forEach { (kind, list) -> RuleGroupCard(groupTitle(kind), list, main, lookups) { editing = it } }
        }
        Text(tr("Tap a rule to change it. Stopping a rule keeps the entries it already created. To add one, make an entry and set Repeat."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp)
    }

    editing?.let { r ->
        RuleSheet(r, lookups, onDismiss = { editing = null }, onStop = { stopping = r }) { updated -> editing = null; onSave(updated) }
    }
    stopping?.let { r ->
        Confirm(tr("Stop “{0}”?", ruleName(r, lookups.categoryById)),
            tr("No new entries will be added. The entries it already created stay in your history."), tr("Stop rule"), { stopping = null }) {
            editing = null
            onStop(r)
        }
    }
    if (timingSheet) TimingSheet(state.timing, onDismiss = { timingSheet = false }) { timingSheet = false; onTiming(it) }
}

/** A rule's amount in the main currency, for totals and rows. */
@Composable
private fun mainAmountOf(): (RecurringRule) -> Long {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    return { r -> Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals) }
}

private fun groupTitle(kind: TxnKind) = when (kind) {
    TxnKind.INCOME -> tr("Income")
    TxnKind.EXPENSE -> tr("Spending")
    TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr("Transfers")
}

/** The screen's hero: next run, totals by kind, the rule count and when repeats are added. */
@Composable
private fun RepeatsSummary(state: RepeatsState, main: (RecurringRule) -> Long, onTiming: () -> Unit) {
    val currency = LocalCurrency.current
    val rules = state.rules
    // The summary is the screen's hero, so it gets the hero's rounder corners. Its parts pad themselves, so the
    // timing row's highlight reaches the card's edges.
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card)) {
        val next = rules.minOfOrNull { it.nextDate }
        Text(next?.let { tr("Next run · {0}", it.long()) } ?: tr("Nothing scheduled"), Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = ROW_INSET),
            color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(tr("Spending"), currency.format(rules.filter { it.kind == TxnKind.EXPENSE }.sumOf { main(it) }), Modifier.weight(1f))
            Stat(tr("Income"), currency.format(rules.filter { it.kind == TxnKind.INCOME }.sumOf { main(it) }), Modifier.weight(1f))
            Stat(tr("Active rules"), rules.size.toString(), Modifier.weight(1f))
        }
        Divider(inset = ROW_INSET)
        FRow(tr("Repeat entries are added"), value = timingOptions().label(state.timing), valueColor = F.accent, onClick = onTiming,
            inset = ROW_INSET, extraBottom = ROW_INSET)
    }
}

/** This screen's rows start 18dp in from their card's edge. */
private val ROW_INSET = 18.dp

/** A rule card's bottom spacing, kept inside its last row so that row's highlight reaches the rounded edge. */
private val CARD_EDGE = 4.dp

@Composable
private fun RuleGroupCard(title: String, list: List<RecurringRule>, main: (RecurringRule) -> Long, lookups: RuleLookups, onClick: (RecurringRule) -> Unit) {
    val currency = LocalCurrency.current
    FListCard {
        // The group's title sits inside its card, above a hairline.
        Text("$title · ${currency.format(list.sumOf { main(it) })}".uppercase(),
            Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = CARD_EDGE + 14.dp, bottom = 8.dp),
            color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
        list.forEachIndexed { i, r ->
            Divider(inset = ROW_INSET)
            RuleRow(r, main(r), lookups.accountNames, lookups.categoryById, extraBottom = if (i == list.lastIndex) CARD_EDGE else 0.dp) { onClick(r) }
        }
    }
}

@Composable
private fun TimingSheet(timing: RepeatTiming, onDismiss: () -> Unit, onPick: (RepeatTiming) -> Unit) {
    Sheet(tr("Repeat entries are added"), onDismiss) {
        SheetOptionGroup { timingOptions().options.forEach { (value, text) -> SheetOption(text, selected = value == timing) { onPick(value) } } }
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
    TxnKind.INCOME, TxnKind.EXPENSE, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN ->
        "${categoryPath(r.categoryId, catById)} · ${accountName[r.accountId].orEmpty()}"
}

@Composable
private fun RuleRow(
    r: RecurringRule, mainAmount: Long, accountName: Map<String, String>, catById: Map<String, Category>, extraBottom: Dp, onClick: () -> Unit,
) {
    val currency = LocalCurrency.current
    val refund = isRefund(r.kind, r.amount)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = extraBottom).heightIn(min = 64.dp).padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (r.kind == TxnKind.TRANSFER_OUT) Mark(tr("Transfer"), 38.dp, icon = Glyph.Swap) else Mark(mainCategoryName(r.categoryId, catById), 38.dp)
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

/** The rule sheet's fields while they are being changed; [toRule] applies them to the rule. */
@Stable
private class RuleForm(private val rule: RecurringRule, val fmt: CurrencyFormat) {
    // A repeating refund is stored negative; it is edited as a positive amount like in the entry editor.
    private val sign = if (isRefund(rule.kind, rule.amount)) -1 else 1
    val transfer = rule.kind == TxnKind.TRANSFER_OUT
    var amount by mutableStateOf(fmt.toInput(rule.amount * sign))
    var note by mutableStateOf(rule.note)
    var memo by mutableStateOf(rule.memo)
    var accountId by mutableStateOf(rule.accountId)
    var toAccountId by mutableStateOf(rule.toAccountId)
    var categoryId by mutableStateOf(rule.categoryId)
    var repeat by mutableStateOf(rule.repeat)
    var next by mutableStateOf(rule.nextDate)
    var end by mutableStateOf(rule.endDate)

    val parsed: Long? get() = fmt.parse(amount)
    val ok: Boolean get() = parsed.let { it != null && it > 0 } && (!transfer || toAccountId != null)

    fun toRule() = rule.copy(amount = parsed?.let { it * sign } ?: rule.amount, note = note.trim(), memo = memo.trim(), accountId = accountId,
        toAccountId = if (transfer) toAccountId else null, categoryId = if (transfer) null else categoryId,
        repeat = repeat, nextDate = next, endDate = end)
}

/** Change a repeating rule: what it adds and when. Entries already added stay as they are. */
@Composable
private fun RuleSheet(rule: RecurringRule, lookups: RuleLookups, onDismiss: () -> Unit, onStop: () -> Unit, onSave: (RecurringRule) -> Unit) {
    val fmt = LocalCurrencies.current[rule.currency]?.format ?: LocalCurrency.current
    val form = remember(rule) { RuleForm(rule, fmt) }
    var picking by remember { mutableStateOf<String?>(null) }
    val kindLabel = when (rule.kind) {
        TxnKind.INCOME -> tr("Income"); TxnKind.EXPENSE -> tr("Expense")
        TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr("Transfer")
    }

    Sheet(ruleName(rule, lookups.categoryById), onDismiss, bottom = { RuleSheetButtons(form.ok, onStop) { onSave(form.toRule()) } }) {
        Text("$kindLabel · ${routeOf(rule, lookups.accountNames, lookups.categoryById)}", color = F.muted, fontSize = 13.sp)
        StgField(form.amount, { form.amount = it }, label = tr("Amount ({0})", fmt.symbol), number = true, error = !form.ok && form.amount.isNotBlank())
        StgLabel(tr("Repeats"))
        StgChips(RepeatType.entries, form.repeat, { tr(it.label) }) { form.repeat = it }
        Divider()
        FRow(tr("Next date"), value = "${form.next.short()} ${form.next.year}", valueColor = F.accent, onClick = { picking = "next" })
        FRow(tr("Until (optional)"), value = form.end?.let { "${it.short()} ${it.year}" } ?: tr("No end"), valueColor = F.accent, onClick = { picking = "end" })
        RuleRouteChips(rule, form, lookups)
        StgField(form.note, { form.note = it }, label = tr("Note"))
        StgField(form.memo, { form.memo = it }, label = tr("Description"), singleLine = false)
        RuleSummaryLine(form)
    }
    DatePickerHost(picking == "next", form.next, { picking = null }, { d -> d?.let { form.next = it } })
    DatePickerHost(picking == "end", form.end ?: form.next, { picking = null }, { form.end = it }, clearable = true)
}

@Composable
private fun RuleSheetButtons(ok: Boolean, onStop: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Stopping is destructive but secondary: red text on a soft red, only as wide as its words.
        Box(
            Modifier.height(52.dp).clip(F.buttonShape).background(F.dangerTint).clickable(role = Role.Button, onClick = onStop).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Stop rule"), color = F.danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        PrimaryButton(tr("Save changes"), enabled = ok, modifier = Modifier.weight(1f), onClick = onSave)
    }
}

/** From and To accounts for a transfer; account and category for the rest. */
@Composable
private fun RuleRouteChips(rule: RecurringRule, form: RuleForm, lookups: RuleLookups) {
    val catById = lookups.categoryById
    val visible = lookups.accounts.filter { !it.hidden || it.id == form.accountId || it.id == form.toAccountId }
    StgLabel(if (form.transfer) tr("From") else tr("Account"))
    StgChips(visible, visible.firstOrNull { it.id == form.accountId }, { it.name }) { form.accountId = it.id }
    if (form.transfer) {
        StgLabel(tr("To"))
        val targets = visible.filter { it.id != form.accountId }
        StgChips(targets, targets.firstOrNull { it.id == form.toAccountId }, { it.name }) { form.toAccountId = it.id }
    } else {
        StgLabel(tr("Category"))
        val catKind = if (rule.kind == TxnKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val options = flattenCategories(lookups.categories.filter { it.kind == catKind })
        StgChips(options, options.firstOrNull { it.id == form.categoryId }, { categoryPath(it.id, catById) }) { form.categoryId = it.id }
    }
}

/** "₹500 monthly, next on …, with no end date." under the fields. */
@Composable
private fun RuleSummaryLine(form: RuleForm) {
    val parsed = form.parsed
    Text(
        if (parsed != null && parsed > 0) tr("{0} {1}, next on {2}", form.fmt.format(parsed), tr(form.repeat.label).lowercase(), form.next.long()) +
            (form.end?.let { ", " + tr("last by {0}", "${it.short()} ${it.year}") } ?: ", " + tr("with no end date")) + "."
        else tr("Enter an amount."),
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(F.controlShape).background(F.page).padding(12.dp), color = F.muted, fontSize = 13.sp,
    )
}
