package app.moneytracker.ui.fresh

import androidx.compose.runtime.Composable
import app.moneytracker.core.tr
import app.moneytracker.data.EntryEditor
import app.moneytracker.ui.EntrySaved
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.message

// Saving from the add screen (AddScreen.kt): the button's wording and what happens after.

/** Save, then close (or clear the form for the next entry when [continueAfter]); a refused save says why in a toast. */
@Composable
internal fun rememberSave(c: AddContext): (continueAfter: Boolean) -> Unit {
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    return { continueAfter ->
        val label = savedToast(c)
        fun after(saved: EntrySaved) = when {
            !saved.isNew -> { nav.pop(); toaster.show(tr("Changes saved")) }
            continueAfter -> {
                c.model.startNext(usualAccount = true); c.ui.feeTarget = false
                toaster.show(tr("{0}. Add the next one", label), saved.undo)
            }
            else -> { nav.pop(); toaster.show(label, saved.undo) }
        }
        c.model.save(::after)?.let { toaster.show(it.message()) }
    }
}

/** "Moved ₹200", or "Saved ₹200 · Food". */
private fun savedToast(c: AddContext): String {
    val amount = c.model.fmt.format(c.model.amountValue() ?: 0L)
    return if (c.model.form.isTransfer) tr("Moved {0}", amount)
    else listOfNotNull(tr("Saved {0}", amount), c.categoryLabel()).joinToString(" · ")
}

/** The Save button says what is still missing, or what will be saved. */
internal fun saveLabel(c: AddContext): String {
    val f = c.model.form
    val total = c.model.amountValue()
    val catLabel = c.categoryLabel()
    val fmt = c.model.fmt
    return when {
        total == null || total <= 0 -> tr("Enter an amount")
        f.isTransfer && (f.accountId == null || f.toAccountId == null) -> tr("Pick two accounts")
        f.isTransfer && f.toAccountId == f.accountId -> tr("Pick two different accounts")
        f.accountId == null -> tr("Choose an account")
        !f.isTransfer && !f.isAdjustment && f.categoryId == null -> tr("Pick a category")
        f.isTransfer -> tr("Move {0}", fmt.format(total))
        catLabel != null -> tr("Save {0} · {1}", fmt.format(total), c.choices.catById[f.categoryId]?.name ?: catLabel)
        else -> tr("Save {0}", fmt.format(total))
    }
}

/** Everything a save needs is there (the fee is checked on Save). */
internal fun AddContext.isReady(): Boolean {
    val f = model.form
    return EntryEditor.check(f.type, model.amountValue(), 0L, f.accountId, f.toAccountId, f.categoryId, f.isAdjustment) == null
}
