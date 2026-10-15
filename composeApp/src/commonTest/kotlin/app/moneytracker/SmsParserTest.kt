package app.moneytracker

import app.moneytracker.domain.SmsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic messages in the style of common Indian bank alerts (no real account data). */
class SmsParserTest {
    @Test fun cardSpendAtMerchant() {
        val p = SmsParser.parse("VM-HDFCBK", "Rs.1,250.50 spent on HDFC Bank Card x1234 at SWIGGY on 2026-10-12. Avl Lmt: Rs 50,000")!!
        assertEquals("HDFC Bank", p.bank)
        assertEquals(125_050, p.amount)
        assertFalse(p.isCredit)
        assertEquals("1234", p.accountDigits)
        assertEquals("Swiggy", p.vendor)
    }

    @Test fun upiDebitToVpa() {
        val p = SmsParser.parse("AX-ICICIT", "ICICI Bank Acct XX567 debited for Rs 499.00 on 12-Oct-26; zomato@paytm credited. UPI:123456789012")!!
        assertEquals("ICICI Bank", p.bank)
        assertEquals(49_900, p.amount)
        assertFalse(p.isCredit)
        assertEquals("567", p.accountDigits)
    }

    @Test fun upiVpaVendorExtracted() {
        val p = SmsParser.parse("JD-SBIINB", "Dear UPI user A/C X9876 debited by 120.0 on date 12Oct26 trf to VPA swiggy.stores@axb Refno 1234")!!
        assertEquals("SBI", p.bank)
        assertEquals(12_000, p.amount)
        assertEquals("swiggy.stores", p.vendor)
    }

    @Test fun salaryCredit() {
        val p = SmsParser.parse("VK-KOTAKB", "Rs 85,000.00 credited to your a/c XX4321 on 01-10-2026 by NEFT from ACME CORP. Avl bal Rs 1,02,000")!!
        assertTrue(p.isCredit)
        assertEquals(8_500_000, p.amount)
        assertEquals("Kotak Bank", p.bank)
        assertEquals("Acme Corp", p.vendor)
    }

    @Test fun foreignCurrencyDetected() {
        val p = SmsParser.parse("AMEXIN", "You've spent USD 25.99 on your American Express card ending 1007 at NETFLIX.COM on 10 Oct.")!!
        assertEquals("USD", p.currencyCode)
        assertEquals(2_599, p.amount)
        assertEquals("1007", p.accountDigits)
    }

    @Test fun ignoresOtpAndOffers() {
        assertNull(SmsParser.parse("VM-HDFCBK", "123456 is your OTP for txn of Rs 2,000 at AMAZON. Do not share."))
        assertNull(SmsParser.parse("VM-ICICIB", "Congratulations! You are eligible for a pre-approved loan of Rs 5,00,000"))
        assertNull(SmsParser.parse("AD-SHOPCO", "Flat 50% off today only!"))
    }
}
