package app.moneytracker.ui

import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.LocalBackup
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.RecurringRule
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MoreTab() {
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize().background(PageBackground)) {
        TitleTopBar(tr("Settings")) { Text("v0.1", Modifier.padding(end = 12.dp), color = SubtleText, fontSize = 13.sp) }
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(12.dp))
        val tiles = listOf(
            Triple(tr("Configuration"), Icons.Outlined.Settings) { nav.push(Screen.Configuration) },
            Triple(tr("Accounts"), Icons.Outlined.AccountBalanceWallet) { nav.push(Screen.AccountList) },
            Triple(tr("Backup"), Icons.Outlined.Restore) { nav.push(Screen.Backup) },
            Triple(tr("Categories"), Icons.Outlined.Category) { nav.push(Screen.Categories(CategoryKind.EXPENSE)) },
            Triple(tr("Budget"), Icons.Outlined.EditNote) { nav.push(Screen.Budget) },
            Triple(tr("Repeat"), Icons.Outlined.Repeat) { nav.push(Screen.Recurring) },
            Triple(tr("Bookmarks"), Icons.Outlined.StarOutline) { nav.push(Screen.Templates) },
            Triple(tr("Detected SMS"), Icons.Outlined.MarkEmailUnread) { nav.push(Screen.Inbox) },
            Triple(tr("Passcode"), Icons.Outlined.Lock) { nav.push(Screen.Security) },
            Triple(tr("Calculator"), Icons.Outlined.Calculate) { nav.push(Screen.Calculator) },
            Triple(tr("Help"), Icons.Outlined.HelpOutline) { nav.push(Screen.Help) },
            Triple(tr("About"), Icons.Outlined.Info) { nav.push(Screen.About) },
        )
        Column(Modifier.card().padding(vertical = 8.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, icon, onClick) -> Tile(label, icon, Modifier.weight(1f), onClick) }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(AccentTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = Accent)
        }
        Text(label, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Ink)
    }
}

// ───────────────────────── configuration

@Composable
fun ConfigurationScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val startDay by repo.monthStartDay.collectAsState(1)
    val platform = LocalPlatform.current
    var editing by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<String?>(null) }   // key of a multiple-choice setting being edited
    var accentDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var locationDisclosure by remember { mutableStateOf(false) }
    val language by remember(repo) { repo.setting(Keys.LANGUAGE) }.collectAsState(null)
    val ps by repo.periodSettings.collectAsState(repo.periodSettingsNow())
    val carryOver by remember(repo) { repo.flag(Keys.CARRY_OVER) }.collectAsState(false)
    val cardAtPayment by remember(repo) { repo.flag(Keys.CARD_AT_PAYMENT) }.collectAsState(false)
    val reminder by remember(repo) { repo.flag(Keys.REMINDER) }.collectAsState(false)
    val quickAdd by remember(repo) { repo.flag(Keys.QUICK_ADD_NOTIFICATION) }.collectAsState(false)
    val capture by remember(repo) { repo.flag(Keys.CAPTURE_ENABLED) }.collectAsState(false)
    val location by remember(repo) { repo.flag(Keys.LOCATION) }.collectAsState(false)
    val useSubcategories by repo.useSubcategories.collectAsState(false)
    val passcode by remember(repo) { repo.setting(Keys.PASSCODE) }.collectAsState(null)
    val reminderTime by remember(repo) { repo.setting(Keys.REMINDER_TIME) }.collectAsState(null)
    val startScreen by remember(repo) { repo.setting(Keys.START_SCREEN) }.collectAsState(null)
    @Composable fun opt(key: String) = remember(repo, key) { repo.setting(key) }
    val swipe by opt(Keys.SWIPE).collectAsState(null)
    val colorSet by opt(Keys.COLOR_SET).collectAsState(null)
    val timeInput by opt(Keys.TIME_INPUT).collectAsState(null)
    val timeSort by opt(Keys.TIME_SORT).collectAsState(null)
    val inputOrder by opt(Keys.INPUT_ORDER).collectAsState(null)
    val theme by opt(Keys.THEME).collectAsState(null)
    val widgetTheme by opt(Keys.WIDGET_THEME).collectAsState(null)
    val widgetOpacity by opt(Keys.WIDGET_OPACITY).collectAsState(null)
    val repeatTiming by opt(Keys.REPEAT_TIMING).collectAsState(null)
    val autocomplete by opt(Keys.AUTOCOMPLETE).collectAsState(null)
    val showDescription by remember(repo) { repo.flag(Keys.SHOW_DESCRIPTION) }.collectAsState(false)
    val noteButton by remember(repo) { repo.setting(Keys.NOTE_BUTTON) }.collectAsState(null)
    val symbolAfter by remember(repo) { repo.flag(Keys.SYMBOL_AFTER) }.collectAsState(false)
    var resetAutocompleteDone by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }
    // Widgets show colours, currency format and their own style; repaint them after any change.
    fun put(key: String, value: String) = scope.launch { withContext(repo.io) { repo.putSetting(key, value) }; platform.refreshWidgets() }
    fun setFlag(key: String, on: Boolean) = put(key, if (on) "1" else "0")

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Configuration"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConfigSection(tr("Category / Repeat"))
            ConfigRow(tr("Income category setting")) { nav.push(Screen.Categories(CategoryKind.INCOME)) }
            ConfigRow(tr("Expenses category setting")) { nav.push(Screen.Categories(CategoryKind.EXPENSE)) }
            ConfigToggle(tr("Sub-categories"), tr("Pick a sub-category (Food › Lunch) when adding an entry"), useSubcategories) { setFlag(Keys.USE_SUBCATEGORIES, it) }
            ConfigRow(tr("Budget setting")) { nav.push(Screen.Budget) }
            ConfigRow(tr("Repeat setting")) { nav.push(Screen.Recurring) }
            ConfigRow(tr("Repeat entries are added"), choiceLabel(Keys.REPEAT_TIMING, repeatTiming)) { choice = Keys.REPEAT_TIMING }
            ConfigSection(tr("Configuration"))
            ConfigRow(tr("Currency symbol"), currency.symbol) { editing = Keys.CURRENCY_SYMBOL }
            ConfigRow(tr("Decimal places"), currency.decimals.toString()) { editing = Keys.CURRENCY_DECIMALS }
            ConfigToggle(tr("Symbol after amount"), if (symbolAfter) "1,000.00 ${currency.symbol}" else "${currency.symbol} 1,000.00", symbolAfter) { setFlag(Keys.SYMBOL_AFTER, it) }
            ConfigRow(tr("Sub currencies")) { nav.push(Screen.Currencies) }
            ConfigRow(tr("Monthly start date"), tr("Every {0}", startDay)) { editing = Keys.MONTH_START_DAY }
            ConfigRow(tr("If start date is a weekend"), when (ps.shift) {
                app.moneytracker.domain.Periods.WeekendShift.NONE -> tr("Keep")
                app.moneytracker.domain.Periods.WeekendShift.BEFORE -> tr("Friday before")
                app.moneytracker.domain.Periods.WeekendShift.AFTER -> tr("Monday after")
            }) { choice = Keys.WEEKEND_SHIFT }
            ConfigRow(tr("Weekly start day"), tr(ps.weekStart.name.lowercase().replaceFirstChar { it.uppercase() })) { choice = Keys.WEEK_START }
            ConfigRow(tr("Start screen"), tr((startScreen ?: "DAILY").lowercase().replaceFirstChar { it.uppercase() })) { choice = Keys.START_SCREEN }
            ConfigToggle(tr("Carry-over"), tr("Show the balance carried from earlier months"), carryOver) { setFlag(Keys.CARRY_OVER, it) }
            ConfigToggle(tr("Card expenses at payment"), tr("Count credit-card purchases when the bill is paid, not when you buy"), cardAtPayment) { setFlag(Keys.CARD_AT_PAYMENT, it) }
            ConfigRow(tr("Tags")) { nav.push(Screen.Tags) }
            ConfigRow(tr("Swipe"), choiceLabel(Keys.SWIPE, swipe)) { choice = Keys.SWIPE }
            ConfigRow(tr("Income / expense colors"), choiceLabel(Keys.COLOR_SET, colorSet)) { choice = Keys.COLOR_SET }
            ConfigRow(tr("Time input"), choiceLabel(Keys.TIME_INPUT, timeInput)) { choice = Keys.TIME_INPUT }
            ConfigRow(tr("Order within a day"), choiceLabel(Keys.TIME_SORT, timeSort)) { choice = Keys.TIME_SORT }
            ConfigToggle(tr("Show description"), tr("Description under the note in lists"), showDescription) { setFlag(Keys.SHOW_DESCRIPTION, it) }
            ConfigToggle(tr("Note button"), tr("Add a note from the Daily view, next to +"), noteButton != "0") { setFlag(Keys.NOTE_BUTTON, it) }
            ConfigToggle(tr("Autocomplete notes"), tr("Suggest past notes while typing; picks their category and account"), autocomplete != "0") {
                put(Keys.AUTOCOMPLETE, if (it) "1" else "0")
            }
            ConfigRow(tr("Reset autocomplete"), if (resetAutocompleteDone) tr("Done") else null) {
                scope.launch { withContext(repo.io) { repo.resetAutocomplete() }; resetAutocompleteDone = true }
            }
            ConfigRow(tr("Input order"), choiceLabel(Keys.INPUT_ORDER, inputOrder)) { choice = Keys.INPUT_ORDER }
            ConfigRow(tr("Style"), choiceLabel(Keys.THEME, theme)) { choice = Keys.THEME }
            ConfigRow(tr("Accent colour"), tr(accentChoice.label)) { accentDialog = true }
            ConfigRow(tr("Language"), I18n.LANGUAGES.firstOrNull { it.first == language.orEmpty() }?.takeIf { it.first.isNotEmpty() }?.second ?: tr("Phone language")) { languageDialog = true }
            ConfigRow(tr("Widget style"), choiceLabel(Keys.WIDGET_THEME, widgetTheme)) { choice = Keys.WIDGET_THEME }
            ConfigRow(tr("Widget background"), choiceLabel(Keys.WIDGET_OPACITY, widgetOpacity)) { choice = Keys.WIDGET_OPACITY }
            Text(
                tr("Set decimal places before entering data; existing amounts are not converted."),
                Modifier.padding(horizontal = 28.dp, vertical = 12.dp), fontSize = 13.sp, color = SubtleText,
            )

            ConfigSection(tr("Security & alerts"))
            ConfigRow(tr("Passcode"), if (passcode.isNullOrBlank()) tr("Off") else tr("On")) { nav.push(Screen.Security) }
            ConfigToggle(tr("Daily reminder"), tr("A nudge to record the day's spending"), reminder) { on ->
                val (h, m) = (reminderTime ?: "21:00").split(":").map { it.toIntOrNull() ?: 0 }
                platform.setReminder(on, h, m) { active -> setFlag(Keys.REMINDER, active) }
            }
            if (reminder) ConfigRow(tr("Reminder time"), reminderTime ?: "21:00") { timeDialog = true }
            ConfigToggle(tr("Quick add in notification bar"), tr("Expense / Income / Transfer buttons"), quickAdd) { on ->
                platform.setQuickAddNotification(on) { active -> setFlag(Keys.QUICK_ADD_NOTIFICATION, active) }
            }
            ConfigRow(tr("Bank SMS"), if (capture) tr("On") else tr("Off")) { nav.push(Screen.MessageSettings) }
            ConfigToggle(tr("Save location"), tr("Attach your current location to new entries"), location) { on ->
                if (on) locationDisclosure = true else setFlag(Keys.LOCATION, false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (locationDisclosure) {
        DisclosureDialog(tr("Save location"),
            tr("When you add a new entry, MoneyTracker saves where you are (latitude and longitude) with it. " +
                "Location is read only while you add an entry, never in the background, and stays on your phone."),
            { locationDisclosure = false }) { locationDisclosure = false; setFlag(Keys.LOCATION, true) }
    }
    if (languageDialog) {
        ChoiceDialog(tr("Language"), I18n.LANGUAGES.map { (tag, name) -> tag.ifEmpty { "system" } to (if (tag.isEmpty()) tr(name) else name) }, { languageDialog = false }) { tag ->
            put(Keys.LANGUAGE, if (tag == "system") "" else tag); languageDialog = false
        }
    }
    if (accentDialog) {
        AlertDialog(
            onDismissRequest = { accentDialog = false },
            title = { Text(tr("Accent colour")) },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    // "Follow system" only where the OS provides wallpaper colours (Android 12+).
                    AccentChoice.entries.filter { it != AccentChoice.SYSTEM || systemAccent != null }.forEach { a ->
                        val c = a.tones().let { androidx.compose.ui.graphics.Color(if (darkTheme) it.dark else it.light) }
                        Box(
                            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                                .clickable { put(Keys.ACCENT, a.name); accentDialog = false },
                            contentAlignment = Alignment.Center,
                        ) { if (a == accentChoice) Icon(Icons.Filled.Check, tr(a.label), tint = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { accentDialog = false }) { Text(tr("Close")) } },
        )
    }

    choice?.let { key ->
        val options: List<Pair<String, String>> = CHOICES[key] ?: when (key) {
            Keys.WEEKEND_SHIFT -> listOf("NONE" to tr("Keep the date"), "BEFORE" to tr("Move to the Friday before"), "AFTER" to tr("Move to the Monday after"))
            Keys.WEEK_START -> listOf("SUNDAY", "MONDAY", "SATURDAY").map { it to tr(it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
            else -> listOf("DAILY", "CALENDAR", "MONTHLY", "TOTAL", "NOTE").map { it to tr(it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
        }
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(CHOICE_TITLES[key] ?: when (key) { Keys.WEEKEND_SHIFT -> tr("Start date on a weekend"); Keys.WEEK_START -> tr("Week starts on"); else -> tr("Open the app on") }) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable { put(key, value); choice = null }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choice = null }) { Text(tr("Cancel")) } },
        )
    }

    if (timeDialog) {
        var text by remember { mutableStateOf(reminderTime ?: "21:00") }
        val parts = text.split(":").map { it.toIntOrNull() }
        val valid = parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59
        AlertDialog(
            onDismissRequest = { timeDialog = false },
            title = { Text(tr("Reminder time (24h, HH:mm)")) },
            text = { OutlinedTextField(text, { text = it.take(5) }, singleLine = true, isError = !valid) },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    put(Keys.REMINDER_TIME, "${parts[0].toString().padStart(2, '0')}:${parts[1].toString().padStart(2, '0')}")
                    platform.setReminder(true, parts[0]!!, parts[1]!!) {}
                    timeDialog = false
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text(tr("Cancel")) } },
        )
    }

    editing?.let { key ->
        val initial = when (key) {
            Keys.MONTH_START_DAY -> startDay.toString()
            Keys.CURRENCY_SYMBOL -> currency.symbol
            else -> currency.decimals.toString()
        }
        var text by remember(key) { mutableStateOf(initial) }
        val valid = when (key) {
            Keys.MONTH_START_DAY -> text.toIntOrNull() in 1..31
            Keys.CURRENCY_DECIMALS -> text.toIntOrNull() in 0..3
            else -> text.isNotBlank() && text.length <= 4
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(when (key) { Keys.MONTH_START_DAY -> tr("Monthly start date (1–31)"); Keys.CURRENCY_SYMBOL -> tr("Currency symbol"); else -> tr("Decimal places (0–3)") }) },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = if (key == Keys.CURRENCY_SYMBOL) KeyboardType.Text else KeyboardType.Number))
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    scope.launch { withContext(repo.io) { repo.putSetting(key, text.trim()) } }
                    editing = null
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
internal fun ConfigSection(title: String) {
    Column {
        Text(title, Modifier.fillMaxWidth().background(PageBackground).padding(start = 24.dp, top = 22.dp, bottom = 8.dp),
            color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        HorizontalDivider(color = DividerColor)
    }
}

@Composable
internal fun ConfigToggle(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable { onChange(!checked) }.padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, fontSize = 12.sp, color = SubtleText)
        }
        AppSwitch(checked, onChange)
    }
}

@Composable
internal fun ConfigRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        if (value != null) Text(value, color = Accent)
    }
}

// ───────────────────────── categories

@Composable
fun CategoriesScreen(initialKind: CategoryKind) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState(emptyList())
    var kind by remember { mutableStateOf(initialKind) }
    val accounts by repo.accounts.collectAsState(emptyList())
    val accountNames by repo.accountNames.collectAsState(emptyMap())
    val useSubcategories by repo.useSubcategories.collectAsState(false)
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(if (kind == CategoryKind.INCOME) tr("Income categories") else tr("Expense categories"), nav::pop)
            UnderlineTabs(listOf(tr("Income"), tr("Expense")), kind.ordinal) { kind = CategoryKind.entries[it] }
            LazyColumn(Modifier.fillMaxSize()) {
                if (!useSubcategories) item(key = "hint") {
                    Text(tr("Sub-categories are off. Turn them on in Configuration to add or show them."),
                        Modifier.fillMaxWidth().background(PageBackground).padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = SubtleText)
                }
                items(flattenCategories(categories.filter { it.kind == kind }).filter { useSubcategories || it.parentId == null }, key = { it.id }) { c ->
                    Row(
                        Modifier.fillMaxWidth().background(if (c.parentId == null) SurfaceColor else PageBackground).clickable { editing = c }
                            .padding(start = if (c.parentId != null) 52.dp else 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(c.name, fontWeight = if (c.parentId == null) FontWeight.Medium else null,
                                color = if (c.parentId == null) Ink else SubtleText)
                            c.defaultAccountId?.let { accountNames[it] }?.let { Text(tr("Default: {0}", it), fontSize = 12.sp, color = IncomeColor) }
                        }
                        // Reorder among siblings (main categories, or the subs of one main).
                        val siblings = categories.filter { it.kind == c.kind && it.parentId == c.parentId }
                        val i = siblings.indexOf(c)
                        fun move(delta: Int) {
                            val order = siblings.map { it.id }.toMutableList()
                            val j = i + delta
                            if (j !in order.indices) return
                            order[i] = order[j].also { order[j] = order[i] }
                            scope.launch { withContext(repo.io) { repo.reorderCategories(order) } }
                        }
                        IconButton(onClick = { move(-1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, tr("Move {0} up", c.name), tint = SubtleText) }
                        IconButton(onClick = { move(1) }, enabled = i < siblings.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, tr("Move {0} down", c.name), tint = SubtleText) }
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Outlined.Delete, tr("Delete {0}", c.name), tint = SubtleText) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Category("", "", kind, null, categories.size) }
    }

    editing?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var parentId by remember(c) { mutableStateOf(c.parentId) }
        var defaultAccount by remember(c) { mutableStateOf(c.defaultAccountId) }
        val parents = categories.filter { it.kind == c.kind && it.parentId == null && it.id != c.id }
        val hasChildren = categories.any { it.parentId == c.id }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (c.id.isEmpty()) tr("Add category") else tr("Edit category")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name (emoji welcome 🍜)")) })
                    val inherited = parentId?.let { p -> categories.firstOrNull { it.id == p }?.defaultAccountId }?.let { accountNames[it] }
                    Picker(
                        tr("Default account"), listOf<String?>(null) + accounts.map { it.id }, defaultAccount,
                        { id -> id?.let { accountNames[it] } ?: inherited?.let { tr("Same as main category ({0})", it) } ?: tr("None") },
                        { defaultAccount = it },
                    )
                    if (!hasChildren && useSubcategories) {
                        Picker(tr("Parent (optional)"), listOf<Category?>(null) + parents, parents.firstOrNull { it.id == parentId },
                            { it?.name ?: tr("None — main category") }, { parentId = it?.id })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val saved = c.copy(id = c.id.ifEmpty { newId() }, name = name.trim(), parentId = parentId, defaultAccountId = defaultAccount)
                    scope.launch { withContext(repo.io) { repo.saveCategory(saved) } }
                    editing = null
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel")) } },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete “{0}”?", c.name)) },
            text = { Text(tr("Sub-categories are removed too. Existing transactions keep their amounts but show as uncategorized.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(repo.io) { repo.deleteCategory(c.id) } }
                    deleting = null
                }) { Text(tr("Delete"), color = DangerColor) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
}

// ───────────────────────── recurring

@Composable
fun RecurringScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val scope = rememberCoroutineScope()
    val rules by repo.rules.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val accountName by repo.accountNames.collectAsState(emptyMap())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val timing by remember(repo) { repo.setting(Keys.REPEAT_TIMING) }.collectAsState(null)
    var editing by remember { mutableStateOf<RecurringRule?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var timingDialog by remember { mutableStateOf(false) }

    editing?.let { r -> RuleEditDialog(r, accounts, categories, onDismiss = { editing = null }) { updated ->
        editing = null
        scope.launch { withContext(repo.io) { repo.saveRule(updated); repo.generateRecurring() } }
    } }
    if (timingDialog) {
        ChoiceDialog(tr("Timing of reflection"), CHOICES.getValue(Keys.REPEAT_TIMING), { timingDialog = false }) { v ->
            timingDialog = false
            scope.launch { withContext(repo.io) { repo.putSetting(Keys.REPEAT_TIMING, v); repo.generateRecurring() } }
        }
    }

    // Repeat settings: timing, then the rules grouped by Income / Expenses / Transfer with group totals.
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Repeat Setting"), nav::pop) {
            if (rules.isNotEmpty()) IconButton(onClick = { deleting = !deleting }) {
                Icon(Icons.Outlined.Delete, tr("Delete"), tint = if (deleting) DangerColor else androidx.compose.material3.LocalContentColor.current)
            }
            IconButton(onClick = {
                nav.push(Screen.EditTxn(preset = app.moneytracker.data.TxnDraft(app.moneytracker.data.DraftType.EXPENSE, repo.today(), "",
                    amount = 0, repeat = app.moneytracker.domain.RepeatType.MONTHLY)))
            }) { Icon(Icons.Outlined.Add, tr("Add")) }
        }
        HorizontalDivider(color = DividerColor)
        ConfigRow(tr("Timing of reflection"), CHOICES.getValue(Keys.REPEAT_TIMING).firstOrNull { it.first == (timing ?: "ON_DATE") }?.second) { timingDialog = true }
        if (rules.isEmpty()) {
            EmptyState(tr("No repeating transactions. Use “Rep/Inst.” when adding one."), Icons.Outlined.Repeat)
            return@Column
        }
        fun main(r: RecurringRule) = app.moneytracker.domain.Fx.toMain(r.amount, r.currency?.let { currencies[it] }, currency.decimals)
        val groups = listOf(
            tr("Income") to rules.filter { it.kind == TxnKind.INCOME },
            tr("Expenses") to rules.filter { it.kind == TxnKind.EXPENSE },
            tr("Transfer") to rules.filter { it.kind == TxnKind.TRANSFER_OUT },
        ).filter { it.second.isNotEmpty() }
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (title, list) ->
                item(key = "g$title") {
                    Row(Modifier.fillMaxWidth().background(PageBackground).padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                        Text(title, Modifier.weight(1f), color = SubtleText)
                        val kind = list.first().kind
                        Text(currency.format(list.sumOf { main(it) }), color = when (kind) {
                            TxnKind.INCOME -> IncomeColor; TxnKind.EXPENSE -> ExpenseColor; else -> Ink
                        })
                    }
                    HorizontalDivider(color = DividerColor)
                }
                items(list, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = r }
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (deleting) IconButton(onClick = { scope.launch { withContext(repo.io) { repo.deleteRule(r.id) } } }) {
                            Icon(Icons.Outlined.RemoveCircleOutline, tr("Stop this rule"), tint = DangerColor)
                        }
                        Column(Modifier.width(96.dp)) {
                            Text(r.nextDate.let { "${it.monthNumber}/${it.dayOfMonth}/${it.year % 100}" }, fontSize = 13.sp, color = SubtleText)
                            Text(tr(r.repeat.label), fontSize = 12.sp, color = SubtleText)
                        }
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The note keeps its room; the description takes what's left.
                                Text(r.note.ifBlank { categoryPath(r.categoryId, catById) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (r.memo.isNotBlank()) Text("  " + r.memo, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = SubtleText,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(when (r.kind) {
                                TxnKind.TRANSFER_OUT -> "${accountName[r.accountId]} → ${accountName[r.toAccountId]}"
                                else -> "${accountName[r.accountId].orEmpty()}  ${categoryPath(r.categoryId, catById)}"
                            }, fontSize = 13.sp, color = SubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.endDate?.let { Text(tr("Until: {0}", "${it.monthNumber}/${it.dayOfMonth}/${it.year % 100}"), fontSize = 12.sp, color = SubtleText) }
                        }
                        val refund = app.moneytracker.domain.isRefund(r.kind, r.amount)
                        Text(currency.format(if (refund) -main(r) else main(r), showSign = refund), color = when {
                            r.kind == TxnKind.INCOME || refund -> IncomeColor; r.kind == TxnKind.EXPENSE -> ExpenseColor; else -> Ink
                        })
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
            item {
                Text(tr("Tap a rule to change it. Stopping a rule keeps the transactions it already created."), Modifier.padding(20.dp), fontSize = 13.sp, color = SubtleText)
            }
        }
    }
}

// ───────────────────────── backup

@Composable
fun BackupScreen() {
    val nav = LocalNavigator.current
    val backup = LocalBackup.current
    val local by backup.localBackups.collectAsState()
    val status by backup.status.collectAsState()
    val pendingImport by backup.pendingImport.collectAsState()
    var restoring by remember { mutableStateOf<LocalBackup?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<Boolean?>(null) }   // true = everything, false = entries only

    pendingImport?.let { ImportPreviewDialog(it, onCancel = backup::cancelImport, onImport = backup::confirmImport) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Backup"), nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Everything stays on this device. A snapshot is taken automatically once a day (last 10 kept). " +
                        "Export a copy to another folder, SD card or cloud drive to protect against losing the phone."),
                        fontSize = 13.sp, color = SubtleText)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BackupButton(tr("Back up now"), Modifier.weight(1f), filled = true, onClick = backup::backupNow)
                        BackupButton(tr("Export file"), Modifier.weight(1f), onClick = backup::exportToFile)
                    }
                    BackupButton(tr("Share backup (e-mail, chat…)"), Modifier.fillMaxWidth(), onClick = backup::shareBackup)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BackupButton(tr("Restore file"), Modifier.weight(1f)) { confirmImport = true }
                        BackupButton(tr("Export CSV"), Modifier.weight(1f), onClick = backup::exportCsv)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BackupButton(tr("Export Excel"), Modifier.weight(1f), onClick = backup::exportExcel)
                        BackupButton(tr("Import CSV / Excel"), Modifier.weight(1f), onClick = backup::importSpreadsheet)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BackupButton(tr("Export photos"), Modifier.weight(1f), onClick = backup::exportPhotos)
                        BackupButton(tr("Import photos"), Modifier.weight(1f), onClick = backup::importPhotos)
                    }
                    Text(tr("Import adds transactions (columns: Date, Type, Account, Category, Subcategory, Amount, Note…). " +
                        "Missing accounts and categories are created. A snapshot is taken first. " +
                        "Receipt photos travel separately: export them as a .zip next to the backup file."),
                        fontSize = 12.sp, color = SubtleText)
                    status?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it, Modifier.weight(1f), color = if (it.startsWith("Failed")) DangerColor else IncomeColor)
                            TextButton(onClick = backup::clearStatus) { Text(tr("OK")) }
                        }
                    }
                }
                ConfigSection(tr("Money Manager"))
                Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackupButton(tr("Export for Money Manager (.mmbak)"), Modifier.fillMaxWidth(), onClick = backup::exportMoneyManager)
                    Text(tr("Restore the file in Money Manager under Backup › Data Recovery. It replaces the data there. " +
                        "Accounts, entries (refunds as negative expenses), categories, budgets, repeats, tags and notes are included; " +
                        "photos, bookmarks and detected SMS are not. MoneyTracker is not affiliated with Realbyte."),
                        fontSize = 12.sp, color = SubtleText)
                }
                ConfigSection(tr("Reset"))
                Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BackupButton(tr("Clear entries"), Modifier.weight(1f)) { resetting = false }
                        BackupButton(tr("Reset everything"), Modifier.weight(1f)) { resetting = true }
                    }
                    Text(tr("A snapshot is saved first, so a reset can be undone from the list below."), fontSize = 12.sp, color = SubtleText)
                }
                ConfigSection(tr("Snapshots on this device"))
            }
            if (local.isEmpty()) item { Text(tr("No snapshots yet."), Modifier.padding(24.dp), color = SubtleText) }
            items(local, key = { it.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(backupLabel(b.name))
                        Text(tr("{0} KB", b.sizeBytes / 1024), fontSize = 12.sp, color = SubtleText)
                    }
                    TextButton(onClick = { restoring = b }) { Text(tr("Restore"), color = Accent) }
                    IconButton(onClick = { backup.deleteLocal(b.name) }) { Icon(Icons.Outlined.Delete, tr("Delete snapshot"), tint = SubtleText) }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }

    restoring?.let { b ->
        RestoreConfirm(tr("Restore “{0}”?", backupLabel(b.name)), onDismiss = { restoring = null }) {
            restoring = null; backup.restoreLocal(b.name)
        }
    }
    resetting?.let { everything ->
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text(if (everything) tr("Reset everything?") else tr("Clear all entries?")) },
            text = {
                Text(if (everything) tr("Deletes all entries, accounts, categories, budgets, repeats, bookmarks and tags, and returns to the starting setup. Passcode and settings stay.")
                else tr("Deletes all entries, budgets, repeats, bookmarks and detected SMS. Accounts and categories stay."))
            },
            confirmButton = { TextButton(onClick = { resetting = null; backup.reset(everything) }) { Text(if (everything) tr("Reset") else tr("Clear"), color = DangerColor) } },
            dismissButton = { TextButton(onClick = { resetting = null }) { Text(tr("Cancel")) } },
        )
    }
    if (confirmImport) {
        RestoreConfirm(tr("Restore from a file?"), onDismiss = { confirmImport = false }) {
            confirmImport = false; backup.importFromFile()
        }
    }
}

/** "pre-import-20261013-105723.mtbak" → "Before import · 13 Oct 2026, 10:57" (older backups end in .mmdb). */
private fun backupLabel(file: String): String {
    val base = file.removeSuffix(".mtbak").removeSuffix(".mmdb")
    val m = Regex("""^(.*)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}$""").find(base) ?: return base
    val (kind, y, mo, d, h, mi) = m.destructured
    val what = when (kind) {
        "auto" -> tr("Daily backup"); "manual" -> tr("Manual backup"); "pre-restore" -> tr("Before restore"); "pre-import" -> tr("Before import"); "before-reset" -> tr("Before reset")
        else -> kind.replaceFirstChar { it.uppercase() }
    }
    val month = runCatching { monthShort(mo.toInt()) }.getOrDefault(mo)
    return "$what · ${d.toInt()} $month $y, $h:$mi"
}

@Composable
private fun BackupButton(label: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.height(46.dp)
            .then(if (filled) Modifier.background(Accent, shape) else Modifier.border(1.dp, BorderColor, shape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Color.White else Ink) }
}

@Composable
private fun RestoreConfirm(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(tr("Your current data will be replaced. A safety snapshot of it is taken first, so you can undo this from the list.")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Restore"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Multiple-choice settings: stored value → label. The first option is the default. */
private val CHOICES: Map<String, List<Pair<String, String>>> get() = mapOf(
    Keys.SWIPE to listOf("DATE" to tr("Change month"), "TAB" to tr("Change tab"), "NONE" to tr("Off")),
    Keys.COLOR_SET to listOf("A" to tr("Income green, expense red"), "B" to tr("Income red, expense green")),
    Keys.TIME_INPUT to listOf("NONE" to tr("Off"), "INPUT" to tr("Record time"), "LIST" to tr("Record and show in lists")),
    Keys.TIME_SORT to listOf("DESC" to tr("Latest first"), "ASC" to tr("Earliest first")),
    Keys.INPUT_ORDER to listOf("AMOUNT" to tr("Amount first"), "ACCOUNT" to tr("Account first")),
    Keys.THEME to listOf("SYSTEM" to tr("Follow system"), "LIGHT" to tr("Light"), "DARK" to tr("Dark")),
    Keys.REPEAT_TIMING to listOf("ON_DATE" to tr("On the date"), "MONTH_START" to tr("On the first day of the month")),
    Keys.WIDGET_THEME to listOf("SYSTEM" to tr("Follow system"), "LIGHT" to tr("Light"), "DARK" to tr("Dark")),
    Keys.WIDGET_OPACITY to listOf("100", "80", "60", "40", "20").map { it to tr("{0}% opaque", it) },
)
private val CHOICE_TITLES: Map<String, String> get() = mapOf(
    Keys.SWIPE to tr("Swipe left / right"), Keys.COLOR_SET to tr("Income / expense colors"), Keys.TIME_INPUT to tr("Time input"),
    Keys.TIME_SORT to tr("Order within a day"), Keys.INPUT_ORDER to tr("Input order"), Keys.THEME to tr("Style"),
    Keys.REPEAT_TIMING to tr("Add repeat entries"), Keys.WIDGET_THEME to tr("Widget style"), Keys.WIDGET_OPACITY to tr("Widget background"),
)
private fun choiceLabel(key: String, value: String?): String {
    val options = CHOICES.getValue(key)
    return (options.firstOrNull { it.first == value } ?: options.first()).second
}

/** Change a repeating rule: what it adds and when. Entries already added stay as they are. */
@Composable
private fun RuleEditDialog(
    rule: RecurringRule,
    accounts: List<Account>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (RecurringRule) -> Unit,
) {
    val fmt = LocalCurrencies.current[rule.currency]?.format ?: LocalCurrency.current
    // A repeating refund is stored negative; it is edited as a positive amount like in the entry editor.
    val sign = if (app.moneytracker.domain.isRefund(rule.kind, rule.amount)) -1 else 1
    var amount by remember { mutableStateOf(fmt.toInput(rule.amount * sign)) }
    var note by remember { mutableStateOf(rule.note) }
    var memo by remember { mutableStateOf(rule.memo) }
    var accountId by remember { mutableStateOf(rule.accountId) }
    var toAccountId by remember { mutableStateOf(rule.toAccountId) }
    var categoryId by remember { mutableStateOf(rule.categoryId) }
    var repeat by remember { mutableStateOf(rule.repeat) }
    var next by remember { mutableStateOf(rule.nextDate) }
    var end by remember { mutableStateOf(rule.endDate) }
    var picking by remember { mutableStateOf<String?>(null) }
    val transfer = rule.kind == TxnKind.TRANSFER_OUT
    val catKind = if (rule.kind == TxnKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val visible = accounts.filter { !it.hidden || it.id == accountId || it.id == toAccountId }
    val parsed = fmt.parse(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Edit repeat")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text(tr("Amount")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Picker(if (transfer) tr("From") else tr("Account"), visible, visible.firstOrNull { it.id == accountId }, { it.name }, { accountId = it.id }, Modifier.fillMaxWidth())
                if (transfer) {
                    val targets = visible.filter { it.id != accountId }
                    Picker(tr("To"), targets, targets.firstOrNull { it.id == toAccountId }, { it.name }, { toAccountId = it.id }, Modifier.fillMaxWidth())
                } else {
                    val options = flattenCategories(categories.filter { it.kind == catKind })
                    Picker(tr("Category"), options, options.firstOrNull { it.id == categoryId }, { it.name }, { categoryId = it.id }, Modifier.fillMaxWidth())
                }
                OutlinedTextField(note, { note = it }, label = { Text(tr("Note")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(memo, { memo = it }, label = { Text(tr("Description")) }, modifier = Modifier.fillMaxWidth())
                Picker(tr("Repeat"), RepeatType.entries, repeat, { tr(it.label) }, { repeat = it }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Next: {0} {1}", next.short(), next.year), Modifier.weight(1f))
                    TextButton(onClick = { picking = "next" }) { Text(tr("Change")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Until: {0}", end?.let { "${it.short()} ${it.year}" } ?: tr("no end")), Modifier.weight(1f))
                    TextButton(onClick = { picking = "end" }) { Text(tr("Change")) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed > 0 && (!transfer || toAccountId != null), onClick = {
                onSave(rule.copy(amount = parsed?.let { it * sign } ?: rule.amount, note = note.trim(), memo = memo.trim(), accountId = accountId,
                    toAccountId = if (transfer) toAccountId else null, categoryId = if (transfer) null else categoryId,
                    repeat = repeat, nextDate = next, endDate = end))
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    DatePickerHost(picking == "next", next, { picking = null }, { d -> d?.let { next = it } })
    DatePickerHost(picking == "end", end ?: next, { picking = null }, { end = it }, clearable = true)
}

/** Spreadsheet import check: every row read from the file, untick any to leave out, then import. */
@Composable
private fun ImportPreviewDialog(parsed: app.moneytracker.data.TabularImport.Parsed, onCancel: () -> Unit, onImport: (Set<Int>) -> Unit) {
    val currency = LocalCurrency.current
    var keep by remember(parsed) { mutableStateOf(parsed.rows.indices.toSet()) }
    androidx.compose.ui.window.Dialog(onCancel, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SurfaceColor)) {
            BackTopBar(tr("Import {0} of {1}", keep.size, parsed.rows.size), onCancel) {
                TextButton(enabled = keep.isNotEmpty(), onClick = { onImport(keep) }) { Text(tr("Import"), color = Accent) }
            }
            HorizontalDivider(color = DividerColor)
            if (parsed.errors.isNotEmpty()) {
                Text(tr("{0} rows couldn't be read and will be skipped: {1}", parsed.errors.size, parsed.errors.take(3).joinToString("; ")),
                    Modifier.fillMaxWidth().background(PageBackground).padding(16.dp), color = DangerColor, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(keep.size == parsed.rows.size,
                    { keep = if (it) parsed.rows.indices.toSet() else emptySet() })
                Text(tr("All"), color = SubtleText)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(parsed.rows.indices.toList(), key = { it }) { i ->
                    val r = parsed.rows[i]
                    Row(Modifier.fillMaxWidth().clickable { keep = if (i in keep) keep - i else keep + i }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(i in keep, { keep = if (it) keep + i else keep - i })
                        Column(Modifier.weight(1f)) {
                            Text("${r.date.short()} ${r.date.year} · " + when (r.type) {
                                app.moneytracker.data.DraftType.TRANSFER -> "${r.account} → ${r.toAccount}"
                                else -> listOf(r.category, r.subcategory).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "—" } + " · ${r.account}"
                            }, fontSize = 14.sp, maxLines = 1)
                            if (r.note.isNotBlank()) Text(r.note, fontSize = 12.sp, color = SubtleText, maxLines = 1)
                        }
                        Text((r.currency?.let { "$it " } ?: "") + currency.format(r.amount), color = when (r.type) {
                            app.moneytracker.data.DraftType.INCOME, app.moneytracker.data.DraftType.REFUND -> IncomeColor
                            app.moneytracker.data.DraftType.EXPENSE -> ExpenseColor
                            else -> Ink
                        })
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
    }
}
