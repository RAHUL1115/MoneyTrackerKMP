package app.moneytracker.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Which account a new entry probably goes in, learned from the person's own recent entries of the same kind.
 *
 * It starts from how often each account was used lately (older entries count for less: their weight halves every
 * [HALF_LIFE_DAYS]) and lets each clue about the new entry shift that:
 *  - its category (and main category), e.g. Clothes → a "Clothes" envelope account;
 *  - the words of its note and the whole note, e.g. "fuel" → the fuel card;
 *  - the size of the amount, e.g. ₹30 → the everyday card, ₹60,000 → the bank account;
 *  - the account of the latest entry and the last account used with this category (people use accounts in runs).
 * A clue is the share of its past uses per account, smoothed toward the overall habit, so a clue never seen before
 * changes nothing and a once-used account can't win on noise. The same note with the same category used last time
 * goes straight to that account.
 *
 * Nothing here is tuned to one person: on a real 5-year history the results barely moved across half-lives of
 * 3 weeks to 6 months and smoothing of 1–4, so these are middle-of-the-range values.
 */
object AccountPredictor {
    /** One earlier entry. [day] is the epoch day; [amount] in minor units of whatever currency it was entered in. */
    data class Past(val accountId: String, val categoryId: String?, val mainCategoryId: String?, val note: String, val amount: Long, val day: Long)

    /** The entry being filled in. Unknown parts: null category, blank note, null amount. */
    data class Ask(val categoryId: String?, val mainCategoryId: String?, val note: String, val amount: Long?, val today: Long)

    const val HALF_LIFE_DAYS = 60.0
    const val WINDOW_DAYS = 365L
    private const val SMOOTHING = 2.0

    private val weights = mapOf('c' to 1.0, 's' to 0.5, 'w' to 1.0, 'n' to 1.0, 'm' to 0.5, 'p' to 0.7, 'q' to 0.7)
    /** Filler that starts many notes ("for fuel", "paid rent") and says nothing about the account. */
    private val filler = setOf("for", "the", "and", "from", "paid", "pay", "bought", "buy", "with", "liye")

    /**
     * Accounts best first with a 0–1 confidence, from [history] oldest first. Only [allowed] accounts (all when null)
     * are learned and suggested. Empty when no allowed account was used in the last [WINDOW_DAYS].
     */
    fun rank(history: List<Past>, ask: Ask, allowed: Set<String>? = null): List<Pair<String, Double>> {
        val learned = Learned()
        for (p in history) {
            val age = ask.today - p.day
            if (age in 0..WINDOW_DAYS && (allowed == null || p.accountId in allowed)) learned.count(p, 0.5.pow(age / HALF_LIFE_DAYS))
            learned.moveOn(p)
        }
        if (learned.prior.isEmpty()) return emptyList()
        val score = scores(learned, ask)
        val best = score.values.max()
        val z = score.values.sumOf { exp(it - best) }
        return score.entries.map { it.key to exp(it.value - best) / z }.sortedByDescending { it.second }
    }

    /** Log-score per account: the overall habit, shifted by each clue about [ask]; the same note and category as before wins outright. */
    private fun scores(learned: Learned, ask: Ask): Map<String, Double> {
        val prior = learned.prior
        val total = prior.values.sum()
        val habit = prior.mapValues { (_, n) -> (n + 0.5) / (total + 0.5 * prior.size) }
        val score = habit.mapValuesTo(HashMap()) { (_, p) -> ln(p) }
        for (f in learned.cluesFor(ask.categoryId, ask.mainCategoryId, ask.note, ask.amount)) {
            val row = learned.counts[f] ?: continue
            val seen = row.values.sum()
            val w = weights.getValue(f[0])
            for ((a, p) in habit) score[a] = score.getValue(a) + w * (ln(((row[a] ?: 0.0) + SMOOTHING * p) / (seen + SMOOTHING)) - ln(p))
        }
        noteKey(ask.categoryId, ask.note)?.let { learned.sameNote[it] }?.takeIf { it in score }?.let { a -> score[a] = score.values.max() + 1.0 }
        return score
    }

    /** What the history taught, entry by entry: decayed uses per account and per clue, and the context the next entry sees. */
    private class Learned {
        val prior = HashMap<String, Double>()
        val counts = HashMap<String, HashMap<String, Double>>()
        val sameNote = HashMap<String, String>()
        private var previous: String? = null
        private val lastForCategory = HashMap<String, String>()

        fun cluesFor(categoryId: String?, mainCategoryId: String?, note: String, amount: Long?) =
            clues(categoryId, mainCategoryId, note, amount, previous, categoryId?.let { lastForCategory[it] })

        /** Counts [p] with weight [w] toward its account. */
        fun count(p: Past, w: Double) {
            prior[p.accountId] = (prior[p.accountId] ?: 0.0) + w
            for (f in cluesFor(p.categoryId, p.mainCategoryId, p.note, p.amount)) {
                val row = counts.getOrPut(f) { HashMap() }
                row[p.accountId] = (row[p.accountId] ?: 0.0) + w
            }
            noteKey(p.categoryId, p.note)?.let { sameNote[it] = p.accountId }
        }

        /** [p] becomes the latest entry, counted or not. */
        fun moveOn(p: Past) {
            previous = p.accountId
            p.categoryId?.let { lastForCategory[it] = p.accountId }
        }
    }

    fun words(note: String): List<String> = SmsParser.words(note).filter { it !in filler }

    private fun noteKey(categoryId: String?, note: String): String? =
        words(note).takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { "${categoryId.orEmpty()}|$it" }

    private fun clues(categoryId: String?, mainCategoryId: String?, note: String, amount: Long?, previous: String?, lastForCategory: String?) = buildList {
        val main = mainCategoryId ?: categoryId
        main?.let { add("c$it") }
        if (categoryId != null && categoryId != main) add("s$categoryId")
        val w = words(note)
        w.forEach { add("w$it") }
        if (w.isNotEmpty()) add("n" + w.joinToString(" "))
        // Doubling buckets in minor units: the same person's amounts stay comparable whatever the currency's decimals.
        amount?.takeIf { it > 0 }?.let { add("m" + (63 - (it + 1).countLeadingZeroBits())) }
        previous?.let { add("p$it") }
        lastForCategory?.let { add("q$it") }
    }
}
