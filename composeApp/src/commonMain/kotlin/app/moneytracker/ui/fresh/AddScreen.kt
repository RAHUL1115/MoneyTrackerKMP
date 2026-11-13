package app.moneytracker.ui.fresh

import app.moneytracker.data.NoteSuggestion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.ui.EntryChoices
import app.moneytracker.ui.EntryFormModel
import app.moneytracker.ui.EntryHints
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.collectAsState
import app.moneytracker.ui.collectEntryChoices
import app.moneytracker.ui.rememberAccountSummary
import app.moneytracker.ui.rememberEntryForm
import app.moneytracker.ui.rememberEntryHints
import app.moneytracker.ui.rememberSetting
import app.moneytracker.ui.suggestedCategory
import app.moneytracker.ui.tr

/** The new layout's add / edit entry screen: wiring. */
@Composable
internal fun FreshEditTxn(s: Screen.EditTxn) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val model = rememberEntryForm(repo, s, scope)
    val hints = rememberEntryHints(repo, scope)
    val choices = collectEntryChoices(repo)
    val recentTxns by hints.recentTxns.collectAsState()
    val lines = rememberAccountSummary()?.lines?.associateBy { it.account.id }.orEmpty()
    val ui = rememberSaveable(saver = AddUi.Saver) { AddUi(AddUi.startStep(s, model.form, model.setup.accountFirst)) }
    AddEffects(s, model, ui, choices)
    AddScreen(AddContext(s, model, hints, choices, ui, AccountTrayData(recentTxns, lines)))
}

/** Everything the add screen's pieces draw from: the form, its hints and choices, and the screen's own UI state. */
internal class AddContext(
    val screen: Screen.EditTxn,
    val model: EntryFormModel,
    val hints: EntryHints,
    val choices: EntryChoices,
    val ui: AddUi,
    val tray: AccountTrayData,
)

/** Location, the unsaved-changes guard and the keyboard overlay. */
@Composable
private fun AddEffects(s: Screen.EditTxn, model: EntryFormModel, ui: AddUi, choices: EntryChoices) {
    val nav = LocalNavigator.current
    val location = LocalPlatform.current.location
    val locationOn by rememberSetting(Prefs.LOCATION)
    // Capture location once for new entries if the user enabled it.
    LaunchedEffect(locationOn) {
        if (locationOn && s.txnId == null && model.form.lat == null) location.currentLocation({ a, b -> model.edit { it.copy(lat = a, lng = b) } }, {})
    }
    // Unsaved-changes guard: compare the form with what it held once loaded.
    LaunchedEffect(model.form.existing, choices.accounts) { model.takeBaseline() }
    DisposableEffect(nav) {
        val remove = nav.interceptBack {
            if (model.isDirty) { ui.confirmDiscard = true; true } else false
        }
        onDispose { remove() }
    }
    DisposableEffect(Unit) { KeyboardOverlay.active = true; onDispose { KeyboardOverlay.active = false } }
}

// ───────────── one screen for adding and editing
// Nothing here moves: every zone below the amount has a fixed height, and the keyboard draws over the keypad and
// Save instead of resizing the screen (past notes ride on a strip on top of it). Type and date change rarely, so
// they sit at the top; the amount, category, keypad and Save stay near the thumb.

@Composable
private fun AddScreen(c: AddContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val learned by produceState<String?>(null, f.note, f.type) { value = c.hints.learnedCategory(f.note, f.type) }
    val suggestedCat = f.suggestedCategory(learned, c.choices.catById)?.takeIf { !f.isAdjustment }
    // Past notes matching what is typed (only while the note field has focus).
    val noteMatches by produceState(emptyList<NoteSuggestion>(), f.note, c.ui.noteFocused, f.type) {
        value = if (!c.ui.noteFocused) emptyList() else c.hints.noteMatches(f.note, f.type, 3)
    }
    TrayEffects(c)
    Box(Modifier.fillMaxSize().background(F.page).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = F.gutter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Glyph.Close, tr("Close"), { nav.back() }, background = F.card)
                TypeTabs(f, Modifier.weight(1f)) { c.model.pickType(it) }
            }
            ReceiptCard(c)
            ExtraChips(c)
            // Spare height sits between the chips and the keypad; on shorter screens the keypad gives some up.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                SaveRow(c)
                if (c.ui.step != Step.CLOSED) Tray(c, Modifier.weight(1f, fill = false).heightIn(min = 232.dp, max = 300.dp))
            }
        }
        // Past notes and the suggested category ride on a strip on top of the keyboard, so they push nothing.
        if (c.ui.noteFocused && (noteMatches.isNotEmpty() || suggestedCat != null)) Box(
            Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().background(F.card)
                .padding(horizontal = F.gutter, vertical = 8.dp),
        ) { NoteSuggestions(c, noteMatches, suggestedCat) }
    }
    AddSheets(c)
    AddDialogs(c)
}

/** The tray follows the note's focus and the keyboard, and a bank message opens on what is still missing. */
@Composable
private fun TrayEffects(c: AddContext) {
    val ui = c.ui
    val focus = LocalFocusManager.current
    val f = c.model.form
    // A tap on the note, or the keyboard closing, keeps the tray in step with it.
    LaunchedEffect(ui.noteFocused) { if (ui.noteFocused) ui.step = Step.NOTE }
    // The keypad hides under the phone keyboard but keeps its space, so a shorter keyboard never shows half of it.
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        // The note is the last step: closing its keyboard closes the tray too, as in the classic layout.
        if (imeWasUp && !imeUp && ui.step == Step.NOTE) ui.closeTray(focus)
        imeWasUp = imeUp
    }
    LaunchedEffect(f.isTransfer) {
        if (ui.step != Step.CLOSED && ui.step !in stepOrder(c.model.form, c.model.setup.accountFirst)) ui.step = Step.AMOUNT
    }
    // From a bank message: start on the first thing still missing; with everything there the keypad stays closed
    // and Save is in reach.
    LaunchedEffect(c.screen.captureId) {
        if (c.screen.captureId == null || c.model.form.isTransfer) return@LaunchedEffect
        val missing = firstMissing(c.model.form, c.model.amountValue())
        if (missing != null) ui.goTo(missing, focus) else ui.step = Step.CLOSED
    }
}

/** Expense / Income / Transfer; a refund sits on Income, an adjustment has none selected. */
@Composable
private fun TypeTabs(f: app.moneytracker.ui.EntryForm, modifier: Modifier, onPick: (DraftType) -> Unit) {
    val tabs = listOf(DraftType.EXPENSE to tr("Expense"), DraftType.INCOME to tr("Income"), DraftType.TRANSFER to tr("Transfer"))
    val selected = if (f.isAdjustment) null else if (f.type == DraftType.REFUND) DraftType.INCOME else f.type
    Row(modifier.height(44.dp).clip(F.iconShape).background(F.card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEach { (t, label) ->
            val on = t == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) F.page else Color.Transparent).clickable { onPick(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) F.ink else F.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
