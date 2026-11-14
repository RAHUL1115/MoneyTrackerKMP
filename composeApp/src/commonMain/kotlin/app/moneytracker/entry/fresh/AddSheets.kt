@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.moneytracker.entry.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.formatTime
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.designsystem.classic.long
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.Pill
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.SecondaryButton
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.designsystem.fresh.SheetOption
import app.moneytracker.designsystem.fresh.SheetOptionGroup
import app.moneytracker.designsystem.fresh.pick
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus

// The small sheets the add screen's chips open (AddScreen.kt).

@Composable
internal fun AddSheets(c: AddContext) {
    val ui = c.ui
    val close = { ui.sheet = AddSheet.NONE }
    when (ui.sheet) {
        AddSheet.NONE -> Unit
        AddSheet.DATE -> DateSheet(c, close)
        AddSheet.DESC -> Sheet(tr("Description"), close, bottom = { PrimaryButton(tr("Done"), close) }) {
            AddInput(c.model.form.memo, { v -> c.model.edit { it.copy(memo = v) } }, tr("Anything you want to remember"), Modifier.fillMaxWidth(),
                singleLine = false, fieldModifier = Modifier.heightIn(min = 72.dp))
        }
        AddSheet.PHOTO -> PhotoSheet(c, close)
        AddSheet.PLACE -> PlaceSheet(c, close)
        AddSheet.ORIGIN -> Sheet(tr("Original message"), close) {
            Text(c.model.form.origin, color = F.ink, fontSize = 14.sp, lineHeight = 18.sp)
        }
        AddSheet.TAGS -> TagSheet(c, close)
        AddSheet.REPEAT -> {
            val f = c.model.form
            RepeatSheet(
                canRepeat = f.existing == null, canInstall = c.model.canInstall,
                repeat = f.repeat, repeatEnd = f.repeatEnd, installments = f.installments, start = f.date,
                onDismiss = close,
                onApply = { r, end, inst -> c.model.edit { it.copy(repeat = r, repeatEnd = end, installments = inst) }; close() },
            )
        }
        AddSheet.CURRENCY -> CurrencySheet(c, close)
        AddSheet.BOOKMARK -> BookmarkSheet(c, close)
        AddSheet.COPY -> CopySheet(c, close)
    }
}

@Composable
private fun DateSheet(c: AddContext, close: () -> Unit) {
    val f = c.model.form
    val timeOn = c.model.setup.timeOn
    val today = remember { c.model.setup.today() }
    Sheet(if (timeOn) tr("Date and time") else tr("Date"), close) {
        val days = listOf(today, today.minus(DatePeriod(days = 1)), today.minus(DatePeriod(days = 2)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d -> AddChip(dateLabel(d, today), f.date == d, Modifier.weight(1f)) { c.model.edit { it.copy(date = d) }; close() } }
            val other = f.date !in days
            AddChip(if (other) f.date.short() else tr("Pick date"), other, Modifier.weight(1f)) { c.ui.datePicker = true }
        }
        Spacer(Modifier.height(8.dp))
        Text(f.date.long(), color = F.muted, fontSize = 13.sp)
        if (timeOn) {
            Spacer(Modifier.height(4.dp))
            FRow(tr("Time"), value = f.timeOfDay?.let { formatTime(it) } ?: tr("No time"), valueColor = F.ink, onClick = { c.ui.timePicker = true })
        }
    }
}

@Composable
private fun PhotoSheet(c: AddContext, close: () -> Unit) {
    val photos = LocalPlatform.current.photos
    val allPhotos = c.model.form.photoFiles
    val add: (String) -> Unit = { file -> c.model.edit { it.copy(newPhotos = it.newPhotos + file) } }
    Sheet(tr("Receipt photos"), close) {
        if (allPhotos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            allPhotos.forEachIndexed { i, file ->
                Box(Modifier.size(72.dp).clip(F.controlShape).background(F.fill).clickable(onClickLabel = tr("View receipt photo {0}", i + 1)) { c.ui.viewer = i }) {
                    photos.PhotoImage(file, Modifier.fillMaxSize())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("Take photo"), { photos.takePhoto(add) }, Modifier.weight(1f))
            SecondaryButton(tr("From gallery"), { photos.pickPhoto(add) }, Modifier.weight(1f))
        }
        Text(tr("Photos stay private on this phone."), Modifier.padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
    }
}

@Composable
private fun PlaceSheet(c: AddContext, close: () -> Unit) {
    val location = LocalPlatform.current.location
    val toaster = LocalToaster.current
    Sheet(tr("Location"), close) {
        Text(tr("Saved where you added this entry."), color = F.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val la = c.model.form.lat
        val ln = c.model.form.lng
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (la != null && ln != null) SecondaryButton(tr("Open in maps"), { location.openMap(la, ln) }, Modifier.weight(1f))
            SecondaryButton(tr("Remove location"), {
                c.model.edit { it.copy(lat = null, lng = null) }; close(); toaster.show(tr("Location removed from this entry"))
            }, Modifier.weight(1f), danger = true)
        }
    }
}

@Composable
private fun TagSheet(c: AddContext, close: () -> Unit) {
    val nav = LocalNavigator.current
    val tags = c.choices.tags
    val tagIds = c.model.form.tagIds
    Sheet(tr("Tags"), close, bottom = { PrimaryButton(tr("Done"), close) }) {
        if (tags.isEmpty()) Text(tr("No tags yet"), color = F.muted, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t ->
                val on = t.id in tagIds
                Pill("#" + t.name, on, { c.model.edit { it.copy(tagIds = if (on) it.tagIds - t.id else it.tagIds + t.id) } }, role = Role.Checkbox)
            }
        }
        AddLink(tr("Manage tags ›")) { close(); nav.push(Screen.Tags) }
    }
}

@Composable
private fun CurrencySheet(c: AddContext, close: () -> Unit) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val code = c.model.form.currencyCode
    fun pick(new: String?) { c.model.edit { it.copy(currencyCode = new) }; close() }
    Sheet(tr("Currency"), close) {
        SheetOptionGroup {
            SheetOption("${currency.symbol}  ${tr("Main currency")}", code == null) { pick(null) }
            LocalCurrencies.current.values.sortedBy { it.sortOrder }.forEach { cur ->
                SheetOption("${cur.symbol}  ${cur.code}", code == cur.code) { pick(cur.code) }
            }
        }
        AddLink(tr("Manage currencies ›")) { close(); nav.push(Screen.Currencies) }
    }
}

@Composable
private fun BookmarkSheet(c: AddContext, close: () -> Unit) {
    val toaster = LocalToaster.current
    val f = c.model.form
    var name by remember { mutableStateOf(f.note.ifBlank { f.categoryId?.let { categoryPath(it, c.choices.catById) }.orEmpty() }) }
    Sheet(tr("Save as bookmark"), close, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("Cancel"), close, Modifier.weight(1f))
            PrimaryButton(tr("Save bookmark"), {
                val t = c.model.saveBookmark(name)
                c.ui.bookmarked = true; close()
                toaster.show(tr("Saved “{0}” as a bookmark", t.name))
            }, Modifier.weight(1f), enabled = name.isNotBlank())
        }
    }) {
        Text(tr("Saves this entry so you can start a new one from it later: Activity › ☆ Bookmarks."), color = F.muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        AddInput(name, { name = it }, tr("Name"), Modifier.fillMaxWidth())
    }
}

@Composable
private fun CopySheet(c: AddContext, close: () -> Unit) {
    val nav = LocalNavigator.current
    val f = c.model.form
    fun open(withTodaysDate: Boolean) {
        close()
        val d = c.model.copyDraft(withTodaysDate) ?: return
        nav.pop(); nav.push(Screen.EditTxn(preset = d))
    }
    Sheet(tr("Copy entry"), close) {
        Text(tr("Opens a new entry with the same amount, category, account and note."), color = F.muted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        SheetOption(tr("Copy with today's date")) { open(withTodaysDate = true) }
        SheetOption(tr("Copy with the entry's date ({0})", f.date.short())) { open(withTodaysDate = false) }
        // A refund for this purchase: same category, account and amount, all editable.
        if (f.canRecordRefund) {
            SheetOption(tr("Record a refund for this"), subtitle = tr("Money back for this purchase")) {
                close()
                nav.push(Screen.EditTxn(preset = c.model.refundDraft()))
            }
        }
    }
}
