package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.accounts.classic.SmallOutlineButton
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.app.Screen
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.domain.Capture
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.isRefund

/** What the classic inbox asks for: its holder's actions plus navigation. */
private class InboxActions(
    val back: () -> Unit,
    /** Reads a pasted message; the callback gets the new capture, or null when it wasn't one. */
    val paste: (String, (Capture?) -> Unit) -> Unit,
    val add: (Capture) -> Unit,
    val dismiss: (Capture) -> Unit,
)

/** Classic list of detected bank messages, each added as an entry or dismissed; a message can be pasted in too. */
@Composable
fun InboxScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) {
        MessageReviewModel(repo.captureStore, repo.messageIntake, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, scope)
    }
    val state by model.state.collectAsState()
    val drafts by model.drafts.collectAsState()
    val actions = InboxActions(
        back = nav::pop,
        paste = model::paste,
        add = { c -> drafts[c.id]?.let { nav.push(Screen.EditTxn(preset = it, captureId = c.id)) } },
        dismiss = { c -> model.dismiss(c) {} },
    )
    InboxContent(state.captures, state.accountNames, actions)
}

@Composable
private fun InboxContent(captures: List<Capture>, accountName: Map<String, String>, actions: InboxActions) {
    var pasting by remember { mutableStateOf(false) }
    var pasteResult by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Detected transactions"), actions.back) {
            TextButton(onClick = { pasting = true; pasteResult = null }) { Text(tr("Paste message"), color = Accent) }
        }
        HorizontalDivider(color = DividerColor)
        pasteResult?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = SubtleText, fontSize = 13.sp) }
        if (pasting) PasteMessageDialog(onDismiss = { pasting = false }) { msg ->
            pasting = false
            actions.paste(msg) { c -> pasteResult = if (c == null) tr("That doesn't look like a payment message (or it was already added).") else null }
        }
        if (captures.isEmpty()) {
            EmptyState(tr("Nothing to review. Turn on \"Detect bank SMS\" in Configuration, or paste a message."), Icons.Outlined.Inbox)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(captures, key = { it.id }) { c ->
                CaptureCard(c, accountName, onAdd = { actions.add(c) }, onDismiss = { actions.dismiss(c) })
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}

@Composable
private fun PasteMessageDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Paste a bank message")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Paste a bank SMS about a payment. It is read on this phone only."), fontSize = 13.sp, color = SubtleText)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(160.dp), label = { Text(tr("Message")) })
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRead(text) }) { Text(tr("Read"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** One detected payment: who, how much (a refund shows as money in), the message, and Add / Dismiss. */
@Composable
private fun CaptureCard(c: Capture, accountName: Map<String, String>, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.merchant.ifBlank { c.sender }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(c.accountId?.let { accountName[it] }, c.sender).joinToString(" · "), color = SubtleText, fontSize = 12.sp)
            }
            c.amount?.let {
                val refund = isRefund(c.kind, it)
                Column(horizontalAlignment = Alignment.End) {
                    Text(currency.format(if (refund) -it else it, showSign = refund), color = if (c.kind == TxnKind.INCOME || refund) IncomeColor else ExpenseColor, fontWeight = FontWeight.Medium)
                    if (refund) Text(tr("Refund"), color = SubtleText, fontSize = 11.sp)
                }
            }
        }
        Text(c.text, Modifier.padding(top = 6.dp), color = SubtleText, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallOutlineButton(tr("Add"), onAdd)
            SmallOutlineButton(tr("Dismiss"), onDismiss)
        }
    }
}
