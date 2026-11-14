package app.moneytracker.entry.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup

// The classic editor's account panel (EditTxnScreen.kt): a grid of all accounts, or groups on the left and their
// accounts on the right ([grouped], remembered in settings).

private val AccountPanelHeight = 260.dp

@Composable
internal fun AccountPanel(
    title: String,
    accounts: List<Account>,
    groups: List<AccountGroup>,
    grouped: Boolean,
    onGrouped: (Boolean) -> Unit,
    selectedIds: Set<String>,
    onPick: (Account) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.sheet()) {
        PanelHeader(title, onEdit, onClose, extra = {
            IconButton(onClick = { onGrouped(!grouped) }) {
                Icon(if (grouped) Icons.Outlined.GridView else Icons.Outlined.ViewList, if (grouped) tr("Show all accounts") else tr("Show by group"), tint = InkSoft)
            }
        })
        // The grid leaves hidden accounts out; the group view lists them greyed, so they stay reachable.
        if (grouped) GroupedAccounts(accounts, groups, onPick)
        else {
            val visible = accounts.filter { !it.hidden || it.id in selectedIds }
            if (visible.isEmpty() && accounts.isNotEmpty()) AllAccountsHidden { onGrouped(true) }
            else GridPanel(visible, { it.name }, onPick)
        }
    }
}

@Composable
private fun AllAccountsHidden(onShowGroups: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(AccountPanelHeight).background(PageBackground).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr("All accounts are hidden"), color = Ink, fontWeight = FontWeight.SemiBold)
        Text(tr("Pick one from the group view, or show accounts again in Settings › Accounts."), Modifier.padding(top = 6.dp),
            color = SubtleText, fontSize = 13.sp, textAlign = TextAlign.Center)
        TextButton(onClick = onShowGroups, Modifier.padding(top = 8.dp)) { Text(tr("Show by group"), color = Accent) }
    }
}

@Composable
private fun GroupedAccounts(accounts: List<Account>, groups: List<AccountGroup>, onPick: (Account) -> Unit) {
    val used = groups.filter { g -> accounts.any { it.groupId == g.id } }
    var selected by remember { mutableStateOf(used.firstOrNull()?.id) }
    Row(Modifier.fillMaxWidth().height(AccountPanelHeight)) {
        Column(Modifier.weight(1f).background(PageBackground).verticalScroll(rememberScrollState())) {
            used.forEach { g ->
                val isSel = g.id == selected
                Row(
                    Modifier.fillMaxWidth().height(56.dp).background(if (isSel) SurfaceColor else PageBackground)
                        .clickable { selected = g.id }.padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.name, Modifier.weight(1f), color = if (isSel) Accent else Ink, fontWeight = if (isSel) FontWeight.SemiBold else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accounts.count { it.groupId == g.id }.toString(), color = SubtleText, fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            accounts.filter { it.groupId == selected }.forEach { a ->
                Box(Modifier.fillMaxWidth().height(56.dp).clickable { onPick(a) }.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(a.name, color = if (a.hidden) SubtleText else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
