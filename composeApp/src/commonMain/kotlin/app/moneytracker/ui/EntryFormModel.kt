package app.moneytracker.ui

import app.moneytracker.data.NoteSuggestion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import app.moneytracker.data.BookmarkStore
import app.moneytracker.data.CaptureStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.PhotoStore
import app.moneytracker.data.Template
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.newId
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a save ended. [undo] takes back a plain new entry (no repeat, not from a bank message); it outlives the screen. */
class EntrySaved(val isNew: Boolean, val undo: (() -> Unit)?)

/**
 * The entry form shared by both layouts: what is typed ([form]), the rules for changing it (defaults for the account,
 * type switches, past notes), and saving or deleting through [EntryEditor]. Each layout only draws [form] and calls
 * these functions. Plain field edits go through [edit]; anything with a rule has its own function.
 */
class EntryFormModel(
    private val editor: EntryEditor,
    private val guess: AccountGuess,
    private val categories: CategoryStore,
    private val photos: PhotoStore,
    private val captures: CaptureStore,
    private val bookmarks: BookmarkStore,
    val setup: EntrySetup,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    var form by mutableStateOf(setup.blankForm())
        private set

    private var opened: EntryEditor.Opened? = null
    private var baseline by mutableStateOf<String?>(null)
    private var baselineRestored = false

    /** How amounts are typed and shown in the form's currency. */
    val fmt: CurrencyFormat get() = setup.formatOf(form.currencyCode)

    /** True once the form differs from what it held when loaded. */
    val isDirty: Boolean get() = baseline != null && form.dirtyKey() != baseline

    /** Installments are offered for credit-card expenses. */
    val canInstall: Boolean get() = form.type == DraftType.EXPENSE && guess.isCreditCard(form.accountId)

    init {
        val id = setup.screen.txnId
        if (id != null) load(id) else if (setup.screen.preset == null && !setup.accountFirst) applyHabitAccount(null)
        followPhotos()
    }

    // Filled before the first frame: a few indexed rows, and loading later would flash the new-entry defaults.
    private fun load(id: String) {
        val o = editor.open(id) ?: return
        opened = o
        val d = o.draft
        val f = setup.formatOf(d.currency)
        form = form.copy(
            existing = o.txn, existingPhotos = o.photos, adjustUp = o.txn.kind == TxnKind.ADJUST_UP,
            type = d.type, date = d.date, accountId = d.accountId, toAccountId = d.toAccountId, accountChosen = true,
            categoryId = if (d.type == DraftType.TRANSFER) null else d.categoryId, currencyCode = d.currency,
            amountText = f.toInput(d.amount), feeText = if (d.fee > 0) f.toInput(d.fee) else form.feeText,
            note = d.note, memo = d.memo, installments = d.installments, tagIds = d.tagIds, timeOfDay = d.timeOfDay,
            origin = d.origin, lat = d.lat, lng = d.lng,
        )
    }

    /** Keeps the saved photos current (added or removed elsewhere while editing). */
    private fun followPhotos() {
        val t = form.existing ?: return
        scope.launch {
            photos.photos(t.id).collect { list -> form = form.copy(existingPhotos = list.filter { it.id !in form.removedPhotoIds }) }
        }
    }

    /** A plain field edit, such as the note, date, tags or a new photo. */
    fun edit(change: (EntryForm) -> EntryForm) = set(change(form))

    private fun set(next: EntryForm) {
        val fixed = next.problem != null && next.checkedFields() != form.checkedFields()
        form = if (fixed) next.copy(problem = null) else next
    }

    /** The amount typed so far, when it's a plain number (not a sum still being worked out); helps guess the account. */
    private fun enteredAmount(): Long? = form.amountText.takeIf { it.isNotBlank() }?.let { fmt.parse(it) }?.takeIf { it > 0 }

    /** The amount, with a half-typed calculation counted as its result. */
    fun amountValue(): Long? = fmt.parse(settleAmount(form.amountText, fmt))

    private fun feeValue(): Long? = if (form.isTransfer && form.feeText.isNotBlank()) fmt.parse(settleAmount(form.feeText, fmt)) else 0L

    /** The account usually used for this kind of entry (and [category]), unless one was chosen. */
    fun applyHabitAccount(category: String?) {
        val f = form
        if (f.accountChosen || f.isTransfer) return
        val a = guess.usual(f.txnKind, category, f.note, enteredAmount()) ?: return
        set(f.withDefaultAccount(a))
    }

    /** The account the tray marks as usual. */
    fun usualAccount(): String? = guess.usualId(form.txnKind, form.categoryId, form.note, enteredAmount())

    /**
     * Expense / Income / Transfer. On a balance adjustment this turns it into a normal entry. A refund stays put on
     * Income; [keepRefundCategory] (classic layout) keeps the expense category when a refund becomes an expense.
     */
    fun pickType(t: DraftType, keepRefundCategory: Boolean = false) {
        val f = form
        when {
            f.isAdjustment -> set(f.copy(converted = true, type = t, categoryId = if (t == DraftType.TRANSFER) null else MODIFIED_BALANCE_CATEGORY))
            t == f.type || (t == DraftType.INCOME && f.type == DraftType.REFUND) -> Unit
            else -> {
                val keep = keepRefundCategory && t == DraftType.EXPENSE && f.type == DraftType.REFUND
                set(f.copy(type = t, categoryId = if (keep) f.categoryId else null, toAccountId = if (t == DraftType.TRANSFER) f.toAccountId else null))
                applyHabitAccount(form.categoryId)
            }
        }
    }

    /** Income ⇄ refund (money back into an expense category). Returns true when it is now a refund. */
    fun toggleRefund(): Boolean {
        val on = form.type != DraftType.REFUND
        set(form.copy(type = if (on) DraftType.REFUND else DraftType.INCOME, categoryId = null))
        return on
    }

    /** Also takes the category's preferred account (or its main category's), else the usual one, unless one was picked. */
    fun pickCategory(c: Category) {
        val f = form.copy(categoryId = c.id)
        val default = guess.forCategory(c, f.txnKind, f.note, enteredAmount())
        set(if (!f.accountChosen && default != null) f.withDefaultAccount(default) else f)
    }

    /** The account paid from (or into); a transfer's destination can't be the same one. */
    fun pickFrom(a: Account) {
        val f = form.withDefaultAccount(a)
        set(f.copy(accountChosen = true, toAccountId = f.toAccountId.takeIf { it != a.id }))
    }

    fun pickTo(a: Account) = set(form.copy(toAccountId = a.id))

    fun swapAccounts() = set(form.copy(accountId = form.toAccountId, toAccountId = form.accountId))

    /** Picking a past note also brings back its category, account and amount (unless already set). */
    fun pickNote(m: NoteSuggestion) {
        var f = form.copy(note = m.note)
        val categoryId = m.categoryId
        if (!f.isTransfer && categoryId != null && categories.categoryById.current[categoryId]?.kind == f.catKind) f = f.copy(categoryId = categoryId)
        if (!f.accountChosen && m.accountId != null && guess.live(m.accountId) != null) f = f.copy(accountId = m.accountId, accountChosen = true)
        if (canTakeAmount(f, m)) f = f.copy(currencyCode = m.currency, amountText = setup.formatOf(m.currency).toInput(m.amount))
        set(f)
    }

    // The amount used last time, in its currency, unless one is already typed.
    private fun canTakeAmount(f: EntryForm, m: NoteSuggestion) =
        f.amountText.isEmpty() && m.amount > 0 && (m.currency == null || setup.currencies.containsKey(m.currency))

    /** Fills in what Smart entry understood from speech or typing; a guessed account stays a default. */
    fun fillFrom(g: SmartGuess) {
        val d = g.draft
        if (setup.screen.txnId == null) pickType(if (d.type == DraftType.REFUND) DraftType.INCOME else d.type)
        var f = form
        if (d.type == DraftType.REFUND && f.type == DraftType.INCOME) f = f.copy(type = DraftType.REFUND, categoryId = null)
        if (d.amount > 0) f = f.copy(amountText = fmt.toInput(d.amount))
        if (d.note.isNotBlank()) f = f.copy(note = d.note)
        f = f.copy(date = d.date)
        if (d.type != DraftType.REFUND && d.categoryId != null) f = f.copy(categoryId = d.categoryId)
        if (d.accountId.isNotBlank() && (!f.accountChosen || !g.accountGuessed)) f = f.copy(accountId = d.accountId, accountChosen = !g.accountGuessed)
        set(f)
    }

    /** Removes [file]. Returns true for a saved photo (it leaves the entry on Save); a new one the caller deletes itself. */
    fun removePhoto(file: String): Boolean {
        val f = form
        val saved = f.existingPhotos.firstOrNull { it.fileName == file }
        set(if (saved != null) f.copy(existingPhotos = f.existingPhotos - saved, removedPhotoIds = f.removedPhotoIds + saved.id)
            else f.copy(newPhotos = f.newPhotos - file))
        return saved != null
    }

    /**
     * Checks the form and, when it's complete, saves it off the main thread, then calls [onSaved] on the caller's
     * thread. Returns why it can't be saved (also kept in [EntryForm.problem]).
     */
    fun save(onSaved: (EntrySaved) -> Unit): EntryProblem? {
        val f = form
        val amount = amountValue()
        val fee = feeValue()
        // Every entry needs a category (transfers and balance corrections have none of their own).
        val problem = EntryEditor.check(f.type, amount, fee, f.accountId, f.toAccountId, f.categoryId, f.isAdjustment)
        form = f.copy(problem = problem)
        val accountId = f.accountId
        if (problem != null) return problem
        if (amount == null || fee == null || accountId == null) return null  // ruled out by check()
        val existing = f.existing
        if (existing != null && f.isAdjustment) saveAdjustment(existing, amount, accountId, onSaved)
        else saveEntry(f.toDraft(accountId, amount, fee), onSaved)
        return null
    }

    private fun saveAdjustment(t: Txn, amount: Long, accountId: String, onSaved: (EntrySaved) -> Unit) {
        val f = form
        scope.launch {
            withContext(io) { editor.saveAdjustment(t, f.date, amount, accountId, f.adjustUp, f.note.trim(), f.memo.trim(), f.timeOfDay) }
            onSaved(EntrySaved(isNew = false, undo = null))
        }
    }

    private fun saveEntry(draft: TxnDraft, onSaved: (EntrySaved) -> Unit) {
        val existing = form.existing
        val files = form.photoFiles
        val capture = setup.screen.captureId?.let { id -> captures.pendingCaptures.current.firstOrNull { it.id == id } }
        scope.launch {
            val id = withContext(io) { editor.save(draft, existing, files, capture) }
            val undoable = existing == null && draft.repeat == null && capture == null
            onSaved(EntrySaved(isNew = existing == null, undo = if (undoable) ({ undoAdd(id) }) else null))
        }
    }

    // The screen may be gone by the time the toast's Undo is tapped.
    private fun undoAdd(id: String) {
        outlivingScope().launch { withContext(io) { editor.delete(id) } }
    }

    /** Deletes the entry being edited (a transfer with its pair and fee, an instalment with its whole plan). */
    fun delete(onDeleted: () -> Unit) {
        val e = form.existing ?: return
        scope.launch {
            withContext(io) { editor.delete(e) }
            onDeleted()
        }
    }

    /** Saves the form as a bookmark named [name] and returns it. */
    fun saveBookmark(name: String): Template {
        val f = form
        val t = Template(newId(), name.trim(), f.type, f.accountId, f.toAccountId, f.categoryId, amountValue() ?: 0, f.note.trim(), f.memo.trim())
        scope.launch { withContext(io) { bookmarks.saveTemplate(t) } }
        return t
    }

    /** The entry being edited as a new entry's preset (no location), dated today or on its own date. */
    fun copyDraft(withTodaysDate: Boolean): TxnDraft? {
        val d = opened?.draft?.copy(lat = null, lng = null) ?: return null
        return if (withTodaysDate) d.copy(date = setup.today()) else d
    }

    /** A refund for the purchase being edited: same category, account and amount, all editable. */
    fun refundDraft(): TxnDraft = TxnDraft(
        DraftType.REFUND, setup.today(), form.accountId.orEmpty(), categoryId = form.categoryId,
        amount = amountValue() ?: 0L, note = form.note, currency = form.currencyCode, tagIds = form.tagIds,
    )

    /** Clears the form for the next entry after Save & new; [usualAccount] pre-picks the usual account again. */
    fun startNext(usualAccount: Boolean) {
        form = form.copy(
            accountChosen = false, timeOfDay = if (setup.timeOn) nowMinutes() else null, amountText = "", feeText = "", note = "",
            memo = "", categoryId = null, tagIds = emptyList(), newPhotos = emptyList(), installments = 1, repeat = null,
            repeatEnd = null, problem = null, origin = "",
        )
        if (usualAccount && !setup.accountFirst) applyHabitAccount(null)
        baseline = form.dirtyKey()
    }

    /** Remembers the form as loaded, for [isDirty]. A restored form keeps the baseline it was saved with. */
    fun takeBaseline() {
        if (!baselineRestored && (setup.screen.txnId == null || form.existing != null)) baseline = form.dirtyKey()
    }

    /** Everything typed, for the platform's saved state (the app closed in the background). */
    fun saveState(): String = form.saveState(baseline)

    /** Puts back [saveState] over the freshly loaded form; a string from another version is ignored. */
    fun restoreState(saved: String) {
        val (restored, savedBaseline) = form.restoredFrom(saved) ?: return
        form = restored
        baseline = savedBaseline
        baselineRestored = savedBaseline != null
    }

    companion object {
        fun saver(make: () -> EntryFormModel) =
            Saver<EntryFormModel, String>(save = { it.saveState() }, restore = { make().apply { restoreState(it) } })
    }
}
