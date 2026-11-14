package app.moneytracker.messages.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.IconTile
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.SecondaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SubScreen
import app.moneytracker.domain.Capture
import app.moneytracker.messages.MessageReviewModel
import app.moneytracker.messages.MessageReviewState
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What the review screen asks for: its holder's actions plus navigation and the inbox import. */
private class MessageReviewActions(
    val back: () -> Unit,
    val dismiss: (Capture) -> Unit,
    val add: (Capture, TxnDraft) -> Unit,
    /** Reads a pasted message; the callback gets the new capture, or null when it wasn't one. */
    val paste: (String, (Capture?) -> Unit) -> Unit,
    val pasted: () -> Unit,
    val importInbox: () -> Unit,
    val openSettings: () -> Unit,
)

/** Bank SMS review queue: one fixed-size card per message, each with what will be added and its own Dismiss / Add. */
@Composable
internal fun SmsReviewScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val messageCapture = LocalPlatform.current.messageCapture
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) {
        MessageReviewModel(repo.captureStore, repo.messageIntake, repo.settingsStore, repo.accountStore, repo.categoryStore, repo.io, scope)
    }
    val state by model.state.collectAsState()
    val drafts by model.drafts.collectAsState()
    val days = state.importDays
    val actions = MessageReviewActions(
        back = nav::pop,
        dismiss = { c ->
            model.dismiss(c) { toaster.show(tr("Dismissed message from {0}", c.sender.ifBlank { tr("pasted text") })) { model.undoDismiss(c) } }
        },
        add = { c, d -> nav.push(Screen.EditTxn(preset = d, captureId = c.id)) },
        paste = model::paste,
        pasted = { toaster.show(tr("Message read · it's in the list")) },
        importInbox = {
            messageCapture.importInboxMessages(days) { n ->
                toaster.show(when (n) { null -> tr("SMS permission was not granted"); 0 -> tr("No new bank messages found"); else -> tr("Added {0} messages from the last {1} days", n, days) })
            }
        },
        openSettings = { nav.push(Screen.MessageSettings) },
    )
    SmsReviewContent(state, drafts, actions)
}

@Composable
private fun SmsReviewContent(state: MessageReviewState, drafts: Map<String, TxnDraft>, actions: MessageReviewActions) {
    var pasting by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }
    val captures = state.captures
    // Importing reads the inbox, so it first shows the SMS disclosure while detection is off.
    val import = { if (state.captureEnabled) actions.importInbox() else disclosure = actions.importInbox }
    SubScreen(
        tr("Bank messages"), actions.back,
        subtitle = tr("{0} to review · read on this phone only", captures.size),
        actions = { ReviewMenu(state.importDays, onPaste = { pasting = true }, onImport = import, onSettings = actions.openSettings) },
    ) {
        if (captures.isEmpty()) NothingToReview(state.captureEnabled, state.importDays, onPaste = { pasting = true }, onImport = import)
        // One fixed-size card per message, each with its own Dismiss / Add: nothing to select, so nothing shifts.
        captures.forEach { c ->
            key(c.id) {
                val d = drafts[c.id]
                MessageCard(c, d, state, onDismiss = { actions.dismiss(c) }, onAdd = { d?.let { actions.add(c, it) } })
            }
        }
        if (captures.isNotEmpty()) Text(tr("Add opens the editor filled in; nothing is saved until you tap Save there. Your corrections are suggested next time for messages like this."),
            color = F.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }

    if (pasting) PasteSheet(onDismiss = { pasting = false }, onPaste = actions.paste) { pasting = false; actions.pasted() }
    disclosure?.let { action ->
        Confirm(tr("Detect bank SMS"), smsDisclosureText(), tr("Continue"), onDismiss = { disclosure = null }, danger = false) { action() }
    }
}

@Composable
private fun ReviewMenu(days: Int, onPaste: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconTile(Glyph.More, tr("More"), { menu = true })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Paste a message")) }, onClick = { menu = false; onPaste() })
            DropdownMenuItem(text = { Text(tr("Import last {0} days", days)) }, onClick = { menu = false; onImport() })
            DropdownMenuItem(text = { Text(tr("Bank SMS settings")) }, onClick = { menu = false; onSettings() })
        }
    }
}

@Composable
private fun NothingToReview(captureEnabled: Boolean, days: Int, onPaste: () -> Unit, onImport: () -> Unit) {
    FCard(padding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(F.accentTint), contentAlignment = Alignment.Center) {
                Icon(Glyph.Check, null, tint = F.accent, modifier = Modifier.size(28.dp))
            }
            Text(tr("Nothing to review"), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (captureEnabled) tr("New bank messages will show up here. You can also paste one or import older ones.")
                else tr("Turn on \"Detect bank SMS\" to get suggestions from bank messages, or paste one."),
                color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(tr("Paste a message"), Modifier.weight(1f), Glyph.Paste, onPaste)
                SmallAction(tr("Import last {0} days", days), Modifier.weight(1f), onClick = onImport)
            }
        }
    }
}

internal fun smsDisclosureText() = tr("MoneyTracker reads SMS from banks and card issuers on this phone to suggest income and expense entries. " +
    "Messages are processed only on your phone and are never uploaded or shared. " +
    "Other messages are ignored. You can turn this off at any time here in Bank SMS.")

private fun whenLabel(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val day = if (t.date == today) tr("Today") else t.date.short()
    return "$day, ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

@Composable
private fun reviewAmount(d: TxnDraft?): String {
    val currency = LocalCurrency.current
    return when {
        d == null || d.amount == 0L -> "—"
        d.type == DraftType.REFUND || d.type == DraftType.INCOME -> currency.format(d.amount, showSign = true)
        else -> currency.format(d.amount)
    }
}

/** One waiting message: sender and time, what will be added (category · account), the amount, two lines of the SMS. */
@Composable
private fun MessageCard(c: Capture, d: TxnDraft?, names: MessageReviewState, onDismiss: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.sender.ifBlank { tr("Pasted") }, Modifier.clip(RoundedCornerShape(7.dp)).background(F.fill).padding(horizontal = 8.dp, vertical = 4.dp),
                color = F.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(whenLabel(c.receivedAt), color = F.muted, fontSize = 12.sp)
        }
        SuggestedEntry(c, d, names)
        Text(c.text, color = F.muted, fontSize = 12.sp, lineHeight = 17.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.height(44.dp).clip(F.iconShape).background(F.fill).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) { Text(tr("Dismiss"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            Box(Modifier.weight(1f).height(44.dp).clip(F.iconShape).background(if (d != null) F.accent else F.fill)
                .clickable(enabled = d != null, role = Role.Button, onClick = onAdd), contentAlignment = Alignment.Center) {
                Text(tr("Add"), color = if (d != null) F.onAccent else F.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The entry a message suggests: category mark, note, "category · account" (amber when one is unknown) and the amount. */
@Composable
private fun SuggestedEntry(c: Capture, d: TxnDraft?, names: MessageReviewState) {
    val catName = d?.categoryId?.let { names.categoryById[it]?.name }
    val acct = d?.accountId?.let { names.accountNames[it] }
    val missing = listOfNotNull(tr("category").takeIf { catName == null }, tr("account").takeIf { acct == null })
    val income = d?.type == DraftType.REFUND || d?.type == DraftType.INCOME
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(F.fill), contentAlignment = Alignment.Center) {
            Text((catName ?: "?").take(2).uppercase(), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(d?.note?.ifBlank { null } ?: c.merchant.ifBlank { c.sender }, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (missing.isEmpty()) "$catName · $acct" else listOfNotNull(catName, acct, tr("{0} not known", missing.joinToString(tr(" and ")))).joinToString(" · "),
                color = if (missing.isEmpty()) F.muted else F.warn, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(reviewAmount(d), color = if (income) F.income else F.expense, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = F.money)
    }
}

@Composable
private fun SmallAction(text: String, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(F.iconShape).background(F.card).border(1.dp, F.border, F.iconShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = F.ink, modifier = Modifier.size(18.dp))
        Text(text, Modifier.padding(start = 6.dp), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PasteSheet(onDismiss: () -> Unit, onPaste: (String, (Capture?) -> Unit) -> Unit, onRead: (Capture) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Sheet(
        tr("Paste a bank message"), onDismiss,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Cancel"), onDismiss, Modifier.weight(0.4f))
                PrimaryButton(tr("Read message"), enabled = text.isNotBlank(), modifier = Modifier.weight(0.6f), onClick = {
                    onPaste(text) { c -> if (c == null) error = true else onRead(c) }
                })
            }
        },
    ) {
        Text(tr("Paste a bank SMS about a payment. It is read on this phone only."), color = F.muted, fontSize = 13.sp)
        SmsTextArea(text, { text = it; error = false }, minHeight = 120.dp)
        if (error) Text(tr("That doesn't look like a payment message (or it was already added)."), color = F.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Multi-line input in the new layout's style. */
@Composable
internal fun SmsTextArea(value: String, onChange: (String) -> Unit, minHeight: Dp = 96.dp) {
    BasicTextField(
        value, onChange,
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(F.controlShape).background(F.card)
            .border(1.dp, F.border, F.controlShape).padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = TextStyle(color = F.ink, fontSize = 14.sp, lineHeight = 21.sp), cursorBrush = SolidColor(F.accent),
    )
}
