@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.moneytracker.ui

import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.defaultAccountFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

private const val PHOTO_NOTICE_KEY = "photoNoticeShown"

private enum class Panel { NONE, AMOUNT, FEE, CATEGORY, ACCOUNT, TO_ACCOUNT }

@Composable
fun EditTxnScreen(screen: Screen.EditTxn) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())

    var existing by remember { mutableStateOf<Txn?>(null) }
    // Balance adjustments edit as Increase / Decrease with date, amount, account and note.
    var adjustUp by remember { mutableStateOf(true) }
    // Picking Income / Expense / Transfer on an adjustment turns it into a normal entry.
    var convertedFromAdjustment by remember { mutableStateOf(false) }
    val accountFirst = remember { repo.settingNow(app.moneytracker.data.MoneyRepository.Keys.INPUT_ORDER) == "ACCOUNT" }
    var type by remember { mutableStateOf(screen.preset?.type ?: DraftType.EXPENSE) }
    var date by remember { mutableStateOf(screen.date ?: screen.preset?.date ?: repo.today()) }
    var accountId by remember { mutableStateOf(screen.preset?.accountId?.ifBlank { null }) }
    // True once the account came from the user (or an existing entry / preset); category defaults never override it.
    var accountChosen by remember { mutableStateOf(accountId != null) }
    var toAccountId by remember { mutableStateOf(screen.preset?.toAccountId) }
    var categoryId by remember { mutableStateOf(screen.preset?.categoryId) }
    val currencies = LocalCurrencies.current
    val platform = LocalPlatform.current
    var currencyCode by remember { mutableStateOf(screen.preset?.currency) }
    val enteredFmt = currencyCode?.let { currencies[it]?.format } ?: currency
    var amountText by remember { mutableStateOf(screen.preset?.amount?.takeIf { it > 0 }?.let { enteredFmt.toInput(it) }.orEmpty()) }
    var tagIds by remember { mutableStateOf(screen.preset?.tagIds.orEmpty()) }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lng by remember { mutableStateOf<Double?>(null) }
    var existingPhotos by remember { mutableStateOf(emptyList<app.moneytracker.domain.Photo>()) }
    var newPhotos by remember { mutableStateOf(emptyList<String>()) }
    var tagDialog by remember { mutableStateOf(false) }
    var bookmarkDialog by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var photoNotice by remember { mutableStateOf(false) }
    val tags by repo.tags.collectAsState(emptyList())
    val locationOn by remember(repo) { repo.flag(app.moneytracker.data.MoneyRepository.Keys.LOCATION) }.collectAsState(false)
    val capture = screen.captureId?.let { id -> repo.pendingCaptures.collectAsState(emptyList()).value.firstOrNull { it.id == id } }
    var feeText by remember { mutableStateOf("") }
    var showFee by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(screen.preset?.note.orEmpty()) }
    var memo by remember { mutableStateOf(screen.preset?.memo.orEmpty()) }
    var origin by remember { mutableStateOf(screen.preset?.origin.orEmpty()) }
    var installments by remember { mutableStateOf(screen.preset?.installments ?: 1) }
    var repeat by remember { mutableStateOf(screen.preset?.repeat) }
    var repeatEnd by remember { mutableStateOf(screen.preset?.repeatEnd) }
    var error by remember { mutableStateOf<String?>(null) }
    var panel by remember {
        mutableStateOf(if (screen.txnId == null && screen.preset == null) (if (accountFirst && accountId == null) Panel.ACCOUNT else Panel.AMOUNT) else Panel.NONE)
    }
    var datePicker by remember { mutableStateOf(false) }
    var repeatDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf(false) }
    val noteFocus = remember { FocusRequester() }
    var noteFocused by remember { mutableStateOf(false) }
    val timeInput by remember(repo) { repo.setting(app.moneytracker.data.MoneyRepository.Keys.TIME_INPUT) }.collectAsState(repo.settingNow(app.moneytracker.data.MoneyRepository.Keys.TIME_INPUT))
    val timeOn = timeInput == "INPUT" || timeInput == "LIST"
    // New entries get the current time when time input is on; edits keep what was stored.
    var timeOfDay by remember { mutableStateOf(screen.preset?.timeOfDay ?: if (screen.txnId == null && timeOn) nowMinutes() else null) }
    var timePicker by remember { mutableStateOf(false) }

    // Fill the form with the entry being edited (transfer pair / fee / installment plan, tags, photos) before the
    // first frame: a few indexed rows read in well under a millisecond, and loading later would flash the
    // new-entry defaults first.
    remember(screen.txnId) {
        val id = screen.txnId ?: return@remember
        val t = repo.txn(id) ?: return@remember
        val d = repo.draftFrom(t)
        existingPhotos = repo.photosNow(t.id)
        adjustUp = t.kind == TxnKind.ADJUST_UP
        existing = t
        type = d.type; date = d.date; accountId = d.accountId; toAccountId = d.toAccountId; accountChosen = true
        categoryId = if (d.type == DraftType.TRANSFER) null else d.categoryId
        currencyCode = d.currency
        val fmt = d.currency?.let { currencies[it]?.format } ?: currency
        amountText = fmt.toInput(d.amount)
        if (d.fee > 0) { feeText = fmt.toInput(d.fee); showFee = true }
        note = d.note; memo = d.memo; installments = d.installments; tagIds = d.tagIds; timeOfDay = d.timeOfDay; origin = d.origin
        lat = t.lat; lng = t.lng
    }
    // Then keep the photo strip current (photos added or removed while editing).
    LaunchedEffect(existing?.id) { existing?.let { t -> repo.photos(t.id).collect { existingPhotos = it } } }
    // New entries: capture location once if the user enabled it.
    LaunchedEffect(locationOn) {
        if (locationOn && screen.txnId == null && lat == null) platform.currentLocation({ a, b -> lat = a; lng = b }, {})
    }
    // Learned suggestion for the category from the note's words (tiny Naive Bayes, a few SQL rows).
    val suggestion by androidx.compose.runtime.produceState<app.moneytracker.data.MoneyRepository.Suggestion?>(null, note, type) {
        value = if (note.length < 3 || type == DraftType.TRANSFER) null
        else withContext(repo.io) { repo.suggest(app.moneytracker.domain.SmsParser.words(note)) }
    }

    // Past notes matching what is typed (only while the note field has focus).
    val noteMatches by androidx.compose.runtime.produceState(emptyList<app.moneytracker.data.MoneyRepository.NoteSuggestion>(), note, noteFocused, type) {
        value = if (!noteFocused || note.isBlank()) emptyList()
        else withContext(repo.io) { repo.noteSuggestions(note) }.filter { s ->
            when (type) {
                DraftType.INCOME -> s.kind == TxnKind.INCOME
                DraftType.EXPENSE, DraftType.REFUND -> s.kind == TxnKind.EXPENSE
                DraftType.TRANSFER -> s.kind == TxnKind.TRANSFER_OUT
            }
        }.take(4)
    }

    // Unsaved-changes guard: compare the form with what it held once loaded.
    fun formKey() = listOf(type, date, accountId, toAccountId, categoryId, amountText, feeText, note, memo, installments,
        repeat, repeatEnd, tagIds, currencyCode, newPhotos, existingPhotos.size, timeOfDay, adjustUp, convertedFromAdjustment).toString()
    var baseline by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(existing, accounts) { if (screen.txnId == null || existing != null) baseline = formKey() }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirtyState = androidx.compose.runtime.rememberUpdatedState(baseline != null && formKey() != baseline)
    val panelState = androidx.compose.runtime.rememberUpdatedState(panel)
    // Back closes an open keypad/picker first, then asks before throwing away edits.
    androidx.compose.runtime.DisposableEffect(nav) {
        val remove = nav.interceptBack {
            when {
                panelState.value != Panel.NONE -> { panel = Panel.NONE; true }
                dirtyState.value -> { confirmDiscard = true; true }
                else -> false
            }
        }
        onDispose { remove() }
    }

    // Existing entries open read-only (Delete / Copy / Bookmark); touching any field switches to editing.
    var viewing by remember { mutableStateOf(screen.txnId != null) }
    LaunchedEffect(panel, noteFocused, datePicker, timePicker, repeatDialog, tagDialog, dirtyState.value) {
        if (panel != Panel.NONE || noteFocused || datePicker || timePicker || repeatDialog || tagDialog || dirtyState.value) viewing = false
    }

    val isAdjustment = (existing?.kind == TxnKind.ADJUST_UP || existing?.kind == TxnKind.ADJUST_DOWN) && !convertedFromAdjustment
    val groupById = groups.associateBy { it.id }
    val accountById = accounts.associateBy { it.id }
    val isCreditCard = accountId?.let { accountById[it] }?.let { groupById[it.groupId]?.type?.isCreditCard } == true
    val catKind = if (type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val catById by repo.categoryById.collectAsState(emptyMap())
    val useSubcategories by repo.useSubcategories.collectAsState(false)

    fun nextAfterAmount() {
        panel = when {
            type == DraftType.TRANSFER && accountId == null -> Panel.ACCOUNT
            type == DraftType.TRANSFER && toAccountId == null -> Panel.TO_ACCOUNT
            type != DraftType.TRANSFER && categoryId == null -> Panel.CATEGORY
            type != DraftType.TRANSFER && accountId == null -> Panel.ACCOUNT
            else -> Panel.NONE
        }
    }

    fun resetForNext() {
        accountChosen = false
        timeOfDay = if (timeOn) nowMinutes() else null
        amountText = ""; feeText = ""; showFee = false; note = ""; memo = ""; categoryId = null; tagIds = emptyList(); newPhotos = emptyList()
        installments = 1; repeat = null; repeatEnd = null; error = null; panel = if (accountFirst) Panel.ACCOUNT else Panel.AMOUNT
    }

    fun save(continueAfter: Boolean) {
        // A half-typed calculation ("120+30") counts as its result.
        fun value(t: String) = if (app.moneytracker.domain.Calc.hasOperator(t)) app.moneytracker.domain.Calc.evaluate(t, enteredFmt.decimals) ?: t else t
        val amount = enteredFmt.parse(value(amountText))
        val fee = if (type == DraftType.TRANSFER && showFee && feeText.isNotBlank()) enteredFmt.parse(value(feeText)) else 0L
        error = when {
            amount == null || amount <= 0 -> tr("Enter an amount")
            fee == null -> tr("Fee is not a valid amount")
            accountId == null -> tr("Choose an account")
            type == DraftType.TRANSFER && toAccountId == null -> tr("Choose the destination account")
            type == DraftType.TRANSFER && toAccountId == accountId -> tr("Pick two different accounts")
            else -> null
        }
        if (error != null) return
        val adj = existing
        if (adj != null && isAdjustment) {
            scope.launch {
                withContext(repo.io) { repo.updateAdjustment(adj, date, amount!!, accountId!!, adjustUp, note.trim(), memo.trim(), timeOfDay) }
                platform.refreshWidgets(); nav.pop()
            }
            return
        }
        val draft = TxnDraft(
            type, date, accountId!!, if (type == DraftType.TRANSFER) toAccountId else null,
            if (type == DraftType.TRANSFER) null else categoryId,
            amount!!, fee ?: 0, note.trim(), memo.trim(),
            if (type == DraftType.EXPENSE && isCreditCard) installments else 1,
            if (existing == null) repeat else null, repeatEnd,
            currency = currencyCode, tagIds = tagIds, lat = lat, lng = lng, timeOfDay = timeOfDay, origin = origin,
        )
        val photosToAttach = existingPhotos.map { it.fileName } + newPhotos
        scope.launch {
            withContext(repo.io) {
                val e = existing
                val id = if (e == null) repo.create(draft) else repo.update(e, draft)
                // Photos follow the (possibly new) row id; recurring rules get none.
                if (draft.repeat == null) photosToAttach.forEach { repo.addPhoto(id, it) }
                repo.learnFromDraft(draft)
                capture?.let { repo.completeCapture(it, draft, id) }
            }
            platform.refreshWidgets()
            if (continueAfter) { resetForNext(); baseline = formKey() } else nav.pop()
        }
    }

    val title = if (isAdjustment) (if (adjustUp) tr("Income Balance") else tr("Expense Balance")) else when (type) { DraftType.INCOME -> tr("Income"); DraftType.EXPENSE -> tr("Expense"); DraftType.REFUND -> tr("Refund"); DraftType.TRANSFER -> tr("Transfer") }
    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        BackTopBar(title, { nav.back() }) {
            if (!isAdjustment) IconButton(onClick = { bookmarkDialog = true }) { Icon(Icons.Outlined.StarOutline, tr("Save as bookmark")) }
            val e = existing
            if (e != null) {
                if (!isAdjustment) IconButton(onClick = { copyDialog = true }) { Icon(Icons.Outlined.ContentCopy, tr("Copy")) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, tr("Delete")) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            // An adjustment has no type selected; choosing one converts it into a normal entry.
            // A refund is money coming in, so it lives on the Income tab (the "Refund" box on the category row).
            val tabs = listOf(DraftType.INCOME, DraftType.EXPENSE, DraftType.TRANSFER)
            TypeSegments(listOf(tr("Income"), tr("Expense"), tr("Transfer")),
                if (isAdjustment) -1 else tabs.indexOf(if (type == DraftType.REFUND) DraftType.INCOME else type), { i ->
                val t = tabs[i]
                if (isAdjustment) {
                    convertedFromAdjustment = true; type = t
                    categoryId = if (t == DraftType.TRANSFER) null else app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
                } else if (t != type && !(t == DraftType.INCOME && type == DraftType.REFUND)) {
                    // Refund → Expense keeps the (expense) category.
                    val sameCategories = t == DraftType.EXPENSE && type == DraftType.REFUND
                    type = t; if (!sameCategories) categoryId = null; if (t != DraftType.TRANSFER) toAccountId = null
                }
            })
            Spacer(Modifier.height(12.dp))

            FormRow("Date", false, onClick = { panel = Panel.NONE; datePicker = true }, trailing = if (isAdjustment) null else ({
                Column(
                    Modifier.clickable { panel = Panel.NONE; repeatDialog = true }.padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Repeat, null, tint = if (repeat != null || installments > 1) Accent else SubtleText, modifier = Modifier.size(20.dp))
                    Text(tr("Rep/Inst."), fontSize = 10.sp, color = SubtleText)
                }
            })) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(date.numeric())
                        if (timeOn) Text(
                            timeOfDay?.let { formatTime(it) } ?: tr("Add time"),
                            Modifier.padding(start = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { panel = Panel.NONE; timePicker = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (timeOfDay == null) SubtleText else Ink,
                        )
                    }
                    val extra = listOfNotNull(repeat?.label?.let { tr(it) }, if (installments > 1) tr("{0} installments", installments) else null)
                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), color = Accent, fontSize = 12.sp)
                }
            }
            FormRow("Amount", panel == Panel.AMOUNT, onClick = { focus.clearFocus(); panel = Panel.AMOUNT }, trailing = if (type == DraftType.TRANSFER) ({
                Text(
                    tr("Fees"),
                    Modifier.border(1.dp, if (showFee) Accent else SubtleText, RoundedCornerShape(4.dp))
                        .clickable { showFee = !showFee; if (showFee) panel = Panel.FEE else feeText = "" }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (showFee) Accent else InkSoft,
                )
            }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(amountDisplay(amountText, panel == Panel.AMOUNT, enteredFmt), Modifier.weight(1f))
                    if (currencies.isNotEmpty() && !isAdjustment) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            Text(currencyCode ?: tr("Main"), Modifier.border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp)
                            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("${currency.symbol}  ${tr("Main currency")}") }, onClick = { currencyCode = null; open = false })
                                currencies.values.forEach { c ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("${c.symbol}  ${c.code}") }, onClick = { currencyCode = c.code; open = false })
                                }
                            }
                        }
                    }
                }
            }
            if (type == DraftType.TRANSFER && showFee) {
                FormRow("Fees", panel == Panel.FEE, onClick = { focus.clearFocus(); panel = Panel.FEE }) {
                    Text(amountDisplay(feeText, panel == Panel.FEE, enteredFmt))
                }
            }
            if (type == DraftType.TRANSFER) {
                FormRow("From", panel == Panel.ACCOUNT, onClick = { focus.clearFocus(); panel = Panel.ACCOUNT }, trailing = {
                    IconButton(onClick = { val a = accountId; accountId = toAccountId; toAccountId = a }) {
                        Icon(Icons.Outlined.SwapVert, tr("Swap accounts"), tint = SubtleText)
                    }
                }) { Text(accountId?.let { accountById[it]?.name }.orEmpty()) }
                FormRow("To", panel == Panel.TO_ACCOUNT, onClick = { focus.clearFocus(); panel = Panel.TO_ACCOUNT }) {
                    Text(toAccountId?.let { accountById[it]?.name }.orEmpty())
                }
            } else {
                if (isAdjustment) FormRow("Category", false, onClick = {}) { Text(tr(app.moneytracker.domain.MODIFIED_BALANCE_NAME)) }
                else FormRow("Category", panel == Panel.CATEGORY, onClick = { focus.clearFocus(); panel = Panel.CATEGORY },
                    // Income tab only: a refund books money back against an expense category.
                    trailing = if (type == DraftType.INCOME || type == DraftType.REFUND) ({
                        val refund = type == DraftType.REFUND
                        // Long-press (or hover with a mouse) explains what the box does.
                        androidx.compose.material3.TooltipBox(
                            positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = {
                                PlainTooltip(Modifier.widthIn(max = 260.dp)) {
                                    Text(tr("Money back for a purchase, like a returned order. It lowers spending in the expense category instead of counting as income."))
                                }
                            },
                            state = androidx.compose.material3.rememberTooltipState(),
                        ) {
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                    type = if (refund) DraftType.INCOME else DraftType.REFUND; categoryId = null
                                    if (panel == Panel.NONE) panel = Panel.CATEGORY
                                }.padding(end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(refund, null, Modifier.size(36.dp))
                                Text(tr("Refund"), fontSize = 13.sp, color = if (refund) IncomeColor else SubtleText)
                            }
                        }
                    }) else null,
                ) {
                    val sug = suggestion?.categoryId?.takeIf { categoryId == null && catById[it]?.kind == catKind }
                    if (categoryId == null && sug != null) {
                        Text(tr("Suggested: {0}  ✓", categoryPath(sug, catById)), Modifier.clickable { categoryId = sug }, color = IncomeColor)
                    } else Text(categoryId?.let { categoryPath(it, catById) }.orEmpty())
                }
                FormRow("Account", panel == Panel.ACCOUNT, onClick = { focus.clearFocus(); panel = Panel.ACCOUNT }) {
                    Text(accountId?.let { accountById[it]?.name }.orEmpty())
                }
            }
            FormRow("Note", false, onClick = { noteFocus.requestFocus() }) {
                BasicTextField(
                    note, { note = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(noteFocus).onFocusChanged {
                        noteFocused = it.isFocused
                        if (it.isFocused) panel = Panel.NONE
                    },
                )
            }
            // Autocomplete: picking a past note also brings back its category and (unless already chosen) account.
            if (noteMatches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(start = 104.dp, end = 16.dp).clip(RoundedCornerShape(12.dp)).background(PageBackground)) {
                    noteMatches.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                note = m.note
                                if (type != DraftType.TRANSFER && m.categoryId != null && catById[m.categoryId]?.kind == catKind) {
                                    categoryId = m.categoryId
                                }
                                if (!accountChosen && m.accountId != null && accountById[m.accountId] != null) { accountId = m.accountId; accountChosen = true }
                                focus.clearFocus()
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(m.note, Modifier.weight(1f), color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val hint = if (type == DraftType.TRANSFER) null else m.categoryId?.let { catById[it]?.name }
                            if (hint != null) Text(hint, color = SubtleText, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (!isAdjustment) FormRow("Tags", false, onClick = { panel = Panel.NONE; tagDialog = true }) {
                val names = tags.filter { it.id in tagIds }.joinToString(" ") { "#" + it.name }
                Text(names.ifEmpty { "" }, color = IncomeColor)
            }
            if (lat != null && lng != null) {
                FormRow("Place", false, onClick = { platform.openMap(lat!!, lng!!) }, trailing = {
                    IconButton(onClick = { lat = null; lng = null }) { Icon(Icons.Filled.Close, tr("Remove location"), tint = SubtleText) }
                }) { Text("📍 ${((lat!! * 10000).toLong() / 10000.0)}, ${((lng!! * 10000).toLong() / 10000.0)}", color = SubtleText) }
            }
            run {  // description for every kind; photos not for adjustments
                Spacer(Modifier.height(12.dp).fillMaxWidth().background(PageBackground))
                // Description field and photo button side by side (they used to overlap, and the field swallowed the taps).
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f).padding(top = 12.dp)) {
                        if (memo.isEmpty()) Text(tr("Description"), color = SubtleText)
                        BasicTextField(
                            memo, { memo = it },
                            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { if (it.isFocused) panel = Panel.NONE },
                        )
                    }
                    if (!isAdjustment) Box {
                        IconButton(onClick = {
                            focus.clearFocus(); panel = Panel.NONE
                            if (repo.settingNow(PHOTO_NOTICE_KEY) == "1") photoMenu = true else photoNotice = true
                        }) {
                            Icon(Icons.Outlined.PhotoCamera, tr("Add photo"), tint = SubtleText)
                        }
                        androidx.compose.material3.DropdownMenu(photoMenu, { photoMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Take photo")) }, onClick = { photoMenu = false; platform.takePhoto { newPhotos = newPhotos + it } })
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Choose from gallery")) }, onClick = { photoMenu = false; platform.pickPhoto { newPhotos = newPhotos + it } })
                        }
                    }
                }
                // The bank message this entry came from, read-only.
                if (origin.isNotBlank() && repo.settingNow(app.moneytracker.data.MoneyRepository.Keys.SMS_SHOW_ORIGINAL) != "0") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(tr("Original message"), color = SubtleText, fontSize = 12.sp)
                        Text(origin, Modifier.padding(top = 2.dp), color = InkSoft, fontSize = 13.sp)
                    }
                }
                // Receipt previews: one photo fills the width; several scroll sideways. Removing takes effect on Save.
                val allPhotos = if (isAdjustment) emptyList() else existingPhotos.map { it.id to it.fileName } + newPhotos.map { it to it }
                if (allPhotos.isNotEmpty()) {
                    var fullScreen by remember { mutableStateOf<Int?>(null) }
                    fullScreen?.let { start -> PhotoViewer(allPhotos.map { it.second }, start) { fullScreen = null } }
                    @Composable
                    fun PhotoCard(i: Int, key: String, file: String, modifier: Modifier) {
                        Box(modifier.height(180.dp).background(PageBackground, RoundedCornerShape(10.dp)).clickable { fullScreen = i }) {
                            platform.PhotoImage(file, Modifier.fillMaxSize())
                            Box(
                                Modifier.align(Alignment.TopEnd).minimumInteractiveComponentSize().clickable {
                                    val p = existingPhotos.firstOrNull { it.id == key }
                                    if (p != null) existingPhotos = existingPhotos - p
                                    else { newPhotos = newPhotos - file; platform.deletePhotoFile(file) }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, tr("Remove photo {0}", i + 1), Modifier.size(28.dp).background(Color(0x99000000), CircleShape).padding(5.dp), tint = Color.White)
                            }
                        }
                    }
                    if (allPhotos.size == 1) {
                        val (key, file) = allPhotos.first()
                        PhotoCard(0, key, file, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                    } else {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            allPhotos.forEachIndexed { i, (key, file) -> PhotoCard(i, key, file, Modifier.width(260.dp)) }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
            if (viewing && existing != null) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // "Refund" starts a refund for this expense (same category, account and amount; all editable).
                    val refundable = existing?.let { it.kind == TxnKind.EXPENSE && !it.isRefund && it.transferGroupId == null } == true
                    listOfNotNull(
                        Triple("Delete", DangerColor) { confirmDelete = true },
                        Triple("Copy", Ink) { copyDialog = true },
                        if (refundable) Triple("Refund", IncomeColor) {
                            nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.REFUND, repo.today(), accountId.orEmpty(), categoryId = categoryId,
                                amount = enteredFmt.parse(amountText) ?: 0L, note = note, currency = currencyCode, tagIds = tagIds)))
                        } else null,
                        Triple("Bookmark", Ink) { bookmarkDialog = true },
                    ).filter { !isAdjustment || it.first == "Delete" }.forEach { (label, color, action) ->
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(FillColor).clickable(onClick = action),
                            contentAlignment = Alignment.Center,
                        ) { Text(tr(label), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color) }
                    }
                }
                Text(tr("Tap any field to edit."), Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = TextAlign.Center, fontSize = 12.sp, color = SubtleText)
            } else Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (type == DraftType.INCOME || type == DraftType.REFUND) IncomeColor else if (type == DraftType.TRANSFER) InkSoft else ExpenseColor)
                        .clickable { save(false) },
                    contentAlignment = Alignment.Center,
                ) { Text(tr("Save"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                if (existing == null) {
                    Box(
                        Modifier.width(120.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(FillColor).clickable { save(true) },
                        contentAlignment = Alignment.Center,
                    ) { Text(tr("Continue"), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink) }
                }
            }
            Spacer(Modifier.height(if (panel == Panel.NONE) 24.dp else 340.dp))
        }

        when (panel) {
            Panel.NONE -> Unit
            Panel.AMOUNT, Panel.FEE -> AmountKeypad(
                title = if (panel == Panel.FEE) "Fees" else "Amount",
                currency = enteredFmt,
                text = if (panel == Panel.FEE) feeText else amountText,
                onChange = { if (panel == Panel.FEE) feeText = it else amountText = it },
                onClose = { panel = Panel.NONE },
                onDone = { if (panel == Panel.FEE) panel = Panel.NONE else nextAfterAmount() },
            )
            Panel.CATEGORY -> CategoryPanel(
                categories.filter { it.kind == catKind }, useSubcategories, categoryId,
                onPick = { c ->
                    categoryId = c.id
                    // Category default account (or its main category's), unless the user already picked one.
                    val default = defaultAccountFor(c.id, catById)?.let { id -> accounts.firstOrNull { it.id == id } }
                    if (!accountChosen && default != null) {
                        accountId = default.id
                        if (amountText.isEmpty() || currencyCode == null) currencyCode = default.currency
                    }
                    panel = if (accountId == null) Panel.ACCOUNT else Panel.NONE
                    if (panel == Panel.NONE && note.isEmpty()) noteFocus.requestFocus()
                },
                onEdit = { nav.push(Screen.Categories(catKind)) },
                onClose = { panel = Panel.NONE },
            )
            Panel.ACCOUNT, Panel.TO_ACCOUNT -> AccountPanel(
                title = if (type == DraftType.TRANSFER) (if (panel == Panel.ACCOUNT) "From" else "To") else "Accounts",
                accounts = accounts.filter { panel == Panel.ACCOUNT || it.id != accountId },
                selectedIds = setOfNotNull(accountId, toAccountId),
                onPick = { a ->
                    if (panel == Panel.ACCOUNT) {
                        accountId = a.id
                        accountChosen = true
                        if (amountText.isEmpty() || currencyCode == null) currencyCode = a.currency
                        if (toAccountId == a.id) toAccountId = null
                        panel = when {
                            type == DraftType.TRANSFER && toAccountId == null -> Panel.TO_ACCOUNT
                            accountFirst && amountText.isEmpty() -> Panel.AMOUNT
                            else -> Panel.NONE
                        }
                    } else { toAccountId = a.id; panel = if (accountFirst && amountText.isEmpty()) Panel.AMOUNT else Panel.NONE }
                    if (panel == Panel.NONE && note.isEmpty()) noteFocus.requestFocus()
                },
                onEdit = { nav.push(Screen.AccountList) },
                onClose = { panel = Panel.NONE },
            )
        }
    }

    DatePickerHost(datePicker, date, { datePicker = false }, { it?.let { d -> date = d } })

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(tr("Discard this entry?")) },
            text = { Text(tr("What you've entered won't be saved.")) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; nav.pop() }) { Text(tr("Discard"), color = DangerColor) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(tr("Keep editing")) } },
        )
    }

    if (photoNotice) {
        AlertDialog(
            onDismissRequest = { photoNotice = false },
            title = { Text(tr("About receipt photos")) },
            text = {
                Text(tr("Photos are saved privately on this phone and shown with the entry. They're not included in backups or exports yet, " +
                    "and they're removed if you uninstall the app."))
            },
            confirmButton = {
                TextButton(onClick = {
                    photoNotice = false; photoMenu = true
                    scope.launch { withContext(repo.io) { repo.putSetting(PHOTO_NOTICE_KEY, "1") } }
                }) { Text(tr("OK"), color = Accent) }
            },
        )
    }

    if (tagDialog) TagPickerDialog(tagIds, onDismiss = { tagDialog = false }) { tagIds = it; tagDialog = false }

    if (bookmarkDialog) {
        var name by remember { mutableStateOf(note.ifBlank { categoryId?.let { categoryPath(it, catById) }.orEmpty() }) }
        AlertDialog(
            onDismissRequest = { bookmarkDialog = false },
            title = { Text(tr("Save as bookmark")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Bookmarks pre-fill a new entry in one tap. Leave the amount empty to be asked each time."), fontSize = 13.sp, color = SubtleText)
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val t = app.moneytracker.data.Template(
                        app.moneytracker.data.newId(), name.trim(), type, accountId, toAccountId, categoryId,
                        enteredFmt.parse(amountText) ?: 0, note.trim(), memo.trim(),
                    )
                    scope.launch { withContext(repo.io) { repo.saveTemplate(t) } }
                    bookmarkDialog = false
                }) { Text(tr("Save"), color = Accent) }
            },
            dismissButton = { TextButton(onClick = { bookmarkDialog = false }) { Text(tr("Cancel")) } },
        )
    }

    if (repeatDialog) {
        RepeatInstallmentDialog(
            canRepeat = existing == null, canInstall = type == DraftType.EXPENSE && isCreditCard,
            repeat = repeat, repeatEnd = repeatEnd, installments = installments,
            onDismiss = { repeatDialog = false },
            onApply = { r, end, inst -> repeat = r; repeatEnd = end; installments = inst; repeatDialog = false },
        )
    }

    if (copyDialog) {
        val e = existing
        AlertDialog(
            onDismissRequest = { copyDialog = false },
            title = { Text(tr("Copy entry")) },
            text = {
                Column {
                    listOf(true to "Copy with today's date", false to "Copy with date of entry").forEach { (today, label) ->
                        Text(tr(label), Modifier.fillMaxWidth().clickable {
                            copyDialog = false
                            if (e != null) scope.launch {
                                val d = withContext(repo.io) { repo.draftFrom(e) }
                                nav.pop(); nav.push(Screen.EditTxn(preset = if (today) d.copy(date = repo.today()) else d))
                            }
                        }.padding(vertical = 14.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { copyDialog = false }) { Text(tr("Cancel")) } },
        )
    }

    if (timePicker) TimePickerDialog(timeOfDay ?: nowMinutes(), onDismiss = { timePicker = false }, onClear = { timeOfDay = null; timePicker = false }) {
        timeOfDay = it; timePicker = false
    }

    if (confirmDelete) {
        val e = existing!!
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete transaction?")) },
            text = {
                Text(when {
                    e.installmentGroupId != null -> tr("This deletes every installment of this purchase.")
                    e.transferGroupId != null -> tr("Both sides of the transfer (and its fee) will be deleted.")
                    else -> tr("This cannot be undone.")
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(repo.io) { repo.delete(e, wholeInstallmentPlan = true) }
                        nav.pop()
                    }
                }) { Text(tr("Delete"), color = DangerColor) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Raw digits while typing on the keypad; grouped "₹ 10,000.00" otherwise. */
private fun amountDisplay(text: String, editing: Boolean, currency: CurrencyFormat): String = when {
    text.isEmpty() -> ""
    editing -> "${currency.symbol} $text"
    else -> currency.parse(text)?.let { currency.format(it) } ?: "${currency.symbol} $text"
}

/** "Label   value" row; the value area gets a coral underline while its panel is open. */
@Composable
private fun FormRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr(label), Modifier.width(84.dp), color = SubtleText)
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)) { value() }
            }
            HorizontalDivider(color = if (active) Accent else Color.Transparent, thickness = 1.5.dp)
        }
        if (trailing != null) Box(Modifier.padding(start = 6.dp)) { trailing() }
    }
}

/** Category / account pickers keep one height however few items there are, so the sheet never jumps. */
private val PanelBodyHeight = 260.dp

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** Bottom panels (keypad, category, account) float over the form as a rounded sheet. */
private fun Modifier.sheet() = fillMaxWidth().shadow(16.dp, SheetShape).clip(SheetShape).background(SurfaceColor)

@Composable
private fun PanelHeader(title: String, onEdit: (() -> Unit)?, onClose: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
        // Grab handle: the panel reads as a sheet over the form.
        Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).background(FillStrong, RoundedCornerShape(50)).align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            extra?.invoke()
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, tr("Edit"), tint = InkSoft) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Close"), tint = InkSoft) }
        }
    }
}

@Composable
private fun AmountKeypad(
    title: String,
    currency: CurrencyFormat,
    text: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val ops = app.moneytracker.domain.Calc.OPS
    fun press(k: String) {
        // The number being typed is whatever follows the last operator.
        val head = text.dropLastWhile { it !in ops }
        val num = text.substring(head.length)
        val next = when (k) {
            "⌫" -> text.dropLast(1)
            "C" -> ""
            "=" -> app.moneytracker.domain.Calc.evaluate(text, currency.decimals) ?: text
            "+", "−", "×", "÷" -> when {
                text.isEmpty() -> text
                text.last() in ops -> text.dropLast(1) + k          // change the operator
                text.last() == '.' -> text.dropLast(1) + k
                else -> text + k
            }
            "." -> if (currency.decimals == 0 || num.contains('.')) text else text + (if (num.isEmpty()) "0." else ".")
            else -> {
                val candidate = if (num == "0") head + k else text + k
                val newNum = candidate.substring(head.length)
                val frac = newNum.substringAfter('.', "")
                if (newNum.contains('.') && frac.length > currency.decimals) text
                else if (newNum.substringBefore('.').length > 12) text else candidate
            }
        }
        onChange(next)
    }
    Column(Modifier.sheet()) {
        PanelHeader(title, null, onClose)
        // Live result while an expression is being typed.
        if (app.moneytracker.domain.Calc.hasOperator(text)) {
            val result = app.moneytracker.domain.Calc.evaluate(text, currency.decimals)
            Text(
                "= " + (result?.let { currency.parse(it)?.let { m -> currency.format(m) } } ?: "…"),
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp), color = Accent, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            )
        }
        Column(Modifier.fillMaxWidth().background(PageBackground).padding(6.dp)) {
            val keys = listOf(
                listOf("7", "8", "9", "÷", "⌫"),
                listOf("4", "5", "6", "×", "C"),
                listOf("1", "2", "3", "−", "="),
                listOf("00", "0", ".", "+", "Done"),
            )
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    row.forEach { k ->
                        val isDone = k == "Done"
                        val isOp = k in ops || k == "="
                        val isFn = k == "⌫" || k == "C"
                        Box(
                            Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp))
                                .background(when { isDone -> Accent; isOp -> AccentTint; isFn -> FillStrong; else -> SurfaceColor })
                                .clickable {
                                    if (isDone) {
                                        // Done settles a pending expression first.
                                        if (app.moneytracker.domain.Calc.hasOperator(text)) {
                                            app.moneytracker.domain.Calc.evaluate(text, currency.decimals)?.let(onChange) ?: return@clickable
                                        }
                                        onDone()
                                    } else press(k)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "⌫" -> Icon(Icons.AutoMirrored.Outlined.Backspace, tr("Delete digit"), tint = Ink)
                                else -> Text(
                                    if (isDone) tr(k) else k, fontSize = if (isDone) 16.sp else if (isOp) 24.sp else 22.sp,
                                    color = if (isDone) Color.White else if (isOp) Accent else Ink,
                                    fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> GridPanel(items: List<T>, label: (T) -> String, onPick: (T) -> Unit, columns: Int = 3) {
    Column(Modifier.fillMaxWidth().background(PageBackground).height(PanelBodyHeight).verticalScroll(rememberScrollState()).padding(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(62.dp)) {
                row.forEach { item ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
                            .clickable { onPick(item) }.padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label(item), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryPanel(
    categories: List<Category>,
    useSubcategories: Boolean,
    currentId: String?,
    onPick: (Category) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    val mains = categories.filter { it.parentId == null }
    Column(Modifier.sheet()) {
        PanelHeader("Category", onEdit, onClose)
        if (!useSubcategories) {
            // Sub-categories off: a main category is picked in one tap.
            GridPanel(mains, { it.name }, onPick)
            return@Column
        }
        // Sub-categories on: two panes — mains on the left, the selected main's subs on the right.
        val subsOf = categories.filter { it.parentId != null }.groupBy { it.parentId }
        val current = categories.firstOrNull { it.id == currentId }
        var selected by remember { mutableStateOf(current?.parentId ?: current?.id?.takeIf { subsOf.containsKey(it) }) }
        Row(Modifier.fillMaxWidth().height(PanelBodyHeight)) {
            Column(Modifier.weight(1f).background(PageBackground).verticalScroll(rememberScrollState())) {
                mains.forEach { m ->
                    val hasSubs = subsOf.containsKey(m.id)
                    val isSel = m.id == selected
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).background(if (isSel) SurfaceColor else PageBackground)
                            .clickable { if (hasSubs) selected = m.id else onPick(m) }
                            .padding(start = 20.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.name, Modifier.weight(1f), color = if (isSel) Accent else Ink, fontWeight = if (isSel) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hasSubs) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Has sub-categories"), tint = if (isSel) Accent else SubtleText)
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                subsOf[selected].orEmpty().forEach { sub ->
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).clickable { onPick(sub) }.padding(horizontal = 20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(sub.name, color = if (sub.id == currentId) Accent else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPanel(title: String, accounts: List<Account>, selectedIds: Set<String>, onPick: (Account) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val groups by repo.groups.collectAsState(emptyList())
    val grouped by remember(repo) { repo.flag(app.moneytracker.data.MoneyRepository.Keys.ACCOUNTS_GROUPED) }.collectAsState(false)
    Column(Modifier.sheet()) {
        PanelHeader(title, onEdit, onClose, extra = {
            // Grid of all accounts, or groups on the left and their accounts on the right (remembered).
            IconButton(onClick = {
                scope.launch { withContext(repo.io) { repo.putSetting(app.moneytracker.data.MoneyRepository.Keys.ACCOUNTS_GROUPED, if (grouped) "0" else "1") } }
            }) { Icon(if (grouped) Icons.Outlined.GridView else Icons.Outlined.ViewList, if (grouped) tr("Show all accounts") else tr("Show by group"), tint = InkSoft) }
        })
        // The grid leaves hidden accounts out; the group view lists them greyed, so they stay reachable.
        if (!grouped) {
            val visible = accounts.filter { !it.hidden || it.id in selectedIds }
            if (visible.isEmpty() && accounts.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().height(PanelBodyHeight).background(PageBackground).padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("All accounts are hidden"), color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(tr("Pick one from the group view, or show accounts again in Settings › Accounts."), Modifier.padding(top = 6.dp),
                        color = SubtleText, fontSize = 13.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = {
                        scope.launch { withContext(repo.io) { repo.putSetting(app.moneytracker.data.MoneyRepository.Keys.ACCOUNTS_GROUPED, "1") } }
                    }, Modifier.padding(top = 8.dp)) { Text(tr("Show by group"), color = Accent) }
                }
            } else GridPanel(visible, { it.name }, onPick)
            return@Column
        }
        val used = groups.filter { g -> accounts.any { it.groupId == g.id } }
        var selected by remember { mutableStateOf(used.firstOrNull()?.id) }
        Row(Modifier.fillMaxWidth().height(PanelBodyHeight)) {
            Column(Modifier.weight(1f).background(PageBackground).verticalScroll(rememberScrollState())) {
                used.forEach { g ->
                    val isSel = g.id == selected
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).background(if (isSel) SurfaceColor else PageBackground)
                            .clickable { selected = g.id }.padding(start = 20.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g.name, Modifier.weight(1f), color = if (isSel) Accent else Ink, fontWeight = if (isSel) FontWeight.SemiBold else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(accounts.count { it.groupId == g.id }.toString(), color = SubtleText, fontSize = 12.sp)
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                accounts.filter { it.groupId == selected }.forEach { a ->
                    Box(Modifier.fillMaxWidth().height(56.dp).clickable { onPick(a) }.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                        Text(a.name, color = if (a.hidden) SubtleText else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepeatInstallmentDialog(
    canRepeat: Boolean,
    canInstall: Boolean,
    repeat: RepeatType?,
    repeatEnd: LocalDate?,
    installments: Int,
    onDismiss: () -> Unit,
    onApply: (RepeatType?, LocalDate?, Int) -> Unit,
) {
    var r by remember { mutableStateOf(repeat) }
    var end by remember { mutableStateOf(repeatEnd) }
    var inst by remember { mutableStateOf(installments.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Repeat / Installments")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canRepeat) {
                    Picker(tr("Repeat"), listOf<RepeatType?>(null) + RepeatType.entries, r, { it?.label?.let { l -> tr(l) } ?: tr("Don't repeat") }, { r = it }, Modifier.fillMaxWidth())
                    if (r != null) DateField(tr("Until (optional)"), end, { end = it }, Modifier.fillMaxWidth(), clearable = true)
                } else Text(tr("Repeat can only be set on new transactions."), color = SubtleText, fontSize = 13.sp)
                if (canInstall) {
                    OutlinedTextField(
                        inst, { inst = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Installments (months)")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                } else Text(tr("Installments are available for credit-card expenses."), color = SubtleText, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(r, end, (inst.toIntOrNull() ?: 1).coerceIn(1, 60)) }) { Text(tr("OK"), color = Accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Current local time as minutes after midnight. */
fun nowMinutes(): Int {
    val t = kotlinx.datetime.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    return t.hour * 60 + t.minute
}

/** "3:02 PM" */
fun formatTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) tr("AM") else tr("PM")}"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initial: Int, onDismiss: () -> Unit, onClear: () -> Unit, onPick: (Int) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(initial / 60, initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { androidx.compose.material3.TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(tr("OK"), color = Accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(tr("No time")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
}

/** Full-screen receipt photos, swipe between them. */
@Composable
private fun PhotoViewer(files: List<String>, start: Int, onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(start) { files.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                platform.PhotoFull(files[page], Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("Close"), tint = Color.White) }
                if (files.size > 1) Text("${pager.currentPage + 1} / ${files.size}", color = Color.White)
            }
        }
    }
}
