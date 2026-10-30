package app.moneytracker.ui.fresh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import app.moneytracker.domain.totals
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.Template
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Recurrence
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.ui.BiometricStatus
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.byMainCategory
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.label
import app.moneytracker.ui.mainCategoryName
import app.moneytracker.ui.short
import app.moneytracker.ui.toReportRow
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

// ───────────────────────── Home tab

/** Every Home card keeps its content this far from its edge, so text lines up down the screen. */
private val CARD_INSET = 18.dp

// Home is a quick look for the one or two times a day the app is opened: what went out today, yesterday and this month,
// bank SMS waiting to be added, and today's and yesterday's entries.
@Composable
internal fun HomeTab(onOpenTab: (FreshTab) -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val today = repo.today()
    val hour = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val greeting = when (hour) { in 5..11 -> tr("Good morning"); in 12..16 -> tr("Good afternoon"); else -> tr("Good evening") }
    Column(Modifier.fillMaxSize().background(F.page).verticalScroll(rememberScrollState())) {
        TabHeader(greeting, overline = "${today.weekday()}, ${today.short()}") {
            IconTile(Icons.Outlined.Settings, tr("Settings"), { nav.push(Screen.Configuration) })
        }
        Column(Modifier.padding(horizontal = F.gutter).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SpendingHero(onOpenTab)
            SmsWaiting()
            RecentDays()
            Text(tr("See everything in Activity ›"),
                Modifier.align(Alignment.CenterHorizontally).height(44.dp).clip(F.iconShape).background(F.card)
                    .clickable { onOpenTab(FreshTab.ACTIVITY) }.padding(horizontal = 20.dp).wrapContentHeight(),
                color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun entriesText(n: Int) = when (n) { 0 -> tr("Nothing"); 1 -> tr("1 entry"); else -> tr("{0} entries", n) }

/** Spent today (big), yesterday and this period so far, and the budget left as a thin bar. */
@Composable
private fun SpendingHero(onOpenTab: (FreshTab) -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val today = repo.today()
    val yesterday = today.minus(DatePeriod(days = 1))
    val ym = ps.labelFor(today)
    val range = ps.month(ym)
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val recentRows by remember(today) { repo.reportRows(DateRange(yesterday, today)) }.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val expenseRows = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == Flow.EXPENSE }
    val spentByMain = byMainCategory(expenseRows, catById, Flow.EXPENSE)
    val spentBySub = expenseRows.filter { r -> r.categoryId?.let { catById[it]?.parentId } != null }
        .groupBy { it.categoryId!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val budget = budgetLines(effectiveBudgets(entries, ym), spentByMain, spentBySub) { id -> catById[id]?.parentId != null }
        .firstOrNull { it.categoryId == TOTAL_BUDGET }
    val daysLeft = (today.daysUntil(range.endInclusive) + 1).coerceAtLeast(1)
    val daysGone = (range.start.daysUntil(today) + 1).coerceAtLeast(1)

    // Entry counts skip the second half of a transfer, like the lists do.
    fun dayRows(d: kotlinx.datetime.LocalDate) = recentRows.filter { it.date == d && it.kind != TxnKind.TRANSFER_IN }
    val todayRows = dayRows(today)
    val yesterdayRows = dayRows(yesterday)
    val monthSpent = totals(rows).expense

    val soft = F.muted
    Column(
        Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).padding(horizontal = CARD_INSET, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().clickable { onOpenTab(FreshTab.ACTIVITY) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Spent today"), color = soft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(currency.format(totals(todayRows).expense), color = F.ink, fontSize = 34.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroFigure(tr("Yesterday"), currency.format(totals(yesterdayRows).expense), Modifier.weight(1f)) { onOpenTab(FreshTab.ACTIVITY) }
            HeroFigure(tr("{0} so far", tr(actMonthNames[ym.month - 1])), currency.format(monthSpent), Modifier.weight(1f)) { onOpenTab(FreshTab.INSIGHTS) }
        }
        // The budget line shows once a budget is set; setting one lives on the Budget screen.
        if (budget != null) Column(Modifier.fillMaxWidth().clickable { nav.push(Screen.Budget) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            run {
                val left = budget.remaining
                Row {
                    Text(if (left >= 0) tr("{0} left in budget", currency.format(left)) else tr("{0} over budget", currency.format(-left)),
                        Modifier.weight(1f), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                    Text(if (daysLeft == 1) tr("Last day ›") else tr("{0} days to go ›", daysLeft), color = soft, fontSize = 13.sp)
                }
                Bar(budget.ratio, fill = if (budget.ratio > 1f) F.warn else F.accent, track = F.fill, height = 6.dp)
            }
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

/** Bank SMS waiting to be added, shown only when there are some. */
@Composable
private fun SmsWaiting() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    if (pending.isEmpty()) return
    val sum = pending.sumOf { it.amount ?: 0L }
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { nav.push(Screen.Inbox) }.padding(horizontal = CARD_INSET, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Sms, null, tint = F.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (pending.size == 1) tr("1 payment found in bank SMS") else tr("{0} payments found in bank SMS", pending.size),
                color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (sum > 0) currency.format(sum) else null, tr("tap to add them")).joinToString(" · "),
                color = F.muted, fontSize = 12.sp, style = F.money, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

/** Today's and yesterday's entries, each day with its total. */
@Composable
private fun RecentDays() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val today = repo.today()
    val yesterday = today.minus(DatePeriod(days = 1))
    val range = remember(today) { DateRange(yesterday, today) }
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val data = rememberActEntryData()
    val shown = txns.filter { it.kind != TxnKind.TRANSFER_IN }
    listOf(today to tr("Today"), yesterday to tr("Yesterday")).forEach { (day, title) ->
        val dayTxns = shown.filter { it.date == day }.sortedWith(compareByDescending<Txn> { it.timeOfDay ?: -1 }.thenByDescending { it.createdAt })
        if (dayTxns.isEmpty() && day != today) return@forEach
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val t = totals(rows.filter { it.date == day })
                if (dayTxns.isEmpty()) Text(tr("Nothing yet"), color = F.muted, fontSize = 13.sp)
                else if (t.expense > 0 || t.income > 0) Text(
                    listOfNotNull(
                        if (t.income > 0) "+" + currency.format(t.income) else null,
                        if (t.expense > 0) "−" + currency.format(t.expense) else null,
                    ).joinToString(" · "),
                    color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, style = F.money,
                )
            }
            // An empty day is just its heading with "Nothing yet"; no empty card.
            if (dayTxns.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(horizontal = CARD_INSET, vertical = 4.dp)) {
                dayTxns.forEachIndexed { i, txn ->
                    if (i > 0) Divider()
                    ActEntryRow(txn, data) { nav.push(Screen.EditTxn(txnId = txn.id)) }
                }
            }
        }
    }
}

// ───────────────────────── lock

private const val PIN_LENGTH = 4

@Composable
fun FreshLock(hash: String, onUnlocked: () -> Unit) {
    val repo = LocalRepo.current
    val platform = LocalPlatform.current
    val biometricOn by remember(repo) { repo.flag(Keys.BIOMETRIC) }.collectAsState(false)
    val status by platform.biometricStatus.collectAsState()
    val canUseBiometric = biometricOn && status == BiometricStatus.AVAILABLE
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    fun promptBiometric() = platform.authenticate(tr("Unlock MoneyTracker"), onUnlocked) { msg -> error = msg }
    // Offer the biometric prompt straight away; the PIN pad stays underneath as the fallback.
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) promptBiometric() }
    fun press(k: String) {
        when (k) {
            "bio" -> { error = null; promptBiometric() }
            "del" -> pin = pin.dropLast(1)
            else -> if (pin.length < PIN_LENGTH) {
                pin += k
                if (pin.length == PIN_LENGTH) {
                    if (platform.hashPin(pin) == hash) onUnlocked()
                    else { attempts++; error = tr("Wrong passcode ({0}). Try again", attempts); pin = "" }
                }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(F.page).statusBarsPadding().padding(horizontal = 32.dp).padding(top = 72.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(F.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lock, null, tint = F.onAccent, modifier = Modifier.size(30.dp))
        }
        Text(tr("Unlock MoneyTracker"), color = F.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            error ?: if (canUseBiometric) tr("Enter passcode or use your fingerprint / face") else tr("Enter passcode"),
            color = if (error != null) F.danger else F.muted, fontSize = 14.sp,
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(PIN_LENGTH) { i ->
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) F.accent else Color.Transparent).border(2.dp, F.accent, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (canUseBiometric) "bio" else "", "0", "del").chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    val bg = when (k) { "bio" -> F.accentTint; "del", "" -> Color.Transparent; else -> F.card }
                    Box(
                        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                            .then(if (k.isEmpty()) Modifier else Modifier.clickable { press(k) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "bio" -> Icon(Icons.Outlined.Fingerprint, tr("Unlock with biometrics"), tint = F.accent, modifier = Modifier.size(30.dp))
                            "del" -> Icon(Icons.AutoMirrored.Filled.Backspace, tr("Delete digit"), tint = F.ink)
                            else -> Text(k, color = F.ink, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun FreshInbox() = SmsReviewScreen()
@Composable internal fun FreshMessageSettings() = SmsSettingsScreen()
