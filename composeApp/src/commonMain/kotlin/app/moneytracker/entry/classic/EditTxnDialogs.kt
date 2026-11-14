@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.moneytracker.entry.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.nowMinutes
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.DangerColor
import app.moneytracker.designsystem.classic.DateField
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.Picker
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.domain.RepeatType
import app.moneytracker.lists.classic.TagPickerDialog
import kotlinx.datetime.LocalDate

// The dialogs of the classic editor (EditTxnScreen.kt).

@Composable
internal fun EditDialogs(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val ui = c.ui
    DatePickerHost(ui.datePicker, f.date, { ui.datePicker = false }, { it?.let { d -> c.model.edit { form -> form.copy(date = d) } } })
    if (ui.confirmDiscard) {
        AlertDialog(
            onDismissRequest = { ui.confirmDiscard = false },
            title = { Text(tr("Discard this entry?")) },
            text = { Text(tr("What you've entered won't be saved.")) },
            confirmButton = { TextButton(onClick = { ui.confirmDiscard = false; nav.pop() }) { Text(tr("Discard"), color = DangerColor) } },
            dismissButton = { TextButton(onClick = { ui.confirmDiscard = false }) { Text(tr("Keep editing")) } },
        )
    }
    if (ui.photoNotice) PhotoNoticeDialog(c)
    if (ui.tagDialog) TagPickerDialog(f.tagIds, onDismiss = { ui.tagDialog = false }) { ids -> c.model.edit { it.copy(tagIds = ids) }; ui.tagDialog = false }
    if (ui.bookmarkDialog) BookmarkDialog(c)
    if (ui.repeatDialog) {
        RepeatInstallmentDialog(
            canRepeat = f.existing == null, canInstall = c.model.canInstall,
            repeat = f.repeat, repeatEnd = f.repeatEnd, installments = f.installments,
            onDismiss = { ui.repeatDialog = false },
            onApply = { r, end, inst -> c.model.edit { it.copy(repeat = r, repeatEnd = end, installments = inst) }; ui.repeatDialog = false },
        )
    }
    if (ui.copyDialog) CopyDialog(c)
    if (ui.timePicker) TimePickerDialog(f.timeOfDay ?: nowMinutes(), onDismiss = { ui.timePicker = false }, onClear = { c.model.edit { it.copy(timeOfDay = null) }; ui.timePicker = false }) { t ->
        c.model.edit { it.copy(timeOfDay = t) }; ui.timePicker = false
    }
    if (ui.confirmDelete) DeleteDialog(c)
}

@Composable
private fun PhotoNoticeDialog(c: EditContext) {
    AlertDialog(
        onDismissRequest = { c.ui.photoNotice = false },
        title = { Text(tr("About receipt photos")) },
        text = {
            Text(tr("Photos are saved privately on this phone and shown with the entry. They're not included in backups or exports yet, " +
                "and they're removed if you uninstall the app."))
        },
        confirmButton = {
            TextButton(onClick = {
                c.ui.photoNotice = false; c.ui.photoMenu = true
                c.hints.markPhotoNoticeSeen()
            }) { Text(tr("OK"), color = Accent) }
        },
    )
}

@Composable
private fun BookmarkDialog(c: EditContext) {
    val f = c.model.form
    var name by remember { mutableStateOf(f.note.ifBlank { f.categoryId?.let { categoryPath(it, c.choices.catById) }.orEmpty() }) }
    AlertDialog(
        onDismissRequest = { c.ui.bookmarkDialog = false },
        title = { Text(tr("Save as bookmark")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Bookmarks pre-fill a new entry in one tap. Leave the amount empty to be asked each time."), fontSize = 13.sp, color = SubtleText)
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                c.model.saveBookmark(name)
                c.ui.bookmarkDialog = false
            }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = { c.ui.bookmarkDialog = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun CopyDialog(c: EditContext) {
    val nav = LocalNavigator.current
    AlertDialog(
        onDismissRequest = { c.ui.copyDialog = false },
        title = { Text(tr("Copy entry")) },
        text = {
            Column {
                listOf(true to "Copy with today's date", false to "Copy with date of entry").forEach { (today, label) ->
                    Text(tr(label), Modifier.fillMaxWidth().clickable {
                        c.ui.copyDialog = false
                        c.model.copyDraft(withTodaysDate = today)?.let { d -> nav.pop(); nav.push(Screen.EditTxn(preset = d)) }
                    }.padding(vertical = 14.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { c.ui.copyDialog = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun DeleteDialog(c: EditContext) {
    val nav = LocalNavigator.current
    val e = c.model.form.existing ?: return
    AlertDialog(
        onDismissRequest = { c.ui.confirmDelete = false },
        title = { Text(tr("Delete transaction?")) },
        text = {
            Text(when {
                e.installmentGroupId != null -> tr("This deletes every installment of this purchase.")
                e.transferGroupId != null -> tr("Both sides of the transfer (and its fee) will be deleted.")
                else -> tr("This cannot be undone.")
            })
        },
        confirmButton = {
            TextButton(onClick = {
                c.ui.confirmDelete = false
                c.model.delete { nav.pop() }
            }) { Text(tr("Delete"), color = DangerColor) }
        },
        dismissButton = { TextButton(onClick = { c.ui.confirmDelete = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun RepeatInstallmentDialog(
    canRepeat: Boolean,
    canInstall: Boolean,
    repeat: RepeatType?,
    repeatEnd: LocalDate?,
    installments: Int,
    onDismiss: () -> Unit,
    onApply: (RepeatType?, LocalDate?, Int) -> Unit,
) {
    var r by remember { mutableStateOf(repeat) }
    var end by remember { mutableStateOf(repeatEnd) }
    var inst by remember { mutableStateOf(installments.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Repeat / Installments")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canRepeat) {
                    Picker(tr("Repeat"), listOf<RepeatType?>(null) + RepeatType.entries, r, { it?.label?.let { l -> tr(l) } ?: tr("Don't repeat") }, { r = it }, Modifier.fillMaxWidth())
                    if (r != null) DateField(tr("Until (optional)"), end, { end = it }, Modifier.fillMaxWidth(), clearable = true)
                } else Text(tr("Repeat can only be set on new transactions."), color = SubtleText, fontSize = 13.sp)
                if (canInstall) {
                    OutlinedTextField(
                        inst, { inst = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Installments (months)")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                } else Text(tr("Installments are available for credit-card expenses."), color = SubtleText, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(r, end, (inst.toIntOrNull() ?: 1).coerceIn(1, 60)) }) { Text(tr("OK"), color = Accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun TimePickerDialog(initial: Int, onDismiss: () -> Unit, onClear: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initial / 60, initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(tr("OK"), color = Accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(tr("No time")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}

/** Full-screen receipt photos, swipe between them. */
@Composable
internal fun PhotoViewer(files: List<String>, start: Int, onDismiss: () -> Unit) {
    val receiptPhotos = LocalPlatform.current.photos
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(start) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                receiptPhotos.PhotoFull(files[page], Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("Close"), tint = Color.White) }
                if (files.size > 1) Text("${pager.currentPage + 1} / ${files.size}", color = Color.White)
            }
        }
    }
}
