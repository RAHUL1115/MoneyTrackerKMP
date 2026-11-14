package app.moneytracker.entry.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.NoteSuggestion
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.bleedScroll
import app.moneytracker.designsystem.fresh.choiceTap

// The chips, Save row and note suggestions of the add screen (AddScreen.kt).

/** Resting colour of the extra chips: white on the grey page. */
private val LocalChipRest = staticCompositionLocalOf<Color?> { null }

/** Everything else about the entry: one chip each, each opening a small sheet. A chip shows its value once set. */
@Composable
internal fun ExtraChips(c: AddContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    CompositionLocalProvider(LocalChipRest provides F.card) {
        Row(Modifier.bleedScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeeAndRefundChips(c)
            // Most used first; photo and description, rarely added, go last.
            if (!f.isAdjustment) EntryChips(c)
            if (f.lat != null && f.lng != null) ExtraChip(tr("Location"), true) { ui.sheet = AddSheet.PLACE }
            if (f.origin.isNotBlank() && c.hints.showsBankMessage()) ExtraChip(tr("Bank SMS"), true) { ui.sheet = AddSheet.ORIGIN }
            if (!f.isAdjustment) PhotoChip(c)
            ExtraChip(if (f.memo.isBlank()) tr("Description") else tr("Description ✓"), f.memo.isNotBlank()) { focus.clearFocus(); ui.sheet = AddSheet.DESC }
        }
    }
}

/** A transfer's fee, and the refund switch on income. */
@Composable
private fun FeeAndRefundChips(c: AddContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    val toaster = LocalToaster.current
    // A transfer's fee is a chip that points the keypad at it.
    if (f.isTransfer) ExtraChip(
        if (f.feeText.isEmpty()) tr("Fee") else tr("Fee {0}", shown(c.model.fmt, addExprShown(f.feeText))), ui.feeTarget || f.feeText.isNotEmpty(),
        toggledOn = ui.feeTarget,
    ) {
        ui.feeTarget = !ui.feeTarget; ui.goTo(Step.AMOUNT, focus)
    }
    // Like the classic layout, a refund is money coming in, so it sits on the Income tab (with expense categories).
    val refundable = !f.isAdjustment && (f.type == DraftType.INCOME || f.type == DraftType.REFUND) && f.existing?.transferGroupId == null
    if (refundable) ExtraChip(if (f.type == DraftType.REFUND) tr("Refund ✓") else tr("Refund"), f.type == DraftType.REFUND, toggledOn = f.type == DraftType.REFUND) {
        val on = c.model.toggleRefund()
        toaster.show(if (on) tr("Refund: pick the expense category it lowers") else tr("Back to income"))
    }
}

/** Repeat, tags and bookmark: not for balance adjustments. */
@Composable
private fun EntryChips(c: AddContext) {
    val f = c.model.form
    val ui = c.ui
    val focus = LocalFocusManager.current
    val repeatLabel = listOfNotNull(f.repeat?.let { tr(it.label) }, if (f.installments > 1) tr("{0} installments", f.installments) else null).joinToString(" · ")
    ExtraChip(repeatLabel.ifEmpty { tr("Repeat") }, repeatLabel.isNotEmpty()) { focus.clearFocus(); ui.sheet = AddSheet.REPEAT }
    val tagNames = c.choices.tags.filter { it.id in f.tagIds }.map { "#" + it.name }
    ExtraChip(when (tagNames.size) { 0 -> tr("Tags"); 1 -> tagNames[0]; else -> tr("{0} tags", tagNames.size) }, tagNames.isNotEmpty()) {
        focus.clearFocus(); ui.sheet = AddSheet.TAGS
    }
    ExtraChip(if (ui.bookmarked) tr("Bookmarked ★") else tr("Bookmark"), ui.bookmarked) { focus.clearFocus(); ui.sheet = AddSheet.BOOKMARK }
}

@Composable
private fun PhotoChip(c: AddContext) {
    val focus = LocalFocusManager.current
    val count = c.model.form.photoFiles.size
    ExtraChip(when (count) { 0 -> tr("Photo"); 1 -> tr("1 photo"); else -> tr("{0} photos", count) }, count > 0) {
        focus.clearFocus()
        if (count == 0 && !c.hints.photoNoticeSeen()) c.ui.photoNotice = true else c.ui.sheet = AddSheet.PHOTO
    }
}

/**
 * One "more about this entry" chip: soft pill, tinted once it holds a value. A chip that switches something on and
 * off passes [toggledOn]: TalkBack then reads it as a checkbox, and its tick is only for the eye.
 */
@Composable
private fun ExtraChip(text: String, set: Boolean, toggledOn: Boolean? = null, onClick: () -> Unit) {
    val tap = if (toggledOn == null) Modifier.clickable(onClick = onClick) else Modifier.choiceTap(toggledOn, Role.Checkbox, onClick)
    val spoken = if (toggledOn == null) Modifier else Modifier.clearAndSetSemantics { contentDescription = text.removeSuffix(" ✓") }
    Box(
        Modifier.height(36.dp).clip(F.pillShape).background(if (set) F.accentTint else LocalChipRest.current ?: F.page).then(tap).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, spoken, color = if (set) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Past notes matching what is typed, and the learned category, as chips over the keyboard. */
@Composable
internal fun NoteSuggestions(c: AddContext, noteMatches: List<NoteSuggestion>, suggestedCat: String?) {
    val focus = LocalFocusManager.current
    val currencies = LocalCurrencies.current
    val currency = LocalCurrency.current
    val catById = c.choices.catById
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (suggestedCat != null) SuggestChip(tr("Suggested: {0} ✓", categoryPath(suggestedCat, catById)), accent = true) {
            catById[suggestedCat]?.let { c.model.pickCategory(it) }
        }
        noteMatches.forEach { m ->
            val amount = m.amount.takeIf { it > 0 }?.let { (m.currency?.let { cur -> currencies[cur]?.format } ?: currency).format(it) }
            SuggestChip(listOfNotNull(m.note, amount).joinToString(" · ")) {
                c.model.pickNote(m); focus.clearFocus()
                val f = c.model.form
                if (!f.isTransfer && f.categoryId == null) c.ui.step = Step.CATEGORY
                else if (f.accountId == null) c.ui.step = if (f.isTransfer) Step.FROM else Step.ACCOUNT
            }
        }
    }
}

@Composable
private fun SuggestChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(F.pillShape).background(if (accent) F.accentTint else F.fill)
            .border(1.dp, if (accent) F.accentTint else F.border, F.pillShape).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) F.accent else F.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Delete, Copy and Save changes when editing; Save & new and Save when adding. */
@Composable
internal fun SaveRow(c: AddContext) {
    val f = c.model.form
    val save = rememberSave(c)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (f.existing != null) {
            Box(
                Modifier.size(54.dp).clip(F.fabShape).background(F.dangerTint)
                    .clickable(role = Role.Button) { c.ui.confirmDelete = true }.semantics { contentDescription = tr("Delete entry") },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Delete, null, tint = F.danger) }
            if (!f.isAdjustment) Box(
                Modifier.size(54.dp).clip(F.fabShape).border(1.dp, F.border, F.fabShape)
                    .clickable(role = Role.Button) { c.ui.sheet = AddSheet.COPY }.semantics { contentDescription = tr("Copy entry") },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Copy, null, tint = F.ink, modifier = Modifier.size(20.dp)) }
            PrimaryButton(tr("Save changes"), { save(false) }, Modifier.weight(1f).height(54.dp))
        } else {
            Box(
                Modifier.height(54.dp).clip(F.fabShape).background(F.card).border(1.dp, F.border, F.fabShape)
                    .clickable(role = Role.Button) { save(true) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Save & new"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
            PrimaryButton(saveLabel(c), { save(false) }, Modifier.weight(1f).height(54.dp), enabled = c.isReady())
        }
    }
}
