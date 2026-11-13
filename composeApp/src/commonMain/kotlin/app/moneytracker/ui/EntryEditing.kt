package app.moneytracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.Category
import app.moneytracker.domain.Tag
import kotlinx.coroutines.CoroutineScope

/** The shared entry editor for this screen (widgets follow the database on their own). */
@Composable
internal fun rememberEntryEditor(): EntryEditor {
    val repo = LocalRepo.current
    return remember(repo) { repo.entryEditor }
}

/** The entry form for [screen], as both layouts use it. What was typed survives the app closing in the background. */
@Composable
internal fun rememberEntryForm(repo: MoneyRepository, screen: Screen.EditTxn, scope: CoroutineScope): EntryFormModel {
    val main = LocalCurrency.current
    val currencies = LocalCurrencies.current
    fun make() = EntryFormModel(
        repo.entryEditor, AccountGuess(repo.accountStore, repo.categoryStore, repo.learningStore), repo.categoryStore,
        repo.photoStore, repo.captureStore, repo.bookmarkStore,
        EntrySetup.read(screen, repo.settingsStore, main, currencies, repo.today), repo.io, scope,
    )
    return rememberSaveable(repo, saver = EntryFormModel.saver(::make)) { make() }
}

/** Suggestions and notices around the entry form. */
@Composable
internal fun rememberEntryHints(repo: MoneyRepository, scope: CoroutineScope): EntryHints =
    remember(repo) { EntryHints(repo.learningStore, repo.txnStore, repo.settingsStore, repo.today, repo.io, scope) }

/** The accounts, categories and tags the entry form picks from, kept current. */
class EntryChoices(
    val accounts: List<Account>,
    val groups: List<AccountGroup>,
    val categories: List<Category>,
    val catById: Map<String, Category>,
    val tags: List<Tag>,
    val useSubcategories: Boolean,
)

@Composable
internal fun collectEntryChoices(repo: MoneyRepository): EntryChoices {
    val accounts by repo.accountStore.accounts.collectAsState()
    val groups by repo.accountStore.groups.collectAsState()
    val categories by repo.categoryStore.categories.collectAsState()
    val catById by repo.categoryStore.categoryById.collectAsState()
    val tags by repo.tagStore.tags.collectAsState()
    val useSubcategories by repo.settingsStore.useSubcategories.collectAsState(false)
    return EntryChoices(accounts, groups, categories, catById, tags, useSubcategories)
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
