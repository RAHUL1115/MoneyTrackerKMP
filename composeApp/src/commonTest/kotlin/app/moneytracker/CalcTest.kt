package app.moneytracker

import app.moneytracker.domain.Calc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalcTest {
    @Test fun precedenceAndRounding() {
        assertEquals("14", Calc.evaluate("2+3×4", 2))
        assertEquals("10.5", Calc.evaluate("12−3÷2", 2))
        assertEquals("33.33", Calc.evaluate("100÷3", 2))
        assertEquals("33", Calc.evaluate("100÷3", 0))
        assertEquals("250", Calc.evaluate("250", 2))
    }

    @Test fun invalidOrIncompleteIsNull() {
        assertNull(Calc.evaluate("5÷0", 2))
        assertNull(Calc.evaluate("5+", 2))
        assertNull(Calc.evaluate("3−5", 2))      // amounts can't go negative
        assertNull(Calc.evaluate("", 2))
    }

    @Test fun operatorDetection() {
        assertTrue(Calc.hasOperator("1+2"))
        assertFalse(Calc.hasOperator("12.5"))
    }
}
