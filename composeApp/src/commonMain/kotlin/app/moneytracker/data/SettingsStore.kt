package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.Periods
import app.moneytracker.domain.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Month boundaries and week layout, all user settings. */
data class PeriodSettings(
    val startDay: Int = 1,
    val shift: Periods.WeekendShift = Periods.WeekendShift.NONE,
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
) {
    fun month(label: YearMonth) = Periods.month(label, startDay, shift)
    fun labelFor(date: LocalDate) = Periods.labelFor(date, startDay, shift)
    fun year(year: Int) = DateRange(month(YearMonth(year, 1)).start, month(YearMonth(year, 12)).endInclusive)
}

/** Stored settings, read raw or through their typed [Setting] keys, plus the combined views several screens need. */
class SettingsStore(private val db: Db) {
    private val q get() = db.q

    /** Raw stored text by key, for backups and file formats that carry settings as they are. Screens use [Prefs]. */
    fun settingNow(key: String): String? = q.getSetting(key).executeAsOneOrNull()
    fun putSetting(key: String, value: String) = q.putSetting(key, value)

    /** A setting, live: emits when its value changes. */
    fun <T> setting(s: Setting<T>): Flow<T> = q.getSetting(s.key).asFlow().mapToOneOrNull(db.io).map(s::read).distinctUntilChanged()
    fun <T> settingNow(s: Setting<T>): T = s.read(settingNow(s.key))
    fun <T> putSetting(s: Setting<T>, value: T) = putSetting(s.key, s.write(value))

    /** Removes stored settings no feature reads any more ([Prefs.retired]). */
    fun dropRetiredSettings() = q.deleteSettings(Prefs.retired)

    val monthStartDay: Flow<Int> = setting(Prefs.MONTH_START_DAY)

    /** Sub-categories are optional and off unless the user turns them on. */
    val useSubcategories: Flow<Boolean> = setting(Prefs.USE_SUBCATEGORIES)

    val periodSettings: CachedFlow<PeriodSettings> = CachedFlow(combine(
        setting(Prefs.MONTH_START_DAY), setting(Prefs.WEEKEND_SHIFT), setting(Prefs.WEEK_START), ::PeriodSettings,
    )) { periodSettingsNow() }

    fun periodSettingsNow() = PeriodSettings(settingNow(Prefs.MONTH_START_DAY), settingNow(Prefs.WEEKEND_SHIFT), settingNow(Prefs.WEEK_START))

    val currency: CachedFlow<CurrencyFormat> = CachedFlow(
        combine(setting(Prefs.CURRENCY_SYMBOL), setting(Prefs.CURRENCY_DECIMALS), setting(Prefs.SYMBOL_AFTER), ::CurrencyFormat),
    ) { currencyFormatNow() }
    fun currencyFormatNow() = CurrencyFormat(settingNow(Prefs.CURRENCY_SYMBOL), settingNow(Prefs.CURRENCY_DECIMALS), settingNow(Prefs.SYMBOL_AFTER))
    fun mainDecimals() = settingNow(Prefs.CURRENCY_DECIMALS)
}
