package app.moneytracker.data

import app.moneytracker.data.MoneyRepository.NoteSuggestion
import app.moneytracker.data.MoneyRepository.Suggestion
import app.moneytracker.domain.AccountPredictor
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.TinyBayes
import app.moneytracker.domain.TxnKind

/**
 * What the app learns from the person's own entries: note words → category and account (a tiny Naive Bayes),
 * past notes for autocomplete, and the account usually used. Everything stays on the phone.
 */
class LearningStore(private val db: Db, private val settings: SettingsStore) {
    private val q get() = db.q

    /** Past notes starting with [prefix], most recently used first, with the category/account/amount used last time. */
    fun noteSuggestions(prefix: String): List<NoteSuggestion> {
        if (prefix.isBlank() || !settings.settingNow(Prefs.AUTOCOMPLETE)) return emptyList()
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val since = settings.settingNow(Prefs.AUTOCOMPLETE_SINCE)
        return q.noteSuggestions(escaped, since).executeAsList()
            .filter { !it.note.equals(prefix, ignoreCase = false) }
            .map { NoteSuggestion(it.note, it.categoryId, it.accountId, TxnKind.valueOf(it.kind), it.amount ?: 0L, it.currency) }
    }

    fun resetAutocomplete() = settings.putSetting(Prefs.AUTOCOMPLETE_SINCE, db.now())

    /**
     * The account this entry probably goes in, from the person's recent entries of the same [kind]: its category,
     * note words and amount, and the accounts used just before (see [AccountPredictor]). Only live, visible accounts
     * (and only [allowed] ones when given); null when none was used in the last year or the setting is off.
     * A category's own default account is the caller's to prefer.
     */
    fun habitAccount(kind: TxnKind, categoryId: String?, allowed: Set<String>? = null, note: String = "", amount: Long? = null): String? {
        if (!settings.settingNow(Prefs.HABIT_ACCOUNT)) return null
        val usable = q.allAccounts().executeAsList().filter { it.hidden == 0L && (allowed == null || it.id in allowed) }.map { it.id }.toSet()
        val today = db.today().toEpochDays().toLong()
        val history = q.accountHistory(kind.name, today - AccountPredictor.WINDOW_DAYS).executeAsList().asReversed().map {
            AccountPredictor.Past(it.accountId, it.categoryId, it.parentId ?: it.categoryId, it.note, it.amount, it.date)
        }
        val main = categoryId?.let { id -> q.allCategories().executeAsList().firstOrNull { it.id == id }?.parentId } ?: categoryId
        return AccountPredictor.rank(history, AccountPredictor.Ask(categoryId, main, note, amount, today), usable).firstOrNull()?.first
    }

    /** Remembers that these words went with this category / account. Cheap: a few row updates. */
    fun learn(tokens: List<String>, categoryId: String?, accountId: String?) = db.transaction {
        val labels = listOfNotNull(categoryId?.let { "cat:$it" }, accountId?.let { "acc:$it" })
        for (label in labels) {
            q.seedLabel(label); q.bumpLabel(label)
            for (t in tokens.distinct().take(12)) { q.seedToken(t, label); q.bumpToken(t, label) }
        }
    }

    fun suggest(tokens: List<String>): Suggestion {
        if (tokens.isEmpty()) return Suggestion(null, null, 0.0, 0.0)
        val tc = q.tokenCounts(tokens.distinct()).executeAsList().associate { (it.token to it.label) to it.count }
        val lc = q.labelCounts().executeAsList().associate { it.label to it.count }
        val liveCats = q.allCategories().executeAsList().map { "cat:" + it.id }.toSet()
        val liveAccs = q.allAccounts().executeAsList().map { "acc:" + it.id }.toSet()
        val cat = TinyBayes.predict(tokens, tc, lc.filterKeys { it in liveCats }, "cat:")
        val acc = TinyBayes.predict(tokens, tc, lc.filterKeys { it in liveAccs }, "acc:")
        var catId = cat?.label?.removePrefix("cat:")
        // With sub-categories off, suggest the main category even if history learned a sub-category.
        if (catId != null && !settings.settingNow(Prefs.USE_SUBCATEGORIES)) {
            catId = q.allCategories().executeAsList().firstOrNull { it.id == catId }?.parentId ?: catId
        }
        return Suggestion(catId, acc?.label?.removePrefix("acc:"), cat?.confidence ?: 0.0, acc?.confidence ?: 0.0)
    }

    /** Learns from a manually saved transaction: note words → category, plus account. */
    fun learnFromDraft(d: TxnDraft) {
        if (d.type == DraftType.TRANSFER) return
        val words = SmsParser.words(d.note)
        if (words.isNotEmpty()) learn(words, d.categoryId, d.accountId)
    }
}
