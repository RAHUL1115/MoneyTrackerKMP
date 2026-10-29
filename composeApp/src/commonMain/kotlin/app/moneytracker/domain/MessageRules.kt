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

    /** Explicit rules win; otherwise use instrument + digits before learning or a unique bank name. */
    fun suggestAccount(
        accounts: List<Account>, groups: List<AccountGroup>, sender: String, text: String,
        parsed: SmsParser.Parsed, learnedAccountId: String?,
    ): String? {
        // A configured content rule is deliberate; a shared bank sender alone is not enough
        // to override an explicit credit-card/account reference in the message.
        matchAccount(accounts.filter { it.smsMatch.isNotEmpty() }, sender, text)?.let { return it }
        val types = groups.associate { it.id to it.type }
        val candidates = accounts.filter { a ->
            val type = types[a.groupId]
            val namedBank = SmsParser.bankOf("", a.name)
            val bankCompatible = parsed.bank == null || namedBank == null || namedBank == parsed.bank
            bankCompatible && when (parsed.instrument) {
                SmsParser.Instrument.CREDIT_CARD -> type == AccountType.CREDIT_CARD
                SmsParser.Instrument.DEBIT_CARD -> type == AccountType.DEBIT_CARD
                // A generic card may be a debit card attached directly to a bank account.
                SmsParser.Instrument.CARD -> type == AccountType.CREDIT_CARD || type == AccountType.DEBIT_CARD ||
                    type == AccountType.BANK || type == AccountType.SAVINGS || type == AccountType.OVERDRAFT
                SmsParser.Instrument.BANK_ACCOUNT -> type == AccountType.BANK || type == AccountType.SAVINGS || type == AccountType.OVERDRAFT
                null -> true
            }
        }
        matchAccount(candidates, sender, text)?.let { return it }
        val digitMatches = parsed.accountDigits?.let { digits ->
            val pattern = Regex("""(?<!\d)$digits(?!\d)""")
            candidates.filter { pattern.containsMatchIn(it.name) }
        }.orEmpty()
        if (digitMatches.isNotEmpty()) {
            return digitMatches.singleOrNull()?.id ?: learnedAccountId?.takeIf { id -> digitMatches.any { it.id == id } }
        }
        learnedAccountId?.takeIf { id -> candidates.any { it.id == id } }?.let { return it }
        return parsed.bank?.let { bank ->
            candidates.filter { it.name.contains(bank.substringBefore(' '), ignoreCase = true) }.singleOrNull()?.id
        }
    }

    /** Match configured text (longest first) and sender, falling back to a sender-only rule. */
    fun matchAccount(accounts: List<Account>, sender: String, text: String): String? {
        fun senderOk(a: Account) = a.smsSender.isEmpty() || sender.contains(a.smsSender, ignoreCase = true)
        return accounts.filter { it.smsMatch.isNotEmpty() && text.contains(it.smsMatch, ignoreCase = true) && senderOk(it) }
            .maxByOrNull { it.smsMatch.length }?.id
            ?: accounts.filter { it.smsMatch.isEmpty() && it.smsSender.isNotEmpty() && sender.contains(it.smsSender, ignoreCase = true) }.singleOrNull()?.id
    }
}
