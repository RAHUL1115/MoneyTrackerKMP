package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.AccountLine
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.Screen
import app.moneytracker.ui.accounts.ManageAccountsState
import app.moneytracker.ui.accounts.displayName
import app.moneytracker.ui.accounts.rememberManageAccountsModel
import app.moneytracker.ui.tr

/** Order groups, hide whole groups, restore deleted accounts; links to the detailed lists. Each change offers Undo. */
@Composable
internal fun ManageAccountsSheet(lines: List<AccountLine>, onDismiss: () -> Unit) {
    val toaster = LocalToaster.current
    val model = rememberManageAccountsModel()
    val state by model.state.collectAsState()
    ManageSheetContent(
        state, noAccounts = lines.isEmpty() && state.accounts.isEmpty(), onDismiss,
        onHideGroup = { g, hide ->
            val before = model.setGroupHidden(g.id, hide)
            toaster.show(if (hide) tr("{0} hidden", g.name) else tr("{0} shown", g.name)) { model.restoreHidden(before) }
        },
        onMoveGroup = model::moveGroup,
        onRestore = { a ->
            model.restoreAccount(a.id)
            toaster.show(tr("{0} restored to its group", a.name)) { model.deleteAccount(a.id) }
        },
    )
}

@Composable
private fun ManageSheetContent(
    state: ManageAccountsState, noAccounts: Boolean, onDismiss: () -> Unit, onHideGroup: (AccountGroup, hide: Boolean) -> Unit,
    onMoveGroup: (index: Int, delta: Int) -> Unit, onRestore: (Account) -> Unit,
) {
    val nav = LocalNavigator.current
    Sheet(tr("Manage accounts"), onDismiss, bottom = {
        PrimaryButton(tr("Add account"), { onDismiss(); nav.push(Screen.EditAccount()) })
    }) {
        Text(tr("Arrows change the order of groups. The eye hides a group's accounts from lists and pickers (history and balance stay)."),
            color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        state.groups.forEachIndexed { i, g ->
            GroupOrderRow(g, state.accounts.filter { it.groupId == g.id }, canMoveUp = i > 0, canMoveDown = i < state.groups.lastIndex,
                onHide = { hide -> onHideGroup(g, hide) }) { delta -> onMoveGroup(i, delta) }
        }
        Gap(8.dp)
        Divider()
        // Rounded highlights, like the sheet's other options.
        FRow(tr("Accounts"), tr("Order, hide or edit each account"), onClick = { onDismiss(); nav.push(Screen.AccountList) }, modifier = Modifier.clip(F.optionShape))
        FRow(tr("Account groups"), tr("Rename, change type, add or delete groups"), onClick = { onDismiss(); nav.push(Screen.AccountGroups) },
            modifier = Modifier.clip(F.optionShape))
        FRow(tr("Net worth trend"), tr("Assets and liabilities over the last 12 months"), onClick = { onDismiss(); nav.push(Screen.AccountsTrend) },
            modifier = Modifier.clip(F.optionShape))
        if (state.deletedAccounts.isNotEmpty()) DeletedAccounts(state.deletedAccounts, onRestore)
        if (noAccounts) Text(tr("No accounts yet."), color = F.muted, fontSize = 13.sp)
    }
}

@Composable
private fun GroupOrderRow(g: AccountGroup, inGroup: List<Account>, canMoveUp: Boolean, canMoveDown: Boolean, onHide: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    val hidden = inGroup.isNotEmpty() && inGroup.all { it.hidden }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(g.name, color = if (hidden) F.muted else F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("{0} · {1} accounts", g.type.displayName, inGroup.size) + if (hidden) " · " + tr("hidden") else "", color = F.muted, fontSize = 12.sp)
        }
        if (inGroup.isNotEmpty()) SmallTile(if (hidden) Glyph.EyeOff else Glyph.Eye, if (hidden) tr("Show {0}", g.name) else tr("Hide {0}", g.name)) {
            onHide(!hidden)
        }
        SmallTile(Glyph.ChevronUp, tr("Move {0} up", g.name), enabled = canMoveUp) { onMove(-1) }
        SmallTile(Glyph.ChevronDown, tr("Move {0} down", g.name), enabled = canMoveDown) { onMove(1) }
    }
}

@Composable
private fun DeletedAccounts(deleted: List<Account>, onRestore: (Account) -> Unit) {
    var showDeleted by remember { mutableStateOf(false) }
    Divider()
    FRow(tr("Deleted accounts ({0})", deleted.size), null, chevron = false, onClick = { showDeleted = !showDeleted }, trailing = {
        Icon(if (showDeleted) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = F.muted)
    }, modifier = Modifier.clip(F.optionShape).expandState(showDeleted))
    if (showDeleted) deleted.forEach { a ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, Modifier.weight(1f), color = F.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("Restore"), Modifier.clip(F.innerShape).clickable(role = Role.Button) { onRestore(a) }.padding(horizontal = 10.dp, vertical = 8.dp),
                color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SmallTile(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(36.dp).clip(F.innerShape).background(F.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) F.ink else F.border, modifier = Modifier.size(18.dp)) }
}
