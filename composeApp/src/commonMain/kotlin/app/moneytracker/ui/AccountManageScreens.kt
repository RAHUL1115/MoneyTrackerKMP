package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DangerColor
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.GreyHeader
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.Picker
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountType
import app.moneytracker.ui.accounts.ManageAccountsState
import app.moneytracker.ui.accounts.displayName
import app.moneytracker.ui.accounts.rememberManageAccountsModel

// Classic layout's Accounts list (order, hide, restore) and Account groups screen; the new layout opens these too.

@Composable
fun AccountListScreen() {
    val model = rememberManageAccountsModel()
    val state by model.state.collectAsState()
    AccountListContent(state, onHide = model::setHidden, onMove = model::moveAccount, onRestore = model::restoreAccount)
}

@Composable
private fun AccountListContent(
    state: ManageAccountsState, onHide: (id: String, hidden: Boolean) -> Unit, onMove: (id: String, delta: Int) -> Unit, onRestore: (id: String) -> Unit,
) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Accounts"), nav::pop) {
                TextButton(onClick = { nav.push(Screen.AccountGroups) }) { Text(tr("Groups"), color = Accent) }
            }
            HorizontalDivider(color = DividerColor)
            Text(tr("👁 hides an account from lists and pickers (history and balance stay). Arrows change the order."),
                Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
            LazyColumn(Modifier.fillMaxSize()) {
                state.groups.forEach { g ->
                    val inGroup = state.accounts.filter { it.groupId == g.id }
                    if (inGroup.isEmpty()) return@forEach
                    item(key = "g${g.id}") { GreyHeader(g.name) }
                    items(inGroup, key = { it.id }) { a ->
                        val i = inGroup.indexOf(a)
                        OrderedAccountRow(a, canMoveUp = i > 0, canMoveDown = i < inGroup.lastIndex, { onHide(a.id, !a.hidden) }) { delta -> onMove(a.id, delta) }
                    }
                }
                if (state.deletedAccounts.isNotEmpty()) {
                    item(key = "deleted") { GreyHeader(tr("Deleted accounts")) }
                    items(state.deletedAccounts, key = { "d" + it.id }) { a -> DeletedAccountRow(a.name) { onRestore(a.id) } }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { nav.push(Screen.EditAccount()) }
    }
}

@Composable
private fun OrderedAccountRow(a: Account, canMoveUp: Boolean, canMoveDown: Boolean, onToggleHidden: () -> Unit, onMove: (delta: Int) -> Unit) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable { nav.push(Screen.EditAccount(a.id)) }.padding(start = 24.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.name, Modifier.weight(1f).padding(vertical = 14.dp), color = if (a.hidden) SubtleText else Ink)
        IconButton(onClick = onToggleHidden) {
            Icon(if (a.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (a.hidden) tr("Show {0}", a.name) else tr("Hide {0}", a.name),
                tint = if (a.hidden) SubtleText else Accent)
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move {0} up", a.name)) }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move {0} down", a.name)) }
    }
    HorizontalDivider(color = DividerColor)
}

@Composable
private fun DeletedAccountRow(name: String, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 28.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), color = SubtleText)
        TextButton(onClick = onRestore) { Text(tr("Restore"), color = Accent) }
    }
    HorizontalDivider(color = DividerColor)
}

/** Account groups: rename, type (default / credit card / debit card), order, add, delete empty ones. */
@Composable
fun AccountGroupsScreen() {
    val model = rememberManageAccountsModel()
    val state by model.state.collectAsState()
    var editing by remember { mutableStateOf<AccountGroup?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    AccountGroupsContent(state, onMove = model::moveGroup, onRestore = model::restoreGroup, onAdd = { editing = model.newGroup() }, onEdit = { editing = it })
    editing?.let { g ->
        GroupDialog(g, isNew = state.groups.none { it.id == g.id }, onDismiss = { editing = null }, onSave = { model.saveGroup(it) }) {
            model.deleteGroup(g.id) { notice = tr("Move or delete the accounts in “{0}” first.", g.name) }
        }
    }
    notice?.let { m -> AlertDialog(onDismissRequest = { notice = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { notice = null }) { Text(tr("OK")) } }) }
}

@Composable
private fun AccountGroupsContent(
    state: ManageAccountsState, onMove: (index: Int, delta: Int) -> Unit, onRestore: (id: String) -> Unit, onAdd: () -> Unit, onEdit: (AccountGroup) -> Unit,
) {
    val nav = LocalNavigator.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Account groups"), nav::pop)
            HorizontalDivider(color = DividerColor)
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.groups, key = { it.id }) { g ->
                    val i = state.groups.indexOf(g)
                    GroupRow(g, state.accounts.count { it.groupId == g.id }, canMoveUp = i > 0, canMoveDown = i < state.groups.lastIndex, { delta -> onMove(i, delta) }) { onEdit(g) }
                }
                if (state.deletedGroups.isNotEmpty()) {
                    item(key = "deletedGroups") { GreyHeader(tr("Deleted groups")) }
                    items(state.deletedGroups, key = { "d" + it.id }) { g -> DeletedGroupRow(g.name) { onRestore(g.id) } }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd), onAdd)
    }
}

@Composable
private fun GroupRow(g: AccountGroup, accountCount: Int, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (delta: Int) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(start = 24.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(g.name, color = Ink)
            Text(tr("{0} · {1} accounts", g.type.displayName, accountCount), fontSize = 12.sp, color = SubtleText)
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move {0} up", g.name)) }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move {0} down", g.name)) }
    }
    HorizontalDivider(color = DividerColor)
}

@Composable
private fun DeletedGroupRow(name: String, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f).padding(vertical = 14.dp), color = SubtleText)
        TextButton(onClick = onRestore) { Text(tr("Restore"), color = Accent) }
    }
    HorizontalDivider(color = DividerColor)
}

@Composable
private fun GroupDialog(g: AccountGroup, isNew: Boolean, onDismiss: () -> Unit, onSave: (AccountGroup) -> Unit, onDelete: () -> Unit) {
    var name by remember(g.id) { mutableStateOf(g.name) }
    var type by remember(g.id) { mutableStateOf(g.type) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("New group") else tr("Edit group")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
                Picker(tr("Type"), AccountType.entries, type, { it.displayName }, { type = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(g.copy(name = name, type = type)); onDismiss() }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { onDelete(); onDismiss() }) { Text(tr("Delete"), color = DangerColor) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}
