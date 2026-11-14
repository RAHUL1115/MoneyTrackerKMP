package app.moneytracker.web

import app.moneytracker.data.PeriodSettings
import app.moneytracker.domain.DateRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** The web dashboard's pure pieces: periods asked for in the query, tag names from the form, password checks. */
class WebPartsTest {
    private val ps = PeriodSettings()
    private val today = LocalDate.parse("2026-11-07")
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun monthComesFromTheQueryOrToday() {
        assertEquals(202603, requestedMonth(mapOf("ym" to "202603"), ps, today).key)
        assertEquals(202611, requestedMonth(emptyMap(), ps, today).key)
        assertEquals(202611, requestedMonth(mapOf("ym" to "202613"), ps, today).key)   // no 13th month
        assertEquals(202611, requestedMonth(mapOf("ym" to "soon"), ps, today).key)
    }

    @Test fun rangesForEachPeriod() {
        assertEquals(DateRange(d("2026-11-01"), d("2026-11-30")) to "month", requestedRange(emptyMap(), ps, today))
        assertEquals(DateRange(d("2026-09-01"), d("2026-11-30")) to "3m", requestedRange(mapOf("period" to "3m"), ps, today))
        assertEquals(DateRange(d("2025-01-01"), d("2025-12-31")) to "year", requestedRange(mapOf("period" to "year", "ym" to "202505"), ps, today))
        assertEquals(
            DateRange(d("2026-01-05"), d("2026-02-10")) to "custom",
            requestedRange(mapOf("period" to "custom", "from" to "2026-01-05", "to" to "2026-02-10"), ps, today),
        )
    }

    @Test fun customRangesAreChecked() {
        fun refusal(from: String?, to: String?): String {
            val q = buildMap { put("period", "custom"); from?.let { put("from", it) }; to?.let { put("to", it) } }
            return assertFailsWith<ApiError> { requestedRange(q, ps, today) }.message.orEmpty()
        }
        assertEquals("Pick a start date", refusal(null, "2026-01-01"))
        assertEquals("Pick an end date", refusal("2026-01-01", "someday"))
        assertEquals("The end date is before the start date", refusal("2026-02-01", "2026-01-01"))
        assertEquals("Pick 10 years or less", refusal("2010-01-01", "2026-01-01"))
    }

    @Test fun daysSoFarCountsTodayAndStopsAtTheEnds() {
        val nov = DateRange(d("2026-11-01"), d("2026-11-30"))
        assertEquals(7, daysSoFar(nov, today))
        assertEquals(0, daysSoFar(nov, d("2026-10-31")))
        assertEquals(30, daysSoFar(nov, d("2026-12-15")))
    }

    @Test fun tagNamesAreTrimmedAndKeptOnce() {
        val form = mapOf("tags" to listOf("#Trip", "trip", "  food ", "#", "", 42, "x".repeat(80)))
        assertEquals(listOf("Trip", "food", "x".repeat(60)), EntryDraftReader.tagNames(form))
        assertEquals(emptyList(), EntryDraftReader.tagNames(mapOf("tags" to "not a list")))
        assertEquals(20, EntryDraftReader.tagNames(mapOf("tags" to (1..30).map { "t$it" })).size)
    }

    @Test fun passwordCheckRefusesMalformedHashes() {
        val stored = WebPassword.hash("secret1")
        assertTrue(WebPassword.check("secret1", stored))
        assertFalse(WebPassword.check("secret2", stored))
        listOf(null, "", "secret1", "w1:", "w1:abc", "w1:zz:00", "w1:00:0", "w1:00:00:00", "x1" + stored.drop(2)).forEach {
            assertFalse(WebPassword.check("secret1", it), "accepted $it")
        }
    }
}
