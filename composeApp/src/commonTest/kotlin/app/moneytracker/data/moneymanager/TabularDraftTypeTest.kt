package app.moneytracker.data.moneymanager

import app.moneytracker.data.DraftType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a spreadsheet's Type column (and a To account or a minus sign) becomes an entry type. */
class TabularDraftTypeTest {
    private fun type(text: String, hasTo: Boolean = false, negative: Boolean = false) = TabularImport.draftType(text, hasTo, negative)

    @Test fun namedTypes() {
        assertEquals(DraftType.TRANSFER, type("transfer"))
        assertEquals(DraftType.INCOME, type("income"))
        assertEquals(DraftType.INCOME, type("income balance"))
        assertEquals(DraftType.INCOME, type("adjust_up"))
        assertEquals(DraftType.EXPENSE, type("expenses"))
        assertEquals(DraftType.EXPENSE, type("adjust_down"))
        assertEquals(DraftType.REFUND, type("refund"))
        assertNull(type("loan"))
    }

    @Test fun columnsAndSignsDecideTheRest() {
        assertEquals(DraftType.TRANSFER, type("expense", hasTo = true))
        // A negative expense is a refund; a blank type follows the sign.
        assertEquals(DraftType.REFUND, type("expense", negative = true))
        assertEquals(DraftType.EXPENSE, type("", negative = true))
        assertEquals(DraftType.INCOME, type(""))
    }
}
