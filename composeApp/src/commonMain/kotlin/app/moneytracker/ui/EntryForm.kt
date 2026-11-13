package app.moneytracker.ui

import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.InputOrder
import app.moneytracker.data.Prefs
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TimeInput
import app.moneytracker.data.TxnDraft
import app.moneytracker.data.primaryKind
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Photo
import app.moneytracker.domain.RepeatType
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.LocalDate

/**
 * Everything typed into the entry form (add or edit), as both layouts draw it. Immutable: [EntryFormModel] swaps in
 * a changed copy, and owns the rules for changing it.
 */
data class EntryForm(
    val date: LocalDate,
    /** The entry being edited; null for a new one. */
    val existing: Txn? = null,
    /** Balance adjustments edit as Increase / Decrease. */
    val adjustUp: Boolean = true,
    /** Picking a type on a balance adjustment turns it into a normal entry. */
    val converted: Boolean = false,
    val type: DraftType = DraftType.EXPENSE,
    val accountId: String? = null,
    /** True once the account came from the user (or an existing entry / preset); defaults never override it. */
    val accountChosen: Boolean = false,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val currencyCode: String? = null,
    val amountText: String = "",
    val feeText: String = "",
    val tagIds: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
    val existingPhotos: List<Photo> = emptyList(),
    /** Saved photos removed in this edit: they leave the entry only when it's saved. */
    val removedPhotoIds: Set<String> = emptySet(),
    val newPhotos: List<String> = emptyList(),
    val note: String = "",
    val memo: String = "",
    val origin: String = "",
    val installments: Int = 1,
    val repeat: RepeatType? = null,
    val repeatEnd: LocalDate? = null,
    val timeOfDay: Int? = null,
    /** Why the last Save was refused; cleared once a field it checks changes. */
    val problem: EntryProblem? = null,
) {
    val isAdjustment: Boolean get() = existing?.kind?.isAdjustment == true && !converted
    val isTransfer: Boolean get() = type == DraftType.TRANSFER
    val catKind: CategoryKind get() = if (type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val txnKind: TxnKind get() = type.primaryKind()

    /** File names the entry will have once saved: the kept saved photos, then the new ones. */
    val photoFiles: List<String> get() = existingPhotos.map { it.fileName } + newPhotos

    /** A purchase that can get a refund recorded against it. */
    val canRecordRefund: Boolean get() = existing?.let { it.kind == TxnKind.EXPENSE && !it.isRefund && it.transferGroupId == null } == true

    /** The fields a refused Save looked at; changing one clears [problem]. */
    internal fun checkedFields(): List<Any?> = listOf(amountText, accountId, toAccountId, categoryId, type)

    /** What the unsaved-changes guard compares with the form as it was loaded. */
    internal fun dirtyKey(): String = listOf(type, date, accountId, toAccountId, categoryId, amountText, feeText, note, memo,
        installments, repeat, repeatEnd, tagIds, currencyCode, newPhotos, existingPhotos.size, timeOfDay, adjustUp, converted).toString()

    /** Takes [a] as a default account; its currency too while no amount is typed or no currency is set. */
    internal fun withDefaultAccount(a: Account): EntryForm =
        copy(accountId = a.id, currencyCode = if (amountText.isEmpty() || currencyCode == null) a.currency else currencyCode)

    internal fun toDraft(accountId: String, amount: Long, fee: Long) = TxnDraft(
        type, date, accountId, toAccountId, categoryId, amount, fee, note.trim(), memo.trim(), installments, repeat, repeatEnd,
        currency = currencyCode, tagIds = tagIds, lat = lat, lng = lng, timeOfDay = timeOfDay, origin = origin,
    )
}

/** The learned category worth offering: only while none is picked, and only of the form's kind. */
fun EntryForm.suggestedCategory(learned: String?, catById: Map<String, Category>): String? =
    learned?.takeIf { categoryId == null && catById[it]?.kind == catKind }

/** What the form starts from, read once when the screen opens. */
class EntrySetup(
    val screen: Screen.EditTxn,
    val main: CurrencyFormat,
    val currencies: Map<String, Currency>,
    /** Settings › Input order: a new entry starts on the account. */
    val accountFirst: Boolean,
    /** Settings › Time input: new entries get the current time. */
    val timeOn: Boolean,
    val today: () -> LocalDate,
) {
    /** How amounts in [code] are typed and shown (null = the main currency). */
    fun formatOf(code: String?): CurrencyFormat = code?.let { currencies[it]?.format } ?: main

    /** The form for a new entry: blank, or filled from the screen's preset. */
    fun blankForm(): EntryForm {
        val p = screen.preset
        val accountId = p?.accountId?.ifBlank { null }
        return EntryForm(
            date = screen.date ?: p?.date ?: today(),
            type = p?.type ?: DraftType.EXPENSE,
            accountId = accountId, accountChosen = accountId != null,
            toAccountId = p?.toAccountId?.ifBlank { null }, categoryId = p?.categoryId, currencyCode = p?.currency,
            amountText = p?.amount?.takeIf { it > 0 }?.let { formatOf(p.currency).toInput(it) }.orEmpty(),
            tagIds = p?.tagIds.orEmpty(), note = p?.note.orEmpty(), memo = p?.memo.orEmpty(), origin = p?.origin.orEmpty(),
            installments = p?.installments ?: 1, repeat = p?.repeat, repeatEnd = p?.repeatEnd,
            timeOfDay = p?.timeOfDay ?: if (screen.txnId == null && timeOn) nowMinutes() else null,
        )
    }

    companion object {
        fun read(screen: Screen.EditTxn, settings: SettingsStore, main: CurrencyFormat, currencies: Map<String, Currency>, today: () -> LocalDate) =
            EntrySetup(
                screen, main, currencies,
                accountFirst = settings.settingNow(Prefs.INPUT_ORDER) == InputOrder.ACCOUNT,
                timeOn = settings.settingNow(Prefs.TIME_INPUT) != TimeInput.NONE,
                today = today,
            )
    }
}

/** Plain-string form of [EntryForm] for the platform's saved state; [baseline] rides along. */
internal fun EntryForm.saveState(baseline: String?): String = StateWriter().bool(adjustUp).bool(converted).str(type.name).date(date)
    .str(accountId).bool(accountChosen).str(toAccountId).str(categoryId).str(currencyCode).str(amountText).str(feeText).strings(tagIds)
    .double(lat).double(lng).strings(removedPhotoIds.toList()).strings(newPhotos).str(note).str(memo).str(origin).int(installments)
    .str(repeat?.name).date(repeatEnd).int(timeOfDay).str(baseline).toString()

/** Puts [saved] back over this freshly loaded form, with the baseline it carried; null when it can't be read. */
internal fun EntryForm.restoredFrom(saved: String): Pair<EntryForm, String?>? = runCatching {
    val r = StateReader(saved)
    val form = copy(
        adjustUp = r.bool(), converted = r.bool(), type = DraftType.valueOf(r.need()), date = r.needDate(), accountId = r.str(),
        accountChosen = r.bool(), toAccountId = r.str(), categoryId = r.str(), currencyCode = r.str(), amountText = r.need(),
        feeText = r.need(), tagIds = r.strings(), lat = r.double(), lng = r.double(), removedPhotoIds = r.strings().toSet(),
        newPhotos = r.strings(), note = r.need(), memo = r.need(), origin = r.need(), installments = r.needInt(),
        repeat = r.str()?.let { RepeatType.valueOf(it) }, repeatEnd = r.date(), timeOfDay = r.int(),
    )
    form.copy(existingPhotos = existingPhotos.filter { it.id !in form.removedPhotoIds }) to r.str()
}.getOrNull()
