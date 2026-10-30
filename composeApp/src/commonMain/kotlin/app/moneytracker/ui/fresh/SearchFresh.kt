package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.classify
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.TxnFilter
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.toReportRow
import app.moneytracker.ui.tr
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

private enum class SearchType(val title: String) { INCOME("Income"), EXPENSES("Expenses"), TRANSFERS("Transfers") }
private enum class SearchDates(val title: String) { MONTH("This month"), LAST("Last month"), THREE("Last 3 months"), YEAR("This year"), ALL("All time"), CUSTOM("Custom") }

private fun LocalDate.searchLabel() = "$dayOfMonth ${monthShort(monthNumber)} $year"

/** Text, type, account, amount and date filters over every entry; results open the entry. */
@Composable
internal fun FreshSearch() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val toaster = LocalToaster.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val accounts by repo.accounts.collectAsState(emptyList())
    val data = rememberActEntryData()
    var text by rememberSaveable { mutableStateOf("") }
    var types by rememberSaveable { mutableStateOf(listOf<String>()) }
    var accts by rememberSaveable { mutableStateOf(listOf<String>()) }
    var minText by rememberSaveable { mutableStateOf("") }
    var maxText by rememberSaveable { mutableStateOf("") }
    var dates by rememberSaveable { mutableStateOf(SearchDates.ALL) }
    var from by rememberSaveable { mutableStateOf<Int?>(null) }
    var to by rememberSaveable { mutableStateOf<Int?>(null) }
    var datesSheet by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    val today = repo.today()
    val now = ps.labelFor(today)
    val range = when (dates) {
        SearchDates.MONTH -> ps.month(now)
        SearchDates.LAST -> ps.month(now.plus(-1))
        SearchDates.THREE -> DateRange(ps.month(now.plus(-2)).start, ps.month(now).endInclusive)
        SearchDates.YEAR -> ps.year(today.year)
        SearchDates.ALL -> DateRange(LocalDate(1970, 1, 1), LocalDate(2200, 1, 1))
        SearchDates.CUSTOM -> {
            val a = from?.let { LocalDate.fromEpochDays(it) } ?: LocalDate(1970, 1, 1)
            val b = to?.let { LocalDate.fromEpochDays(it) } ?: LocalDate(2200, 1, 1)
            if (a <= b) DateRange(a, b) else DateRange(b, a)
        }
    }
    val min = currency.parse(minText)
    val max = currency.parse(maxText)
    val active = text.isNotBlank() || min != null || max != null || types.isNotEmpty() || accts.isNotEmpty() || dates != SearchDates.ALL
    val found by produceState(emptyList<Txn>(), text, range, min, max, accounts) {
        value = if (!active) emptyList() else withContext(repo.io) { repo.search(text, range, min, max) }
    }
    // Each transfer once (its outgoing side), then the type and account chips.
    val results = found.sortedBy { if (it.kind == TxnKind.TRANSFER_IN) 1 else 0 }.distinctBy { it.transferGroupId ?: it.id }
        .filter { t ->
            val type = when (t.kind) {
                TxnKind.INCOME -> SearchType.INCOME.name
                TxnKind.EXPENSE -> SearchType.EXPENSES.name
                TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN -> SearchType.TRANSFERS.name
                else -> null
            }
            (types.isEmpty() || type in types) && (accts.isEmpty() || t.accountId in accts || t.toAccountId in accts)
        }
        .sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.createdAt })
    val rows = results.map { it.toReportRow(data.flags) }
    val earned = rows.filter { classify(it) == Flow.INCOME }.sumOf { it.amount }
    val spent = rows.filter { classify(it) == Flow.EXPENSE }.sumOf { it.amount }

    fun clear() { text = ""; types = emptyList(); accts = emptyList(); minText = ""; maxText = ""; dates = SearchDates.ALL; from = null; to = null }
    fun applyToActivity() {
        val kind = when (types.toSet()) {
            setOf(SearchType.EXPENSES.name) -> ActKind.OUT
            setOf(SearchType.TRANSFERS.name) -> ActKind.MOVED
            else -> ActKind.ALL
        }
        val month = if (dates == SearchDates.ALL) null else ps.labelFor(minOf(range.endInclusive, today).coerceAtLeast(range.start)).key
        ActivityHandoff.pending = ActivityFilterRequest(TxnFilter(accountIds = accts.toSet()), kind, month)
        nav.pop()
        toaster.show(tr("Filter applied to Activity"))
    }

    Column(Modifier.fillMaxSize().background(F.page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"), { nav.pop() })
            Row(
                Modifier.weight(1f).height(44.dp).clip(F.iconShape).background(F.card).border(2.dp, F.accent, F.iconShape).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = F.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text(tr("Note, category, account or #tag"), color = F.muted, fontSize = 14.sp, maxLines = 1)
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = TextStyle(color = F.ink, fontSize = 14.sp), cursorBrush = SolidColor(F.accent))
                }
                if (text.isNotEmpty()) Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(F.page).clickable(onClickLabel = tr("Clear search text")) { text = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, tr("Clear search text"), tint = F.muted, modifier = Modifier.size(16.dp)) }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "filters") {
                FCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldLabel(tr("Type"))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SearchType.entries.forEach { t ->
                                    ActChip(tr(t.title), t.name in types) { types = if (t.name in types) types - t.name else types + t.name }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldLabel(tr("Accounts"))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                accounts.filter { !it.hidden || it.id in accts }.forEach { a ->
                                    ActChip(a.name, a.id in accts) { accts = if (a.id in accts) accts - a.id else accts + a.id }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AmountField(tr("Min amount"), minText, currency.format(0), Modifier.weight(1f)) { minText = it }
                            AmountField(tr("Max amount"), maxText, tr("Any"), Modifier.weight(1f)) { maxText = it }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel(tr("Dates"), Modifier.weight(1f))
                            Row(
                                Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(F.page).clickable { datesSheet = true }.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(if (dates == SearchDates.ALL) tr("All time") else "${range.start.searchLabel()} – ${range.endInclusive.searchLabel()}".takeIf { dates != SearchDates.CUSTOM || from != null || to != null } ?: tr("Custom"),
                                    color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Icon(Icons.Filled.KeyboardArrowDown, null, tint = F.ink, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (active) item(key = "count") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (results.size == 1) tr("1 entry") else tr("{0} entries", results.size), Modifier.weight(1f), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(buildAnnotatedString {
                        append(tr("in") + " ")
                        withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.Medium)) { append(currency.format(earned)) }
                        append(" · " + tr("out") + " ")
                        withStyle(SpanStyle(color = F.expense, fontWeight = FontWeight.Medium)) { append(currency.format(spent)) }
                    }, color = F.muted, fontSize = 13.sp, style = F.money)
                }
            }
            if (!active) item(key = "hint") {
                Text(tr("Type a word or pick a filter to find entries."), Modifier.fillMaxWidth().padding(16.dp), color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else if (results.isEmpty()) item(key = "none") {
                FCard(padding = 24.dp) {
                    Text(tr("No matches"), Modifier.fillMaxWidth(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(tr("Try fewer words, another date range, or clear the filters."), Modifier.fillMaxWidth().padding(top = 6.dp),
                        color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            } else item(key = "results") {
                Column(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).padding(horizontal = 16.dp)) {
                    results.forEachIndexed { i, t ->
                        if (i > 0) Divider()
                        ActEntryRow(t, data, showDate = true) { nav.push(Screen.EditTxn(txnId = t.id)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(52.dp).clip(F.buttonShape).background(F.card).clickable(onClick = ::clear).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Clear"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            PrimaryButton(tr("Apply filter to Activity"), ::applyToActivity, Modifier.weight(1f))
        }
    }

    if (datesSheet) Sheet(tr("Dates"), { datesSheet = false }, bottom = { PrimaryButton(tr("Done"), { datesSheet = false }) }) {
        SearchDates.entries.forEach { d ->
            val sub = when (d) {
                SearchDates.ALL -> null
                SearchDates.CUSTOM -> tr("Pick the first and last day")
                else -> {
                    val r = when (d) {
                        SearchDates.MONTH -> ps.month(now)
                        SearchDates.LAST -> ps.month(now.plus(-1))
                        SearchDates.THREE -> DateRange(ps.month(now.plus(-2)).start, ps.month(now).endInclusive)
                        else -> ps.year(today.year)
                    }
                    "${r.start.searchLabel()} – ${r.endInclusive.searchLabel()}"
                }
            }
            SheetOption(tr(d.title), selected = d == dates, subtitle = sub) { dates = d }
        }
        if (dates == SearchDates.CUSTOM) {
            Gap(4.dp)
            Column(Modifier.fillMaxWidth().clip(F.iconShape).background(F.page).padding(4.dp)) {
                FRow(tr("From"), value = from?.let { LocalDate.fromEpochDays(it).searchLabel() } ?: tr("Any"), onClick = { picking = "from" })
                FRow(tr("To"), value = to?.let { LocalDate.fromEpochDays(it).searchLabel() } ?: tr("Any"), onClick = { picking = "to" })
            }
        }
    }
    DatePickerHost(picking != null, (if (picking == "from") from else to)?.let { LocalDate.fromEpochDays(it) } ?: today, { picking = null }, { d ->
        if (picking == "from") from = d?.toEpochDays() else to = d?.toEpochDays()
    }, clearable = true)
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)

@Composable
private fun AmountField(label: String, value: String, placeholder: String, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label)
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, F.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = F.muted, fontSize = 14.sp, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = F.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(F.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}
