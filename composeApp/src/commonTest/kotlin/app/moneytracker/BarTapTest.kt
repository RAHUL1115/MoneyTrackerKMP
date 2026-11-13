package app.moneytracker

import app.moneytracker.ui.barIndexAt
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tapping the bar chart: the width splits into equal slots, one per bar; taps past either end go to the nearest bar. */
class BarTapTest {
    @Test fun aTapLandsOnTheSlotUnderIt() {
        assertEquals(0, barIndexAt(x = 5f, width = 120f, count = 12))
        assertEquals(5, barIndexAt(x = 55f, width = 120f, count = 12))
        assertEquals(11, barIndexAt(x = 119f, width = 120f, count = 12))
    }

    @Test fun slotEdgesBelongToTheNextBar() {
        assertEquals(1, barIndexAt(x = 50f, width = 100f, count = 2))
    }

    @Test fun tapsOutsideTheChartGoToTheNearestBar() {
        assertEquals(0, barIndexAt(x = -5f, width = 100f, count = 4))
        assertEquals(3, barIndexAt(x = 140f, width = 100f, count = 4))
    }
}
