package app.moneytracker.domain

/**
 * Message settings: blocked senders and phrases, a content filter (words stripped before
 * reading the message), and per-account rules that send a message to an account.
 */
object MessageRules {
    /** Settings hold one entry per line. */
    fun lines(setting: String?): List<String> = setting.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun isBlocked(sender: String, text: String, blockedSenders: List<String>, blockedPhrases: List<String>): Boolean {
        val digits = sender.filter { it.isLetterOrDigit() }
        return blockedSenders.any { b -> b.filter { it.isLetterOrDigit() }.let { it.isNotEmpty() && digits.contains(it, ignoreCase = true) } } ||
            blockedPhrases.any { text.contains(it, ignoreCase = true) }
    }

    fun clean(text: String, filters: List<String>): String =
        filters.fold(text) { acc, f -> acc.replace(f, " ", ignoreCase = true) }.replace(Regex(" {2,}"), " ").trim()

    /**
     * The account a message belongs to: the account whose match text appears in the message
     * (longest first) and whose sender, if set, sent it.
     */
    fun matchAccount(accounts: List<Account>, sender: String, text: String): String? {
        fun senderOk(a: Account) = a.smsSender.isEmpty() || sender.contains(a.smsSender, ignoreCase = true)
        return accounts.filter { it.smsMatch.isNotEmpty() && text.contains(it.smsMatch, ignoreCase = true) && senderOk(it) }
            .maxByOrNull { it.smsMatch.length }?.id
            ?: accounts.firstOrNull { it.smsMatch.isEmpty() && it.smsSender.isNotEmpty() && sender.contains(it.smsSender, ignoreCase = true) }?.id
    }
}
