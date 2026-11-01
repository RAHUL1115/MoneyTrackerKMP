package app.moneytracker

import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyMaskTest {
    @Test fun maskedAmountsAllLookTheSame() {
        val f = CurrencyFormat(masked = true)
        assertEquals("₹ ••••", f.format(54_000))
        assertEquals("₹ ••••", f.format(1_23_45_67_800))     // a huge amount is no longer
        assertEquals("-₹ ••••", f.format(-30_000))            // direction stays
        assertEquals("+••••", f.format(5_000, showSign = true, withSymbol = false))
    }

    @Test fun maskingIsDisplayOnly() {
        val f = CurrencyFormat(masked = true)
        assertEquals(12_345L, f.parse("123.45"))
        assertEquals("123.45", f.toInput(12_345))
        assertEquals("₹ 123.45", f.copy(masked = false).format(12_345))
    }

    @Test fun otherCurrenciesMaskToo() {
        val usd = Currency("USD", "$", 2, 83.0, masked = true)
        assertEquals("$ ••••", usd.format.format(9_999))
    }
}
