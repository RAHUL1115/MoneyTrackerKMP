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

    @Test fun hsbcCardUsedAt() {
        // "used at …" with the limit and amount due after it.
        val p = SmsParser.parse("VM-HSBCIN", "HSBC Credit Card xx6456 used at RELIANCE RETAIL LIMITE for INR 3599.00 on 02/11/26. " +
            "Avl limit INR 490738.08; due INR 3261.92. Call +914065118002 to report fraud.")!!
        assertEquals("HSBC", p.bank)
        assertEquals(359_900, p.amount)
        assertFalse(p.isCredit)
        assertEquals("6456", p.accountDigits)
        assertEquals(SmsParser.Instrument.CREDIT_CARD, p.instrument)
        assertEquals("Reliance Retail Limite", p.vendor)
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

    @Test fun parsesPublicMultiBankFormats() {
        for (example in PublicSmsFixtures.examples) {
            val p = SmsParser.parse(example.sender, example.text)!!
            val context = "${example.name}: ${example.source}"
            assertEquals(example.bank, p.bank, context)
            assertEquals(example.amount, p.amount, context)
            assertEquals(example.digits, p.accountDigits, context)
            assertEquals(example.instrument, p.instrument, context)
            assertEquals(example.refund, p.isRefund, context)
            assertEquals(example.refund, p.isCredit, context)
        }
    }

    @Test fun recognizesBankFromSenderWithDltSuffixWithoutBankNameInBody() {
        for ((sender, bank) in listOf(
            "CP-HDFCBK-S" to "HDFC Bank", "AX-ICICIT-S" to "ICICI Bank",
            "SBIUPI-S" to "SBI", "AX-AXISBK-S" to "Axis Bank",
            "TX-KOTAKB-S" to "Kotak Bank", "VA-SLCBNK-S" to "slice",
        )) {
            assertEquals(bank, SmsParser.bankOf(sender, "Rs.250 spent on your credit card xx1234."), sender)
        }
    }

    @Test fun instrumentIsIndependentOfCreditOrDebitDirection() {
        val cases = listOf(
            "Rs.250 spent on credit card xx1234 at SHOP." to SmsParser.Instrument.CREDIT_CARD,
            "Rs.250 refunded to credit-card xx1234 at SHOP." to SmsParser.Instrument.CREDIT_CARD,
            "Rs.250 credited to a/c xx1234 by ACME." to SmsParser.Instrument.BANK_ACCOUNT,
            "Rs.250 spent using debit card xx1234 at SHOP." to SmsParser.Instrument.DEBIT_CARD,
            "Rs.250 spent using card xx1234 at SHOP." to SmsParser.Instrument.CARD,
            "Rs.250 debited from account xx1234 towards credit card payment." to SmsParser.Instrument.BANK_ACCOUNT,
            "Rs.250 received from ACME." to null,
        )
        for ((sms, instrument) in cases) {
            assertEquals(instrument, SmsParser.parse("AD-HDFCBK", sms)!!.instrument, sms)
        }
    }

    @Test fun ignoresOtpAndOffers() {
        assertNull(SmsParser.parse("VM-HDFCBK", "123456 is your OTP for txn of Rs 2,000 at AMAZON. Do not share."))
        assertNull(SmsParser.parse("VM-ICICIB", "Congratulations! You are eligible for a pre-approved loan of Rs 5,00,000"))
        assertNull(SmsParser.parse("AD-SHOPCO", "Flat 50% off today only!"))
        assertNull(SmsParser.parse("VM-HDFCBK", "Get cashback offer: spend Rs 5,000 and win a voucher worth Rs 500!"))
        assertNull(SmsParser.parse("VM-SBIINB", "Rs 1,499.00 will be debited from A/c XX1234 on 05-Nov for your mandate."))
    }

    @Test fun ignoresFailedPayments() {
        assertNull(SmsParser.parse("VM-HDFCBK", "Your payment of Rs.500 at SHOP failed. No amount debited from a/c XX1234."))
        assertNull(SmsParser.parse("VM-ICICIB", "Txn of INR 2,000.00 on Card XX9876 at AMAZON was declined due to insufficient limit."))
        assertNull(SmsParser.parse("VM-SBIINB", "UPI transaction of Rs 300 to ZOMATO is unsuccessful. Amount not debited."))
    }

    @Test fun reversalIsARefund() {
        val p = SmsParser.parse("VM-HDFCBK", "Reversal of Rs.500.00 for transaction debited from a/c XX1234 on 03-Nov-26.")!!
        assertTrue(p.isCredit)
        assertTrue(p.isRefund)
        assertEquals(50_000, p.amount)
    }

    @Test fun keepsTransactionsWithPromoFooters() {
        // Real alerts often end with an ad; the transaction itself must still be read.
        val upi = SmsParser.parse("JD-HDFCBK-S", "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 04/11/26 Ref 612345678901. " +
            "Not You? Call 18001234567. Exclusive offer: win cashback on your next order!")!!
        assertEquals(25_000, upi.amount)
        assertFalse(upi.isCredit)
        assertEquals("1234", upi.accountDigits)
        val card = SmsParser.parse("AX-ICICIT", "INR 1,200.00 spent using ICICI Bank Card XX9876 on 04-Nov-26 on AMAZON. " +
            "Avl Limit: INR 50,000.00. Congratulations, you have unlocked a voucher.")!!
        assertEquals(120_000, card.amount)
        assertEquals("9876", card.accountDigits)
        val salary = SmsParser.parse("VM-SBIINB", "Your A/c XX4321 is credited with Rs 40,000.00 on 01-Nov-26. Check out our FD offers.")!!
        assertTrue(salary.isCredit)
        assertEquals(4_000_000, salary.amount)
    }
}
