package app.moneytracker.entry

import app.moneytracker.data.AccountStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.LearningStore
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.defaultAccountFor

/**
 * Which account an entry probably uses: the category's preferred account (or its main category's), else the one
 * usually used for this kind, category, note and amount. Only accounts that still exist count. Blocking reads.
 */
class AccountGuess(private val accounts: AccountStore, private val categories: CategoryStore, private val learning: LearningStore) {

    fun live(id: String?): Account? = id?.let { accounts.accounts.current.firstOrNull { a -> a.id == it } }

    /** The usual account's id as learned, whether or not it still exists. */
    fun usualId(kind: TxnKind, categoryId: String?, note: String, amount: Long?): String? =
        learning.habitAccount(kind, categoryId, note = note, amount = amount)

    fun usual(kind: TxnKind, categoryId: String?, note: String, amount: Long?): Account? = live(usualId(kind, categoryId, note, amount))

    fun forCategory(c: Category, kind: TxnKind, note: String, amount: Long?): Account? =
        live(defaultAccountFor(c.id, categories.categoryById.current)) ?: usual(kind, c.id, note, amount)

    fun isCreditCard(accountId: String?): Boolean {
        val a = live(accountId) ?: return false
        return accounts.groups.current.firstOrNull { it.id == a.groupId }?.type?.isCreditCard == true
    }
}
