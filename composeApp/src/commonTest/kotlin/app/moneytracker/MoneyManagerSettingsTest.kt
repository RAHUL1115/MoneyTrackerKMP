package app.moneytracker

import app.moneytracker.data.MoneyManagerSettings
import app.moneytracker.data.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Settings written to a Money Manager backup come back as the same choices. */
class MoneyManagerSettingsTest {
    @Test fun timeCodesRoundTrip() {
        for (input in listOf("LIST", "INPUT", "NONE")) for (sort in listOf("ASC", "DESC")) {
            val code = MoneyManagerSettings.timeCode(input, sort)
            assertEquals(input to sort, MoneyManagerSettings.timeChoice(code), "code $code")
        }
        assertEquals("3", MoneyManagerSettings.timeCode("LIST", "ASC"))
        assertEquals("5", MoneyManagerSettings.timeCode("NONE", null))
        assertNull(MoneyManagerSettings.timeCode(null, "ASC"))
        assertNull(MoneyManagerSettings.timeChoice("9"))
    }

    @Test fun exportedSettingsReadBack() {
        val ours = mapOf(
            Prefs.MONTH_START_DAY.key to "25", Prefs.COLOR_SET.key to "B", Prefs.CARRY_OVER.key to "1", Prefs.CARD_AT_PAYMENT.key to "1",
            Prefs.USE_SUBCATEGORIES.key to "1", Prefs.WEEK_START.key to "SATURDAY", Prefs.WEEKEND_SHIFT.key to "AFTER",
            Prefs.START_SCREEN.key to "CALENDAR", Prefs.SWIPE.key to "NONE", Prefs.TIME_INPUT.key to "INPUT", Prefs.TIME_SORT.key to "ASC",
            Prefs.SHOW_DESCRIPTION.key to "0", Prefs.AUTOCOMPLETE.key to "1", Prefs.NOTE_BUTTON.key to "0", Prefs.INPUT_ORDER.key to "ACCOUNT",
            Prefs.REPEAT_TIMING.key to "MONTH_START", Prefs.SMS_BLOCKED_SENDERS.key to "AD-PROMO\nVM-SPAM", Prefs.SMS_BLOCKED_PHRASES.key to "offer",
            Prefs.SMS_CONTENT_FILTER.key to "Dear customer", Prefs.SMS_AUTO_SAVE.key to "1", Prefs.SMS_ALERT.key to "0",
            Prefs.SMS_SHOW_ORIGINAL.key to "2", Prefs.SMS_IMPORT_DAYS.key to "9",
        )
        val rows = MoneyManagerSettings.rows(ours)
        val back = MoneyManagerSettings.read(rows.associate { it.second to it.third }, rows.map { it.first.toString() to it.third }, symbolPosition = "S")
        assertEquals(ours + (Prefs.SYMBOL_AFTER.key to "1"), back)
    }

    @Test fun unsetSettingsWriteMoneyManagersDefaults() {
        val rows = MoneyManagerSettings.rows(emptyMap()).associate { it.second to it.third }
        assertEquals(
            mapOf("start_day" to "1", "money_color" to "0", "carry_over" to "0", "card_accounting_type" to "0", "use_sub_category" to "0",
                "month_start_weekend_type" to "0", "main_tab_first_view" to "0", "use_quick_add_noti" to "0"),
            rows,
        )
    }
}
