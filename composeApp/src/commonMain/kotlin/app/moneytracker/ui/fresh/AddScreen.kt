@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.Template
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.Calc
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.Photo
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.defaultAccountFor
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalCurrencies
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.VoiceParser
import app.moneytracker.ui.categoryPath
import app.moneytracker.ui.formatTime
import app.moneytracker.ui.long
import app.moneytracker.ui.nowMinutes
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private const val PHOTO_NOTICE_KEY = "photoNoticeShown"

private enum class AddSheet { NONE, CATEGORY, ACCOUNT, FROM, TO, TAGS, REPEAT, CURRENCY, AMOUNT, FEE, BOOKMARK, COPY }

/** The entry being added or edited. Mirrors the classic editor's state and rules; only the screens differ. */
@Stable
private class AddForm(val repo: MoneyRepository, s: Screen.EditTxn, val main: CurrencyFormat, val currencies: Map<String, Currency>, timeOn: Boolean) {
    val accountFirst = repo.settingNow(Keys.INPUT_ORDER) == "ACCOUNT"
    var existing by mutableStateOf<Txn?>(null)
    // Balance adjustments edit as Increase / Decrease; picking a type turns one into a normal entry.
    var adjustUp by mutableStateOf(true)
    var converted by mutableStateOf(false)
    var type by mutableStateOf(s.preset?.type ?: DraftType.EXPENSE)
    var date by mutableStateOf(s.date ?: s.preset?.date ?: repo.today())
    var accountId by mutableStateOf(s.preset?.accountId?.ifBlank { null })
    // True once the account came from the user (or an existing entry / preset); defaults never override it.
    var accountChosen by mutableStateOf(accountId != null)
    var toAccountId by mutableStateOf(s.preset?.toAccountId?.ifBlank { null })
    var categoryId by mutableStateOf(s.preset?.categoryId)
    var currencyCode by mutableStateOf(s.preset?.currency)
    val fmt: CurrencyFormat get() = currencyCode?.let { currencies[it]?.format } ?: main
    var amountText by mutableStateOf(s.preset?.amount?.takeIf { it > 0 }?.let { fmt.toInput(it) }.orEmpty())
    var feeText by mutableStateOf("")
    var tagIds by mutableStateOf(s.preset?.tagIds.orEmpty())
    var lat by mutableStateOf<Double?>(null)
    var lng by mutableStateOf<Double?>(null)
    var existingPhotos by mutableStateOf(emptyList<Photo>())
    var newPhotos by mutableStateOf(emptyList<String>())
    var note by mutableStateOf(s.preset?.note.orEmpty())
    var memo by mutableStateOf(s.preset?.memo.orEmpty())
    var origin by mutableStateOf(s.preset?.origin.orEmpty())
    var installments by mutableStateOf(s.preset?.installments ?: 1)
    var repeat by mutableStateOf(s.preset?.repeat)
    var repeatEnd by mutableStateOf(s.preset?.repeatEnd)
    var timeOfDay by mutableStateOf(s.preset?.timeOfDay ?: if (s.txnId == null && timeOn) nowMinutes() else null)
    var error by mutableStateOf<String?>(null)

    val isAdjustment get() = (existing?.kind == TxnKind.ADJUST_UP || existing?.kind == TxnKind.ADJUST_DOWN) && !converted
    val catKind get() = if (type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val txnKind get() = when (type) { DraftType.INCOME -> TxnKind.INCOME; DraftType.TRANSFER -> TxnKind.TRANSFER_OUT; else -> TxnKind.EXPENSE }
    val isCreditCard: Boolean get() {
        val a = accountId?.let { id -> repo.accounts.current.firstOrNull { it.id == id } } ?: return false
        return repo.groups.current.firstOrNull { it.id == a.groupId }?.type?.isCreditCard == true
    }

    init {
        if (s.txnId != null) load(s.txnId)
        else if (s.preset == null && !accountFirst) applyHabitAccount(null)
    }

    // Filled before the first frame: a few indexed rows, and loading later would flash the new-entry defaults.
    private fun load(id: String) {
        val t = repo.txn(id) ?: return
        val d = repo.draftFrom(t)
        existingPhotos = repo.photosNow(t.id)
        adjustUp = t.kind == TxnKind.ADJUST_UP
        existing = t
        type = d.type; date = d.date; accountId = d.accountId; toAccountId = d.toAccountId; accountChosen = true
        categoryId = if (d.type == DraftType.TRANSFER) null else d.categoryId
        currencyCode = d.currency
        amountText = fmt.toInput(d.amount)
        if (d.fee > 0) feeText = fmt.toInput(d.fee)
        note = d.note; memo = d.memo; installments = d.installments; tagIds = d.tagIds; timeOfDay = d.timeOfDay; origin = d.origin
        lat = t.lat; lng = t.lng
    }

    private fun live(id: String?): Account? = id?.let { repo.accounts.current.firstOrNull { a -> a.id == it } }

    /** The account usually used for this kind of entry (and category), unless one was chosen. */
    fun applyHabitAccount(category: String?) {
        if (accountChosen || type == DraftType.TRANSFER) return
        val a = live(repo.habitAccount(txnKind, category)) ?: return
        accountId = a.id
        if (amountText.isEmpty() || currencyCode == null) currencyCode = a.currency
    }

    fun pickType(t: DraftType) {
        if (isAdjustment) {
            converted = true; type = t
            categoryId = if (t == DraftType.TRANSFER) null else MODIFIED_BALANCE_CATEGORY
        } else if (t != type && !(t == DraftType.EXPENSE && type == DraftType.REFUND)) {
            type = t; categoryId = null; if (t != DraftType.TRANSFER) toAccountId = null
            applyHabitAccount(null)
        }
    }

    /** Category's preferred account (or its main category's), else the one usually used with it, unless one was picked. */
    fun pickCategory(c: Category) {
        categoryId = c.id
        val default = live(defaultAccountFor(c.id, repo.categoryById.current)) ?: live(repo.habitAccount(txnKind, c.id))
        if (!accountChosen && default != null) {
            accountId = default.id
            if (amountText.isEmpty() || currencyCode == null) currencyCode = default.currency
        }
    }

    fun pickFrom(a: Account) {
        accountId = a.id; accountChosen = true
        if (amountText.isEmpty() || currencyCode == null) currencyCode = a.currency
        if (toAccountId == a.id) toAccountId = null
    }

    /** Picking a past note also brings back its category, account and amount (unless already set). */
    fun pickNote(m: MoneyRepository.NoteSuggestion) {
        note = m.note
        val catById = repo.categoryById.current
        if (type != DraftType.TRANSFER && m.categoryId != null && catById[m.categoryId]?.kind == catKind) categoryId = m.categoryId
        if (!accountChosen && m.accountId != null && live(m.accountId) != null) { accountId = m.accountId; accountChosen = true }
        if (amountText.isEmpty() && m.amount > 0 && (m.currency == null || currencies.containsKey(m.currency))) {
            currencyCode = m.currency
            amountText = fmt.toInput(m.amount)
        }
    }

    fun formKey() = listOf(type, date, accountId, toAccountId, categoryId, amountText, feeText, note, memo, installments,
        repeat, repeatEnd, tagIds, currencyCode, newPhotos, existingPhotos.size, timeOfDay, adjustUp, converted).toString()

    fun resetForNext(timeOn: Boolean) {
        accountChosen = false
        timeOfDay = if (timeOn) nowMinutes() else null
        amountText = ""; feeText = ""; note = ""; memo = ""; categoryId = null; tagIds = emptyList(); newPhotos = emptyList()
        installments = 1; repeat = null; repeatEnd = null; error = null; origin = ""
        if (!accountFirst) applyHabitAccount(null)
    }

    fun amountValue(): Long? = fmt.parse(addSettle(amountText, fmt))
}

private fun shown(f: CurrencyFormat, expr: String) = if (f.symbolAfter) "$expr ${f.symbol}" else "${f.symbol}$expr"

@Composable
internal fun FreshEditTxn(s: Screen.EditTxn) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val currencies = LocalCurrencies.current
    val platform = LocalPlatform.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val accounts by repo.accounts.collectAsState(emptyList())
    val groups by repo.groups.collectAsState(emptyList())
    val categories by repo.categories.collectAsState(emptyList())
    val catById by repo.categoryById.collectAsState(emptyMap())
    val tags by repo.tags.collectAsState(emptyList())
    val useSubcategories by repo.useSubcategories.collectAsState(false)
    val locationOn by remember(repo) { repo.flag(Keys.LOCATION) }.collectAsState(false)
    val timeInput by remember(repo) { repo.setting(Keys.TIME_INPUT) }.collectAsState(repo.settingNow(Keys.TIME_INPUT))
    val timeOn = timeInput == "INPUT" || timeInput == "LIST"
    val capture = s.captureId?.let { id -> repo.pendingCaptures.collectAsState(emptyList()).value.firstOrNull { it.id == id } }

    val f = remember { AddForm(repo, s, currency, currencies, timeOn) }
    val today = remember { repo.today() }

    // New entries open on the quick screen; "Time, tags, repeat, photo" and existing entries use the full one.
    var details by remember { mutableStateOf(s.txnId != null) }
    var sheet by remember {
        mutableStateOf(if (s.txnId == null && s.preset == null && f.accountFirst && f.accountId == null) AddSheet.ACCOUNT else AddSheet.NONE)
    }
    var feeTarget by remember { mutableStateOf(false) }  // quick screen: keypad types into the fee
    var datesOpen by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var photoNotice by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var bookmarked by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val noteFocus = remember { FocusRequester() }
    var noteFocused by remember { mutableStateOf(false) }

    // Keep the photo strip current; capture location once for new entries if the user enabled it.
    LaunchedEffect(f.existing?.id) { f.existing?.let { t -> repo.photos(t.id).collect { f.existingPhotos = it } } }
    LaunchedEffect(locationOn) {
        if (locationOn && s.txnId == null && f.lat == null) platform.currentLocation({ a, b -> f.lat = a; f.lng = b }, {})
    }
    LaunchedEffect(f.amountText, f.accountId, f.toAccountId) { f.error = null }

    // Learned category from the note's words.
    val suggestion by produceState<MoneyRepository.Suggestion?>(null, f.note, f.type) {
        value = if (f.note.length < 3 || f.type == DraftType.TRANSFER) null
        else withContext(repo.io) { repo.suggest(SmsParser.words(f.note)) }
    }
    val suggestedCat = suggestion?.categoryId?.takeIf { f.categoryId == null && catById[it]?.kind == f.catKind && !f.isAdjustment }
    // Past notes matching what is typed (only while the note field has focus).
    val noteMatches by produceState(emptyList<MoneyRepository.NoteSuggestion>(), f.note, noteFocused, f.type) {
        value = if (!noteFocused || f.note.isBlank()) emptyList()
        else withContext(repo.io) { repo.noteSuggestions(f.note) }.filter { m ->
            when (f.type) {
                DraftType.INCOME -> m.kind == TxnKind.INCOME
                DraftType.EXPENSE, DraftType.REFUND -> m.kind == TxnKind.EXPENSE
                DraftType.TRANSFER -> m.kind == TxnKind.TRANSFER_OUT
            }
        }.take(3)
    }

    // Unsaved-changes guard: compare the form with what it held once loaded.
    var baseline by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.existing, accounts) { if (s.txnId == null || f.existing != null) baseline = f.formKey() }
    val dirty = rememberUpdatedState(baseline != null && f.formKey() != baseline)
    val detailsState = rememberUpdatedState(details)
    DisposableEffect(nav) {
        val remove = nav.interceptBack {
            when {
                detailsState.value && s.txnId == null -> { details = false; true }
                dirty.value -> { confirmDiscard = true; true }
                else -> false
            }
        }
        onDispose { remove() }
    }

    val accountById = accounts.associateBy { it.id }
    val fmt = f.fmt
    val isTransfer = f.type == DraftType.TRANSFER
    val total = f.amountValue()
    val catLabel = f.categoryId?.let { categoryPath(it, catById) }?.ifEmpty { null }
    fun accountName(id: String?) = id?.let { accountById[it]?.name }
    fun dateLabel(d: LocalDate) = when (d) {
        today -> tr("Today")
        today.minus(DatePeriod(days = 1)) -> tr("Yesterday")
        else -> "${d.weekday()} ${d.short()}"
    }

    fun listen() {
        listening = true
        platform.listen { text ->
            listening = false
            val d = VoiceParser.parse(text, repo.today(), fmt)
            if (s.txnId == null) f.pickType(if (d.type == DraftType.REFUND) DraftType.EXPENSE else d.type)
            if (d.type == DraftType.REFUND && f.type == DraftType.EXPENSE) f.type = DraftType.REFUND
            if (d.amount > 0) f.amountText = fmt.toInput(d.amount)
            if (d.note.isNotBlank()) f.note = d.note
            f.date = d.date
            toaster.show(tr("Heard “{0}” · filled in for you", text))
        }
    }

    fun save(continueAfter: Boolean) {
        val amount = f.amountValue()
        val fee = if (isTransfer && f.feeText.isNotBlank()) fmt.parse(addSettle(f.feeText, fmt)) else 0L
        f.error = when {
            amount == null || amount <= 0 -> tr("Enter an amount")
            fee == null -> tr("Fee is not a valid amount")
            f.accountId == null -> tr("Choose an account")
            isTransfer && f.toAccountId == null -> tr("Choose the destination account")
            isTransfer && f.toAccountId == f.accountId -> tr("Pick two different accounts")
            else -> null
        }
        f.error?.let { toaster.show(it); return }
        val adj = f.existing
        if (adj != null && f.isAdjustment) {
            scope.launch {
                withContext(repo.io) { repo.updateAdjustment(adj, f.date, amount!!, f.accountId!!, f.adjustUp, f.note.trim(), f.memo.trim(), f.timeOfDay) }
                platform.refreshWidgets(); nav.pop(); toaster.show(tr("Changes saved"))
            }
            return
        }
        val draft = TxnDraft(
            f.type, f.date, f.accountId!!, if (isTransfer) f.toAccountId else null,
            if (isTransfer) null else f.categoryId,
            amount!!, fee ?: 0, f.note.trim(), f.memo.trim(),
            if (f.type == DraftType.EXPENSE && f.isCreditCard) f.installments else 1,
            if (f.existing == null) f.repeat else null, f.repeatEnd,
            currency = f.currencyCode, tagIds = f.tagIds, lat = f.lat, lng = f.lng, timeOfDay = f.timeOfDay, origin = f.origin,
        )
        val photosToAttach = f.existingPhotos.map { it.fileName } + f.newPhotos
        val cap = capture
        val label = if (isTransfer) tr("Moved {0}", fmt.format(amount)) else listOfNotNull(tr("Saved {0}", fmt.format(amount)), catLabel).joinToString(" · ")
        scope.launch {
            val e = f.existing
            val id = withContext(repo.io) {
                val id = if (e == null) repo.create(draft) else repo.update(e, draft)
                // Photos follow the (possibly new) row id; recurring rules get none.
                if (draft.repeat == null) photosToAttach.forEach { repo.addPhoto(id, it) }
                repo.learnFromDraft(draft)
                cap?.let { repo.completeCapture(it, draft, id) }
                id
            }
            platform.refreshWidgets()
            // A plain new entry can be taken back from the toast (the screen may be gone by then).
            val undo: (() -> Unit)? = if (e == null && draft.repeat == null && cap == null) ({
                MainScope().launch {
                    withContext(repo.io) { repo.txn(id)?.let { repo.delete(it, wholeInstallmentPlan = true) } }
                    platform.refreshWidgets()
                }
                Unit
            }) else null
            when {
                e != null -> { nav.pop(); toaster.show(tr("Changes saved")) }
                continueAfter -> {
                    f.resetForNext(timeOn); details = false; feeTarget = false; baseline = f.formKey()
                    toaster.show(tr("{0}. Add the next one", label), undo)
                }
                else -> { nav.pop(); toaster.show(label, undo) }
            }
        }
    }

    val saveLabel = when {
        total == null || total <= 0 -> tr("Enter an amount")
        isTransfer && (f.accountId == null || f.toAccountId == null) -> tr("Pick two accounts")
        isTransfer && f.toAccountId == f.accountId -> tr("Pick two different accounts")
        f.accountId == null -> tr("Choose an account")
        isTransfer -> tr("Move {0}", fmt.format(total))
        catLabel != null -> tr("Save {0} · {1}", fmt.format(total), catById[f.categoryId]?.name ?: catLabel)
        else -> tr("Save {0}", fmt.format(total))
    }
    val ready = total != null && total > 0 && f.accountId != null && (!isTransfer || (f.toAccountId != null && f.toAccountId != f.accountId))

    // ───────────── pieces shared by both screens

    @Composable
    fun noteField(modifier: Modifier = Modifier) {
        AddInput(
            f.note, { f.note = it }, tr("Note"), modifier,
            fieldModifier = Modifier.focusRequester(noteFocus).onFocusChanged { noteFocused = it.isFocused },
            trailing = if (platform.voiceAvailable) ({
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (listening) F.accent else F.fill)
                        .clickable(onClickLabel = tr("Say the entry, e.g. 200 fuel")) { focus.clearFocus(); listen() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Mic, tr("Say the entry, e.g. 200 fuel"), tint = if (listening) F.onAccent else F.muted, modifier = Modifier.size(16.dp)) }
            }) else null,
        )
    }

    @Composable
    fun noteSuggestions() {
        if (noteMatches.isEmpty() && suggestedCat == null) return
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (suggestedCat != null) SuggestChip(tr("Suggested: {0} ✓", categoryPath(suggestedCat, catById)), accent = true) {
                catById[suggestedCat]?.let { f.pickCategory(it) }
            }
            noteMatches.forEach { m ->
                val amount = m.amount.takeIf { it > 0 }?.let { (m.currency?.let { c -> currencies[c]?.format } ?: currency).format(it) }
                SuggestChip(listOfNotNull(m.note, amount).joinToString(" · ")) {
                    f.pickNote(m); focus.clearFocus()
                    if (!isTransfer && f.categoryId == null) sheet = AddSheet.CATEGORY
                    else if (f.accountId == null) sheet = if (isTransfer) AddSheet.FROM else AddSheet.ACCOUNT
                }
            }
        }
    }

    @Composable
    fun dateChips() {
        val days = listOf(today, today.minus(DatePeriod(days = 1)), today.minus(DatePeriod(days = 2)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d -> AddChip(dateLabel(d), f.date == d, Modifier.weight(1f)) { f.date = d; datesOpen = false } }
            val other = f.date !in days
            AddChip(if (other) f.date.short() else tr("Pick date"), other, Modifier.weight(1f)) { datePicker = true }
        }
    }

    @Composable
    fun currencyChip() {
        if (currencies.isEmpty() || f.isAdjustment) return
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, F.border, RoundedCornerShape(999.dp))
                .clickable { sheet = AddSheet.CURRENCY }.padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.currencyCode ?: tr("Main"), color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.KeyboardArrowDown, tr("Currency"), tint = F.muted, modifier = Modifier.size(16.dp))
        }
    }

    // ───────────── quick screen (new entry)

    if (!details) Column(Modifier.fillMaxSize().background(F.card).statusBarsPadding().padding(horizontal = F.gutter).padding(top = 12.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(Icons.Filled.Close, tr("Close"), { nav.back() }, background = F.fill)
            TypeTabs(f, Modifier.weight(1f), compact = true)
            Row(
                Modifier.height(44.dp).clip(F.iconShape).background(if (datesOpen) F.accentTint else F.fill).clickable { datesOpen = !datesOpen }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.CalendarToday, null, tint = F.ink, modifier = Modifier.size(16.dp))
                Text(dateLabel(f.date), color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (datesOpen) dateChips()
            // Big amount; the hint underneath shows a running result, the category, or what is missing.
            Column(Modifier.fillMaxWidth().clickable { feeTarget = false; focus.clearFocus() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        shown(fmt, addExprShown(f.amountText)), Modifier.weight(1f, fill = false),
                        color = if ((total ?: 0) > 0) F.ink else F.muted, fontSize = if (f.amountText.length > 10) 36.sp else 48.sp,
                        fontWeight = FontWeight.ExtraBold, maxLines = 1, style = F.money,
                    )
                    currencyChip()
                }
                val hasOp = Calc.hasOperator(f.amountText)
                val (hint, color) = when {
                    f.error != null -> f.error!! to F.danger
                    hasOp -> "= " + (total?.let { fmt.format(it) } ?: "…") to F.accent
                    isTransfer && f.accountId != null && f.accountId == f.toAccountId -> tr("Pick two different accounts") to F.danger
                    isTransfer -> tr("Move money · {0}", f.date.long()) to F.muted
                    else -> listOfNotNull(if (f.type == DraftType.REFUND) tr("Refund") else null, catLabel ?: tr("Pick a category"), f.date.long()).joinToString(" · ") to F.muted
                }
                Text(hint, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (isTransfer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountBox(tr("FROM"), accountName(f.accountId) ?: tr("Choose"), Modifier.weight(1f)) { sheet = AddSheet.FROM }
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(F.fill)
                            .clickable(onClickLabel = tr("Swap accounts")) { val a = f.accountId; f.accountId = f.toAccountId; f.toAccountId = a },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.SwapHoriz, tr("Swap accounts"), tint = F.accent, modifier = Modifier.size(18.dp)) }
                    AccountBox(tr("TO"), accountName(f.toAccountId) ?: tr("Choose"), Modifier.weight(1f)) { sheet = AddSheet.TO }
                }
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                        .border(if (feeTarget) 2.dp else 1.dp, if (feeTarget) F.accent else F.border, RoundedCornerShape(12.dp))
                        .clickable { feeTarget = !feeTarget; focus.clearFocus() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Fee (optional)"), Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(if (f.feeText.isEmpty()) shown(fmt, "0") else shown(fmt, addExprShown(f.feeText)),
                        color = if (f.feeText.isEmpty()) F.muted else F.ink, fontSize = 14.sp, style = F.money)
                }
            } else if (!f.isAdjustment) {
                val mains = categories.filter { it.kind == f.catKind && it.parentId == null }
                val current = f.categoryId?.let { catById[it] }
                val selectedMain = current?.parentId ?: current?.id
                val top = mains.take(7).let { t -> if (selectedMain == null || t.any { it.id == selectedMain } || mains.none { it.id == selectedMain }) t else mains.take(6) + mains.first { it.id == selectedMain } }
                val subsOf = categories.filter { it.parentId != null }.groupBy { it.parentId }
                AddGrid(top + listOf(null)) { c, mod ->
                    if (c == null) AddTile("…", tr("All {0}", mains.size), false, mod, mark = "⋯") { sheet = AddSheet.CATEGORY }
                    else {
                        val sel = c.id == selectedMain
                        AddTile(c.name, if (sel && current?.parentId == c.id) current.name else c.name, sel, mod) {
                            // Tapping the chosen main again shows its sub-categories.
                            if (sel && useSubcategories && subsOf.containsKey(c.id)) sheet = AddSheet.CATEGORY
                            else { f.pickCategory(c); if (f.accountId == null) sheet = AddSheet.ACCOUNT }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isTransfer) Row(
                    Modifier.widthIn(max = 160.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, F.border, RoundedCornerShape(12.dp))
                        .clickable { sheet = AddSheet.ACCOUNT }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.CreditCard, null, tint = F.ink, modifier = Modifier.size(16.dp))
                    Text(accountName(f.accountId) ?: tr("Account"), Modifier.weight(1f, fill = false), color = if (f.accountId == null) F.danger else F.ink,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = F.muted, modifier = Modifier.size(14.dp))
                }
                noteField(Modifier.weight(1f))
            }
            noteSuggestions()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val extras = listOfNotNull(
                    if (f.tagIds.isNotEmpty()) "#${f.tagIds.size}" else null,
                    f.repeat?.let { tr(it.label) }, if (f.installments > 1) tr("{0} installments", f.installments) else null,
                    if (f.newPhotos.isNotEmpty()) tr("{0} photos", f.newPhotos.size) else null,
                ).joinToString(" · ")
                Text(
                    when {
                        extras.isNotEmpty() -> extras
                        isTransfer -> if (f.feeText.isNotBlank()) tr("Fee is recorded as an expense") else tr("Transfers are not income or spending")
                        else -> ""
                    },
                    Modifier.weight(1f), color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                AddLink(tr("Time, tags, repeat, photo ›")) { focus.clearFocus(); details = true }
            }
            if (f.origin.isNotBlank() && repo.settingNow(Keys.SMS_SHOW_ORIGINAL) != "0") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(F.fill).padding(10.dp)) {
                    Text(tr("Original message"), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(f.origin, color = F.ink, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!noteFocused) {
            Spacer(Modifier.height(10.dp))
            AddKeypad(if (feeTarget) f.feeText else f.amountText, fmt, { if (feeTarget) f.feeText = it else f.amountText = it })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(tr("Continue"), { save(true) }, Modifier.width(116.dp))
            PrimaryButton(saveLabel, { save(false) }, Modifier.weight(1f), enabled = ready)
        }
    }

    // ───────────── full screen (existing entry, or details of a new one)

    if (details) {
        val e = f.existing
        val title = when {
            f.isAdjustment -> if (f.adjustUp) tr("Balance increase") else tr("Balance decrease")
            e != null -> tr("Edit entry")
            else -> tr("Entry details")
        }
        SubScreen(
            title, { nav.back() },
            actions = {
                if (!f.isAdjustment) IconTile(
                    if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarOutline, tr("Save as bookmark"), { sheet = AddSheet.BOOKMARK },
                    tint = if (bookmarked) F.warn else F.ink,
                )
            },
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e != null) {
                        Box(
                            Modifier.size(52.dp).clip(F.buttonShape).background(F.card).border(1.dp, F.border, F.buttonShape)
                                .clickable(onClickLabel = tr("Delete entry")) { confirmDelete = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Delete, tr("Delete entry"), tint = F.danger) }
                        if (!f.isAdjustment) Row(
                            Modifier.height(52.dp).clip(F.buttonShape).background(F.card).border(1.dp, F.border, F.buttonShape)
                                .clickable { sheet = AddSheet.COPY }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, tint = F.ink, modifier = Modifier.size(18.dp))
                            Text(tr("Copy"), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        PrimaryButton(tr("Save changes"), { save(false) }, Modifier.weight(1f))
                    } else {
                        SecondaryButton(tr("Continue"), { save(true) }, Modifier.width(116.dp))
                        PrimaryButton(saveLabel, { save(false) }, Modifier.weight(1f), enabled = ready)
                    }
                }
            },
        ) {
            FCard {
                TypeTabs(f, Modifier.fillMaxWidth(), compact = false)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        shown(fmt, addExprShown(f.amountText)),
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { focus.clearFocus(); sheet = AddSheet.AMOUNT },
                        color = if ((total ?: 0) > 0) F.ink else F.muted, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = F.money,
                    )
                    currencyChip()
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallChip(dateLabel(f.date), datesOpen, Icons.Outlined.CalendarToday) { datesOpen = !datesOpen }
                    if (timeOn) SmallChip(f.timeOfDay?.let { formatTime(it) } ?: tr("Add time"), false, null) { timePicker = true }
                }
                if (datesOpen) { Spacer(Modifier.height(8.dp)); dateChips() }
            }

            FCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (isTransfer) {
                        FRow(tr("From"), value = accountName(f.accountId) ?: tr("Choose"), valueColor = F.ink, onClick = { sheet = AddSheet.FROM }, trailing = {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(F.fill)
                                    .clickable(onClickLabel = tr("Swap accounts")) { val a = f.accountId; f.accountId = f.toAccountId; f.toAccountId = a },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.SwapVert, tr("Swap accounts"), tint = F.accent, modifier = Modifier.size(18.dp)) }
                        })
                        Divider()
                        FRow(tr("To"), value = accountName(f.toAccountId) ?: tr("Choose"), valueColor = F.ink, onClick = { sheet = AddSheet.TO },
                            subtitle = if (f.accountId != null && f.accountId == f.toAccountId) tr("Pick two different accounts") else null)
                        Divider()
                        FRow(tr("Fee"), subtitle = tr("Recorded as an expense"),
                            value = f.feeText.takeIf { it.isNotBlank() }?.let { fmt.parse(addSettle(it, fmt))?.let { v -> fmt.format(v) } ?: it } ?: tr("None"),
                            onClick = { sheet = AddSheet.FEE })
                    } else {
                        if (f.isAdjustment) FRow(tr("Category"), value = tr(MODIFIED_BALANCE_NAME), chevron = false)
                        else FRow(tr("Category"), value = catLabel ?: tr("Choose"), valueColor = if (catLabel == null) F.muted else F.ink,
                            onClick = { sheet = AddSheet.CATEGORY })
                        Divider()
                        FRow(tr("Account"), value = accountName(f.accountId) ?: tr("Choose"), valueColor = if (f.accountId == null) F.danger else F.ink,
                            onClick = { sheet = AddSheet.ACCOUNT })
                    }
                    if (!f.isAdjustment) {
                        Divider()
                        FRow(tr("Tags"), value = tags.filter { it.id in f.tagIds }.joinToString(" ") { "#" + it.name }.ifEmpty { tr("None") },
                            valueColor = if (f.tagIds.isEmpty()) F.muted else F.accent, onClick = { sheet = AddSheet.TAGS })
                        Divider()
                        FRow(
                            tr("Repeat"),
                            subtitle = f.repeatEnd?.takeIf { f.repeat != null }?.let { tr("Until {0}", it.long()) },
                            value = listOfNotNull(f.repeat?.let { tr(it.label) }, if (f.installments > 1) tr("{0} installments", f.installments) else null)
                                .joinToString(" · ").ifEmpty { tr("Doesn't repeat") },
                            onClick = { sheet = AddSheet.REPEAT },
                        )
                    }
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    noteField(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    noteSuggestions()
                    val refundable = !f.isAdjustment && (f.type == DraftType.EXPENSE || f.type == DraftType.REFUND) && e?.transferGroupId == null
                    if (refundable) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("This is a refund"), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(tr("Money back lowers this category's spending instead of counting as income"), color = F.muted, fontSize = 12.sp)
                        }
                        Switch(
                            f.type == DraftType.REFUND, { on -> f.type = if (on) DraftType.REFUND else DraftType.EXPENSE },
                            colors = SwitchDefaults.colors(checkedTrackColor = F.accent, checkedThumbColor = F.onAccent, uncheckedTrackColor = F.fill, uncheckedBorderColor = F.border),
                        )
                    } else Spacer(Modifier.height(8.dp))
                }
            }

            FCard {
                Text(tr("Description"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AddInput(f.memo, { f.memo = it }, tr("Anything you want to remember"), Modifier.fillMaxWidth(), singleLine = false,
                    fieldModifier = Modifier.heightIn(min = 40.dp))
                val allPhotos = if (f.isAdjustment) emptyList() else f.existingPhotos.map { it.fileName } + f.newPhotos
                if (!f.isAdjustment || (f.lat != null && f.lng != null)) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        allPhotos.forEachIndexed { i, file ->
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(F.fill).clickable(onClickLabel = tr("View receipt photo {0}", i + 1)) { viewer = i }) {
                                platform.PhotoImage(file, Modifier.fillMaxSize())
                            }
                        }
                        if (!f.isAdjustment) Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, F.border, RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = tr("Add photo")) {
                                    focus.clearFocus()
                                    if (repo.settingNow(PHOTO_NOTICE_KEY) == "1") photoMenu = !photoMenu else photoNotice = true
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PhotoCamera, tr("Add photo"), tint = F.muted) }
                        val la = f.lat; val ln = f.lng
                        if (la != null && ln != null) Row(
                            Modifier.height(36.dp).clip(RoundedCornerShape(999.dp)).background(F.accentTint).clickable { platform.openMap(la, ln) }.padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.LocationOn, null, tint = F.accent, modifier = Modifier.size(16.dp))
                            Text(tr("Location saved"), color = F.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Box(
                                Modifier.size(36.dp).clickable(onClickLabel = tr("Remove location")) { f.lat = null; f.lng = null; toaster.show(tr("Location removed from this entry")) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, tr("Remove location"), tint = F.accent, modifier = Modifier.size(14.dp)) }
                        }
                    }
                    if (photoMenu) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(tr("Take photo"), false, { photoMenu = false; platform.takePhoto { f.newPhotos = f.newPhotos + it } })
                        Pill(tr("Choose from gallery"), false, { photoMenu = false; platform.pickPhoto { f.newPhotos = f.newPhotos + it } })
                    }
                    if (!f.isAdjustment) Text(tr("Photos stay private on this phone."), Modifier.padding(top = 8.dp), color = F.muted, fontSize = 12.sp)
                }
                if (f.origin.isNotBlank() && repo.settingNow(Keys.SMS_SHOW_ORIGINAL) != "0") {
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Original message"), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(f.origin, Modifier.padding(top = 2.dp), color = F.ink, fontSize = 13.sp)
                }
                viewer?.let { start ->
                    AddPhotoViewer(allPhotos, start, onRemove = { i ->
                        val file = allPhotos[i]
                        val p = f.existingPhotos.firstOrNull { it.fileName == file }
                        if (p != null) f.existingPhotos = f.existingPhotos - p
                        else { f.newPhotos = f.newPhotos - file; platform.deletePhotoFile(file) }
                        viewer = null
                        toaster.show(if (p != null) tr("Photo removed. Save to keep the change") else tr("Photo removed"))
                    }, onDismiss = { viewer = null })
                }
            }
            f.error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = F.danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    // ───────────── sheets and dialogs

    when (sheet) {
        AddSheet.NONE -> Unit
        AddSheet.AMOUNT, AddSheet.FEE -> {
            val fee = sheet == AddSheet.FEE
            val text = if (fee) f.feeText else f.amountText
            fun set(v: String) { if (fee) f.feeText = v else f.amountText = v }
            Sheet(if (fee) tr("Fee") else tr("Amount"), { sheet = AddSheet.NONE }, bottom = {
                PrimaryButton(tr("Done"), { set(addSettle(text, fmt)); sheet = AddSheet.NONE })
            }) {
                Text(shown(fmt, addExprShown(text)), Modifier.fillMaxWidth(), color = F.ink, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, style = F.money)
                if (Calc.hasOperator(text)) Text("= " + (fmt.parse(addSettle(text, fmt))?.let { fmt.format(it) } ?: "…"), color = F.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AddKeypad(text, fmt, ::set)
            }
        }
        AddSheet.CATEGORY -> {
            val mains = categories.filter { it.kind == f.catKind && it.parentId == null }
            val subsOf = categories.filter { it.parentId != null && it.kind == f.catKind }.groupBy { it.parentId }
            val current = f.categoryId?.let { catById[it] }
            val selectedMain = current?.parentId ?: current?.id
            fun done() { sheet = if (f.accountId == null) AddSheet.ACCOUNT else AddSheet.NONE }
            Sheet(if (f.catKind == CategoryKind.INCOME) tr("Income categories") else tr("Expense categories"), { sheet = AddSheet.NONE }) {
                if (mains.isEmpty()) Text(tr("No categories yet"), color = F.muted, fontSize = 14.sp)
                AddGrid(mains) { m, mod ->
                    AddTile(m.name, m.name, m.id == selectedMain, mod) {
                        f.pickCategory(m)
                        if (!(useSubcategories && subsOf.containsKey(m.id))) done()
                    }
                }
                val subs = if (useSubcategories) subsOf[selectedMain].orEmpty() else emptyList()
                if (subs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("{0} has sub-categories (optional)", catById[selectedMain]?.name.orEmpty()), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        subs.forEach { sub -> Pill(sub.name, sub.id == f.categoryId, { f.pickCategory(sub); done() }) }
                    }
                }
                AddLink(tr("Edit categories ›")) { sheet = AddSheet.NONE; nav.push(Screen.Categories(f.catKind)) }
            }
        }
        AddSheet.ACCOUNT, AddSheet.FROM, AddSheet.TO -> {
            val forTo = sheet == AddSheet.TO
            val list = accounts.filter { !forTo || it.id != f.accountId }
            val current = if (forTo) f.toAccountId else f.accountId
            var showHidden by remember { mutableStateOf(false) }
            val usual = remember(sheet) { if (sheet == AddSheet.ACCOUNT) repo.habitAccount(f.txnKind, f.categoryId) else null }
            val title = when (sheet) {
                AddSheet.FROM -> tr("Move from")
                AddSheet.TO -> tr("Move to")
                else -> if (f.type == DraftType.INCOME || f.type == DraftType.REFUND) tr("Paid into") else tr("Pay from")
            }
            fun pick(a: Account) {
                if (forTo) { f.toAccountId = a.id; sheet = AddSheet.NONE }
                else { f.pickFrom(a); sheet = if (isTransfer && f.toAccountId == null) AddSheet.TO else AddSheet.NONE }
            }
            Sheet(title, { sheet = AddSheet.NONE }) {
                if (list.isEmpty()) Text(tr("No accounts yet"), color = F.muted, fontSize = 14.sp)
                val known = groups.map { it.id }.toSet()
                val sections = groups.map { it.name to list.filter { a -> a.groupId == it.id } } + (tr("Other") to list.filter { it.groupId !in known })
                sections.forEach { (name, items) ->
                    val shownItems = items.filter { !it.hidden || showHidden || it.id == current }
                    if (shownItems.isNotEmpty()) {
                        SectionLabel(name)
                        shownItems.forEach { a ->
                            SheetOption(
                                a.name, a.id == current,
                                subtitle = listOfNotNull(if (a.id == usual) tr("Usual") else null, if (a.hidden) tr("Hidden") else null, a.currency).joinToString(" · ").ifEmpty { null },
                                leading = { Mark(a.name, 32.dp) },
                            ) { pick(a) }
                        }
                    }
                }
                val hiddenCount = list.count { it.hidden && it.id != current }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (hiddenCount > 0) AddLink(if (showHidden) tr("Hide hidden accounts") else tr("Show hidden accounts ({0})", hiddenCount)) { showHidden = !showHidden }
                    else Spacer(Modifier.width(1.dp))
                    AddLink(if (accounts.isEmpty()) tr("Add an account ›") else tr("Edit accounts ›")) {
                        sheet = AddSheet.NONE; nav.push(if (accounts.isEmpty()) Screen.EditAccount() else Screen.AccountList)
                    }
                }
            }
        }
        AddSheet.TAGS -> Sheet(tr("Tags"), { sheet = AddSheet.NONE }, bottom = { PrimaryButton(tr("Done"), { sheet = AddSheet.NONE }) }) {
            if (tags.isEmpty()) Text(tr("No tags yet"), color = F.muted, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    val on = t.id in f.tagIds
                    Pill("#" + t.name, on, { f.tagIds = if (on) f.tagIds - t.id else f.tagIds + t.id })
                }
            }
            AddLink(tr("Manage tags ›")) { sheet = AddSheet.NONE; nav.push(Screen.Tags) }
        }
        AddSheet.REPEAT -> RepeatSheet(
            canRepeat = f.existing == null, canInstall = f.type == DraftType.EXPENSE && f.isCreditCard,
            repeat = f.repeat, repeatEnd = f.repeatEnd, installments = f.installments, start = f.date,
            onDismiss = { sheet = AddSheet.NONE },
            onApply = { r, end, inst -> f.repeat = r; f.repeatEnd = end; f.installments = inst; sheet = AddSheet.NONE },
        )
        AddSheet.CURRENCY -> Sheet(tr("Currency"), { sheet = AddSheet.NONE }) {
            SheetOption("${currency.symbol}  ${tr("Main currency")}", f.currencyCode == null) { f.currencyCode = null; sheet = AddSheet.NONE }
            currencies.values.sortedBy { it.sortOrder }.forEach { c ->
                SheetOption("${c.symbol}  ${c.code}", f.currencyCode == c.code) { f.currencyCode = c.code; sheet = AddSheet.NONE }
            }
            AddLink(tr("Manage currencies ›")) { sheet = AddSheet.NONE; nav.push(Screen.Currencies) }
        }
        AddSheet.BOOKMARK -> {
            var name by remember { mutableStateOf(f.note.ifBlank { f.categoryId?.let { categoryPath(it, catById) }.orEmpty() }) }
            Sheet(tr("Save as bookmark"), { sheet = AddSheet.NONE }, bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(tr("Cancel"), { sheet = AddSheet.NONE }, Modifier.weight(1f))
                    PrimaryButton(tr("Save bookmark"), {
                        val t = Template(newId(), name.trim(), f.type, f.accountId, f.toAccountId, f.categoryId,
                            fmt.parse(addSettle(f.amountText, fmt)) ?: 0, f.note.trim(), f.memo.trim())
                        scope.launch { withContext(repo.io) { repo.saveTemplate(t) } }
                        bookmarked = true; sheet = AddSheet.NONE
                        toaster.show(tr("“{0}” added to Quick add on Home", t.name))
                    }, Modifier.weight(1f), enabled = name.isNotBlank())
                }
            }) {
                Text(tr("Bookmarks pre-fill a new entry in one tap. Leave the amount empty to be asked each time."), color = F.muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                AddInput(name, { name = it }, tr("Name"), Modifier.fillMaxWidth())
            }
        }
        AddSheet.COPY -> {
            val e = f.existing
            fun open(preset: (TxnDraft) -> TxnDraft) {
                sheet = AddSheet.NONE
                if (e != null) scope.launch {
                    val d = withContext(repo.io) { repo.draftFrom(e) }
                    nav.pop(); nav.push(Screen.EditTxn(preset = preset(d)))
                }
            }
            Sheet(tr("Copy entry"), { sheet = AddSheet.NONE }) {
                Text(tr("Opens a new entry with the same amount, category, account and note."), color = F.muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                SheetOption(tr("Copy with today's date")) { open { it.copy(date = repo.today()) } }
                SheetOption(tr("Copy with the entry's date ({0})", f.date.short())) { open { it } }
                // A refund for this purchase: same category, account and amount, all editable.
                if (e != null && e.kind == TxnKind.EXPENSE && !e.isRefund && e.transferGroupId == null) {
                    SheetOption(tr("Record a refund for this"), subtitle = tr("Money back for this purchase")) {
                        sheet = AddSheet.NONE
                        nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.REFUND, repo.today(), f.accountId.orEmpty(), categoryId = f.categoryId,
                            amount = f.amountValue() ?: 0L, note = f.note, currency = f.currencyCode, tagIds = f.tagIds)))
                    }
                }
            }
        }
    }

    DatePickerHost(datePicker, f.date, { datePicker = false }, { it?.let { d -> f.date = d; datesOpen = false } })
    if (timePicker) AddTimePicker(f.timeOfDay ?: nowMinutes(), { timePicker = false }, { f.timeOfDay = null; timePicker = false }) {
        f.timeOfDay = it; timePicker = false
    }
    if (confirmDiscard) Confirm(tr("Discard this entry?"), tr("What you've entered won't be saved."), tr("Discard"), { confirmDiscard = false }) { nav.pop() }
    if (photoNotice) Confirm(
        tr("About receipt photos"),
        tr("Photos are saved privately on this phone and shown with the entry. They're not included in backups or exports yet, " +
            "and they're removed if you uninstall the app."),
        tr("OK"), { photoNotice = false }, danger = false,
    ) {
        photoMenu = true
        scope.launch { withContext(repo.io) { repo.putSetting(PHOTO_NOTICE_KEY, "1") } }
    }
    if (confirmDelete) f.existing?.let { e ->
        Confirm(
            tr("Delete transaction?"),
            when {
                e.installmentGroupId != null -> tr("This deletes every installment of this purchase.")
                e.transferGroupId != null -> tr("Both sides of the transfer (and its fee) will be deleted.")
                else -> tr("{0} on {1}. This cannot be undone.", fmt.format(f.amountValue() ?: 0L), f.date.long())
            },
            tr("Delete"), { confirmDelete = false },
        ) {
            scope.launch {
                withContext(repo.io) { repo.delete(e, wholeInstallmentPlan = true) }
                platform.refreshWidgets()
                nav.pop(); toaster.show(tr("Entry deleted"))
            }
        }
    }
}

/** Expense / Income / Transfer; a refund sits on Expense, an adjustment has none selected. */
@Composable
private fun TypeTabs(f: AddForm, modifier: Modifier, compact: Boolean) {
    val tabs = listOf(DraftType.EXPENSE to tr("Expense"), DraftType.INCOME to tr("Income"), DraftType.TRANSFER to tr("Transfer"))
    val selected = if (f.isAdjustment) null else if (f.type == DraftType.REFUND) DraftType.EXPENSE else f.type
    Row(modifier.height(44.dp).clip(F.iconShape).background(F.fill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEach { (t, label) ->
            val on = t == selected
            val ink = when (t) { DraftType.INCOME -> F.income; DraftType.TRANSFER -> F.accent; else -> F.expense }
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) F.card else Color.Transparent).clickable { f.pickType(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) ink else F.muted, fontSize = if (compact) 13.sp else 13.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AccountBox(label: String, name: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, F.line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(name, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SuggestChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).background(if (accent) F.accentTint else F.fill)
            .border(1.dp, if (accent) F.accentTint else F.border, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) F.accent else F.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun SmallChip(text: String, selected: Boolean, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) F.accentTint else F.fill).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = F.ink, modifier = Modifier.size(14.dp))
        Text(text, color = F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun RepeatSheet(
    canRepeat: Boolean,
    canInstall: Boolean,
    repeat: RepeatType?,
    repeatEnd: LocalDate?,
    installments: Int,
    start: LocalDate,
    onDismiss: () -> Unit,
    onApply: (RepeatType?, LocalDate?, Int) -> Unit,
) {
    var r by remember { mutableStateOf(repeat) }
    var end by remember { mutableStateOf(repeatEnd) }
    var inst by remember { mutableStateOf(installments) }
    var other by remember { mutableStateOf(if (installments in instChoices) "" else installments.toString()) }
    var endPicker by remember { mutableStateOf(false) }
    Sheet(tr("Repeat / installments"), onDismiss, bottom = { PrimaryButton(tr("Done"), { onApply(r, if (r == null) null else end, inst) }) }) {
        SectionLabel(tr("Repeat"))
        if (canRepeat) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("Doesn't repeat"), r == null, { r = null })
                RepeatType.entries.forEach { t -> Pill(tr(t.label), r == t, { r = t }) }
            }
            if (r != null) FRow(tr("Until"), value = end?.long() ?: tr("No end date"), onClick = { endPicker = true })
            Text(tr("All rules are listed in Settings › Repeat entries."), color = F.muted, fontSize = 12.sp)
        } else Text(tr("Repeat can only be set on new transactions."), color = F.muted, fontSize = 13.sp)
        SectionLabel(tr("Installments"))
        if (canInstall) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                instChoices.forEach { n -> Pill(if (n == 1) tr("None") else tr("{0} months", n), inst == n && other.isEmpty(), { inst = n; other = "" }) }
            }
            AddInput(other, { v -> other = v.filter(Char::isDigit).take(2); inst = (other.toIntOrNull() ?: 1).coerceIn(1, 60) },
                tr("Other number of months"), Modifier.fillMaxWidth().padding(top = 6.dp), number = true)
        } else Text(tr("Installments are available for credit-card expenses."), color = F.muted, fontSize = 13.sp)
    }
    DatePickerHost(endPicker, end ?: start, { endPicker = false }, { end = it }, clearable = true)
}

private val instChoices = listOf(1, 2, 3, 6, 9, 12, 18, 24)
