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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Tag
import app.moneytracker.domain.Txn
import app.moneytracker.domain.classify

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

/** Classic's four date chips, as the shared search dates, with classic's own labels. */
private val classicDates = listOf(
    SearchDates.MONTH to "This month", SearchDates.THREE to "3 months", SearchDates.YEAR to "This year", SearchDates.ALL to "All time",
)

/** What the classic search form asks of its holder. */
private class SearchFormActions(
    val setText: (String) -> Unit,
    val setDates: (SearchDates) -> Unit,
    val pickTag: (Tag) -> Unit,
    val setMin: (String) -> Unit,
    val setMax: (String) -> Unit,
)

/** Classic search: text, date chips, tags and amounts over every entry, drawn from the shared [SearchModel]. */
@Composable
fun SearchScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    fun create(q: SearchQuery) = SearchModel(
        repo.txnStore, repo.accountStore, repo.tagStore, repo.settingsStore, repo.entryFeed(), repo.today, repo.io, scope, q, SearchRules.Classic,
    )
    val model = rememberSaveable(repo, saver = SearchModel.saver(::create)) { create(SearchQuery()) }
    val state by model.state.collectAsState()
    val actions = SearchFormActions(model::setText, model::setDates, model::pickTag, model::setMin, model::setMax)

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Search"), nav::pop)
        SearchFormCard(model.query, state.tags, actions)
        HorizontalDivider(color = DividerColor)
        SearchResults(state) { nav.push(Screen.EditTxn(txnId = it.id)) }
    }
}

@Composable
private fun SearchFormCard(q: SearchQuery, allTags: List<Tag>, actions: SearchFormActions) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().background(PageBackground, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = SubtleText)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (q.text.isEmpty()) Text(tr("Note, category, account or #tag"), color = SubtleText)
                BasicTextField(q.text, actions.setText, singleLine = true, textStyle = TextStyle(fontSize = 15.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            classicDates.forEach { (d, label) -> Chip(tr(label), d == q.dates) { actions.setDates(d) } }
        }
        val tagChoices = searchTagChoices(allTags, q.tagIds, q.text)
        if (tagChoices.isNotEmpty()) {
            Row(
                Modifier.padding(top = 4.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Tags"), color = SubtleText, fontSize = 13.sp)
                tagChoices.forEach { t ->
                    val on = t.id in q.tagIds
                    Chip(if (on) "#${t.name}  ✕" else "#${t.name}", on) { actions.pickTag(t) }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(q.minText, actions.setMin, Modifier.weight(1f), label = { Text(tr("Min amount")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(q.maxText, actions.setMax, Modifier.weight(1f), label = { Text(tr("Max amount")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

@Composable
private fun SearchResults(state: SearchState, onOpen: (Txn) -> Unit) {
    val currency = LocalCurrency.current
    val results = state.results
    val look = state.lookups
    if (results.isNotEmpty()) {
        Text(tr("{0} found · income {1} · expense {2}", results.size, currency.format(state.earned), currency.format(state.spent)),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = SubtleText, fontSize = 13.sp)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        results.groupBy { it.date }.forEach { (date, list) ->
            item(key = "h$date") { Text(date.long(), Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 20.dp, vertical = 6.dp), color = SubtleText, fontSize = 13.sp) }
            items(list, key = { it.id }) { t ->
                TxnLine(t, look.accountName, look.catById, classify(look.rules.row(t)), tags = look.tags[t.id].orEmpty()) { onOpen(t) }
            }
        }
        if (results.isEmpty() && state.active) item { EmptyState(tr("No matches"), Icons.Outlined.Search) }
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
