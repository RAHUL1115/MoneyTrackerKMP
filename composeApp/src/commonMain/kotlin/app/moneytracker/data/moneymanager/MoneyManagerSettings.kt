package app.moneytracker.data.moneymanager

import app.moneytracker.data.Prefs

/**
 * Money Manager's ZETC settings rows ⇄ our settings. Both directions live here, sharing their code tables, so an
 * exported backup restores to the same choices. Each Money Manager row has a numeric type and a text key.
 */
internal object MoneyManagerSettings {
    /** A setting Money Manager stores as a code: [codes] maps our value → its code; [otherwise] is written for anything else. */
    private class Coded(val type: Int, val etcKey: String, val pref: String, val codes: Map<String, String>, val otherwise: String? = null) {
        fun toEtc(s: Map<String, String>): String? = codes[s[pref]] ?: otherwise
        fun fromEtc(etc: Map<String, String>): String? = codes.entries.firstOrNull { it.value == etc[etcKey] }?.key
    }

    private val SWITCH = mapOf("0" to "0", "1" to "1")
    private fun switch(type: Int, etcKey: String, pref: String) = Coded(type, etcKey, pref, SWITCH)
    /** On/off settings Money Manager always has: written as "0" unless switched on. */
    private fun flag(type: Int, etcKey: String, pref: String) = Coded(type, etcKey, pref, SWITCH, otherwise = "0")

    // Written even when unset: without it Money Manager falls back to swapped income/expense colours.
    private val colors = Coded(-6179, "money_color", Prefs.COLOR_SET.key, mapOf("A" to "0", "B" to "1"), otherwise = "0")
    private val carryOver = flag(-5457, "carry_over", Prefs.CARRY_OVER.key)
    private val cardAtPayment = flag(-30009, "card_accounting_type", Prefs.CARD_AT_PAYMENT.key)
    private val useSubcategories = flag(19810, "use_sub_category", Prefs.USE_SUBCATEGORIES.key)
    private val weekStart = Coded(10002, "week_start_day", Prefs.WEEK_START.key, mapOf("SUNDAY" to "0", "MONDAY" to "1", "SATURDAY" to "6"))
    private val weekendShift = Coded(10008, "month_start_weekend_type", Prefs.WEEKEND_SHIFT.key, mapOf("BEFORE" to "1", "AFTER" to "2"), otherwise = "0")
    private val swipe = Coded(10014, "main_tab_scroll_type", Prefs.SWIPE.key, mapOf("TAB" to "0", "DATE" to "1", "NONE" to "2"))
    private val showDescription = switch(10011, "show_tx_memo_on_day_list", Prefs.SHOW_DESCRIPTION.key)
    private val autocomplete = switch(23274, "use_auto_complete", Prefs.AUTOCOMPLETE.key)
    private val noteButton = switch(10016, "memo_btn_on_daily", Prefs.NOTE_BUTTON.key)
    private val inputOrder = Coded(10017, "input_form_order", Prefs.INPUT_ORDER.key, mapOf("AMOUNT" to "1", "ACCOUNT" to "2"))
    private val repeatTiming = Coded(10009, "repeat_reflect_type", Prefs.REPEAT_TIMING.key, mapOf("ON_DATE" to "0", "MONTH_START" to "1"))
    private val smsAutoSave = switch(10006, "sms_auto_save_to_tx", Prefs.SMS_AUTO_SAVE.key)
    private val smsAlert = switch(1078, "sms_show_noti", Prefs.SMS_ALERT.key)
    private val quickAdd = flag(10000, "use_quick_add_noti", Prefs.QUICK_ADD_NOTIFICATION.key)

    /** Time input (asked and shown in lists, asked only, not asked) → its code for (descending, ascending) order. */
    private val TIME_CODES = mapOf("LIST" to ("1" to "3"), "INPUT" to ("2" to "4"), "NONE" to ("5" to "0"))
    private val SHOW_ORIGINAL = setOf("0", "1", "2")
    /** List settings: one ZETC row per line, as (type, key, our setting). */
    private val LISTS = listOf(
        Triple(4838, "sms_reject_call_number", Prefs.SMS_BLOCKED_SENDERS.key),
        Triple(4839, "sms_reject_str", Prefs.SMS_BLOCKED_PHRASES.key),
        Triple(100, "sms_content_auto_del", Prefs.SMS_CONTENT_FILTER.key),
    )

    /**
     * Our settings from a backup: [etc] is ZETC key → value, [etcRows] every row as (type, value) for the list settings,
     * [symbolPosition] the main currency's SYMBOL_POSITION.
     */
    fun read(etc: Map<String, String>, etcRows: List<Pair<String, String>>, symbolPosition: String?): Map<String, String> = buildMap {
        fun putKnown(pref: String, value: String?) { if (value != null) put(pref, value) }
        fun putCoded(c: Coded) = putKnown(c.pref, c.fromEtc(etc))
        putKnown(Prefs.MONTH_START_DAY.key, etc["start_day"]?.toIntOrNull()?.takeIf { it in 1..31 }?.toString())
        putCoded(weekendShift)
        putCoded(weekStart)
        putKnown(Prefs.CARRY_OVER.key, etc["carry_over"]?.takeIf { it == "1" })
        putKnown(Prefs.CARD_AT_PAYMENT.key, etc["card_accounting_type"]?.takeIf { it == "1" })
        // Money Manager's own "Subcategory" switch (off unless the user enabled it there).
        put(Prefs.USE_SUBCATEGORIES.key, if (etc["use_sub_category"] == "1") "1" else "0")
        // Configuration screen choices.
        putKnown(Prefs.START_SCREEN.key, etc["main_tab_first_view"]?.toIntOrNull()?.let { if (it > 0) "CALENDAR" else "DAILY" })
        putCoded(swipe)
        putCoded(colors)
        timeChoice(etc["time_display_order"])?.let { (input, sort) -> put(Prefs.TIME_INPUT.key, input); put(Prefs.TIME_SORT.key, sort) }
        putCoded(showDescription)
        putCoded(autocomplete)
        putCoded(noteButton)
        putCoded(inputOrder)
        putCoded(repeatTiming)
        // Messages: lists are one ZETC row per entry; switches by name.
        for ((type, _, pref) in LISTS) putKnown(pref, listSetting(etcRows, type.toString()))
        putCoded(smsAutoSave)
        putCoded(smsAlert)
        putKnown(Prefs.SMS_SHOW_ORIGINAL.key, (etc["sms_show_org_on_tx"] ?: etc["-12649"])?.takeIf { it in SHOW_ORIGINAL })
        putKnown(Prefs.SMS_IMPORT_DAYS.key, etc["sms_search_day"]?.toIntOrNull()?.takeIf { it > 0 }?.toString())
        putKnown(Prefs.SYMBOL_AFTER.key, mapOf("P" to "0", "S" to "1")[symbolPosition])
    }

    /** Time code → (time input, sort order); 5/0 = not asked (desc/asc), 1/3 = asked and shown in lists, 2/4 = asked only. */
    internal fun timeChoice(code: String?): Pair<String, String>? = TIME_CODES.entries.firstNotNullOfOrNull { (input, codes) ->
        when (code) {
            codes.first -> input to "DESC"
            codes.second -> input to "ASC"
            else -> null
        }
    }

    /** Time input and sort order → Money Manager's time code; null when the input choice is unset. */
    internal fun timeCode(input: String?, sort: String?): String? =
        TIME_CODES[input]?.let { (descending, ascending) -> if (sort == "ASC") ascending else descending }

    private fun listSetting(etcRows: List<Pair<String, String>>, type: String): String? =
        etcRows.filter { it.first == type }.map { it.second.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").takeIf { it.isNotEmpty() }

    /** Our settings as Money Manager ZETC rows (type, key, value); the inverse of [read]. */
    fun rows(s: Map<String, String>): List<Triple<Int, String, String>> = buildList {
        fun row(type: Int, key: String, value: String?) { if (value != null) add(Triple(type, key, value)) }
        fun row(c: Coded) = row(c.type, c.etcKey, c.toEtc(s))
        row(-23459, "start_day", s[Prefs.MONTH_START_DAY.key] ?: "1")
        row(colors)
        row(carryOver)
        row(cardAtPayment)
        row(useSubcategories)
        row(weekStart)
        row(weekendShift)
        row(10003, "main_tab_first_view", if (s[Prefs.START_SCREEN.key] == "CALENDAR") "1" else "0")
        row(swipe)
        row(10004, "time_display_order", timeCode(s[Prefs.TIME_INPUT.key], s[Prefs.TIME_SORT.key]))
        row(showDescription)
        row(autocomplete)
        row(noteButton)
        row(inputOrder)
        row(repeatTiming)
        row(smsAutoSave)
        row(smsAlert)
        row(-12649, "sms_show_org_on_tx", s[Prefs.SMS_SHOW_ORIGINAL.key]?.takeIf { it in SHOW_ORIGINAL })
        row(1077, "sms_search_day", s[Prefs.SMS_IMPORT_DAYS.key])
        row(quickAdd)
        // List settings: one row per line.
        for ((type, key, pref) in LISTS) lines(s[pref]).forEach { row(type, key, it) }
    }

    private fun lines(setting: String?) = setting.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
}
