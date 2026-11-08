package app.moneytracker.ui

import app.moneytracker.ai.SmartParser
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.SmsParser
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
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
)

/** The on-device Smart entry engine, loaded once (about half a second) the first time it's needed. */
object SmartEntry {
    private val lock = Mutex()
    private var parser: SmartParser? = null
    private var failed = false

    suspend fun parser(platform: PlatformServices): SmartParser? = lock.withLock {
        parser ?: if (failed) null else withContext(Dispatchers.Default) {
            runCatching { SmartParser.load { name -> platform.readSmartModel(name) ?: error("missing $name") } }
                .onFailure { failed = true }.getOrNull()
        }.also { parser = it }
    }

    /** Parses [text] against the person's categories and accounts; runs off the main thread. */
    suspend fun guess(
        parser: SmartParser, text: String, today: LocalDate, currency: CurrencyFormat,
        categories: List<Category>, accounts: List<Account>,
        /** What the person's own saved entries say about these note words (the app's learned suggestions); null to skip. */
        learned: (suspend (List<String>) -> MoneyRepository.Suggestion?)? = null,
    ): SmartGuess = withContext(Dispatchers.Default) {
        val expense = categories.filter { it.kind == CategoryKind.EXPENSE }
        val income = categories.filter { it.kind == CategoryKind.INCOME }
        val visible = accounts.filter { !it.hidden }
        val r = parser.parse(text, expense.map { it.name }.distinct(), income.map { it.name }.distinct(), visible.map { it.name })
        val type = when (r.type) {
            "income" -> DraftType.INCOME
            "transfer" -> DraftType.TRANSFER
            "refund" -> DraftType.REFUND
            else -> DraftType.EXPENSE
        }
        val pool = if (type == DraftType.INCOME) income else expense
        val ranked = if (type == DraftType.TRANSFER) emptyList() else r.categories.map { it.name }
        val note = r.itemsHeard.joinToString(", ").replaceFirstChar { it.uppercase() }
        // Personal history first: once someone has saved "samosa" under their own category, that beats the model's guess.
        val mine = if (type == DraftType.TRANSFER || learned == null) null
            else SmsParser.words(note).takeIf { it.isNotEmpty() }?.let { learned(it) }
        val learnedCategory = mine?.takeIf { it.categoryConfidence >= LEARNED_CATEGORY }?.categoryId?.let { id -> pool.firstOrNull { it.id == id } }
        val modelCategory = ranked.firstOrNull()?.let { n -> pool.firstOrNull { it.name == n } }
        val category = learnedCategory ?: modelCategory
        val spoken = r.account.takeIf { it.isNotEmpty() }?.let { n -> visible.firstOrNull { it.name == n } }
        // An account said out loud always wins; otherwise the one these words usually go with, when history is clear.
        val account = spoken ?: mine?.takeIf { it.accountConfidence >= LEARNED_ACCOUNT }?.accountId?.let { id -> visible.firstOrNull { it.id == id } }
        val amount = r.amount?.let { currency.parse(plain(it, currency.decimals)) } ?: 0L
        val days = r.daysAgo.coerceIn(0, 31)
        SmartGuess(
            draft = TxnDraft(type, today.minus(DatePeriod(days = days)), account?.id.orEmpty(), categoryId = category?.id, amount = amount, note = note),
            categoryName = category?.name,
            otherCategories = (listOfNotNull(modelCategory?.name) + ranked).distinct().filter { it != category?.name }.take(2),
            accountName = account?.name,
            daysAgo = days,
            categoryLearned = learnedCategory != null,
        )
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
