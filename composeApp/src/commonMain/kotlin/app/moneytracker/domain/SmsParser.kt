package app.moneytracker.domain

import kotlin.math.ln

/**
 * Lightweight bank-message reader: regexes plus a small sender→bank table. No model files,
 * microseconds per message. Returns null for anything that isn't a money movement (OTPs, offers…).
 */
object SmsParser {
    enum class Instrument { BANK_ACCOUNT, CREDIT_CARD, DEBIT_CARD, CARD }

    data class Parsed(
        val bank: String?,
        val amount: Long,            // minor units of [currencyCode] (or main currency when null)
        val currencyCode: String?,   // ISO code if the message names a currency
        val isCredit: Boolean,
        val accountDigits: String?,  // last digits of card / account, e.g. "1234"
        val vendor: String?,
        /** A credit that gives back earlier spending ("refund", "reversal") rather than income. */
        val isRefund: Boolean = false,
        /** Payment instrument, separate from whether money was credited or debited. */
        val instrument: Instrument? = null,
    )

    /** Sender-ID fragments (e.g. "VM-HDFCBK") and in-text names → display name. */
    private val banks = listOf(
        "HDFC" to "HDFC Bank", "ICICI" to "ICICI Bank", "SBI" to "SBI", "AXIS" to "Axis Bank", "KOTAK" to "Kotak Bank",
        "YESB" to "Yes Bank", "IDFC" to "IDFC First Bank", "INDUS" to "IndusInd Bank", "PNB" to "PNB", "BOB" to "Bank of Baroda",
        "CANB" to "Canara Bank", "UNION" to "Union Bank", "FEDB" to "Federal Bank", "RBL" to "RBL Bank", "AUBANK" to "AU Bank",
        "SCB" to "Standard Chartered", "SCBANK" to "Standard Chartered", "CITI" to "Citi", "AMEX" to "American Express",
        "HSBC" to "HSBC", "PAYTM" to "Paytm Payments Bank", "AIRBNK" to "Airtel Payments Bank", "IDBI" to "IDBI Bank",
        "BOI" to "Bank of India", "CBOI" to "Central Bank", "IOB" to "Indian Overseas Bank", "SIB" to "South Indian Bank",
        "ONECRD" to "OneCard", "JUPITER" to "Jupiter", "FIMONEY" to "Fi", "SLICE" to "slice", "SLCBNK" to "slice",
    )

    private val currencyTokens = mapOf(
        "rs" to null, "rs." to null, "inr" to null, "₹" to null,
        "usd" to "USD", "$" to "USD", "eur" to "EUR", "€" to "EUR", "gbp" to "GBP", "£" to "GBP", "aed" to "AED", "sgd" to "SGD",
    )

    private val amountRe = Regex("""(?i)(rs\.?|inr|₹|usd|\$|eur|€|gbp|£|aed|sgd)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val amountAfterRe = Regex("""(?i)(?:debited|credited|spent|paid|received|withdrawn|sent)\s+(?:by|for|of|with)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val digitsRe = Regex("""(?i)(?:a/c|acct|account|card|ac)\s*(?:no\.?)?\s*(?:ending(?:\s+with|\s+in)?|xx+|\*+|x+|no\.?)?\s*[x*]*(\d{3,6})\b""")
    private val vendorRes = listOf(
        Regex("""(?i)\bvpa\s+([a-z0-9._-]+)@[a-z]+"""),                                   // UPI handle → name part
        Regex("""(?i)\b(?:at|@)\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|txn|using|for|avl|avbl|\d)|[.,]|$)"""),
        Regex("""(?i)\bto\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|upi|txn|from|avl|avbl|\d)|[.,]|$)"""),
        Regex("""(?i)\bfrom\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|upi|txn|avl|avbl|\d)|[.,]|$)"""),
        Regex("""(?i)\binfo:?\s*([a-z0-9][a-z0-9 &'./_-]{1,40}?)(?=[.,]|$)"""),
        Regex("""(?i)\btowards\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=[.,]|$)"""),
    )
    private val debitWords = listOf("debited", "spent", "paid", "purchase", "withdrawn", "withdrawal", "sent", "debit", "txn of", "used for", "charged")
    private val refundWords = listOf("refund", "reversal", "reversed", "chargeback")
    private val creditWords = listOf("credited", "received", "deposited", "refund", "reversed", "credit of", "added to")
    private val noise = listOf("otp", "one time password", "verification code", "offer", "voucher", "win ", "congratulations",
        "pre-approved", "apply now", "eligible for", "due date", "minimum amount due", "statement is ready", "will be debited")

    fun parse(sender: String, text: String, mainDecimals: Int = 2): Parsed? {
        val lower = text.lowercase()
        if (noise.any { it in lower }) return null
        val isDebit = debitWords.any { it in lower }
        val isCredit = creditWords.any { it in lower }
        if (!isDebit && !isCredit) return null

        val (rawAmount, code) = amountRe.find(text)?.let { m ->
            m.groupValues[2] to currencyTokens[m.groupValues[1].lowercase()]
        } ?: amountAfterRe.find(text)?.let { it.groupValues[1] to null } ?: return null
        val major = rawAmount.replace(",", "").toDoubleOrNull() ?: return null
        if (major <= 0) return null
        val amount = kotlin.math.round(major * CurrencyFormat.pow10(mainDecimals)).toLong()

        // "credited … refund" or "debited" + "credited to beneficiary": the earliest keyword decides.
        val firstDebit = debitWords.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
        val firstCredit = creditWords.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE
        val credit = firstCredit < firstDebit

        return Parsed(bankOf(sender, text), amount, code, credit, digitsRe.find(text)?.groupValues?.get(1), vendorOf(text),
            isRefund = credit && refundWords.any { it in lower }, instrument = instrumentOf(text))
    }

    private val instrumentRe = Regex("""(?i)\b(?:credit[\s-]*card|debit[\s-]*card|card|a/c|acct|account|ac)\b""")

    private fun instrumentOf(text: String): Instrument? {
        // Use the instrument attached to the extracted digits, not a later mention of e.g.
        // "towards credit card payment" in a bank-account debit message.
        val digits = digitsRe.find(text)
        val references = instrumentRe.findAll(text).toList()
        val reference = if (digits == null) references.firstOrNull()
            else references.lastOrNull { it.range.first <= digits.range.first }
        val label = reference?.value?.lowercase() ?: return null
        return when {
            label.startsWith("credit") -> Instrument.CREDIT_CARD
            label.startsWith("debit") -> Instrument.DEBIT_CARD
            label == "card" -> Instrument.CARD
            else -> Instrument.BANK_ACCOUNT
        }
    }

    fun bankOf(sender: String, text: String): String? {
        // DLT IDs may include a final category suffix, e.g. CP-HDFCBK-S. Inspect the
        // bank fragment rather than treating the last fragment ("S") as the sender.
        val ids = sender.uppercase().split('-').map { part -> part.filter { it.isLetterOrDigit() } }
        banks.firstOrNull { (k, _) -> ids.any { id -> id.startsWith(k) || (id.length in 5..8 && k in id) } }?.let { return it.second }
        val upper = text.uppercase()
        return banks.firstOrNull { (k, _) -> k.length >= 3 && Regex("""\b$k""").containsMatchIn(upper) }?.second
    }

    fun vendorOf(text: String): String? {
        for (re in vendorRes) {
            val v = re.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', '-', '_') ?: continue
            val cleaned = v.replace(Regex("""\s+"""), " ")
            if (cleaned.length < 2 || cleaned.all { it.isDigit() || it == 'x' || it == 'X' || it == '*' }) continue
            if (cleaned.lowercase() in setOf("your", "your a/c", "a/c", "account", "card", "you", "bank")) continue
            return cleaned.split(' ').joinToString(" ") { w -> if (w.length > 3 && w.all { it.isUpperCase() || !it.isLetter() }) w.lowercase().replaceFirstChar { it.uppercase() } else w }
        }
        return null
    }

    /** Words used for learning: vendor + bank + card digits. */
    fun tokens(p: Parsed): List<String> = buildList {
        p.vendor?.let { addAll(words(it)) }
        p.bank?.let { add("bank:" + it.lowercase()) }
        p.accountDigits?.let { add("digits:$it") }
    }.distinct()

    fun words(s: String): List<String> =
        s.lowercase().split(Regex("""[^a-z0-9@]+""")).filter { it.length >= 3 && !it.all(Char::isDigit) }.distinct()
}

/**
 * Bernoulli Naive Bayes over tiny token sets. Labels are strings like "cat:<id>" / "acc:<id>".
 * [tokenCounts] = examples of a label containing a token; [labelCounts] = examples per label.
 */
object TinyBayes {
    data class Guess(val label: String, val confidence: Double)

    fun predict(
        tokens: List<String>,
        tokenCounts: Map<Pair<String, String>, Long>,
        labelCounts: Map<String, Long>,
        prefix: String,
        minExamples: Long = 1,
    ): Guess? {
        val labels = labelCounts.filterKeys { it.startsWith(prefix) }.filterValues { it >= minExamples }
        if (labels.isEmpty() || tokens.isEmpty()) return null
        val total = labels.values.sum().toDouble()
        val scores = labels.mapValues { (label, n) ->
            // Only tokens seen with *some* label carry evidence; unseen tokens would just add noise.
            ln(n / total) + tokens.sumOf { t ->
                val c = tokenCounts[t to label] ?: 0L
                ln((c + 0.5) / (n + 1.0))
            }
        }
        val best = scores.maxBy { it.value }
        val evidence = tokens.any { t -> (tokenCounts[t to best.key] ?: 0L) > 0 }
        if (!evidence) return null
        // Softmax confidence among labels.
        val max = best.value
        val z = scores.values.sumOf { kotlin.math.exp(it - max) }
        return Guess(best.key, 1.0 / z)
    }
}
