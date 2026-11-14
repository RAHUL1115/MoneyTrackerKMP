package app.moneytracker.designsystem.classic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tapping the donut chart: slices start at 12 o'clock and run clockwise; the hole and the outside select nothing. */
class DonutSliceTest {
    private val halves = listOf(50L, 50L)

    @Test fun slicesRunClockwiseFromTheTop() {
        assertEquals(0, donutSliceAt(dx = 80f, dy = 0f, outer = 100f, values = halves))    // 3 o'clock
        assertEquals(1, donutSliceAt(dx = -80f, dy = 0f, outer = 100f, values = halves))   // 9 o'clock
        assertEquals(2, donutSliceAt(dx = 0f, dy = 80f, outer = 100f, values = listOf(1L, 1L, 2L)))   // 6 o'clock, third slice
    }

    @Test fun holeAndOutsideHitNothing() {
        assertNull(donutSliceAt(dx = 10f, dy = 10f, outer = 100f, values = halves))
        assertNull(donutSliceAt(dx = 120f, dy = 0f, outer = 100f, values = halves))
        assertEquals(0, donutSliceAt(dx = 105f, dy = 0f, outer = 100f, values = halves))   // a little slack past the edge
    }
}
