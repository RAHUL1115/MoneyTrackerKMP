package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.Screen
import app.moneytracker.ui.tr

/** Main tabs of the new layout. The + in the middle of the bar is not a tab: it opens Add. */
enum class FreshTab(val title: String) { HOME("Home"), ACTIVITY("Activity"), INSIGHTS("Insights"), MONEY("Money") }

/** Home of the new layout: the selected tab above the bottom bar. */
@Composable
fun FreshHome() {
    var tab by rememberSaveable { mutableStateOf(FreshTab.HOME) }
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize().background(F.page)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            when (tab) {
                FreshTab.HOME -> HomeTab(onOpenTab = { tab = it })
                FreshTab.ACTIVITY -> ActivityTab()
                FreshTab.INSIGHTS -> InsightsTab()
                FreshTab.MONEY -> MoneyTab()
            }
        }
        BottomBar(tab, onSelect = { tab = it }, onAdd = { nav.push(Screen.EditTxn()) })
    }
}

@Composable
private fun BottomBar(selected: FreshTab, onSelect: (FreshTab) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(F.card)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(F.border))
        Row(Modifier.fillMaxWidth().height(68.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            NavItem(FreshTab.HOME, Icons.Outlined.Home, selected, onSelect)
            NavItem(FreshTab.ACTIVITY, Icons.AutoMirrored.Outlined.ListAlt, selected, onSelect)
            Box(
                Modifier.offset(y = (-18).dp).size(58.dp).shadow(8.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)).background(F.accent).clickable(onClickLabel = tr("Add entry"), onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, tr("Add entry"), tint = F.onAccent, modifier = Modifier.size(28.dp)) }
            NavItem(FreshTab.INSIGHTS, Icons.Outlined.PieChart, selected, onSelect)
            NavItem(FreshTab.MONEY, Icons.Outlined.AccountBalanceWallet, selected, onSelect)
        }
    }
}

@Composable
private fun NavItem(tab: FreshTab, icon: ImageVector, selected: FreshTab, onSelect: (FreshTab) -> Unit) {
    val on = tab == selected
    val color = if (on) F.accent else F.muted
    Column(
        Modifier.size(width = 64.dp, height = 52.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(tab) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(tr(tab.title), color = color, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
    }
}

/**
 * The new layout's version of a pushed screen, or null to show the classic one
 * (help, licences and other plain screens are shared by both layouts).
 */
fun freshScreen(screen: Screen): (@Composable () -> Unit)? = when (screen) {
    is Screen.EditTxn -> { { FreshEditTxn(screen) } }
    Screen.Inbox -> { { FreshInbox() } }
    Screen.MessageSettings -> { { FreshMessageSettings() } }
    Screen.Search -> { { FreshSearch() } }
    is Screen.EditNote -> { { FreshEditNote(screen.noteId) } }
    Screen.Budget -> { { FreshBudget() } }
    is Screen.AccountDetail -> { { FreshAccountDetail(screen.accountId) } }
    is Screen.EditAccount -> { { FreshEditAccount(screen.accountId) } }
    Screen.Configuration -> { { FreshSettings() } }
    is Screen.Categories -> { { FreshCategories(screen.kind) } }
    Screen.Recurring -> { { FreshRepeats() } }
    Screen.Backup -> { { FreshBackup() } }
    else -> null
}

