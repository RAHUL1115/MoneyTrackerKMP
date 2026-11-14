package app.moneytracker.domain

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

    @Test fun amountIsNotTheBalanceWhenOnlyTheBalanceHasACurrency() {
        // IPPB style: the moved amount has no "Rs", the balance does.
        val p = SmsParser.parse("AD-IPBMSG-S", "Dear Customer, Your a/c XXXXXXXX1234 has been debited with 265.44 on 05-05-2026 23:10:18 by UPI. Avl Bal INR 24.42")!!
        assertEquals(26_544, p.amount)
        assertFalse(p.isCredit)
        val c = SmsParser.parse("AX-IPBMSG-S", "Your IPPB account XXXXXXXX1234 has been credited with 100.00 on 28-12-2025 by cash deposit. Avl Bal Rs. 150.00.")!!
        assertEquals(10_000, c.amount)
        assertTrue(c.isCredit)
    }

    @Test fun readsCardTxnAndSuccessfulTransactionFormats() {
        val hdfc = SmsParser.parse("VM-HDFCBK-S", "Txn Rs.28.00 On HDFC Bank Card 1234 At shopqr123@paytm by UPI 612345678901 On 26-02 Not You? Call 18001234567/SMS BLOCK CC 1234 to 7308080808")!!
        assertEquals(2_800, hdfc.amount)
        assertFalse(hdfc.isCredit)
        assertEquals("1234", hdfc.accountDigits)
        val slice = SmsParser.parse("JD-SLCEIT-S", "Your slice credit card transaction of Rs. 29 on Ravi Kumar is successful. If not you, call 08048329999 - slice")!!
        assertEquals(2_900, slice.amount)
        assertFalse(slice.isCredit)
        assertEquals("slice", slice.bank)
        assertEquals(SmsParser.Instrument.CREDIT_CARD, slice.instrument)
    }

    @Test fun ignoresLoanPitchesRemindersAndRequests() {
        for (sms in listOf(
            "Service Alert: Funds of INR 1,50,000.00 on YES BANK Credit Card ending 1234 are available and require consent to continue disbursement. ccybl.in/YESBNK/abc - YES BANK LTD",
            "Access Loan of INR 1,50,000.00 on your YES BANK Credit Card 1234 by providing consent here: ccybl.in/YESBNK/abc - YES BANK LTD",
            "Energy bill for consumer No. 123456789 of Rs. 1,234.00 is due on 08-10-24. You are requested to pay the bill immediately. Ignore if already paid -BEST",
            "Dear Customer, Rs. 8,000* is credited to your wallet acc, Your Ref.id - XXX123 Download T&C Apply Click now http://kx1.in/abc",
            "Dear ABCDE1234F IPO Example Industries Limited : App no OQ123 UPI ID me@okbank for Rs 14,000.00 received. Please check for UPI-Mandate collect request",
            "Indusind Bank Limited has requested money on the Paytm App. On approving, Rs.12000 will be debited from your account.",
            "Dear customer, the premium of Rs 25000 for your policy L123 cannot be debited on 07-Aug-27 as the mandate is not registered with the bank.",
            "Dear Cardholder, your payment e-Mandate set at merchant platform with your credit card ending 1234 has been registered. Merchant: Example, e-Mandate Limit: Rs 1,500.00",
        )) assertNull(SmsParser.parse("JK-YESBNK-S", sms), sms)
    }

    @Test fun keepsRealAlertsThatMentionLoansMandatesRewardsOrAsterisks() {
        assertEquals(9_500_000, SmsParser.parse("VM-HDFCBK-S", "Update! INR 95,000.00 deposited in HDFC Bank A/c XX1234 on 22-MAY-26 for Example Finance Disbursement-XX5678")!!.amount)
        assertEquals(1_000_000, SmsParser.parse("AX-ICICIT-S", "ICICI Bank Acc XX123 debited with INR 10,000.00 on 09-Jan-23. ATM*ABCD123*.Avb Bal: INR116.00.")!!.amount)
        assertEquals(106_082, SmsParser.parse("JD-SBICRD-S", "Transaction of Rs.1,060.82 at EXAMPLE LLC against E-mandate (ID - Ab12) registered by you at merchant has been debited to your SBI Credit Card ending 1234")!!.amount)
        assertEquals(76_900, SmsParser.parse("BX-ONECRD-S", "You have just paid a bill of Rs. 769.00 on Example Tech on card ending XX1234 & earned reward pts: https://example.link/txns -Team OneCard")!!.amount)
        assertNull(SmsParser.parse("JD-HDFCBK-S", "Earn scratch cards and grab amazing rewards with just a UPI transaction of INR 150 or more on your card"))
    }

    @Test fun creditCardBillPaymentIsNotIncome() {
        // Paying the card bill moves money between the user's own accounts; the bank-side debit already records it.
        for (sms in listOf(
            "Dear Cardmember, payment of Rs.1,445.00 is received towards your YES BANK Credit Card ending 1234. It will reflect in your Credit Card within 1-2 working days",
            "Payment of INR 548 has been received towards your Axis Bank Credit Card XX1234 on 03-09-25 - Axis Bank",
            "Repayment of Rs.1,200 received for the slice credit card. The amount has been adjusted.",
        )) assertNull(SmsParser.parse("VM-YESBNK-S", sms), sms)
        // A refund credited to a card is still read.
        assertTrue(SmsParser.parse("VM-HDFCBK", "Refund of Rs.899.00 from AMAZON credited to your HDFC Bank Credit Card xx1234 on 09/08/26.")!!.isRefund)
    }

    @Test fun vendorSkipsBoilerplate() {
        assertEquals("Shree Balaji Medical", SmsParser.parse("VA-YESBNK-S", "INR 35.00 spent on YES BANK Card X1234 @UPI_SHREE BALAJI MEDICAL 18-01-2026 04:55:02 pm. Avl Lmt INR 1,50,000.00. SMS BLKCC 1234 to 9840909000 if not you")!!.vendor)
        assertNull(SmsParser.parse("VM-FEDBNK-S", "Rs 70.00 debited from your A/c using UPI on 02-11-2026 16:23:19 to VPA 9876543210@ybl - (UPI Ref No 612345678901)-Federal Bank")!!.vendor)
        assertNull(SmsParser.parse("JD-UTKSPR-S", "Dear Ravi, your SuperCard 1234 debited for INR 20.00 on 01 Jan 02:57 AM for UPI - 612345678901. To dispute call 18001234567 - Utkarsh SFBL")!!.vendor)
    }

    @Test fun readsBankEmailLaidOutAsLabelValueRows() {
        // slice e-mail alert flattened to text: "To <merchant> RRN <number>"
        val p = SmsParser.parse("noreply@slice.bank.in", "Hi RAVI, ₹104 debited from your slice account xx1234 via UPI. Transaction date 04-Nov-26 To mcdelivery RRN 612345678901 Best, Team slice")!!
        assertEquals(10_400, p.amount)
        assertFalse(p.isCredit)
        assertEquals("slice", p.bank)
        assertEquals("1234", p.accountDigits)
        assertEquals("mcdelivery", p.vendor)
    }

    @Test fun recognizesMoreBanksFromSender() {
        for ((sender, bank) in listOf("JD-UTKSPR-S" to "Utkarsh SFB", "AD-IPBMSG-S" to "India Post Payments Bank", "VM-FEDSCP-S" to "Scapia", "JD-SLCEIT-S" to "slice")) {
            assertEquals(bank, SmsParser.bankOf(sender, "Rs.250 spent on your card xx1234."), sender)
        }
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
