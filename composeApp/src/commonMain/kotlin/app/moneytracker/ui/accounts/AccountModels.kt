package app.moneytracker.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.moneytracker.app.LocalRepo
import app.moneytracker.data.EntryEditor

// Wiring for the account screens of both layouts: each holder is built once per screen from the stores it needs,
// on the screen's coroutine scope.

@Composable
fun rememberAccountsModel(): AccountsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo) {
        AccountsModel(repo.views, repo.accountStore, repo.settingsStore, repo.currencyStore, repo.txnStore, repo.today, repo.io, scope)
    }
}

@Composable
fun rememberManageAccountsModel(): ManageAccountsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo) { ManageAccountsModel(repo.accountStore, repo.io, scope) }
}

@Composable
fun rememberAccountDetailModel(accountId: String): AccountDetailModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, accountId) {
        AccountDetailModel(accountId, repo.views, repo.accountStore, repo.categoryStore, repo.settingsStore, repo.txnStore, repo.tagStore, repo.io, scope)
    }
}

@Composable
fun rememberAccountActionsModel(accountId: String): AccountActionsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, accountId) { AccountActionsModel(accountId, repo.txnStore, repo.entryEditor, repo.io, scope) }
}

@Composable
fun rememberAccountEditModel(accountId: String?): AccountEditModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, accountId) { AccountEditModel(accountId, repo.accountStore, repo.currencyStore, repo.io, scope) }
}
