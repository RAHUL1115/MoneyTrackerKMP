package app.moneytracker

import app.moneytracker.domain.AccountType
import app.moneytracker.domain.SmsParser

/** Public-format excerpts, anonymized where needed. Provenance and transformations: docs/sms-detection-tests.md. */
internal object PublicSmsFixtures {
    private const val INDIAN = "https://github.com/harshsingh-io/indian-bank-sms-parser/blob/e5363133cb41d8bf5f0ddf2c1ab1231dade89bb3/"
    private const val PENNYWISE = "https://github.com/sarim2000/pennywiseai-tracker/blob/0cdab242478eee89194936f6f87f84a858a674c1/parser-core/src/test/kotlin/"

    data class Example(
        val name: String, val source: String, val sender: String, val text: String,
        val bank: String, val amount: Long, val digits: String?,
        val instrument: SmsParser.Instrument?, val target: AccountType?, val refund: Boolean = false,
    )

    val examples = listOf(
        Example("HDFC UPI account debit", PENNYWISE + "TestHDFCBankParser.kt", "CP-HDFCBK-S",
            "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)",
            "HDFC Bank", 50_000, "1234", SmsParser.Instrument.BANK_ACCOUNT, AccountType.BANK),
        Example("HDFC bare card with conflicting block-footer digits", INDIAN + "tests/fixtures/hdfc/07-credit-card-spend.json", "HDFCBK-S(smsft)",
            "Spent Rs.2049 On HDFC Bank Card 1003 At BLUE LEAF STORES On 2025-11-11:11:04:40.Not You? To Block+Reissue Call 1800000000/SMS BLOCK CC 8515 to 7000000000",
            "HDFC Bank", 204_900, "1003", SmsParser.Instrument.CARD, AccountType.CREDIT_CARD),
        Example("HDFC spend without account identity", INDIAN + "tests/fixtures/hdfc/08-debit-card-spend.json", "AD-HDFCBK-S",
            "Spent Rs.2 From DAILY FRESH MART On 2025-12-16:09:40:54 Bal Rs.95853.26 Not You? Call 1800000000/SMS BLOCK DC 3271 to 7000000000",
            "HDFC Bank", 200, null, null, null),
        Example("ICICI card amount before available limit", INDIAN + "tests/fixtures/icici/01-card-spend.json", "ICICIT-S(smsft)",
            "INR 2,995.00 spent using ICICI Bank Card XX1005 on 19-Feb-26 on EXAMPLE STORE E. Avl Limit: INR 87,005.00.",
            "ICICI Bank", 299_500, "1005", SmsParser.Instrument.CARD, AccountType.CREDIT_CARD),
        Example("ICICI debit with counterparty credit", PENNYWISE + "TestICICIBankParser.kt", "AX-ICICIT-S",
            "ICICI Bank Acct XX123 debited for Rs 500.00 on 01-Oct-25; merchant credited. UPI: 123456789012. Call 1800000000 for dispute. Updated Bal: Rs 5,000.00",
            "ICICI Bank", 50_000, "123", SmsParser.Instrument.BANK_ACCOUNT, AccountType.BANK),
        Example("ICICI card refund", INDIAN + "tests/fixtures/icici/04-refund.json", "ICICIT-S(smsft)",
            "EXAMPLE STORE GROCERY refund of Rs 202.00 credited to ICICI Bank Card XX1005 on 17-MAY-26.",
            "ICICI Bank", 20_200, "1005", SmsParser.Instrument.CARD, AccountType.CREDIT_CARD, refund = true),
        Example("SBI UPI without currency marker", INDIAN + "tests/fixtures/sbi/01-upi-debit-no-currency-marker.json", "SBIUPI-S(smsft)",
            "Dear UPI user A/C X1001 debited by 305.0 on date 08Oct25 trf to AAHARA CAFE Refno 100000000001 If not u? call-1800000000 for other services-7000000000-SBI",
            "SBI", 30_500, "1001", SmsParser.Instrument.BANK_ACCOUNT, AccountType.BANK),
        Example("SBI debit card with leading zero suffix", PENNYWISE + "TestSBIParser.kt", "ATMSBI",
            "Dear Customer, transaction number 1234 for Rs.383.00 by SBI Debit Card 0000 done at merchant on 13Sep25 at 21:38:26. Your updated available balance is Rs.999999999.",
            "SBI", 38_300, "0000", SmsParser.Instrument.DEBIT_CARD, AccountType.DEBIT_CARD),
        Example("Axis multiline card", PENNYWISE + "TestAxisBankParser.kt", "AX-AXISBK-S",
            "Spent INR 1299.00\nAxis Bank Card no. XX5678\n12-10-25 14:30:15 IST\nAmazon Pay\nAvl Limit: INR 50000.00\nNot you? SMS BLOCK 5678 to 7000000000",
            "Axis Bank", 129_900, "5678", SmsParser.Instrument.CARD, AccountType.CREDIT_CARD),
        Example("Kotak credit card over UPI", PENNYWISE + "TestKotakBankParser.kt", "TX-KOTAKB-S",
            "INR 20 spent on Kotak Credit Card x1234 on 23-JAN-2026 at UPI-100000000000-SHOP. Avl limit INR 73733.02",
            "Kotak Bank", 2_000, "1234", SmsParser.Instrument.CREDIT_CARD, AccountType.CREDIT_CARD),
        Example("Slice credit card over UPI", PENNYWISE + "com/pennywiseai/parser/core/bank/SliceParserTest.kt", "VA-SLCBNK-S",
            "Rs. 124 spent on your credit card xx1234 at Sample Merchant on 18-Jun-26 (UPI Ref: 100000000000). Not you? Call 080-0000-0000 - slice",
            "slice", 12_400, "1234", SmsParser.Instrument.CREDIT_CARD, AccountType.CREDIT_CARD),
    )
}
