package app.moneytracker.data

import app.moneytracker.domain.Capture
import app.moneytracker.domain.Photo
import app.moneytracker.domain.Txn

/** Why an entry can't be saved yet. Each screen words it its own way (toast, inline text, web error). */
enum class EntryProblem { NO_AMOUNT, BAD_FEE, NO_ACCOUNT, NO_CATEGORY, NO_DESTINATION, SAME_ACCOUNT }

/**
 * The one save path for entries typed by the person: both phone layouts and the web dashboard.
 *
 * It owns what every editor used to repeat by hand: [TxnStore.update] rewrites an entry under a new id, so
 * photos are moved over and location is kept; repeat rules get no photos; instalments only on card expenses;
 * learning from the note; and marking a detected SMS as saved.
 *
 * Blocking database calls: run off the main thread. Form state stays in each screen; only the rules live here.
 */
class EntryEditor(
    private val txns: TxnStore,
    private val photoStore: PhotoStore,
    private val accounts: AccountStore,
    private val learning: LearningStore,
    private val intake: MessageIntake,
    private val writer: EntryWriter,
) {
    /** An entry opened for editing, with what [TxnStore.draftFrom] leaves out (location, photos). */
    class Opened(val txn: Txn, val draft: TxnDraft, val photos: List<Photo>) {
        val isAdjustment get() = txn.kind.isAdjustment
    }

    /** What a delete took away, so it can be put back (same photos, location and exchange rate). */
    class Deleted internal constructor(internal val draft: TxnDraft, internal val photos: List<String>)

    fun open(id: String): Opened? {
        val t = txns.txn(id) ?: return null
        return Opened(t, txns.draftFrom(t).copy(lat = t.lat, lng = t.lng), photoStore.photosNow(t.id))
    }

    /**
     * Saves [draft] as a new entry, or in place of [existing]. [photos] are the file names the entry should have
     * afterwards (removals in the form only take effect on save); null keeps an edited entry's photos as they are.
     * Returns the id of the saved entry (or of the new repeat rule).
     */
    fun save(draft: TxnDraft, existing: Txn? = null, photos: List<String>? = null, capture: Capture? = null): String {
        val transfer = draft.type == DraftType.TRANSFER
        val d = draft.copy(
            toAccountId = if (transfer) draft.toAccountId else null,
            categoryId = if (transfer) null else draft.categoryId,
            fee = if (transfer) draft.fee else 0,
            installments = if (draft.type == DraftType.EXPENSE && isCard(draft.accountId)) draft.installments else 1,
            repeat = if (existing == null) draft.repeat else null,
        )
        val id = txns.inTransaction {
            val files = photos ?: existing?.let { e -> photoStore.photosNow(e.id).map { it.fileName } }.orEmpty()
            val id = if (existing == null) txns.create(d) else txns.update(existing, d)
            if (d.repeat == null) files.forEach { photoStore.addPhoto(id, it) }
            learning.learnFromDraft(d)
            capture?.let { intake.completeCapture(it, d, id) }
            id
        }
        return id
    }

    /** Edits a balance correction in place. [amount] is in the account's currency; [up] = the balance went up. */
    fun saveAdjustment(t: Txn, date: kotlinx.datetime.LocalDate, amount: Long, accountId: String, up: Boolean, note: String, memo: String, timeOfDay: Int? = t.timeOfDay) {
        txns.updateAdjustment(t, date, amount, accountId, up, note, memo, timeOfDay)
    }

    /**
     * Deletes [t] (a transfer with its pair and fee, an instalment with its whole plan). Returns what [restore] needs
     * to put it back, or null for a balance correction, which isn't an entry to recreate.
     */
    fun delete(t: Txn): Deleted? {
        val adjustment = t.kind.isAdjustment
        val gone = if (adjustment) null else
            Deleted(txns.draftFrom(t).copy(lat = t.lat, lng = t.lng, rate = writer.rateOf(t)), photoStore.photosNow(t.id).map { it.fileName })
        txns.delete(t, wholeInstallmentPlan = true)
        return gone
    }

    fun delete(id: String): Deleted? = txns.txn(id)?.let { delete(it) }

    /** Puts a deleted entry back (under a new id). */
    fun restore(d: Deleted): String {
        val id = txns.inTransaction {
            val id = txns.create(d.draft)
            d.photos.forEach { photoStore.addPhoto(id, it) }
            id
        }
        return id
    }

    private fun isCard(accountId: String): Boolean {
        val a = accounts.accountNow(accountId) ?: return false
        return accounts.groupsNow().firstOrNull { it.id == a.groupId }?.type?.isCreditCard == true
    }

    companion object {
        /** First problem in the order the form shows its fields. Transfers and balance corrections need no category. */
        fun check(
            type: DraftType, amount: Long?, fee: Long?, accountId: String?, toAccountId: String?, categoryId: String?,
            isAdjustment: Boolean = false,
        ): EntryProblem? {
            val transfer = type == DraftType.TRANSFER
            return when {
                amount == null || amount <= 0 -> EntryProblem.NO_AMOUNT
                fee == null -> EntryProblem.BAD_FEE
                accountId == null -> EntryProblem.NO_ACCOUNT
                !transfer && !isAdjustment && categoryId == null -> EntryProblem.NO_CATEGORY
                transfer && toAccountId == null -> EntryProblem.NO_DESTINATION
                transfer && toAccountId == accountId -> EntryProblem.SAME_ACCOUNT
                else -> null
            }
        }
    }
}
