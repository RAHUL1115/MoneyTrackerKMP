package app.moneytracker.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.tr
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.NoteSuggestion

// The note, tags, place, description and photos of the classic editor (EditTxnScreen.kt).

@Composable
internal fun NoteRows(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    FormRow("Note", false, onClick = { ui.noteFocus.requestFocus() }) {
        BasicTextField(
            f.note, { v -> c.model.edit { it.copy(note = v) } }, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(ui.noteFocus).onFocusChanged {
                ui.noteFocused = it.isFocused
                if (it.isFocused) ui.panel = Panel.NONE
            },
        )
    }
    // Past notes matching what is typed (only while the note field has focus).
    val noteMatches by produceState(emptyList<NoteSuggestion>(), f.note, ui.noteFocused, f.type) {
        value = if (!ui.noteFocused) emptyList() else c.hints.noteMatches(f.note, f.type, 4)
    }
    if (noteMatches.isNotEmpty()) NoteMatches(c, noteMatches)
}

/** Autocomplete: picking a past note also brings back its category and (unless already chosen) account. */
@Composable
private fun NoteMatches(c: EditContext, noteMatches: List<NoteSuggestion>) {
    val focus = LocalFocusManager.current
    val currencies = LocalCurrencies.current
    val currency = LocalCurrency.current
    val catById = c.choices.catById
    Column(Modifier.fillMaxWidth().padding(start = 104.dp, end = 16.dp).clip(RoundedCornerShape(12.dp)).background(PageBackground)) {
        noteMatches.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    c.model.pickNote(m)
                    focus.clearFocus()
                    if (c.model.form.amountText.isEmpty()) c.ui.panel = Panel.AMOUNT else c.ui.nextAfterAmount(c.model.form)
                }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.note, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val amount = m.amount.takeIf { it > 0 }?.let { (m.currency?.let { cur -> currencies[cur]?.format } ?: currency).format(it) }
                val hint = listOfNotNull(if (c.model.form.isTransfer) null else m.categoryId?.let { catById[it]?.name }, amount)
                    .joinToString(" · ").ifEmpty { null }
                if (hint != null) Text(hint, color = SubtleText, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ExtraRows(c: EditContext) {
    val f = c.model.form
    val location = LocalPlatform.current.location
    if (!f.isAdjustment) FormRow("Tags", false, onClick = { c.ui.panel = Panel.NONE; c.ui.tagDialog = true }) {
        val names = c.choices.tags.filter { it.id in f.tagIds }.joinToString(" ") { "#" + it.name }
        Text(names.ifEmpty { "" }, color = IncomeColor)
    }
    val lat = f.lat
    val lng = f.lng
    if (lat != null && lng != null) {
        FormRow("Place", false, onClick = { location.openMap(lat, lng) }, trailing = {
            IconButton(onClick = { c.model.edit { it.copy(lat = null, lng = null) } }) { Icon(Icons.Filled.Close, tr("Remove location"), tint = SubtleText) }
        }) { Text("📍 ${((lat * 10000).toLong() / 10000.0)}, ${((lng * 10000).toLong() / 10000.0)}", color = SubtleText) }
    }
    // Description for every kind; photos not for adjustments.
    Spacer(Modifier.height(12.dp).fillMaxWidth().background(PageBackground))
    DescriptionRow(c)
    // The bank message this entry came from, read-only.
    if (f.origin.isNotBlank() && c.hints.showsBankMessage()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(tr("Original message"), color = SubtleText, fontSize = 12.sp)
            Text(f.origin, Modifier.padding(top = 2.dp), color = InkSoft, fontSize = 13.sp)
        }
    }
    if (!f.isAdjustment && f.photoFiles.isNotEmpty()) ReceiptPhotos(c)
}

/** Description field and photo button side by side (they used to overlap, and the field swallowed the taps). */
@Composable
private fun DescriptionRow(c: EditContext) {
    val f = c.model.form
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f).padding(top = 12.dp)) {
            if (f.memo.isEmpty()) Text(tr("Description"), color = SubtleText)
            BasicTextField(
                f.memo, { v -> c.model.edit { it.copy(memo = v) } },
                textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { if (it.isFocused) c.ui.panel = Panel.NONE },
            )
        }
        if (!f.isAdjustment) PhotoButton(c)
    }
}

@Composable
private fun PhotoButton(c: EditContext) {
    val focus = LocalFocusManager.current
    val photos = LocalPlatform.current.photos
    val ui = c.ui
    val add: (String) -> Unit = { file -> c.model.edit { it.copy(newPhotos = it.newPhotos + file) } }
    Box {
        IconButton(onClick = {
            focus.clearFocus(); ui.panel = Panel.NONE
            if (c.hints.photoNoticeSeen()) ui.photoMenu = true else ui.photoNotice = true
        }) {
            Icon(Icons.Outlined.PhotoCamera, tr("Add photo"), tint = SubtleText)
        }
        DropdownMenu(ui.photoMenu, { ui.photoMenu = false }) {
            DropdownMenuItem(text = { Text(tr("Take photo")) }, onClick = { ui.photoMenu = false; photos.takePhoto(add) })
            DropdownMenuItem(text = { Text(tr("Choose from gallery")) }, onClick = { ui.photoMenu = false; photos.pickPhoto(add) })
        }
    }
}

/** Receipt previews: one photo fills the width; several scroll sideways. Removing takes effect on Save. */
@Composable
private fun ReceiptPhotos(c: EditContext) {
    val files = c.model.form.photoFiles
    var fullScreen by remember { mutableStateOf<Int?>(null) }
    fullScreen?.let { start -> PhotoViewer(files, start) { fullScreen = null } }
    if (files.size == 1) {
        PhotoCard(c, 0, files.first(), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) { fullScreen = 0 }
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            files.forEachIndexed { i, file -> PhotoCard(c, i, file, Modifier.width(260.dp)) { fullScreen = i } }
        }
    }
}

private val PhotoCardShape = RoundedCornerShape(10.dp)

@Composable
private fun PhotoCard(c: EditContext, i: Int, file: String, modifier: Modifier, onOpen: () -> Unit) {
    val photos = LocalPlatform.current.photos
    val press = remember { MutableInteractionSource() }
    Box(modifier.height(180.dp).background(PageBackground, PhotoCardShape).clickable(press, indication = null, onClick = onOpen)) {
        photos.PhotoImage(file, Modifier.fillMaxSize())
        // The tap highlight follows the card's rounded corners; clipping the card itself would also cut the photo's.
        Box(Modifier.matchParentSize().clip(PhotoCardShape).indication(press, LocalIndication.current))
        Box(
            Modifier.align(Alignment.TopEnd).minimumInteractiveComponentSize().clip(CircleShape)
                .clickable(role = Role.Button) { if (!c.model.removePhoto(file)) photos.deletePhotoFile(file) }
                .semantics { contentDescription = tr("Remove photo {0}", i + 1) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, null, Modifier.size(28.dp).background(Color(0x99000000), CircleShape).padding(5.dp), tint = Color.White)
        }
    }
}

/** Read-only view of a saved entry: Delete, Copy, Refund and Bookmark; tapping any field switches to editing. */
@Composable
internal fun ViewingButtons(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val ui = c.ui
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // "Refund" starts a refund for this expense (same category, account and amount; all editable).
        listOfNotNull(
            Triple("Delete", DangerColor) { ui.confirmDelete = true },
            Triple("Copy", Ink) { ui.copyDialog = true },
            if (f.canRecordRefund) Triple("Refund", IncomeColor) { nav.push(Screen.EditTxn(preset = c.model.refundDraft())) } else null,
            Triple("Bookmark", Ink) { ui.bookmarkDialog = true },
        ).filter { !f.isAdjustment || it.first == "Delete" }.forEach { (label, color, action) ->
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(FillColor).clickable(role = Role.Button, onClick = action),
                contentAlignment = Alignment.Center,
            ) { Text(tr(label), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = color) }
        }
    }
    Text(tr("Tap any field to edit."), Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp, color = SubtleText)
}

@Composable
internal fun SaveButtons(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val color = when (f.type) {
        app.moneytracker.data.DraftType.INCOME, app.moneytracker.data.DraftType.REFUND -> IncomeColor
        app.moneytracker.data.DraftType.TRANSFER -> InkSoft
        app.moneytracker.data.DraftType.EXPENSE -> ExpenseColor
    }
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(color).clickable(role = Role.Button) { c.save(false, nav) },
            contentAlignment = Alignment.Center,
        ) { Text(tr("Save"), color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
        if (f.existing == null) {
            Box(
                Modifier.width(120.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(FillColor).clickable(role = Role.Button) { c.save(true, nav) },
                contentAlignment = Alignment.Center,
            ) { Text(tr("Continue"), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Ink) }
        }
    }
}
