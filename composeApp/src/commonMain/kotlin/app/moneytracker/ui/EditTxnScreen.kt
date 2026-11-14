@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.DraftType
import app.moneytracker.data.Prefs

internal enum class Panel { NONE, AMOUNT, FEE, CATEGORY, ACCOUNT, TO_ACCOUNT }

/**
 * The classic editor's own UI state: the open panel (keypad, categories, accounts) and dialogs. The entry itself
 * lives in [EntryFormModel]. The panel and the fee row survive the app closing in the background.
 */
@Stable
internal class EditUi(panel: Panel, showFee: Boolean, viewing: Boolean) {
    var panel by mutableStateOf(panel)
    var showFee by mutableStateOf(showFee)
    /** Existing entries open read-only (Delete / Copy / Bookmark); touching any field switches to editing. */
    var viewing by mutableStateOf(viewing)
    var tagDialog by mutableStateOf(false)
    var bookmarkDialog by mutableStateOf(false)
    var photoMenu by mutableStateOf(false)
    var photoNotice by mutableStateOf(false)
    var datePicker by mutableStateOf(false)
    var timePicker by mutableStateOf(false)
    var repeatDialog by mutableStateOf(false)
    var copyDialog by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    var noteFocused by mutableStateOf(false)
    val noteFocus = FocusRequester()

    /** After the amount: the first of the accounts or category still empty, else close the panel. */
    fun nextAfterAmount(f: EntryForm) {
        panel = when {
            f.isTransfer && f.accountId == null -> Panel.ACCOUNT
            f.isTransfer && f.toAccountId == null -> Panel.TO_ACCOUNT
            !f.isTransfer && f.categoryId == null -> Panel.CATEGORY
            !f.isTransfer && f.accountId == null -> Panel.ACCOUNT
            else -> Panel.NONE
        }
    }

    /** With nothing left to pick, the note is next. */
    fun focusNoteIfDone(f: EntryForm) {
        if (panel == Panel.NONE && f.note.isEmpty()) noteFocus.requestFocus()
    }

    companion object {
        fun startPanel(s: Screen.EditTxn, f: EntryForm, accountFirst: Boolean): Panel = when {
            s.txnId != null || s.preset != null -> Panel.NONE
            accountFirst && f.accountId == null -> Panel.ACCOUNT
            else -> Panel.AMOUNT
        }

        fun saver(viewing: Boolean) = Saver<EditUi, String>(
            save = { "${it.panel.name},${it.showFee}" },
            restore = { s -> s.split(",").let { EditUi(Panel.valueOf(it[0]), it[1].toBoolean(), viewing) } },
        )
    }
}

/** Everything the classic editor's pieces draw from. */
internal class EditContext(
    val screen: Screen.EditTxn,
    val model: EntryFormModel,
    val hints: EntryHints,
    val choices: EntryChoices,
    val ui: EditUi,
    val accountsGrouped: Boolean,
)

/** The classic layout's add / edit entry screen: wiring. */
@Composable
fun EditTxnScreen(screen: Screen.EditTxn) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val model = rememberEntryForm(repo, screen, scope)
    val hints = rememberEntryHints(repo, scope)
    val choices = collectEntryChoices(repo)
    val grouped by rememberSetting(Prefs.ACCOUNTS_GROUPED)
    val viewing = screen.txnId != null
    val ui = rememberSaveable(saver = EditUi.saver(viewing)) {
        EditUi(EditUi.startPanel(screen, model.form, model.setup.accountFirst), model.form.feeText.isNotEmpty(), viewing)
    }
    EditEffects(screen, model, ui, choices)
    EditTxnContent(EditContext(screen, model, hints, choices, ui, grouped))
}

/** Location, the unsaved-changes guard, and leaving read-only view once anything is touched. */
@Composable
private fun EditEffects(screen: Screen.EditTxn, model: EntryFormModel, ui: EditUi, choices: EntryChoices) {
    val nav = LocalNavigator.current
    val location = LocalPlatform.current.location
    val locationOn by rememberSetting(Prefs.LOCATION)
    // New entries: capture location once if the user enabled it.
    LaunchedEffect(locationOn) {
        if (locationOn && screen.txnId == null && model.form.lat == null) location.currentLocation({ a, b -> model.edit { it.copy(lat = a, lng = b) } }, {})
    }
    // Unsaved-changes guard: compare the form with what it held once loaded.
    LaunchedEffect(model.form.existing, choices.accounts) { model.takeBaseline() }
    // Back closes an open keypad/picker first, then asks before throwing away edits.
    DisposableEffect(nav) {
        val remove = nav.interceptBack {
            when {
                ui.panel != Panel.NONE -> { ui.panel = Panel.NONE; true }
                model.isDirty -> { ui.confirmDiscard = true; true }
                else -> false
            }
        }
        onDispose { remove() }
    }
    val touched = ui.panel != Panel.NONE || ui.noteFocused || ui.datePicker || ui.timePicker || ui.repeatDialog || ui.tagDialog
    LaunchedEffect(touched, model.isDirty) { if (touched || model.isDirty) ui.viewing = false }
}

@Composable
private fun EditTxnContent(c: EditContext) {
    val f = c.model.form
    val ui = c.ui
    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        EditTopBar(c)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            TypeRow(c)
            Spacer(Modifier.height(12.dp))
            DateFormRow(c)
            AmountRows(c)
            if (f.isTransfer) TransferRows(c) else CategoryAndAccountRows(c)
            NoteRows(c)
            ExtraRows(c)
            f.problem?.let { Text(it.message(), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
            if (ui.viewing && f.existing != null) ViewingButtons(c) else SaveButtons(c)
            Spacer(Modifier.height(if (ui.panel == Panel.NONE) 24.dp else 340.dp))
        }
        EditPanels(c)
    }
    EditDialogs(c)
}

@Composable
private fun EditTopBar(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val title = if (f.isAdjustment) (if (f.adjustUp) tr("Income Balance") else tr("Expense Balance")) else when (f.type) {
        DraftType.INCOME -> tr("Income"); DraftType.EXPENSE -> tr("Expense"); DraftType.REFUND -> tr("Refund"); DraftType.TRANSFER -> tr("Transfer")
    }
    BackTopBar(title, { nav.back() }) {
        if (!f.isAdjustment) IconButton(onClick = { c.ui.bookmarkDialog = true }) { Icon(Icons.Outlined.StarOutline, tr("Save as bookmark")) }
        if (f.existing != null) {
            if (!f.isAdjustment) IconButton(onClick = { c.ui.copyDialog = true }) { Icon(Icons.Outlined.ContentCopy, tr("Copy")) }
            IconButton(onClick = { c.ui.confirmDelete = true }) { Icon(Icons.Outlined.Delete, tr("Delete")) }
        }
    }
}

/** Save (and Continue for a new entry): Continue saves and clears the form for the next one. */
internal fun EditContext.save(continueAfter: Boolean, nav: Navigator) {
    model.save { saved ->
        if (saved.isNew && continueAfter) {
            model.startNext(usualAccount = false)
            ui.showFee = false
            ui.panel = if (model.setup.accountFirst) Panel.ACCOUNT else Panel.AMOUNT
        } else nav.pop()
    }
}
