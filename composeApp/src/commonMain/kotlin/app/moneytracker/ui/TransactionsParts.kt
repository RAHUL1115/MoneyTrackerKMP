package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Category
import app.moneytracker.domain.Flow
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isRefund
import kotlin.math.abs

/** One transaction: category (left, grey) · note + account (middle) · amount (right). */
@Composable
fun TxnLine(
    txn: Txn,
    accountName: Map<String, String>,
    catById: Map<String, Category>,
    flow: Flow,
    runningBalance: Long? = null,
    tags: List<String> = emptyList(),
    hasPhoto: Boolean = false,
    inset: Dp = 16.dp,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) AccentTint else SurfaceColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(82.dp).padding(end = 8.dp)) {
            val (main, sub) = when (txn.kind) {
                TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN -> tr("Transfer") to null
                TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> tr(MODIFIED_BALANCE_NAME) to null
                TxnKind.INCOME, TxnKind.EXPENSE -> mainCategoryName(txn.categoryId, catById) to subCategoryName(txn.categoryId, catById)
            }
            Text(main, color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = SubtleText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TxnLineMiddle(txn, accountName, tags, hasPhoto)
        TxnLineAmount(txn, flow, runningBalance)
    }
}

private fun accountLine(txn: Txn, accountName: Map<String, String>) = when (txn.kind) {
    TxnKind.TRANSFER_OUT -> "${accountName[txn.accountId].orEmpty()} → ${accountName[txn.toAccountId].orEmpty()}"
    TxnKind.TRANSFER_IN -> "${accountName[txn.toAccountId].orEmpty()} → ${accountName[txn.accountId].orEmpty()}"
    TxnKind.INCOME, TxnKind.EXPENSE, TxnKind.ADJUST_UP, TxnKind.ADJUST_DOWN -> accountName[txn.accountId].orEmpty()
}

private fun marks(txn: Txn, hasPhoto: Boolean) = buildString {
    if (txn.isRefund) append("  · ").append(tr("Refund"))
    if (txn.recurringRuleId != null) append("  ↻")
    if (hasPhoto) append("  📷")
    if (txn.lat != null) append("  📍")
}

@Composable
private fun RowScope.TxnLineMiddle(txn: Txn, accountName: Map<String, String>, tags: List<String>, hasPhoto: Boolean) {
    val prefs = LocalListPrefs.current
    Column(Modifier.weight(1f).padding(end = 8.dp)) {
        val title = listOfNotNull(txn.note.ifBlank { null }, txn.installmentLabel).joinToString(" ")
        // The note line is always there, even empty, so every entry has the same shape and the account sits on line two.
        Text(title, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (prefs.showDescription && txn.memo.isNotBlank()) Text(txn.memo, color = SubtleText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val time = if (prefs.showTime) txn.timeOfDay?.let { formatTime(it) + "  " }.orEmpty() else ""
        Text(time + accountLine(txn, accountName) + marks(txn, hasPhoto), color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, color = IncomeColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TxnLineAmount(txn: Txn, flow: Flow, runningBalance: Long?) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    Column(horizontalAlignment = Alignment.End) {
        // A refund is money back: shown as +amount in the income colour.
        val refund = txn.isRefund
        val color = when {
            refund && flow != Flow.NONE -> IncomeColor
            flow == Flow.INCOME -> IncomeColor
            flow == Flow.EXPENSE -> ExpenseColor
            else -> InkSoft
        }
        fun shown(v: Long) = if (refund) -v else v
        val foreign = txn.currency?.let { currencies[it] }
        if (foreign != null && txn.originalAmount != null) {
            Text(foreign.format.format(shown(txn.originalAmount), showSign = refund), color = color, style = EntryAmountStyle)
            Text(currency.format(shown(txn.amount), showSign = refund), color = SubtleText, fontSize = 12.sp, style = Tabular)
        } else Text(currency.format(shown(txn.amount), showSign = refund), color = color, style = EntryAmountStyle)
        if (runningBalance != null) {
            Text("(${currency.format(runningBalance, withSymbol = false)})", color = SubtleText, fontSize = 10.sp, style = Tabular)
        }
    }
}

/** The bulk change a selection bar button opens. */
private enum class BulkDialog { DATE, CATEGORY, ACCOUNT, DELETE }

/** Shown while entries are selected: count, net sum and bulk actions. */
@Composable
internal fun SelectionBar(selected: List<Txn>, onClear: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { BulkEditModel(repo.txnStore, repo.io, scope) }
    val categories by repo.categoryStore.categories.collectAsState()
    val accounts by repo.accountStore.accounts.collectAsState()
    var dialog by remember { mutableStateOf<BulkDialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val done: (BulkResult) -> Unit = { r ->
        if (r.partial) message = tr("{0} of {1} entries changed (transfers keep their accounts and have no category).", r.changed, r.total)
        onClear()
    }
    SelectionBarRow(selected, onDialog = { dialog = it }, onClear = onClear)
    when (dialog) {
        BulkDialog.DATE -> DatePickerHost(true, selected.firstOrNull()?.date, { dialog = null }, { d -> if (d != null) model.setDate(selected, d, done) })
        BulkDialog.CATEGORY -> ChoiceDialog(tr("Change category"), flattenCategories(categories).map { it.id to (if (it.parentId != null) "    " + it.name else it.name) },
            { dialog = null }) { id -> dialog = null; model.setCategory(selected, id, done) }
        BulkDialog.ACCOUNT -> ChoiceDialog(tr("Change account"), accounts.filter { !it.hidden }.map { it.id to it.name }, { dialog = null }) { id ->
            dialog = null; model.setAccount(selected, id, done)
        }
        BulkDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr("Delete {0} entries?", selected.size)) },
            text = { Text(tr("Transfers are deleted with both sides.")) },
            confirmButton = { TextButton(onClick = { dialog = null; model.delete(selected, onClear) }) { Text(tr("Delete"), color = DangerColor) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
        )
        null -> {}
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text(tr("OK")) } })
    }
}

@Composable
private fun SelectionBarRow(selected: List<Txn>, onDialog: (BulkDialog) -> Unit, onClear: () -> Unit) {
    val currency = LocalCurrency.current
    val net = selected.filter { it.kind.isListed }.sumOf { it.signedAmount }
    Row(Modifier.fillMaxWidth().background(Accent).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, tr("Cancel selection"), tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(tr("{0} selected", selected.size), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(currency.format(net, showSign = true), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
        IconButton(onClick = { onDialog(BulkDialog.DATE) }) { Icon(Icons.Outlined.CalendarMonth, tr("Change date"), tint = Color.White) }
        IconButton(onClick = { onDialog(BulkDialog.CATEGORY) }) { Icon(Icons.Outlined.Category, tr("Change category"), tint = Color.White) }
        IconButton(onClick = { onDialog(BulkDialog.ACCOUNT) }) { Icon(Icons.Outlined.AccountBalanceWallet, tr("Change account"), tint = Color.White) }
        IconButton(onClick = { onDialog(BulkDialog.DELETE) }) { Icon(Icons.Outlined.Delete, tr("Delete"), tint = Color.White) }
    }
}

/** Scrollable single-choice list dialog. */
@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.first }) { (id, label) ->
                    Text(label, Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 12.dp), color = Ink)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Shown before a sensitive permission prompt (SMS, location): what is read, why, and that it stays on the phone. */
@Composable
fun DisclosureDialog(title: String, body: String, onDismiss: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onContinue) { Text(tr("Continue")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}

/** "Repeat · Rent, To mom, … · 11/1 · −30,000": repeating entries still to come in the shown period (not in the totals). */
@Composable
internal fun UpcomingRepeatsRow(u: UpcomingRepeats, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Repeat"), Modifier.width(96.dp), color = SubtleText, fontSize = 14.sp)
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(u.names.joinToString(",  "), color = InkSoft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${u.first.monthNumber}/${u.first.dayOfMonth}", color = SubtleText, fontSize = 11.sp)
        }
        Text(currency.format(u.sum), color = Ink, fontSize = 15.sp)
    }
    HorizontalDivider(color = DividerColor)
}

/**
 * The newest detected bank SMS payment at the top of the Daily list, tinted so it stands apart from saved entries.
 * Tapping it opens Detected transactions, where it's added or dismissed.
 */
@Composable
internal fun ClassicDetectedCard() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { DetectedCardModel(repo.captureStore, repo.messageIntake, repo.io, scope) }
    val card by model.state.collectAsState()
    val accountName by repo.accountStore.accountNames.collectAsState()
    val catById by repo.categoryStore.categoryById.collectAsState()
    card?.let { DetectedCardContent(it, accountName, catById) { nav.push(Screen.Inbox) } }
}

@Composable
private fun DetectedCardContent(card: DetectedCard, accountName: Map<String, String>, catById: Map<String, Category>, onOpen: () -> Unit) {
    val c = card.capture
    val d = card.draft
    val line = Accent.copy(alpha = 0.18f)
    // Tinted so it reads as "waiting for you", not as one of the saved entries below. The whole card opens the
    // detected SMS; only a complete payment gets the one-tap Add.
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).padding(horizontal = ListCardMargin).clip(RoundedCornerShape(16.dp))
            .background(AccentTint).border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClickLabel = tr("Open detected SMS"), onClick = onOpen)
            .padding(start = ListRowInset + 2.dp, end = ListRowInset + 2.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.MarkEmailUnread, null, Modifier.size(16.dp), tint = Accent)
            Text(tr("Found in bank SMS"), Modifier.weight(1f).padding(start = 6.dp), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(if (card.waiting > 1) tr("+{0} more ›", card.waiting - 1) else tr("Open ›"), color = Accent, fontSize = 12.sp)
        }
        HorizontalDivider(color = line)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.categoryId?.let { catById[it]?.name } ?: tr("Category?"), Modifier.width(76.dp), color = if (d.categoryId == null) ExpenseColor else SubtleText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.merchant.ifBlank { d.note.ifBlank { c.sender } }, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Plain like a saved row: what's missing is chosen on the detected SMS screen, no prompts here.
                Text(accountName[d.accountId] ?: c.sender, color = SubtleText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DetectedAmount(card)
        }
    }
}

@Composable
private fun DetectedAmount(card: DetectedCard) {
    val currency = LocalCurrency.current
    val refund = isRefund(card.capture.kind, card.capture.amount)
    val income = card.capture.kind == TxnKind.INCOME || refund
    Text((if (refund) "+" else "") + currency.format(abs(card.draft.amount)), Modifier.padding(start = 8.dp), color = if (income) IncomeColor else ExpenseColor, fontSize = 15.sp)
}

/** Smart add at the top of the Daily list: tap the box to type an entry the way you'd say it, or the mic to speak it. */
@Composable
internal fun ClassicSmartAddBar(onType: () -> Unit, onSpeak: () -> Unit) {
    val voice = LocalPlatform.current.voice
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).card(ListCardMargin).height(52.dp)
            .clickable(onClickLabel = tr("Type an entry"), onClick = onType)
            .padding(start = ListRowInset + 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Type or say it: “1,200 for new shoes”"), Modifier.weight(1f), color = SubtleText, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (voice.speechAvailable) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Accent).clickable(onClickLabel = tr("Say an entry"), onClick = onSpeak),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Mic, tr("Say an entry"), tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}
