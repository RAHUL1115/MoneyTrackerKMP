package app.moneytracker.ui

import app.moneytracker.core.SmartModels
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Suggestion
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.ai.SmartParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** What Smart entry understood, ready for the Add screen. Names are the person's own category and account names. */
data class SmartGuess(
    val draft: TxnDraft,
    val categoryName: String?,
    val otherCategories: List<String>,
    val accountName: String?,
    val daysAgo: Int,
    /** The category came from the person's own past entries rather than the built-in model. */
    val categoryLearned: Boolean = false,
    /** The account wasn't said: it's the category's default or the usual one, so a later category change may replace it. */
    val accountGuessed: Boolean = false,
)

/** The on-device Smart entry engine, loaded once (about half a second) the first time it's needed. */
object SmartEntry {
    private val lock = Mutex()
    private var parser: SmartParser? = null
    private var failed = false

    suspend fun parser(models: SmartModels): SmartParser? = lock.withLock {
        parser ?: if (failed) null else withContext(Dispatchers.Default) { load(models) }.also { parser = it }
    }

    /**
     * Reads the bundled model files. A missing, corrupt or too-large file can fail in many ways (casts, indexes,
     * memory), so any failure turns Smart entry off for this run instead of crashing. Loading never suspends.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun load(models: SmartModels): SmartParser? = try {
        app.moneytracker.core.Log.timed("Smart", "model load") { SmartParser.load { name -> models.readSmartModel(name) ?: error("missing $name") } }
    } catch (t: Throwable) {
        failed = true
        app.moneytracker.core.Log.e("Smart", t) { "Smart entry models failed to load" }
        null
    }

    /** Parses [text] against the person's categories and accounts; runs off the main thread. */
    suspend fun guess(
        parser: SmartParser, text: String, today: LocalDate, currency: CurrencyFormat,
        categories: List<Category>, accounts: List<Account>,
        /** What the person's own saved entries say about these note words (the app's learned suggestions); null to skip. */
        learned: (suspend (List<String>) -> Suggestion?)? = null,
        /** The account usually used for (kind, category id, note, amount); null to skip. */
        usualAccount: (suspend (app.moneytracker.domain.TxnKind, String?, String, Long) -> String?)? = null,
    ): SmartGuess = withContext(Dispatchers.Default) {
        val expense = categories.filter { it.kind == CategoryKind.EXPENSE }
        val income = categories.filter { it.kind == CategoryKind.INCOME }
        val visible = accounts.filter { !it.hidden }
        val r = app.moneytracker.core.Log.timed("Smart", "parse of ${text.length} chars") {
            parser.parse(text, expense.map { it.name }.distinct(), income.map { it.name }.distinct(), visible.map { it.name })
        }
        val type = draftTypeOf(r.type)
        val note = r.itemsHeard.joinToString(", ").replaceFirstChar { it.uppercase() }
        // Personal history first: once someone has saved "samosa" under their own category, that beats the model's guess.
        val mine = if (type == DraftType.TRANSFER || learned == null) null
            else SmsParser.words(note).takeIf { it.isNotEmpty() }?.let { learned(it) }
        val pick = pickCategory(type, r, if (type == DraftType.INCOME) income else expense, mine)
        val category = pick.chosen
        val spoken = r.account.takeIf { it.isNotEmpty() }?.let { n -> visible.firstOrNull { it.name == n } }
        val amount = r.amount?.let { currency.parse(plain(it, currency.decimals)) } ?: 0L
        val kind = if (type == DraftType.INCOME) app.moneytracker.domain.TxnKind.INCOME else app.moneytracker.domain.TxnKind.EXPENSE
        val guessed = if (spoken != null || type == DraftType.TRANSFER) null else unsaidAccount(
            visible,
            defaultId = app.moneytracker.domain.defaultAccountFor(category?.id, categories.associateBy { it.id }),
            usualId = { usualAccount?.invoke(kind, category?.id, note, amount) },
            learnedId = mine?.takeIf { it.accountConfidence >= LEARNED_ACCOUNT }?.accountId,
        )
        val account = spoken ?: guessed
        val days = r.daysAgo.coerceIn(0, 31)
        SmartGuess(
            draft = TxnDraft(type, today.minus(DatePeriod(days = days)), account?.id.orEmpty(), categoryId = category?.id, amount = amount, note = note),
            categoryName = category?.name,
            otherCategories = pick.others(),
            accountName = account?.name,
            daysAgo = days,
            categoryLearned = pick.learned,
            accountGuessed = spoken == null && account != null,
        )
    }

    private fun draftTypeOf(parsedType: String) = when (parsedType) {
        "income" -> DraftType.INCOME
        "transfer" -> DraftType.TRANSFER
        "refund" -> DraftType.REFUND
        else -> DraftType.EXPENSE
    }

    /** The category picked from [pool], the model's own first choice, and the model's ranking of names (empty for transfers). */
    private class CategoryPick(val chosen: Category?, val fromModel: Category?, val learned: Boolean, val ranked: List<String>) {
        /** The next two names to offer besides the chosen one. */
        fun others() = (listOfNotNull(fromModel?.name) + ranked).distinct().filter { it != chosen?.name }.take(2)
    }

    private fun pickCategory(type: DraftType, r: SmartParser.Result, pool: List<Category>, mine: Suggestion?): CategoryPick {
        val ranked = if (type == DraftType.TRANSFER) emptyList() else r.categories.map { it.name }
        val learnedCategory = mine?.takeIf { it.categoryConfidence >= LEARNED_CATEGORY }?.categoryId?.let { id -> pool.firstOrNull { it.id == id } }
        val modelCategory = ranked.firstOrNull()?.let { n -> pool.firstOrNull { it.name == n } }
        return CategoryPick(learnedCategory ?: modelCategory, modelCategory, learnedCategory != null, ranked)
    }

    /**
     * An account that wasn't said out loud: the category's own default account, then the one usually used with this
     * category, note and amount ("500 for burger" → wherever food usually goes), then the learned one. Only [visible] ones count.
     */
    private suspend fun unsaidAccount(visible: List<Account>, defaultId: String?, usualId: suspend () -> String?, learnedId: String?): Account? {
        fun shown(id: String?) = id?.let { i -> visible.firstOrNull { it.id == i } }
        return shown(defaultId) ?: shown(usualId()) ?: shown(learnedId)
    }

    private const val LEARNED_CATEGORY = 0.5
    private const val LEARNED_ACCOUNT = 0.6

    /** 50.0 → "50", 12.5 → "12.5", rounded to the currency's decimals, without exponent notation. */
    private fun plain(v: Double, decimals: Int): String {
        var f = 1L; repeat(decimals) { f *= 10 }
        val minor = kotlin.math.round(v * f).toLong()
        if (decimals == 0) return minor.toString()
        val frac = (minor % f).toString().padStart(decimals, '0').trimEnd('0')
        return (minor / f).toString() + if (frac.isEmpty()) "" else ".$frac"
    }
}
