package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The shared entry editor for this screen; every change refreshes the home-screen widgets. */
@Composable
internal fun rememberEntryEditor(): EntryEditor {
    val repo = LocalRepo.current
    val platform = LocalPlatform.current
    return remember(repo, platform) { EntryEditor(repo) { MainScope().launch { platform.refreshWidgets() } } }
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
