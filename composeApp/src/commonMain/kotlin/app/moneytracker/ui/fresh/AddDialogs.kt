@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.moneytracker.ui.fresh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.nowMinutes
import app.moneytracker.core.tr
import app.moneytracker.domain.RepeatType
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.ListenSheet
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.long
import kotlinx.datetime.LocalDate

// The dialogs of the add screen (AddScreen.kt): listening, pickers, the photo viewer and the confirmations.

@Composable
internal fun AddDialogs(c: AddContext) {
    val ui = c.ui
    val f = c.model.form
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    if (ui.listening) ListenSheet(onDismiss = { ui.listening = false }, look = newListenLook()) { g ->
        if (g == null) return@ListenSheet
        c.model.fillFrom(g)
        toaster.show(tr("Filled in from what you said. Check and save."))
    }
    DatePickerHost(ui.datePicker, f.date, { ui.datePicker = false }, { it?.let { d -> c.model.edit { form -> form.copy(date = d) }; ui.sheet = AddSheet.NONE } })
    PhotoViewer(c)
    if (ui.timePicker) AddTimePicker(f.timeOfDay ?: nowMinutes(), { ui.timePicker = false }, { c.model.edit { it.copy(timeOfDay = null) }; ui.timePicker = false }) { t ->
        c.model.edit { it.copy(timeOfDay = t) }; ui.timePicker = false
    }
    if (ui.confirmDiscard) Confirm(tr("Discard this entry?"), tr("What you've entered won't be saved."), tr("Discard"), { ui.confirmDiscard = false }) { nav.pop() }
    if (ui.photoNotice) Confirm(
        tr("About receipt photos"),
        tr("Photos are saved privately on this phone and shown with the entry. They're not included in backups or exports yet, " +
            "and they're removed if you uninstall the app."),
        tr("OK"), { ui.photoNotice = false }, danger = false,
    ) {
        ui.sheet = AddSheet.PHOTO
        c.hints.markPhotoNoticeSeen()
    }
    if (ui.confirmDelete) ConfirmDelete(c)
}

@Composable
private fun PhotoViewer(c: AddContext) {
    val start = c.ui.viewer ?: return
    val photos = LocalPlatform.current.photos
    val toaster = LocalToaster.current
    val allPhotos = c.model.form.photoFiles
    AddPhotoViewer(allPhotos, start, onRemove = { i ->
        val file = allPhotos[i]
        val saved = c.model.removePhoto(file)
        if (!saved) photos.deletePhotoFile(file)
        c.ui.viewer = null
        toaster.show(if (saved) tr("Photo removed. Save to keep the change") else tr("Photo removed"))
    }, onDismiss = { c.ui.viewer = null })
}

@Composable
private fun ConfirmDelete(c: AddContext) {
    val e = c.model.form.existing ?: return
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    Confirm(
        tr("Delete transaction?"),
        when {
            e.installmentGroupId != null -> tr("This deletes every installment of this purchase.")
            e.transferGroupId != null -> tr("Both sides of the transfer (and its fee) will be deleted.")
            else -> tr("{0} on {1}. This cannot be undone.", c.model.fmt.format(c.model.amountValue() ?: 0L), c.model.form.date.long())
        },
        tr("Delete"), { c.ui.confirmDelete = false },
    ) {
        c.model.delete { nav.pop(); toaster.show(tr("Entry deleted")) }
    }
}

private val instChoices = listOf(1, 2, 3, 6, 9, 12, 18, 24)

@Composable
internal fun RepeatSheet(
    canRepeat: Boolean,
    canInstall: Boolean,
    repeat: RepeatType?,
    repeatEnd: LocalDate?,
    installments: Int,
    start: LocalDate,
    onDismiss: () -> Unit,
    onApply: (RepeatType?, LocalDate?, Int) -> Unit,
) {
    var r by remember { mutableStateOf(repeat) }
    var end by remember { mutableStateOf(repeatEnd) }
    var inst by remember { mutableStateOf(installments) }
    var other by remember { mutableStateOf(if (installments in instChoices) "" else installments.toString()) }
    var endPicker by remember { mutableStateOf(false) }
    Sheet(tr("Repeat / installments"), onDismiss, bottom = { PrimaryButton(tr("Done"), { onApply(r, if (r == null) null else end, inst) }) }) {
        SectionLabel(tr("Repeat"))
        if (canRepeat) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("Doesn't repeat"), r == null, { r = null }, role = Role.RadioButton)
                RepeatType.entries.forEach { t -> Pill(tr(t.label), r == t, { r = t }, role = Role.RadioButton) }
            }
            if (r != null) FRow(tr("Until"), value = end?.long() ?: tr("No end date"), onClick = { endPicker = true })
            Text(tr("All rules are listed in Settings › Repeat entries."), color = F.muted, fontSize = 12.sp)
        } else Text(tr("Repeat can only be set on new transactions."), color = F.muted, fontSize = 13.sp)
        SectionLabel(tr("Installments"))
        if (canInstall) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                instChoices.forEach { n ->
                    Pill(if (n == 1) tr("None") else tr("{0} months", n), inst == n && other.isEmpty(), { inst = n; other = "" }, role = Role.RadioButton)
                }
            }
            AddInput(other, { v -> other = v.filter(Char::isDigit).take(2); inst = (other.toIntOrNull() ?: 1).coerceIn(1, 60) },
                tr("Other number of months"), Modifier.fillMaxWidth().padding(top = 6.dp), number = true)
        } else Text(tr("Installments are available for credit-card expenses."), color = F.muted, fontSize = 13.sp)
    }
    DatePickerHost(endPicker, end ?: start, { endPicker = false }, { end = it }, clearable = true)
}
