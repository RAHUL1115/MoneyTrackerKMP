package app.moneytracker.data

import app.moneytracker.Log
import app.moneytracker.data.MoneyRepository.Suggestion
import app.moneytracker.domain.Capture
import app.moneytracker.domain.CaptureStatus
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.MessageRules
import app.moneytracker.domain.SmsParser
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.defaultAccountFor
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Turns incoming bank messages into detected payments, fills an entry draft from one, and learns from how the
 * person files it. Spans captures, entries and learning, so it coordinates those stores rather than being one.
 */
class MessageIntake(
    private val db: Db,
    private val settings: SettingsStore,
    private val currencies: CurrencyStore,
    private val accounts: AccountStore,
    private val categories: CategoryStore,
    private val learning: LearningStore,
    private val captures: CaptureStore,
    private val txns: TxnStore,
) {
    private val q get() = db.q

    /**
     * Turns an incoming SMS into a pending suggestion. Returns null when the text
     * isn't a money movement or is a duplicate. Explicit rules win; otherwise match the payment
     * instrument and digits before learning / a unique bank name. Ambiguous accounts need review.
     */
    fun ingestMessage(source: String, sender: String, text: String, receivedAt: Long): Capture? {
        val from = Log.sender(sender)
        val p = parseWanted(source, sender, text, from) ?: return null
        val tokens = SmsParser.tokens(p)
        val s = learning.suggest(tokens)
        val accountId = MessageRules.suggestAccount(accounts.accountsNow(), accounts.groupsNow(), sender, text, p, s.accountId)
        val capture = newCapture(source, sender, text, receivedAt, p, accountId)
        if (!captures.addCapture(capture)) return null.also { Log.d("Sms") { "$source from $from: already seen" } }
        saveIfComplete(capture, source, from)?.let { return it }
        Log.i("Sms") { "$source from $from: ${capture.kind} waiting for review (${capture.id}); account ${if (accountId != null) "matched" else "unknown"}, category ${if (s.categoryId != null) "learned" else "unknown"}${if (p.isRefund) ", refund" else ""}" }
        return capture
    }

    /** The parsed message, or null when the person's rules block it or it isn't a transaction. */
    private fun parseWanted(source: String, sender: String, text: String, from: String): SmsParser.Parsed? {
        if (MessageRules.isBlocked(sender, text, MessageRules.lines(settings.settingNow(Prefs.SMS_BLOCKED_SENDERS)),
                MessageRules.lines(settings.settingNow(Prefs.SMS_BLOCKED_PHRASES)))) return null.also { Log.d("Sms") { "$source from $from: blocked by your rules" } }
        return SmsParser.parse(sender, cleanMessage(text), settings.mainDecimals())
            ?: null.also { Log.d("Sms") { "$source from $from: not a transaction (${text.length} chars)" } }
    }

    private fun newCapture(source: String, sender: String, text: String, receivedAt: Long, p: SmsParser.Parsed, accountId: String?): Capture {
        val vendor = p.vendor.orEmpty()
        return Capture(
            newId(), receivedAt, source, sender, text,
            // Refunds are kept like stored entries: an expense with a negative amount.
            amount = if (p.isRefund) -p.amount else p.amount,
            kind = if (p.isCredit && !p.isRefund) TxnKind.INCOME else TxnKind.EXPENSE,
            merchant = listOfNotNull(vendor.ifBlank { null }, p.bank?.let { "· $it" }).joinToString(" "),
            accountId = accountId,
            status = CaptureStatus.PENDING,
        )
    }

    /** "Auto-save": straight into the books when the account is known. Returns the saved capture, or null to wait for review. */
    private fun saveIfComplete(capture: Capture, source: String, from: String): Capture? {
        if (settings.settingNow(Prefs.SMS_AUTO_SAVE) && capture.accountId != null && (capture.amount ?: 0) != 0L) {
            val d = draftFromCapture(capture)
            // Only complete entries are saved on their own: never without an account or a category.
            if (d.categoryId != null && d.accountId.isNotBlank()) {
                completeCapture(capture, d, txns.create(d))
                Log.i("Sms") { "$source from $from: ${capture.kind} saved automatically (${capture.id})" }
                return capture.copy(status = CaptureStatus.SAVED)
            }
        }
        return null
    }

    private fun cleanMessage(text: String) = MessageRules.clean(text, MessageRules.lines(settings.settingNow(Prefs.SMS_CONTENT_FILTER)))

    /** Builds an editor draft from a pending capture, filling category/account from what was learned. */
    fun draftFromCapture(c: Capture): TxnDraft {
        val p = SmsParser.parse(c.sender, cleanMessage(c.text), settings.mainDecimals())
        val s = p?.let { learning.suggest(SmsParser.tokens(it)) }
        val date = Instant.fromEpochMilliseconds(c.receivedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
        val knownCurrency = p?.currencyCode?.let { code -> currencies.currencyNow(code)?.code }
        val type = c.kind?.toDraftType(c.amount) ?: DraftType.EXPENSE
        val category = learnedCategory(p, s, type)
        val kind = if (type == DraftType.INCOME) TxnKind.INCOME else TxnKind.EXPENSE
        return TxnDraft(
            type, date,
            c.accountId ?: p?.let { MessageRules.suggestAccount(accounts.accountsNow(), accounts.groupsNow(), c.sender, c.text, it, s?.accountId) }
                ?: usualSmsAccount(p, kind, category).orEmpty(),
            categoryId = category, amount = (c.amount ?: 0) * type.sign,
            note = p?.vendor.orEmpty(), currency = knownCurrency,
            origin = if (settings.settingNow(Prefs.SMS_SHOW_ORIGINAL) == SmsOriginal.OFF) "" else c.text,
        )
    }

    private fun learnedCategory(p: SmsParser.Parsed?, s: Suggestion?, type: DraftType): String? {
        // A learned category of the other kind (an income category for a refund, say) doesn't fit.
        val wantedKind = if (type == DraftType.INCOME) CategoryKind.INCOME.name else CategoryKind.EXPENSE.name
        val fits = { id: String -> q.allCategories().executeAsList().any { it.id == id && it.kind == wantedKind } }
        // What this bank/merchant was filed under before; failing that, what the person's own notes with these words
        // were filed under (the same suggestion the editor shows), so a one-tap Add lands in the right category.
        return s?.categoryId?.takeIf(fits)
            ?: p?.vendor?.let { SmsParser.words(it) }?.takeIf { it.isNotEmpty() }?.let { learning.suggest(it).categoryId }?.takeIf(fits)
    }

    /**
     * When the message doesn't say which account, pre-fill the usual one so the entry is one tap
     * from done: the category's default account, then the account most used for this category,
     * then for this kind of entry, then the only account that fits. Only accounts that fit what
     * the message does say (credit card, bank, …) are considered. It's a pre-fill for review,
     * so auto-save still waits for a confident match.
     */
    private fun usualSmsAccount(p: SmsParser.Parsed?, kind: TxnKind, categoryId: String?): String? {
        val fits = MessageRules.candidates(accounts.accountsNow().filter { !it.hidden }, accounts.groupsNow(), p)
        // "… via slice" with "Slice account" and "HDFC card": the slice ones are the likelier pool.
        val pool = p?.bank?.let { bank -> fits.filter { it.name.contains(bank.substringBefore(' '), ignoreCase = true) } }
            ?.takeIf { it.isNotEmpty() } ?: fits
        val ids = pool.map { it.id }.toSet()
        if (ids.isEmpty()) return null
        return defaultAccountFor(categoryId, categories.categoryById.now())?.takeIf { it in ids }
            ?: learning.habitAccount(kind, categoryId, ids, p?.vendor.orEmpty(), p?.amount)
            ?: ids.singleOrNull()
    }

    /** Called when the user saves a capture: marks it and teaches the model vendor/bank/digits → category & account. */
    fun completeCapture(c: Capture, saved: TxnDraft, txnId: String) {
        Log.d("Sms") { "capture ${c.id} saved as $txnId; learning category=${saved.categoryId != null} account=${saved.accountId.isNotBlank()}" }
        captures.setCaptureStatus(c.id, CaptureStatus.SAVED, txnId)
        SmsParser.parse(c.sender, cleanMessage(c.text), settings.mainDecimals())?.let { learning.learn(SmsParser.tokens(it), saved.categoryId, saved.accountId) }
    }
}
