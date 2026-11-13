package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.domain.classify
import app.moneytracker.ui.collectAsState
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

private enum class SearchSpan(val label: String) { MONTH("This month"), THREE("3 months"), YEAR("This year"), ALL("All time") }

/**
 * Tags to offer under a search box: the selected ones first, then the rest. While the text starts with #,
 * the rest are narrowed to names containing what follows it.
 */
fun searchTagChoices(tags: List<Tag>, selected: List<String>, text: String): List<Tag> {
    val chosen = selected.mapNotNull { id -> tags.firstOrNull { it.id == id } }
    val query = text.trim().takeIf { it.startsWith("#") }?.drop(1)?.trim().orEmpty()
    return chosen + tags.filter { it.id !in selected && it.name.contains(query, ignoreCase = true) }
}

/** Toggles [tag] in [selected]; picking a tag while typing #name also clears the typed text. */
fun pickSearchTag(tag: Tag, selected: List<String>, text: String, setText: (String) -> Unit): List<String> {
    if (tag.id in selected) return selected - tag.id
    if (text.trim().startsWith("#")) setText("")
    return selected + tag.id
}

private fun SearchSpan.range(ps: PeriodSettings, today: LocalDate): DateRange = when (this) {
    SearchSpan.MONTH -> ps.month(ps.labelFor(today))
    SearchSpan.THREE -> DateRange(ps.month(ps.labelFor(today).plus(-2)).start, ps.month(ps.labelFor(today)).endInclusive)
    SearchSpan.YEAR -> ps.year(today.year)
    SearchSpan.ALL -> DateRange(LocalDate(1970, 1, 1), LocalDate(2200, 1, 1))
}

/** What the search form holds; the classic search screen's own state. */
private class SearchForm {
    var text by mutableStateOf("")
    var span by mutableStateOf(SearchSpan.ALL)
    var minText by mutableStateOf("")
    var maxText by mutableStateOf("")
    var tagIds by mutableStateOf(listOf<String>())
}

/** Classic search. The new layout has its own search screen. */
@Composable
fun SearchScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.settingsStore.periodSettings.collectAsState()
    val f = remember { SearchForm() }
    val accounts by repo.accountStore.accounts.collectAsState()
    val categories by repo.categoryStore.categories.collectAsState()
    val range = f.span.range(ps, repo.today())
    val min = currency.parse(f.minText)
    val max = currency.parse(f.maxText)
    // Re-run on any change; cheap single SQL query with LIMIT.
    val searching = f.text.isNotBlank() || min != null || max != null || f.tagIds.isNotEmpty()
    val results by produceState(emptyList<Txn>(), f.text, range, min, max, f.tagIds, accounts, categories) {
        value = if (!searching) emptyList() else withContext(repo.io) { repo.txnStore.search(f.text, range, min, max, f.tagIds.toSet()) }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Search"), nav::pop)
        SearchFormCard(f)
        HorizontalDivider(color = DividerColor)
        SearchResults(results, searching) { nav.push(Screen.EditTxn(txnId = it.id)) }
    }
}

@Composable
private fun SearchFormCard(f: SearchForm) {
    val allTags by LocalRepo.current.tagStore.tags.collectAsState()
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().background(PageBackground, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = SubtleText)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (f.text.isEmpty()) Text(tr("Note, category, account or #tag"), color = SubtleText)
                BasicTextField(f.text, { f.text = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchSpan.entries.forEach { s -> Chip(tr(s.label), s == f.span) { f.span = s } }
        }
        val tagChoices = searchTagChoices(allTags, f.tagIds, f.text)
        if (tagChoices.isNotEmpty()) {
            Row(
                Modifier.padding(top = 4.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Tags"), color = SubtleText, fontSize = 13.sp)
                tagChoices.forEach { t ->
                    val on = t.id in f.tagIds
                    Chip(if (on) "#${t.name}  ✕" else "#${t.name}", on) { f.tagIds = pickSearchTag(t, f.tagIds, f.text) { f.text = it } }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(f.minText, { f.minText = it }, Modifier.weight(1f), label = { Text(tr("Min amount")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(f.maxText, { f.maxText = it }, Modifier.weight(1f), label = { Text(tr("Max amount")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

@Composable
private fun SearchResults(results: List<Txn>, searching: Boolean, onOpen: (Txn) -> Unit) {
    val repo = LocalRepo.current
    val currency = LocalCurrency.current
    val rules by repo.reportStore.reportRules.collectAsState()
    val tagNames by repo.tagStore.txnTagNames.collectAsState()
    val accountName by repo.accountStore.accountNames.collectAsState()
    val catById by repo.categoryStore.categoryById.collectAsState()
    val rows = results.map { rules.row(it) }
    val inc = rows.filter { classify(it) == Flow.INCOME }.sumOf { it.amount }
    val exp = rows.filter { classify(it) == Flow.EXPENSE }.sumOf { it.amount }
    if (results.isNotEmpty()) {
        Text(tr("{0} found · income {1} · expense {2}", results.size, currency.format(inc), currency.format(exp)),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = SubtleText, fontSize = 13.sp)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        results.groupBy { it.date }.forEach { (date, list) ->
            item(key = "h$date") { Text(date.long(), Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 20.dp, vertical = 6.dp), color = SubtleText, fontSize = 13.sp) }
            items(list, key = { it.id }) { t ->
                TxnLine(t, accountName, catById, classify(rules.row(t)), tags = tagNames[t.id].orEmpty()) { onOpen(t) }
            }
        }
        if (results.isEmpty() && searching) item { EmptyState(tr("No matches"), Icons.Outlined.Search) }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.minimumInteractiveComponentSize().border(1.dp, if (selected) Accent else BorderColor, RoundedCornerShape(16.dp))
            .background(if (selected) AccentTint else SurfaceColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) Accent else InkSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(items: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    // Wrap by measured width so a long tag moves to the next line instead of being squeezed.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { items.forEach { (id, label) -> Chip(label, id in selected) { onToggle(id) } } }
}
