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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.SearchDates
import app.moneytracker.ui.SearchModel
import app.moneytracker.ui.SearchQuery
import app.moneytracker.ui.SearchState
import app.moneytracker.ui.SearchType
import app.moneytracker.ui.entryFeed
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.searchTagChoices
import app.moneytracker.ui.tr
import kotlinx.datetime.LocalDate

private fun LocalDate.searchLabel() = "$dayOfMonth ${monthShort(monthNumber)} $year"

/** Space between the search screen's sections (tags, filters, count, results). */
private val SECTION_GAP = 16.dp

/** Side padding of a result row's content and of the dividers between results. */
private val RESULT_INSET = 16.dp

/** Which end of a custom date range the date picker is setting. */
private enum class DateEnd { FROM, TO }

/** Text, type, account, amount and date filters over every entry; results open the entry. */
@Composable
internal fun FreshSearch() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    fun create(q: SearchQuery) = SearchModel(
        repo.txnStore, repo.accountStore, repo.tagStore, repo.settingsStore, repo.entryFeed(), repo.today, repo.io, scope, q,
    )
    val model = rememberSaveable(repo, saver = SearchModel.saver(::create)) { create(SearchQuery()) }
    val state by model.state.collectAsState()
    SearchContent(
        model.query, state, model,
        onBack = { nav.pop() },
        onOpen = { nav.push(Screen.EditTxn(txnId = it.id)) },
        onApply = {
            nav.setResult(ACTIVITY_FILTER, state.activityRequest(model.query))
            nav.pop()
            toaster.show(tr("Filter applied to Activity"))
        },
    )
}

@Composable
private fun SearchContent(q: SearchQuery, state: SearchState, model: SearchModel, onBack: () -> Unit, onOpen: (Txn) -> Unit, onApply: () -> Unit) {
    var datesSheet by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(F.page).statusBarsPadding()) {
        SearchBox(q.text, model::setText, onBack)
        // Sections are SECTION_GAP apart; each result is its own item, so the gaps sit on the items, not the list.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = 8.dp)) {
            val tagsShown = q.text.trim().startsWith("#")
            if (tagsShown) item(key = "tagSuggest") { TagSuggestions(q, state.tags, model::pickTag) }
            item(key = "filters") {
                SearchFilters(q, state, model, onDates = { datesSheet = true }, Modifier.padding(top = if (tagsShown) SECTION_GAP else 0.dp))
            }
            if (state.active) item(key = "count") { ResultCount(state, Modifier.padding(top = SECTION_GAP)) }
            results(state, onOpen)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter).padding(top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.height(52.dp).clip(F.buttonShape).background(F.card).clickable(onClick = model::clear).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tr("Clear"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            PrimaryButton(tr("Apply filter to Activity"), onApply, Modifier.weight(1f))
        }
    }
    if (datesSheet) DatesSheet(q, state, model, onDismiss = { datesSheet = false })
}

@Composable
private fun SearchBox(text: String, onText: (String) -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter).padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconTile(Glyph.Back, tr("Back"), onBack)
        Row(
            Modifier.weight(1f).height(44.dp).clip(F.iconShape).background(F.card).border(2.dp, F.accent, F.iconShape).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Glyph.Search, null, tint = F.muted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text(tr("Note, category, account or #tag"), color = F.muted, fontSize = 14.sp, maxLines = 1)
                BasicTextField(text, onText, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = F.ink, fontSize = 14.sp), cursorBrush = SolidColor(F.accent))
            }
            if (text.isNotEmpty()) Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(F.page).clickable(onClickLabel = tr("Clear search text")) { onText("") },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Close, tr("Clear search text"), tint = F.muted, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** While the text starts with #, the tags it could mean, to pick as a filter. */
@Composable
private fun TagSuggestions(q: SearchQuery, tags: List<Tag>, onPick: (Tag) -> Unit) {
    val choices = searchTagChoices(tags, q.tagIds, q.text).filter { it.id !in q.tagIds }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (choices.isEmpty()) Text(tr("No tag matches"), Modifier.padding(vertical = 8.dp), color = F.muted, fontSize = 13.sp)
        choices.forEach { t -> ActChip("#${t.name}", false) { onPick(t) } }
    }
}

@Composable
private fun SearchFilters(q: SearchQuery, state: SearchState, model: SearchModel, onDates: () -> Unit, modifier: Modifier) {
    val currency = LocalCurrency.current
    FCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChipGroup(tr("Type")) {
                SearchType.entries.forEach { t -> ActChip(tr(t.title), t in q.types) { model.toggleType(t) } }
            }
            ChipGroup(tr("Accounts"), scroll = true) {
                state.accounts.forEach { a -> ActChip(a.name, a.id in q.accountIds) { model.toggleAccount(a.id) } }
            }
            if (state.tags.isNotEmpty()) ChipGroup(tr("Tags"), scroll = true) {
                searchTagChoices(state.tags, q.tagIds, "").forEach { t -> ActChip("#${t.name}", t.id in q.tagIds) { model.pickTag(t) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(tr("Min amount"), q.minText, currency.format(0), Modifier.weight(1f), model::setMin)
                AmountField(tr("Max amount"), q.maxText, tr("Any"), Modifier.weight(1f), model::setMax)
            }
            DatesRow(q, state.range, onDates)
        }
    }
}

@Composable
private fun ChipGroup(label: String, scroll: Boolean = false, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        val row = if (scroll) Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()) else Modifier
        Row(row, horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

@Composable
private fun DatesRow(q: SearchQuery, range: DateRange, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(tr("Dates"), Modifier.weight(1f))
        Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(F.page).clickable(onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (q.dates == SearchDates.ALL) tr("All time") else "${range.start.searchLabel()} – ${range.endInclusive.searchLabel()}".takeIf { q.dates != SearchDates.CUSTOM || q.from != null || q.to != null } ?: tr("Custom"),
                color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(Glyph.ChevronDown, null, tint = F.ink, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ResultCount(state: SearchState, modifier: Modifier) {
    val currency = LocalCurrency.current
    val n = state.results.size
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (n == 1) tr("1 entry") else tr("{0} entries", n), Modifier.weight(1f), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(buildAnnotatedString {
            append(tr("in") + " ")
            withStyle(SpanStyle(color = F.income, fontWeight = FontWeight.Medium)) { append(currency.format(state.earned)) }
            append(" · " + tr("out") + " ")
            withStyle(SpanStyle(color = F.ink, fontWeight = FontWeight.Medium)) { append(currency.format(state.spent)) }
        }, color = F.muted, fontSize = 13.sp, style = F.money)
    }
}

private fun LazyListScope.results(state: SearchState, onOpen: (Txn) -> Unit) {
    if (!state.active) item(key = "hint") {
        Text(tr("Type a word or pick a filter to find entries."), Modifier.padding(top = SECTION_GAP).fillMaxWidth().padding(16.dp),
            color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    } else if (state.results.isEmpty()) item(key = "none") {
        FCard(Modifier.padding(top = SECTION_GAP), padding = 24.dp) {
            Text(tr("No matches"), Modifier.fillMaxWidth(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(tr("Try fewer words, another date range, or clear the filters."), Modifier.fillMaxWidth().padding(top = 6.dp),
                color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    } else entryCardItems(state.results, keyPrefix = "r", inset = RESULT_INSET, gapAbove = SECTION_GAP) { t ->
        ActEntryRow(t, state.lookups, showDate = true, inset = RESULT_INSET) { onOpen(t) }
    }
}

@Composable
private fun DatesSheet(q: SearchQuery, state: SearchState, model: SearchModel, onDismiss: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf<DateEnd?>(null) }
    Sheet(tr("Dates"), onDismiss, bottom = { PrimaryButton(tr("Done"), onDismiss) }) {
        SearchDates.entries.forEach { d ->
            val sub = when (d) {
                SearchDates.ALL -> null
                SearchDates.CUSTOM -> tr("Pick the first and last day")
                else -> state.rangeOf(d).let { r -> "${r.start.searchLabel()} – ${r.endInclusive.searchLabel()}" }
            }
            SheetOption(tr(d.title), selected = d == q.dates, subtitle = sub) { model.setDates(d) }
        }
        if (q.dates == SearchDates.CUSTOM) {
            Gap(4.dp)
            Column(Modifier.fillMaxWidth().clip(F.iconShape).background(F.page).padding(4.dp)) {
                FRow(tr("From"), value = q.from?.searchLabel() ?: tr("Any"), onClick = { picking = DateEnd.FROM })
                FRow(tr("To"), value = q.to?.searchLabel() ?: tr("Any"), onClick = { picking = DateEnd.TO })
            }
        }
    }
    DatePickerHost(picking != null, (if (picking == DateEnd.FROM) q.from else q.to) ?: state.today, { picking = null }, { d ->
        if (picking == DateEnd.FROM) model.setFrom(d) else model.setTo(d)
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
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(F.card).border(1.dp, F.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = F.muted, fontSize = 14.sp, maxLines = 1)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = F.ink, fontSize = 14.sp),
                cursorBrush = SolidColor(F.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}
