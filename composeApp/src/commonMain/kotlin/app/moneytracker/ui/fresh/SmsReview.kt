package app.moneytracker.ui.fresh

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.TxnKind
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Bank SMS review queue: one message at a time with what will be added; the rest wait below. */
@Composable
internal fun SmsReviewScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val toaster = LocalToaster.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val captures by repo.pendingCaptures.collectAsState(emptyList())
    val capture by remember(repo) { repo.flag(Keys.CAPTURE_ENABLED) }.collectAsState(false)
    val importDays by remember(repo) { repo.setting(Keys.SMS_IMPORT_DAYS) }.collectAsState(null)
    val days = importDays?.toIntOrNull() ?: 30
    var focusId by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableIntStateOf(0) }
    var pasting by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf<(() -> Unit)?>(null) }
    val current = captures.firstOrNull { it.id == focusId } ?: captures.firstOrNull()

    fun restore(id: String, txnId: String?) = scope.launch {
        withContext(repo.io) {
            txnId?.let { repo.txn(it) }?.let { repo.delete(it) }
            repo.setCaptureStatus(id, CaptureStatus.PENDING)
        }
        if (txnId != null) { added--; platform.refreshWidgets() }
        focusId = id
    }
    fun dismiss(c: Capture) = scope.launch {
        withContext(repo.io) { repo.setCaptureStatus(c.id, CaptureStatus.DISMISSED) }
        toaster.show(tr("Dismissed message from {0}", c.sender.ifBlank { tr("pasted text") })) { restore(c.id, null) }
    }
    fun add(c: Capture, d: TxnDraft) = scope.launch {
        val id = withContext(repo.io) { repo.create(d).also { repo.learnFromDraft(d); repo.completeCapture(c, d, it) } }
        added++
        platform.refreshWidgets()
        toaster.show(tr("Added {0} to Activity", listOfNotNull(currency.format(d.amount), d.note.ifBlank { null }).joinToString(" · "))) { restore(c.id, id) }
    }
    fun import() = platform.importInboxMessages(days) { n ->
        toaster.show(when (n) { null -> tr("SMS permission was not granted"); 0 -> tr("No new bank messages found"); else -> tr("Added {0} messages from the last {1} days", n, days) })
    }

    // The suggested entry for the message on top; its Dismiss / Edit / Add stay pinned at the bottom, under the thumb.
    val accountNames by repo.accountNames.collectAsState(emptyMap())
    val draft by produceState<TxnDraft?>(null, current?.id) { value = current?.let { c -> withContext(repo.io) { repo.draftFromCapture(c) } } }
    val d = draft
    val amountText = reviewAmount(d)
    val ready = d != null && d.amount != 0L && d.accountId in accountNames
    SubScreen(
        tr("Bank messages"), nav::pop,
        actions = { IconTile(Icons.Outlined.Settings, tr("Bank SMS settings"), { nav.push(Screen.MessageSettings) }) },
        bottom = if (current == null) null else ({
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GreyButton(tr("Dismiss")) { dismiss(current) }
                GreyButton(tr("Edit")) { d?.let { nav.push(Screen.EditTxn(preset = it, captureId = current.id)) } }
                PrimaryButton(
                    if (ready) tr("Add {0}", amountText) else tr("Review and add"),
                    onClick = { d?.let { if (ready) add(current, it) else nav.push(Screen.EditTxn(preset = it, captureId = current.id)) } },
                    modifier = Modifier.weight(1f).height(54.dp), enabled = d != null,
                )
            }
        }),
    ) {
        Text(tr("{0} to review · read on this phone only", captures.size), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        if (current != null) {
            ReviewCard(current, d, amountText, ready, position = captures.indexOf(current) + 1, total = captures.size)
        } else {
            FCard(padding = 24.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(F.accentTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, tint = F.accent, modifier = Modifier.size(28.dp))
                    }
                    Text(if (added > 0) tr("All caught up") else tr("Nothing to review"), color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        when {
                            added == 1 -> tr("1 entry added to Activity. New bank messages will show up here.")
                            added > 1 -> tr("{0} entries added to Activity. New bank messages will show up here.", added)
                            capture -> tr("New bank messages will show up here. You can also paste one or import older ones.")
                            else -> tr("Turn on \"Detect bank SMS\" to get suggestions from bank messages, or paste one.")
                        },
                        color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                    if (!capture) {
                        Text(tr("Bank SMS settings"), Modifier.clip(RoundedCornerShape(12.dp)).background(F.fill).clickable { nav.push(Screen.MessageSettings) }
                            .padding(horizontal = 14.dp, vertical = 10.dp), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(tr("Paste a message"), Modifier.weight(1f), Icons.Outlined.ContentPaste) { pasting = true }
            SmallAction(tr("Import last {0} days", days), Modifier.weight(1f)) { if (capture) import() else disclosure = { import() } }
        }
        val others = captures.filter { it.id != current?.id }
        if (others.isNotEmpty()) {
            SectionLabel(tr("Also waiting"))
            FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    others.forEachIndexed { i, c ->
                        if (i > 0) Divider()
                        FRow(
                            c.merchant.ifBlank { c.sender.ifBlank { tr("Pasted message") } },
                            subtitle = "${c.sender.ifBlank { tr("Pasted") }} · ${whenLabel(c.receivedAt)}",
                            value = c.amount?.let { a -> if (app.moneytracker.domain.isRefund(c.kind, a)) currency.format(-a, showSign = true) else currency.format(a) },
                            valueColor = if (c.kind == TxnKind.INCOME || app.moneytracker.domain.isRefund(c.kind, c.amount)) F.income else F.ink,
                            onClick = { focusId = c.id },
                        )
                    }
                }
            }
        }
        Text(tr("Your corrections teach the matcher: the account and category you pick are suggested next time for messages like this."),
            color = F.muted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 4.dp))
    }

    if (pasting) PasteSheet(onDismiss = { pasting = false }) { c -> pasting = false; focusId = c.id; toaster.show(tr("Message read · review it above")) }
    disclosure?.let { action ->
        Confirm(tr("Detect bank SMS"), smsDisclosureText(), tr("Continue"), onDismiss = { disclosure = null }, danger = false) { action() }
    }
}

internal fun smsDisclosureText() = tr("MoneyTracker reads SMS from banks and card issuers on this phone to suggest income and expense entries. " +
    "Messages are processed only on your phone and are never uploaded or shared: the app has no internet access. " +
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

@Composable
private fun ReviewCard(c: Capture, d: TxnDraft?, amountText: String, ready: Boolean, position: Int, total: Int) {
    val repo = LocalRepo.current
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val refund = d?.type == DraftType.REFUND
    Column(
        Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.sender.ifBlank { tr("Pasted") }, Modifier.clip(RoundedCornerShape(8.dp)).background(F.fill).padding(horizontal = 10.dp, vertical = 5.dp),
                color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Text(whenLabel(c.receivedAt), Modifier.weight(1f), color = F.muted, fontSize = 12.5.sp)
            if (total > 1) Text(tr("{0} of {1}", position, total), color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        }
        Text(c.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(F.page).padding(horizontal = 12.dp, vertical = 10.dp),
            color = F.ink, fontSize = 13.sp, lineHeight = 19.sp)
        Column {
            Text(if (refund) tr("We'll add a refund") else if (d?.type == DraftType.INCOME) tr("We'll add income") else tr("We'll add"),
                color = F.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(amountText, color = if (refund || d?.type == DraftType.INCOME) F.income else F.ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, style = F.money)
        }
        Column {
            Divider()
            DetailLine(tr("Account"), d?.accountId?.let { accountName[it] } ?: tr("Choose in Edit"), muted = !ready && d?.accountId !in accountName)
            Divider()
            DetailLine(tr("Category"), d?.categoryId?.let { categoryPath(it, catById) } ?: tr("Not set"), muted = d?.categoryId == null)
            Divider()
            DetailLine(tr("Note"), d?.note?.ifBlank { null } ?: c.merchant.ifBlank { "—" }, muted = false)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, muted: Boolean) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(value, Modifier.weight(1f).padding(start = 12.dp), color = if (muted) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GreyButton(text: String, onClick: () -> Unit) {
    Box(Modifier.height(54.dp).clip(F.buttonShape).background(F.card).border(1.dp, F.border, F.buttonShape).clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallAction(text: String, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(F.iconShape).background(F.card).border(1.dp, F.border, F.iconShape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = F.ink, modifier = Modifier.size(18.dp))
        Text(text, Modifier.padding(start = 6.dp), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PasteSheet(onDismiss: () -> Unit, onRead: (Capture) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Sheet(
        tr("Paste a bank message"), onDismiss,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Cancel"), onDismiss, Modifier.weight(0.4f))
                PrimaryButton(tr("Read message"), enabled = text.isNotBlank(), modifier = Modifier.weight(0.6f), onClick = {
                    val msg = text
                    scope.launch {
                        val c = withContext(repo.io) { repo.ingestMessage("PASTE", "", msg, Clock.System.now().toEpochMilliseconds()) }
                        if (c == null) error = true else onRead(c)
                    }
                })
            }
        },
    ) {
        Text(tr("Paste a bank SMS about a payment. It is read on this phone only."), color = F.muted, fontSize = 13.sp)
        SmsTextArea(text, { text = it; error = false }, minHeight = 120.dp)
        if (error) Text(tr("That doesn't look like a payment message (or it was already added)."), color = F.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Multi-line input in the new layout's style. */
@Composable
internal fun SmsTextArea(value: String, onChange: (String) -> Unit, minHeight: Dp = 96.dp) {
    BasicTextField(
        value, onChange,
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(12.dp)).background(F.card)
            .border(1.dp, F.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = TextStyle(color = F.ink, fontSize = 14.sp, lineHeight = 21.sp), cursorBrush = SolidColor(F.accent),
    )
}
