package app.moneytracker.web

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.TagStore
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import kotlinx.datetime.LocalDate

/** A refused API call: [status] and a message the dashboard page shows as it is. */
internal class ApiError(val status: Int, message: String) : Exception(message)

/**
 * Turns the dashboard's entry form (a JSON object) into a [TxnDraft], with the phone's rules: a type, a date, an
 * account, an amount above zero, and a category of the right kind (or two different accounts for a transfer).
 * Anything wrong throws an [ApiError] naming what to fix.
 */
internal class EntryDraftReader(
    private val accounts: AccountStore,
    private val categories: CategoryStore,
    private val currencies: CurrencyStore,
    private val settings: SettingsStore,
    private val tags: TagStore,
) {
    /** [base] is the entry being edited, so what the page can't change (its currency, a transfer's fee) stays. */
    fun read(b: Map<*, *>, base: TxnDraft?): TxnDraft {
        val type = type(b)
        val date = LocalDate.Formats.ISO.parseOrNull(str(b, "date")) ?: throw ApiError(400, "Pick a date")
        val byId = accounts.accountsNow().associateBy { it.id }
        val account = account(b, byId, type)
        val currency = base?.currency ?: account.currency
        val amount = amount(b, currency)
        val to = if (type == DraftType.TRANSFER) transferTarget(b, byId, account) else null
        val category = if (type == DraftType.TRANSFER) null else category(b, type)
        requireCategory(type, amount, account, to, category)
        val note = str(b, "note").take(500)
        val memo = str(b, "memo").take(2000)
        val tagIds = tagNames(b).map { tags.tagNamed(it) }
        val fee = if (type == DraftType.TRANSFER && base?.type == DraftType.TRANSFER) base.fee else 0L
        return (base ?: TxnDraft(type, date, account.id, amount = amount)).copy(
            type = type, date = date, accountId = account.id, toAccountId = to?.id, categoryId = category,
            amount = amount, fee = fee, note = note, memo = memo, installments = 1, repeat = null, repeatEnd = null,
            currency = currency, tagIds = tagIds,
        )
    }

    private fun type(b: Map<*, *>): DraftType =
        DraftType.entries.firstOrNull { it.name == str(b, "type") } ?: throw ApiError(400, "Pick income, expense or transfer")

    private fun account(b: Map<*, *>, byId: Map<String, Account>, type: DraftType): Account =
        byId[str(b, "account")] ?: throw ApiError(400, if (type == DraftType.TRANSFER) "Pick the account to move from" else "Pick an account")

    /** Parsed with the decimals of the entry's own currency (the main currency's when it has none). */
    private fun amount(b: Map<*, *>, currency: String?): Long {
        val decimals = currency?.let { c -> currencies.currenciesNow().firstOrNull { it.code == c }?.decimals } ?: settings.currencyFormatNow().decimals
        return CurrencyFormat(decimals = decimals).parse(str(b, "amount"))?.takeIf { it > 0 } ?: throw ApiError(400, "Enter an amount above zero")
    }

    private fun transferTarget(b: Map<*, *>, byId: Map<String, Account>, from: Account): Account {
        val to = byId[str(b, "to")] ?: throw ApiError(400, "Pick the account to move to")
        if (to.id == from.id) throw ApiError(400, "Pick two different accounts")
        return to
    }

    /** The picked category id, or null when none was picked. */
    private fun category(b: Map<*, *>, type: DraftType): String? {
        val id = str(b, "category").ifEmpty { null } ?: return null
        // Listed only to name past corrections; it isn't a category an entry can pick.
        if (id == MODIFIED_BALANCE_CATEGORY) throw ApiError(400, "Balance corrections are made on the phone. Pick a category")
        requireCategoryKind(id, type)
        return id
    }

    private fun requireCategoryKind(id: String, type: DraftType) {
        val c = categories.categoriesNow().firstOrNull { it.id == id } ?: throw ApiError(400, "That category was deleted on the phone")
        val wanted = if (type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        if (c.kind != wanted) throw ApiError(400, "Pick a category for this type")
    }

    /** Same rule as the phone: every entry needs a category (transfers have none of their own). */
    private fun requireCategory(type: DraftType, amount: Long, account: Account, to: Account?, category: String?) {
        if (EntryEditor.check(type, amount, 0, account.id, to?.id, category) == EntryProblem.NO_CATEGORY) throw ApiError(400, "Pick a category")
    }

    private fun str(b: Map<*, *>, key: String) = (b[key] as? String)?.trim().orEmpty()

    companion object {
        /** ["#Trip", "trip", " food "] → ["Trip", "food"]: trimmed, without '#', once each, at most 60 characters each and 20 in all. */
        fun tagNames(b: Map<*, *>): List<String> =
            ((b["tags"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { (it as? String)?.trim()?.removePrefix("#")?.trim()?.takeIf { n -> n.isNotEmpty() }?.take(60) }
                .distinctBy { it.lowercase() }.take(20)
    }
}
