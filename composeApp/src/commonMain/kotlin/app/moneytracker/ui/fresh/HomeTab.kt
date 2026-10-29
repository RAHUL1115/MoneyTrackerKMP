package app.moneytracker.ui.fresh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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

@Composable
internal fun HomeTab(onOpenTab: (FreshTab) -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val pending by repo.pendingCaptures.collectAsState(emptyList())
    val today = repo.today()
    val hour = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour }
    val greeting = when (hour) { in 5..11 -> tr("Good morning"); in 12..16 -> tr("Good afternoon"); else -> tr("Good evening") }
    Column(Modifier.fillMaxSize().background(F.page).verticalScroll(rememberScrollState())) {
        TabHeader(greeting, overline = "${today.weekday()}, ${today.short()}") {
            IconTile(Icons.Outlined.Sms, if (pending.isEmpty()) tr("Bank messages") else tr("{0} bank messages to review", pending.size),
                { nav.push(Screen.Inbox) }, badge = pending.size)
            IconTile(Icons.Outlined.Settings, tr("Settings"), { nav.push(Screen.Configuration) })
        }
        Column(Modifier.padding(horizontal = F.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BudgetHero()
            QuickAdd()
            UpcomingRepeats()
            RecentCard(onSeeAll = { onOpenTab(FreshTab.ACTIVITY) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** This period's total budget: what's left, spent of total, per day for the days still to go. */
@Composable
private fun BudgetHero() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val today = repo.today()
    val ym = ps.labelFor(today)
    val range = ps.month(ym)
    val rows by remember(range) { repo.reportRows(range) }.collectAsState(emptyList())
    val entries by remember(ym) { repo.budgetEntries(ym) }.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val expenseRows = rows.filter { it.kind == TxnKind.EXPENSE && classify(it) == Flow.EXPENSE }
    val spentByMain = byMainCategory(expenseRows, catById, Flow.EXPENSE)
    val spentBySub = expenseRows.filter { r -> r.categoryId?.let { catById[it]?.parentId } != null }
        .groupBy { it.categoryId!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val total = budgetLines(effectiveBudgets(entries, ym), spentByMain, spentBySub) { id -> catById[id]?.parentId != null }
        .firstOrNull { it.categoryId == TOTAL_BUDGET }
    val daysLeft = (today.daysUntil(range.endInclusive) + 1).coerceAtLeast(1)

    if (total == null) {
        val spent = rows.filter { classify(it) == Flow.EXPENSE }.sumOf { it.amount }
        FCard(onClick = { nav.push(Screen.Budget) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(F.iconShape).background(F.accentTint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Savings, null, tint = F.accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Set a monthly budget"), color = F.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(tr("See what's left to spend each day. You've spent {0} in {1} so far.", currency.format(spent), ym.label()),
                        color = F.muted, fontSize = 13.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = F.muted)
            }
        }
        return
    }
    val left = total.remaining
    val soft = F.onAccent.copy(alpha = 0.78f)
    Column(
        Modifier.fillMaxWidth().clip(F.heroShape).background(F.accent).clickable { nav.push(Screen.Budget) }.padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (left >= 0) tr("Left in {0} budget", ym.label()) else tr("Over {0} budget", ym.label()),
                Modifier.weight(1f), color = soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (daysLeft == 1) tr("Last day") else tr("{0} days to go", daysLeft),
                Modifier.clip(RoundedCornerShape(999.dp)).background(F.onAccent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                color = F.onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(currency.format(if (left >= 0) left else -left), color = F.onAccent, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, style = F.money, maxLines = 1)
        Bar(total.ratio, fill = if (total.ratio > 1f) F.warn else F.onAccent, track = F.onAccent.copy(alpha = 0.18f))
        Row {
            Text(tr("Spent {0} of {1}", currency.format(total.spent), currency.format(total.budget)), Modifier.weight(1f), color = soft, fontSize = 13.sp, style = F.money)
            if (left > 0) Text(tr("≈ {0} / day", currency.format(left / daysLeft)), color = F.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = F.money)
        }
    }
}

/** Bookmarks as one-tap chips: an entry for today, with Undo. Bookmarks without an amount open the editor. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun QuickAdd() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val toaster = LocalToaster.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val templates by repo.templates.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())

    fun draftOf(t: Template) = TxnDraft(t.type, repo.today(), t.accountId.orEmpty(), t.toAccountId, t.categoryId, t.amount, note = t.note, memo = t.memo)
    fun tap(t: Template) {
        val d = draftOf(t)
        val complete = d.amount > 0 && d.accountId in accountName && (d.type != DraftType.TRANSFER || d.toAccountId in accountName)
        if (!complete) { nav.push(Screen.EditTxn(preset = d)); return }
        scope.launch {
            val id = withContext(repo.io) { repo.create(d).also { repo.learnFromDraft(d) } }
            platform.refreshWidgets()
            toaster.show(tr("{0} {1} added to today", t.name, currency.format(t.amount))) {
                scope.launch { withContext(repo.io) { repo.txn(id)?.let { repo.delete(it) } }; platform.refreshWidgets() }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Quick add"), Modifier.weight(1f), color = F.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(tr("Manage"), Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.push(Screen.Templates) }.padding(6.dp),
                color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (templates.isEmpty()) {
            FCard {
                Text(tr("Save entries you add often as bookmarks: open an entry and tap ☆. They show up here and one tap logs them for today."),
                    color = F.muted, fontSize = 13.sp)
            }
            return@Column
        }
        Text(tr("Your bookmarks · one tap logs it, long-press to manage"), color = F.muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            templates.forEach { t ->
                val markName = if (t.type == DraftType.TRANSFER) tr("Transfer") else t.categoryId?.let { mainCategoryName(it, catById) } ?: t.name
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(999.dp)).background(F.card).border(1.dp, F.border, RoundedCornerShape(999.dp))
                        .combinedClickable(onClickLabel = tr("Add {0}", t.name), onLongClick = { nav.push(Screen.Templates) }) { tap(t) }
                        .padding(start = 6.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.clip(CircleShape)) { Mark(markName, 32.dp, icon = if (t.type == DraftType.TRANSFER) Icons.Outlined.SwapHoriz else null) }
                    Text(t.name, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (t.amount > 0) Text(currency.format(t.amount), color = F.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = F.money, maxLines = 1)
                }
            }
        }
    }
}

/** The next date with repeat entries still to be added (next 60 days), all of them summed. */
@Composable
private fun UpcomingRepeats() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val rules by repo.rules.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    if (rules.isEmpty()) return
    val today = repo.today()
    val window = DateRange(today, today.plus(DatePeriod(days = 60)))
    val next = rules.mapNotNull { r -> Recurrence.upcoming(r, window).firstOrNull()?.let { r to it } }
    val first = next.minOfOrNull { it.second }
    val due = next.filter { it.second == first }.map { it.first }
    val title: String
    val subtitle: String
    if (first == null) {
        title = tr("Repeat entries")
        subtitle = tr("{0} active · none due in the next 60 days", rules.size)
    } else {
        val allTransfers = due.all { it.kind.isTransfer }
        val day = if (first == today) tr("today") else first.short()
        title = when {
            due.size == 1 -> tr("Repeat entry on {0}", day)
            allTransfers -> tr("{0} repeat transfers on {1}", due.size, day)
            else -> tr("{0} repeat entries on {1}", due.size, day)
        }
        val names = due.map { r ->
            if (r.kind.isTransfer) r.note.ifBlank { "${accountName[r.accountId].orEmpty()} → ${accountName[r.toAccountId].orEmpty()}" }
            else r.note.ifBlank { categoryPath(r.categoryId, catById) }
        }
        val sum = due.sumOf { r ->
            val main = app.moneytracker.domain.Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
            if (allTransfers || r.kind == TxnKind.INCOME) main else -main
        }
        subtitle = "${names.joinToString(", ")} · ${currency.format(sum, showSign = !allTransfers)}"
    }
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { nav.push(Screen.Recurring) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(tr("Repeat entries"), 38.dp, icon = Icons.Outlined.Autorenew)
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = F.money)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = F.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RecentCard(onSeeAll: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val today = repo.today()
    val range = remember(today) { DateRange(today.minus(DatePeriod(days = 120)), today) }
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val flags = accounts.associate { it.id to it.transferAsExpense }
    val recent = txns.filter { it.kind != TxnKind.TRANSFER_IN }
        .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.createdAt }).take(5)
    FCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            CardTitle(tr("Recent"), tr("See all"), onSeeAll)
            Divider()
            if (recent.isEmpty()) {
                Text(tr("No entries yet. Tap + below to add your first one."), Modifier.padding(vertical = 14.dp), color = F.muted, fontSize = 14.sp)
            }
            recent.forEach { t -> RecentRow(t, accountName, catById, classify(t.toReportRow(flags))) { nav.push(Screen.EditTxn(txnId = t.id)) } }
        }
    }
}

@Composable
private fun RecentRow(t: Txn, accountName: Map<String, String>, catById: Map<String, app.moneytracker.domain.Category>, flow: Flow, onClick: () -> Unit) {
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val transfer = t.kind.isTransfer
    val adjust = t.kind == TxnKind.ADJUST_UP || t.kind == TxnKind.ADJUST_DOWN
    val category = when {
        transfer -> tr("Transfer")
        adjust -> tr(app.moneytracker.domain.MODIFIED_BALANCE_NAME)
        else -> mainCategoryName(t.categoryId, catById)
    }
    val account = if (transfer) "${accountName[t.accountId].orEmpty()} → ${accountName[t.toAccountId].orEmpty()}" else accountName[t.accountId].orEmpty()
    val title = listOfNotNull(t.note.ifBlank { null } ?: category, t.installmentLabel).joinToString(" ")
    val meta = listOfNotNull(
        if (t.isRefund) tr("Refund") else category.takeIf { it != title && !transfer },
        account.ifBlank { null },
        t.date.short(),
    ).joinToString(" · ")
    val refund = t.isRefund
    val color: Color = when {
        refund && flow != Flow.NONE -> F.income
        flow == Flow.INCOME -> F.income
        flow == Flow.EXPENSE -> F.ink
        else -> F.muted
    }
    val foreign = t.currency?.let { currencies[it] }
    val shown = if (refund) -t.amount else t.amount
    val amount = if (foreign != null && t.originalAmount != null) foreign.format.format(if (refund) -t.originalAmount else t.originalAmount, showSign = refund)
    else currency.format(shown, showSign = refund || flow == Flow.INCOME)
    val signed = if (flow == Flow.EXPENSE && !refund) "−$amount" else amount
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Mark(category, 36.dp, icon = if (transfer) Icons.Outlined.SwapHoriz else null)
        Column(Modifier.weight(1f)) {
            Text(title, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(signed, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = F.money, maxLines = 1)
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
        Text(tr("Unlock MoneyTracker"), color = F.ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
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
                            else -> Text(k, color = F.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun FreshWelcome() = WelcomeScreen()
@Composable internal fun FreshInbox() = SmsReviewScreen()
@Composable internal fun FreshMessageSettings() = SmsSettingsScreen()
