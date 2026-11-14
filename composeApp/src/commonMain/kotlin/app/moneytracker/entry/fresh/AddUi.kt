package app.moneytracker.entry.fresh

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import app.moneytracker.app.Screen
import app.moneytracker.entry.EntryForm

/** The row the tray is filling. [CLOSED]: nothing is being filled (after the note, like the classic layout); tapping a row opens its tray again. */
internal enum class Step { AMOUNT, CATEGORY, ACCOUNT, FROM, TO, NOTE, CLOSED }

internal enum class AddSheet { NONE, TAGS, REPEAT, CURRENCY, BOOKMARK, COPY, DATE, PHOTO, DESC, PLACE, ORIGIN }

/**
 * The add screen's own UI state: which row the tray fills, which sheet or dialog is open. The entry itself lives in
 * [app.moneytracker.entry.EntryFormModel]; this only moves between rows. The step and the sheet survive the app closing.
 */
@Stable
internal class AddUi(step: Step, sheet: AddSheet = AddSheet.NONE) {
    var step by mutableStateOf(step)
    var sheet by mutableStateOf(sheet)
    /** Transfers: the keypad types into the fee. */
    var feeTarget by mutableStateOf(false)
    var datePicker by mutableStateOf(false)
    var timePicker by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    var confirmDiscard by mutableStateOf(false)
    var photoNotice by mutableStateOf(false)
    var viewer by mutableStateOf<Int?>(null)
    var bookmarked by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var noteFocused by mutableStateOf(false)
    /** The account tray's tab: Recent or a group id. */
    var acctTab by mutableStateOf<String?>(null)
    /** A main category whose sub-categories the tray shows. */
    var subOf by mutableStateOf<String?>(null)
    val noteFocus = FocusRequester()

    /** Opens the tray on [st]; the note row hands over to the phone keyboard. */
    fun goTo(st: Step, focus: FocusManager) {
        subOf = null
        if (st != Step.AMOUNT) feeTarget = false
        step = st
        if (st == Step.NOTE) noteFocus.requestFocus() else focus.clearFocus()
    }

    fun closeTray(focus: FocusManager) {
        focus.clearFocus()
        step = Step.CLOSED
    }

    /** Picking moves on to the next empty row. */
    fun advance(f: EntryForm, accountFirst: Boolean, fromMessage: Boolean, focus: FocusManager) {
        val order = stepOrder(f, accountFirst)
        val rest = order.drop(order.indexOf(step) + 1)
        if (!fromMessage) {
            goTo(rest.firstOrNull { it == Step.NOTE || !filled(f, it) } ?: Step.NOTE, focus)
            return
        }
        // From a bank message: only what's still missing; a note already read from the SMS is skipped too, and
        // with nothing left the keypad closes so Save is in reach.
        val next = rest.firstOrNull { st ->
            when (st) {
                Step.NOTE -> f.note.isBlank()
                Step.ACCOUNT, Step.FROM -> f.accountId == null
                Step.AMOUNT, Step.CATEGORY, Step.TO, Step.CLOSED -> !filled(f, st)
            }
        }
        if (next != null) goTo(next, focus) else closeTray(focus)
    }

    companion object {
        /** The step a screen opens on: "Account first" (Settings › Input order) starts a new entry on the account. */
        fun startStep(s: Screen.EditTxn, f: EntryForm, accountFirst: Boolean): Step {
            val blankNew = s.txnId == null && s.preset == null
            return if (blankNew && accountFirst && f.accountId == null) Step.ACCOUNT else Step.AMOUNT
        }

        val Saver = Saver<AddUi, String>(
            save = { "${it.step.name},${it.sheet.name}" },
            restore = { s -> s.split(",").let { AddUi(Step.valueOf(it[0]), AddSheet.valueOf(it[1])) } },
        )
    }
}

/** The rows in the order the tray fills them. */
internal fun stepOrder(f: EntryForm, accountFirst: Boolean): List<Step> = when {
    f.isTransfer -> listOf(Step.AMOUNT, Step.FROM, Step.TO, Step.NOTE)
    accountFirst -> listOf(Step.ACCOUNT, Step.AMOUNT, Step.CATEGORY, Step.NOTE)
    else -> listOf(Step.AMOUNT, Step.CATEGORY, Step.ACCOUNT, Step.NOTE)
}

/** A pre-selected usual account still gets its turn; one picked by hand (or loaded for editing) is skipped. */
private fun filled(f: EntryForm, st: Step): Boolean = when (st) {
    Step.CATEGORY -> f.categoryId != null
    Step.ACCOUNT, Step.FROM -> f.accountChosen
    Step.TO -> f.toAccountId != null
    Step.AMOUNT, Step.NOTE, Step.CLOSED -> false
}

/** From a bank message: the first thing still missing (amount, category, account, then note); null when all is there. */
internal fun firstMissing(f: EntryForm, amount: Long?): Step? = listOf(
    Step.AMOUNT to ((amount ?: 0L) <= 0L),
    Step.CATEGORY to (f.categoryId == null),
    Step.ACCOUNT to (f.accountId == null),
    Step.NOTE to f.note.isBlank(),
).firstOrNull { it.second }?.first
