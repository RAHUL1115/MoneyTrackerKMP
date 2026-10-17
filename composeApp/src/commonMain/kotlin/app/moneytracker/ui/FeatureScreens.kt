package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.newId
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.Currency
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Flow
import app.moneytracker.domain.Note
import app.moneytracker.domain.Tag
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.classify
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

// ───────────────────────── search

private enum class SearchSpan(val label: String) { MONTH("This month"), THREE("3 months"), YEAR("This year"), ALL("All time") }

@Composable
fun SearchScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    var text by remember { mutableStateOf("") }
    var span by remember { mutableStateOf(SearchSpan.ALL) }
    var minText by remember { mutableStateOf("") }
    var maxText by remember { mutableStateOf("") }
    val accounts by repo.accounts.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val tagNames by repo.txnTagNames.collectAsState(emptyMap())
    val today = repo.today()
    val range = when (span) {
        SearchSpan.MONTH -> ps.month(ps.labelFor(today))
        SearchSpan.THREE -> DateRange(ps.month(ps.labelFor(today).plus(-2)).start, ps.month(ps.labelFor(today)).endInclusive)
        SearchSpan.YEAR -> ps.year(today.year)
        SearchSpan.ALL -> DateRange(LocalDate(1970, 1, 1), LocalDate(2200, 1, 1))
    }
    val min = currency.parse(minText)
    val max = currency.parse(maxText)
    // Re-run on any change; cheap single SQL query with LIMIT.
    val results by produceState(emptyList<app.moneytracker.domain.Txn>(), text, range, min, max, accounts, categories) {
        value = if (text.isBlank() && min == null && max == null) emptyList() else withContext(repo.io) { repo.search(text, range, min, max) }
    }
    val flags = accounts.associate { it.id to it.transferAsExpense }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Search"), nav::pop)
        Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().background(PageBackground, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = SubtleText)
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (text.isEmpty()) Text(tr("Note, category, account or #tag"), color = SubtleText)
                    BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchSpan.entries.forEach { s -> Chip(tr(s.label), s == span) { span = s } }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(minText, { minText = it }, Modifier.weight(1f), label = { Text(tr("Min amount")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(maxText, { maxText = it }, Modifier.weight(1f), label = { Text(tr("Max amount")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
        HorizontalDivider(color = DividerColor)
        val rows = results.map { it.toReportRow(flags) }
        val inc = rows.filter { classify(it) == Flow.INCOME }.sumOf { it.amount }
        val exp = rows.filter { classify(it) == Flow.EXPENSE }.sumOf { it.amount }
        if (results.isNotEmpty()) {
            Text(tr("{0} found · income {1} · expense {2}", results.size, currency.format(inc), currency.format(exp)),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = SubtleText, fontSize = 13.sp)
        }
        val accountName by repo.accountNames.collectAsState(emptyMap())
        val catById by repo.categoryById.collectAsState(emptyMap())
        LazyColumn(Modifier.fillMaxSize()) {
            results.groupBy { it.date }.forEach { (date, list) ->
                item(key = "h$date") { Text(date.long(), Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 20.dp, vertical = 6.dp), color = SubtleText, fontSize = 13.sp) }
                items(list, key = { it.id }) { t ->
                    TxnLine(t, accountName, catById, classify(t.toReportRow(flags)), tags = tagNames[t.id].orEmpty()) { nav.push(Screen.EditTxn(txnId = t.id)) }
                }
            }
            if (results.isEmpty() && (text.isNotBlank() || min != null || max != null)) item { EmptyState(tr("No matches"), Icons.Outlined.Search) }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.minimumInteractiveComponentSize().border(1.dp, if (selected) Accent else BorderColor, RoundedCornerShape(16.dp))
            .background(if (selected) AccentTint else SurfaceColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) Accent else InkSoft, fontSize = 13.sp,
    )
}

// ───────────────────────── templates (bookmarks)

@Composable
fun TemplatesScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val templates by repo.templates.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Bookmarks"), nav::pop)
        HorizontalDivider(color = DividerColor)
        if (templates.isEmpty()) {
            EmptyState(tr("No bookmarks yet. Open a transaction and tap ☆ to save it as one."), Icons.Outlined.StarOutline)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(templates, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().background(SurfaceColor).clickable {
                        nav.pop()
                        nav.push(Screen.EditTxn(preset = TxnDraft(t.type, repo.today(), t.accountId.orEmpty(), t.toAccountId, t.categoryId, t.amount, note = t.note, memo = t.memo)))
                    }.padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium)
                        val detail = listOfNotNull(
                            t.categoryId?.let { categoryPath(it, catById) },
                            t.accountId?.let { accountName[it] },
                            if (t.type == DraftType.TRANSFER) "→ ${accountName[t.toAccountId].orEmpty()}" else null,
                        )
                        Text(detail.joinToString(" · "), color = SubtleText, fontSize = 13.sp)
                    }
                    if (t.amount > 0) Text(currency.format(t.amount), color = if (t.type == DraftType.INCOME || t.type == DraftType.REFUND) IncomeColor else if (t.type == DraftType.EXPENSE) ExpenseColor else InkSoft)
                    IconButton(onClick = { scope.launch { withContext(repo.io) { repo.deleteTemplate(t.id) } } }) {
                        Icon(Icons.Outlined.Delete, tr("Delete bookmark"), tint = SubtleText)
                    }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}

// ───────────────────────── message inbox

@Composable
fun InboxScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val captures by repo.pendingCaptures.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    var pasting by remember { mutableStateOf(false) }
    var pasteResult by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Detected transactions"), nav::pop) {
            TextButton(onClick = { pasting = true; pasteResult = null }) { Text(tr("Paste message"), color = Accent) }
        }
        HorizontalDivider(color = DividerColor)
        pasteResult?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = SubtleText, fontSize = 13.sp) }
        if (pasting) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pasting = false },
                title = { Text(tr("Paste a bank message")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Paste a bank SMS about a payment. It is read on this phone only."), fontSize = 13.sp, color = SubtleText)
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(160.dp), label = { Text(tr("Message")) })
                    }
                },
                confirmButton = {
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        val msg = text
                        pasting = false
                        scope.launch {
                            val c = withContext(repo.io) { repo.ingestMessage("PASTE", "", msg, kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) }
                            pasteResult = if (c == null) tr("That doesn't look like a payment message (or it was already added).") else null
                        }
                    }) { Text(tr("Read"), color = Accent) }
                },
                dismissButton = { TextButton(onClick = { pasting = false }) { Text(tr("Cancel")) } },
            )
        }
        if (captures.isEmpty()) {
            EmptyState(tr("Nothing to review. Turn on \"Detect bank SMS\" in Configuration, or paste a message."), Icons.Outlined.Inbox)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(captures, key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.merchant.ifBlank { c.sender }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(c.accountId?.let { accountName[it] }, c.sender).joinToString(" · "), color = SubtleText, fontSize = 12.sp)
                        }
                        c.amount?.let {
                            val refund = app.moneytracker.domain.isRefund(c.kind, it)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(currency.format(if (refund) -it else it, showSign = refund), color = if (c.kind == TxnKind.INCOME || refund) IncomeColor else ExpenseColor, fontWeight = FontWeight.Medium)
                                if (refund) Text(tr("Refund"), color = SubtleText, fontSize = 11.sp)
                            }
                        }
                    }
                    Text(c.text, Modifier.padding(top = 6.dp), color = SubtleText, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallOutlineButton(tr("Add")) { nav.push(Screen.EditTxn(preset = repo.draftFromCapture(c), captureId = c.id)) }
                        SmallOutlineButton(tr("Dismiss")) { scope.launch { withContext(repo.io) { repo.setCaptureStatus(c.id, CaptureStatus.DISMISSED) } } }
                    }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}

// ───────────────────────── notes

private val NoteColors = listOf(SurfaceColor, Color(0xFFFFF4D6), Color(0xFFE3F2FD), Color(0xFFE8F5E9), Color(0xFFFCE4EC))

@Composable
fun NotesView() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val notes by repo.notes.collectAsState(emptyList())
    if (notes.isEmpty()) { EmptyState(tr("No notes. Tap + to jot something down.")); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        items(notes, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).background(NoteColors.getOrElse(n.color) { SurfaceColor }, RoundedCornerShape(8.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(8.dp)).clickable { nav.push(Screen.EditNote(n.id)) }.padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title.ifBlank { tr("Untitled") }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.pinned) Icon(Icons.Outlined.PushPin, tr("Pinned"), Modifier.size(16.dp), tint = Accent)
                }
                if (n.content.isNotBlank()) Text(n.content, Modifier.padding(top = 4.dp), color = InkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
fun EditNoteScreen(noteId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val notes by repo.notes.collectAsState(emptyList())
    var loaded by remember { mutableStateOf(noteId == null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0) }
    var pinned by remember { mutableStateOf(false) }
    var createdAt by remember { mutableStateOf(0L) }
    LaunchedEffect(notes) {
        if (loaded) return@LaunchedEffect
        notes.firstOrNull { it.id == noteId }?.let { title = it.title; content = it.content; color = it.color; pinned = it.pinned; createdAt = it.createdAt; loaded = true }
    }
    fun save() {
        if (title.isBlank() && content.isBlank()) { nav.pop(); return }
        val n = Note(noteId ?: newId(), title.trim(), content, color, pinned, if (createdAt == 0L) repo.today().toEpochDays() * 86_400_000L else createdAt, 0)
        scope.launch { withContext(repo.io) { repo.saveNote(n) }; nav.pop() }
    }
    // System Back saves too, same as the arrow — a note is never silently lost.
    val saveState = androidx.compose.runtime.rememberUpdatedState(::save)
    androidx.compose.runtime.DisposableEffect(nav) {
        val remove = nav.interceptBack { saveState.value(); true }
        onDispose { remove() }
    }
    Column(Modifier.fillMaxSize().background(NoteColors.getOrElse(color) { SurfaceColor })) {
        BackTopBar(tr("Note"), ::save) {
            IconButton(onClick = { pinned = !pinned }) { Icon(Icons.Outlined.PushPin, tr("Pin"), tint = if (pinned) Accent else SubtleText) }
            if (noteId != null) IconButton(onClick = { scope.launch { withContext(repo.io) { repo.deleteNote(noteId) }; nav.pop() } }) {
                Icon(Icons.Outlined.Delete, tr("Delete note"))
            }
            TextButton(onClick = ::save) { Text(tr("Save"), color = Accent) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NoteColors.forEachIndexed { i, c ->
                Box(Modifier.minimumInteractiveComponentSize().size(28.dp).background(c, RoundedCornerShape(14.dp)).border(if (i == color) 2.dp else 1.dp, if (i == color) Accent else DividerColor, RoundedCornerShape(14.dp)).clickable { color = i })
            }
        }
        // The note's date (notes are shown by day); defaults to today.
        var pickingDate by remember { mutableStateOf(false) }
        val noteDate = kotlinx.datetime.LocalDate.fromEpochDays(((if (createdAt == 0L) repo.today().toEpochDays() * 86_400_000L else createdAt) / 86_400_000L).toInt())
        Text("${noteDate.monthNumber}.${noteDate.dayOfMonth} (${noteDate.weekday()})",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp).border(1.dp, SubtleText, RoundedCornerShape(4.dp))
                .clickable { pickingDate = true }.padding(horizontal = 14.dp, vertical = 4.dp), color = InkSoft)
        DatePickerHost(pickingDate, noteDate, { pickingDate = false }, { d -> d?.let { createdAt = it.toEpochDays() * 86_400_000L } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box {
                if (title.isEmpty()) Text(tr("Title"), fontSize = 22.sp, color = SubtleText)
                BasicTextField(title, { title = it }, textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Box {
                if (content.isEmpty()) Text(tr("Write something…"), fontSize = 16.sp, color = SubtleText)
                BasicTextField(content, { content = it }, textStyle = TextStyle(fontSize = 16.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth().height(400.dp))
            }
        }
    }
}

// ───────────────────────── currencies

@Composable
fun CurrenciesScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val main = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val currencies by repo.currencies.collectAsState(emptyList())
    var editing by remember { mutableStateOf<Currency?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Sub currencies"), nav::pop)
            HorizontalDivider(color = DividerColor)
            Text(tr("Main currency: {0}. Rates are how many {0} one unit is worth; transactions keep the rate from the day they were entered.", main.symbol),
                Modifier.padding(20.dp), color = SubtleText, fontSize = 13.sp)
            if (currencies.isEmpty()) EmptyState(tr("No other currencies yet. Tap + to add one, e.g. USD for a dollar account."))
            LazyColumn {
                items(currencies, key = { it.code }) { c ->
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = c }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${c.code}  ${c.symbol}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("1 ${c.code} = ${main.symbol} ${c.rate}", color = SubtleText)
                        IconButton(onClick = { scope.launch { withContext(repo.io) { repo.deleteCurrency(c.code) } } }) { Icon(Icons.Outlined.Delete, tr("Delete {0}", c.code), tint = SubtleText) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Currency("", "", 2, 1.0, currencies.size) }
    }
    editing?.let { c ->
        var code by remember(c) { mutableStateOf(c.code) }
        var symbol by remember(c) { mutableStateOf(c.symbol) }
        var decimals by remember(c) { mutableStateOf(c.decimals.toString()) }
        var rate by remember(c) { mutableStateOf(if (c.code.isEmpty()) "" else c.rate.toString()) }
        val valid = code.trim().length == 3 && symbol.isNotBlank() && decimals.toIntOrNull() in 0..3 && (rate.toDoubleOrNull() ?: 0.0) > 0
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (c.code.isEmpty()) tr("Add currency") else tr("Edit {0}", c.code)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.uppercase().take(3) }, label = { Text(tr("ISO code (e.g. USD)")) }, singleLine = true, enabled = c.code.isEmpty())
                    OutlinedTextField(symbol, { symbol = it.take(4) }, label = { Text(tr("Symbol")) }, singleLine = true)
                    OutlinedTextField(decimals, { decimals = it.filter(Char::isDigit).take(1) }, label = { Text(tr("Decimal places")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(rate, { rate = it }, label = { Text(tr("1 unit = how many {0}", main.symbol)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val saved = Currency(code.trim(), symbol.trim(), decimals.toInt(), rate.toDouble(), c.sortOrder)
                    scope.launch { withContext(repo.io) { repo.saveCurrency(saved) } }
                    editing = null
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
}

// ───────────────────────── tags

@Composable
fun TagsScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val tags by repo.tags.collectAsState(emptyList())
    var editing by remember { mutableStateOf<Tag?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Tags"), nav::pop)
            HorizontalDivider(color = DividerColor)
            if (tags.isEmpty()) EmptyState(tr("No tags yet. Add one here or while entering a transaction."))
            LazyColumn {
                items(tags, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = t }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#${t.name}", Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { withContext(repo.io) { repo.deleteTag(t.id) } } }) { Icon(Icons.Outlined.Delete, tr("Delete tag"), tint = SubtleText) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Tag("", "", tags.size) }
    }
    editing?.let { t ->
        var name by remember(t) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (t.id.isEmpty()) tr("Add tag") else tr("Rename tag")) },
            text = { OutlinedTextField(name, { name = it.removePrefix("#") }, label = { Text(tr("Name")) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { withContext(repo.io) { repo.saveTag(t.copy(id = t.id.ifEmpty { newId() }, name = name)) } }
                    editing = null
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
}

/** Multi-select tag picker with inline "create". */
@Composable
fun TagPickerDialog(selected: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val tags by repo.tags.collectAsState(emptyList())
    var chosen by remember { mutableStateOf(selected.toSet()) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tags")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tags.isEmpty()) Text(tr("No tags yet — create one below."), color = SubtleText, fontSize = 13.sp)
                FlowChips(tags.map { it.id to "#" + it.name }, chosen) { id -> chosen = if (id in chosen) chosen - id else chosen + id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.removePrefix("#") }, Modifier.weight(1f), label = { Text(tr("New tag")) }, singleLine = true)
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        val name = newName; newName = ""
                        scope.launch { val id = withContext(repo.io) { repo.tagNamed(name) }; chosen = chosen + id }
                    }) { Text(tr("Add"), color = Accent) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen.toList()) }) { Text(tr("Done"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
fun FlowChips(items: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    // Simple wrapping: rows of up to 3 chips (dialogs are narrow; avoids an experimental FlowRow dependency).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (id, label) -> Chip(label, id in selected) { onToggle(id) } } }
        }
    }
}

// ───────────────────────── stats drill-down

@Composable
fun StatsDetailScreen(s: Screen.StatsDetail) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val range = DateRange(s.from, s.to)
    val txns by remember(range) { repo.txnsBetween(range) }.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val kind = if (s.income) TxnKind.INCOME else TxnKind.EXPENSE
    val inCat = txns.filter { t -> t.kind == kind && t.categoryId?.let { catById[it] }?.let { (it.parentId ?: it.id) == s.mainCategoryId } == true }
    val bySub = inCat.groupBy { t -> catById[t.categoryId]?.takeIf { it.parentId != null }?.id ?: s.mainCategoryId }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val total = bySub.values.sum()
    val color = if (s.income) IncomeColor else ExpenseColor
    // Trend: this category per month over the 8 months ending with the shown period.
    val ps = repo.periodSettingsNow()
    val endYm = ps.labelFor(s.to)
    val months = (7 downTo 0).map { endYm.plus(-it) }
    val trendRange = DateRange(ps.month(months.first()).start, ps.month(months.last()).endInclusive)
    val trendTxns by remember(trendRange) { repo.txnsBetween(trendRange) }.collectAsState(emptyList())
    val trend = months.map { ym ->
        val r = ps.month(ym)
        trendTxns.filter { t -> t.kind == kind && t.date >= r.start && t.date <= r.endInclusive &&
            t.categoryId?.let { catById[it] }?.let { (it.parentId ?: it.id) == s.mainCategoryId } == true }.sumOf { it.amount }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(s.title, nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text("${s.from.short()} – ${s.to.short()} ${s.to.year} · ${currency.format(total)}", Modifier.padding(20.dp), color = SubtleText)
                if (total > 0 && bySub.size > 1) {
                    Box(Modifier.fillMaxWidth().background(SurfaceColor).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        DonutChart(bySub.values.sortedDescending(), Modifier.size(180.dp))
                    }
                }
            }
            item {
                Column(Modifier.padding(vertical = 8.dp).card()) {
                    Text(tr("Last 8 months"), Modifier.padding(start = 16.dp, top = 12.dp), color = SubtleText, fontSize = 12.sp)
                    LineChart(trend, months.map { monthShort(it.month) }, color = color)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(bySub.entries.sortedByDescending { it.value }.withIndex().toList(), key = { "s" + it.value.key }) { (i, e) ->
                Row(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PercentBadge(((e.value * 100.0) / total).toInt().coerceAtLeast(1), ChartColors[i % ChartColors.size])
                    Text(if (e.key == s.mainCategoryId) tr("(no sub-category)") else catById[e.key]?.name.orEmpty(), Modifier.weight(1f).padding(start = 12.dp))
                    Text(currency.format(e.value))
                }
                HorizontalDivider(color = DividerColor)
            }
            item { GreyHeader(tr("Transactions")) }
            items(inCat, key = { it.id }) { t ->
                TxnLine(t, accountName, catById, if (s.income) Flow.INCOME else Flow.EXPENSE) { nav.push(Screen.EditTxn(txnId = t.id)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
