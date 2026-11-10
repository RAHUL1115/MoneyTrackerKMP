package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem

/** The shared entry editor for this screen (widgets follow the database on their own). */
@Composable
internal fun rememberEntryEditor(): EntryEditor {
    val repo = LocalRepo.current
    return remember(repo) { EntryEditor(repo) }
}

/** How both phone layouts word a problem that stops a save. */
internal fun EntryProblem.message(): String = when (this) {
    EntryProblem.NO_AMOUNT -> tr("Enter an amount")
    EntryProblem.BAD_FEE -> tr("Fee is not a valid amount")
    EntryProblem.NO_ACCOUNT -> tr("Choose an account")
    EntryProblem.NO_CATEGORY -> tr("Pick a category")
    EntryProblem.NO_DESTINATION -> tr("Choose the destination account")
    EntryProblem.SAME_ACCOUNT -> tr("Pick two different accounts")
}
