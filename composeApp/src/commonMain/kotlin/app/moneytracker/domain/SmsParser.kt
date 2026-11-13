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
        "ONECRD" to "OneCard", "JUPITER" to "Jupiter", "FIMONEY" to "Fi", "SLICE" to "slice", "SLCBNK" to "slice", "SLCEIT" to "slice",
        "UTKS" to "Utkarsh SFB", "IPBMSG" to "India Post Payments Bank", "IPPB" to "India Post Payments Bank", "FEDSCP" to "Scapia",
    )

    private val currencyTokens = mapOf(
        "rs" to null, "rs." to null, "inr" to null, "₹" to null,
        "usd" to "USD", "$" to "USD", "eur" to "EUR", "€" to "EUR", "gbp" to "GBP", "£" to "GBP", "aed" to "AED", "sgd" to "SGD",
    )

    private val amountRe = Regex("""(?i)(rs\.?|inr|₹|usd|\$|eur|€|gbp|£|aed|sgd)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val amountAfterRe = Regex("""(?i)(?:debited|credited|spent|paid|received|withdrawn|sent)\s+(?:by|for|of|with)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val digitsRe = Regex("""(?i)(?:a/c|acct|account|card|ac)\s*(?:no\.?)?\s*(?:ending(?:\s+with|\s+in)?|xx+|\*+|x+|no\.?)?\s*[x*]*(\d{3,6})\b""")
    private val vendorRes = listOf(
        Regex("""(?i)@\s*upi[_-]([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+\d|[.,]|$)"""),           // YES Bank "@UPI_SHOP NAME 18-01-…"
        Regex("""(?i)\bvpa\s+([a-z0-9._-]+)@[a-z]+"""),                                   // UPI handle → name part
        Regex("""(?i)\b(?:at|@)\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|txn|using|for|avl|avbl|\d)|[.,]|$)"""),
        Regex("""(?i)\bto\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|rrn|upi|txn|from|avl|avbl|utr|\d)|[.,]|$)"""),
        Regex("""(?i)\bfrom\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=\s+(?:on|via|ref|upi|txn|avl|avbl|\d)|[.,]|$)"""),
        Regex("""(?i)\binfo:?\s*([a-z0-9][a-z0-9 &'./_-]{1,40}?)(?=[.,]|$)"""),
        Regex("""(?i)\btowards\s+([a-z0-9][a-z0-9 &'._-]{1,40}?)(?=[.,]|$)"""),
    )
    private val debitWords = listOf("debited", "spent", "paid", "purchase", "withdrawn", "withdrawal", "sent", "debit", "txn of", "txn rs", "transaction of",
        "used for", "used at", "used on", "charged")
    private val refundWords = listOf("refund", "reversal", "reversed", "chargeback")
    private val creditWords = listOf("credited", "received", "deposited", "refund", "reversal", "reversed", "credit of", "added to")
    /** Never a completed transaction: codes, upcoming debits, bills and loan pitches. */
    private val noise = listOf("otp", "one time password", "verification code", "pre-approved", "apply now", "eligible for",
        "due date", "minimum amount due", "statement is ready", "will be debited",
        // Attempts that didn't go through.
        "failed", "declined", "unsuccessful", "not debited", "no amount debited", "could not be processed", "cannot be debited",
        // Bill reminders, credit/loan pitches that need consent, payment requests and bait.
        "is due on", "are due on", "require consent", "requires consent", "continue disbursement", "access loan", "t&c apply",
        "collect request", "requested money", "has requested")
    private val noiseRes = listOf(
        Regex("""(?i)(?:rs\.?|inr|₹)\s*[0-9][0-9,]*(?:\.[0-9]+)?\*"""),   // "Rs. 8,000* is credited" (asterisked bait)
        Regex("""(?i)e-?mandate.{0,120}\bhas been registered|mandate (?:is )?not registered"""),
    )
    /** Paying the card bill: the bank-account debit already records the money leaving, so this isn't income. */
    private val cardPaymentRe = Regex("""(?i)\b(?:re)?payment (?:of )?(?:rs\.?|inr|₹)?\s*[0-9][0-9,]*(?:\.[0-9]+)?\s+(?:has been |is |was )?(?:received|credited)\s+(?:towards|for|to|on|in)\b.{0,60}?\bcard\b""")
    /** Words that, just before an amount, mark it as a balance or limit rather than the money moved. */
    private val balanceRe = Regex("""(?i)(?:bal|balance|avl|avbl|available|limit|lmt|due|outstanding)[\s.:\-]*$""")
    /** Marketing words. Banks often append an ad to a real alert, so these only rule out messages that don't look like one. */
    private val promo = listOf("offer", "voucher", "win ", "congratulations", "cashback", "scratch card", " or more", "redemption transaction")
    private val doneRe = Regex("""(?i)\b(?:debited|credited|spent|sent|withdrawn)\b""")
    private val refRe = Regex("""(?i)\bref""")

    /** A completed movement on an identified account or UPI reference, as opposed to an advert quoting amounts. */
    private fun looksLikeAlert(text: String, lower: String) =
        doneRe.containsMatchIn(text) && (digitsRe.containsMatchIn(text) || "upi" in lower || refRe.containsMatchIn(text))

    fun parse(sender: String, text: String, mainDecimals: Int = 2): Parsed? {
        val lower = text.lowercase()
        if (!isMoneyMovement(text, lower)) return null
        val (rawAmount, code) = amountText(text) ?: return null
        val amount = minorUnits(rawAmount, mainDecimals) ?: return null
        // "credited … refund" or "debited" + "credited to beneficiary": the earliest keyword decides.
        val credit = firstIndexOf(creditWords, lower) < firstIndexOf(debitWords, lower)

        return Parsed(bankOf(sender, text), amount, code, credit, digitsRe.find(text)?.groupValues?.get(1), vendorOf(text),
            isRefund = credit && refundWords.any { it in lower }, instrument = instrumentOf(text))
    }

    /** Not noise, not an advert, and it says money went out or came in. */
    private fun isMoneyMovement(text: String, lower: String): Boolean {
        if (noise.any { it in lower } || noiseRes.any { it.containsMatchIn(text) } || cardPaymentRe.containsMatchIn(text)) return false
        if (promo.any { it in lower } && !looksLikeAlert(text, lower)) return false
        return debitWords.any { it in lower } || creditWords.any { it in lower }
    }

    /**
     * The amount as written and its currency code (null for the main currency). Skips "Avl Bal INR 24.42"-style amounts;
     * falls back to "debited with 265.44" when only the balance has a currency.
     */
    private fun amountText(text: String): Pair<String, String?>? =
        amountRe.findAll(text).firstOrNull { m -> !balanceRe.containsMatchIn(text.substring(maxOf(0, m.range.first - 25), m.range.first)) }
            ?.let { m -> m.groupValues[2] to currencyTokens[m.groupValues[1].lowercase()] }
            ?: amountAfterRe.find(text)?.let { it.groupValues[1] to null }

    /** "1,250.50" → 125050 with 2 decimals; null when it isn't a positive number. */
    private fun minorUnits(rawAmount: String, decimals: Int): Long? {
        val major = rawAmount.replace(",", "").toDoubleOrNull() ?: return null
        if (major <= 0) return null
        return kotlin.math.round(major * CurrencyFormat.pow10(decimals)).toLong()
    }

    private fun firstIndexOf(words: List<String>, lower: String): Int =
        words.mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: Int.MAX_VALUE

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

    /** The merchant or person named by the first vendor pattern that finds a real name. */
    fun vendorOf(text: String): String? = vendorRes.firstNotNullOfOrNull { re ->
        re.find(text)?.groupValues?.get(1)?.let { v ->
            val cleaned = v.trim().trimEnd('.', '-', '_').replace(spacesRe, " ")
            if (isPlaceholder(cleaned)) null else softenCapitals(cleaned)
        }
    }

    private val spacesRe = Regex("""\s+""")
    private val longNumberRe = Regex("""^\d{5,}\b""")
    private val notVendors = setOf("your", "your a/c", "a/c", "account", "card", "you", "bank", "vpa", "rs", "inr")
    private val notVendorPhrases = listOf("dispute", "if not you", "credit card account")

    /** Masked digits, "your a/c", a long number or a disclaimer phrase caught by a vendor pattern. */
    private fun isPlaceholder(cleaned: String): Boolean {
        val low = cleaned.lowercase()
        return cleaned.length < 2 || cleaned.all { it.isDigit() || it in "xX*" } ||
            low in notVendors || low.startsWith("your ") || longNumberRe.containsMatchIn(low) || notVendorPhrases.any { it in low }
    }

    /** "AMAZON PAY" → "Amazon Pay": words over three letters written all in capitals. */
    private fun softenCapitals(name: String) = name.split(' ').joinToString(" ") { w ->
        if (w.length > 3 && w.all { it.isUpperCase() || !it.isLetter() }) w.lowercase().replaceFirstChar { it.uppercase() } else w
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
