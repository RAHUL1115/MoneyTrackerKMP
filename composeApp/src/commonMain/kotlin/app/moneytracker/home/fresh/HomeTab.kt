package app.moneytracker.home.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.fresh.ActivityTab
import app.moneytracker.activity.fresh.actMonthNames
import app.moneytracker.app.FreshTab
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.Privacy
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.classic.weekday
import app.moneytracker.designsystem.fresh.Bar
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.domain.BudgetLine
import app.moneytracker.entry.fresh.newListenLook
import app.moneytracker.home.HomeState
import app.moneytracker.home.rememberHomeModel
import app.moneytracker.messages.fresh.SmsReviewScreen
import app.moneytracker.messages.fresh.SmsSettingsScreen
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ───────────────────────── Home tab

/** Every Home card keeps its content this far from its edge, so text lines up down the screen. */
private val CARD_INSET = 18.dp

// Home: a quick look at today on top (spent today, yesterday and this month, the budget, Smart add, bank SMS waiting),
// then every entry below it with the List / Calendar / Months / Notes views. The summary slides away as you scroll.
@Composable
internal fun HomeTab(onOpenTab: (FreshTab) -> Unit) {
    val model = rememberHomeModel()
    val state by model.state.collectAsState()
    val today = state.today
    val hour = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val greeting = when (hour) { in 5..11 -> tr("Good morning"); in 12..16 -> tr("Good afternoon"); else -> tr("Good evening") }
    ActivityTab(greeting, overline = "${today.weekday()}, ${today.short()}") { onShowList ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SpendingHero(state, onShowList, onOpenTab)
            SmartAddBar()
            SmsWaiting(state)
        }
    }
}

/**
 * Smart add, one tap from Home: tap the box to type an entry the way you'd say it, or the mic to speak it.
 * Both open the same sheet; the Add screen then opens filled in.
 */
@Composable
private fun SmartAddBar() {
    val nav = LocalNavigator.current
    val voice = app.moneytracker.core.LocalPlatform.current.voice
    var open by remember { mutableStateOf<Boolean?>(null) }   // true: typing, false: speaking
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(F.cardShape).background(F.card)
            .clickable(onClickLabel = tr("Type an entry")) { open = true }.padding(start = CARD_INSET, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Type or say it: “1,200 for new shoes”"), Modifier.weight(1f), color = F.muted, fontSize = 15.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (voice.speechAvailable) {
            Box(
                Modifier.size(44.dp).clip(F.iconShape).background(F.accent).clickable(onClickLabel = tr("Say an entry")) { open = false },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Mic, tr("Say an entry"), tint = F.onAccent, modifier = Modifier.size(22.dp)) }
        }
    }
    open?.let { typing ->
        app.moneytracker.entry.classic.ListenSheet(onDismiss = { open = null }, look = newListenLook(), startTyping = typing) { g ->
            nav.push(Screen.EditTxn(preset = g?.draft))
        }
    }
}

/** Spent today (big), yesterday and this period so far, and the budget left as a thin bar. */
@Composable
private fun SpendingHero(state: HomeState, onShowList: () -> Unit, onOpenTab: (FreshTab) -> Unit) {
    val currency = LocalCurrency.current
    Column(
        Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // While amounts are hidden, a tap on a figure peeks at them instead of opening its list or tab.
        Column(Modifier.fillMaxWidth().figureTap(Privacy.tap(onShowList)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Spent today"), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(currency.format(state.spentToday), color = F.ink, fontSize = 34.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroFigure(tr("Yesterday"), currency.format(state.spentYesterday), Privacy.tap(onShowList), Modifier.weight(1f))
            HeroFigure(tr("{0} so far", tr(actMonthNames[state.month.month - 1])), currency.format(state.monthSpent), Privacy.tap { onOpenTab(FreshTab.INSIGHTS) },
                Modifier.weight(1f))
        }
        // The budget line shows once a budget is set; setting one lives on the Budget screen.
        state.budget?.let { BudgetLeft(it, state.daysLeft) }
    }
}

@Composable
private fun BudgetLeft(budget: BudgetLine, daysLeft: Int) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().figureTap(Privacy.tap { nav.push(Screen.Budget) }), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val left = budget.remaining
        Row {
            Text(if (left >= 0) tr("{0} left in budget", currency.format(left)) else tr("{0} over budget", currency.format(-left)),
                Modifier.weight(1f), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
            Text(if (daysLeft == 1) tr("Last day ›") else tr("{0} days to go ›", daysLeft), color = F.muted, fontSize = 13.sp)
        }
        Bar(budget.ratio, fill = if (budget.ratio > 1f) F.warn else F.accent, track = F.track, height = 6.dp)
    }
}

/** How far a hero figure's tap highlight reaches past its text on every side. */
private val FIGURE_TAP_PAD = 8.dp

/**
 * A hero figure's tap: a rounded highlight [FIGURE_TAP_PAD] larger than the text on every side, without moving
 * anything. The padding sits inside the clickable, and the layout step hands the same amount back, so the figure
 * measures and sits exactly where it would without it.
 */
private fun Modifier.figureTap(onClick: () -> Unit): Modifier = this
    .layout { measurable, constraints ->
        val pad = FIGURE_TAP_PAD.roundToPx()
        val placeable = measurable.measure(constraints.offset(2 * pad, 2 * pad))
        layout(placeable.width - 2 * pad, placeable.height - 2 * pad) { placeable.place(-pad, -pad) }
    }
    .clip(F.controlShape).clickable(onClick = onClick).padding(FIGURE_TAP_PAD)

@Composable
private fun HeroFigure(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.figureTap(onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

/** Bank SMS waiting to be added, shown only when there are some. */
@Composable
private fun SmsWaiting(state: HomeState) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val pending = state.pending
    if (pending.isEmpty()) return
    val sum = state.pendingSum
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { nav.push(Screen.Inbox) }.padding(horizontal = CARD_INSET, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
            Icon(Glyph.Message, null, tint = F.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (pending.size == 1) tr("1 payment found in bank SMS") else tr("{0} payments found in bank SMS", pending.size),
                color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (sum > 0) currency.format(sum) else null, tr("tap to add them")).joinToString(" · "),
                color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
        }
        Icon(Glyph.ChevronRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable internal fun FreshInbox() = SmsReviewScreen()
@Composable internal fun FreshMessageSettings() = SmsSettingsScreen()
