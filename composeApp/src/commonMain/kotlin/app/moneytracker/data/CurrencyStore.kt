package app.moneytracker.data

import app.moneytracker.Log
import app.moneytracker.domain.Currency
import app.moneytracker.db.Currency as CurrencyRow

/** The other currencies the person uses, with the rate each is converted at, and the daily online rate update. */
class CurrencyStore(private val db: Db, private val settings: SettingsStore) {
    private val q get() = db.q

    val currencies: CachedFlow<List<Currency>> = db.cached({ q.allCurrencies() }) { rows -> rows.map { it.toDomain() } }
    fun currenciesNow(): List<Currency> = currencies.now()
    fun currencyNow(code: String?): Currency? = code?.let { c -> q.allCurrencies().executeAsList().firstOrNull { it.code == c }?.toDomain() }

    fun saveCurrency(c: Currency) = q.upsertCurrency(CurrencyRow(c.code.uppercase(), c.symbol, c.decimals.toLong(), c.rate, c.sortOrder.toLong(), 0, db.now()))
    fun deleteCurrency(code: String) = q.softDeleteCurrency(db.now(), code)

    /**
     * Fetches today's rates for every other currency through [get] (URL → reply, null on failure) and saves them.
     * Entries already saved keep the rate they were entered with.
     */
    suspend fun updateRates(get: suspend (String) -> String?): RatesUpdate {
        val main = settings.settingNow(Prefs.CURRENCY_CODE).uppercase()
        val others = q.allCurrencies().executeAsList().map { it.toDomain() }.filter { it.code.uppercase() != main }
        if (others.isEmpty()) return RatesUpdate.NothingToUpdate.also { Log.d("Rates") { "no other currencies; nothing to fetch" } }
        val quote = runCatching { get(ExchangeRates.url(main, others.map { it.code }))?.let(ExchangeRates::parse) }
            .onFailure { Log.w("Rates", it) { "fetch failed" } }.getOrNull()
            ?: return RatesUpdate.Failed.also { Log.w("Rates") { "no usable reply for $main → ${others.joinToString(",") { it.code }}" } }
        val updated = others.filter { it.code.uppercase() in quote.perMain }
        db.transaction {
            updated.forEach { c -> saveCurrency(c.copy(rate = ExchangeRates.mainPerUnit(quote.perMain.getValue(c.code.uppercase())))) }
            settings.putSetting(Prefs.RATES_DATE, quote.date)
            settings.putSetting(Prefs.RATES_CHECKED, db.now())
        }
        Log.i("Rates") { "rates of ${quote.date}: updated ${updated.size}/${others.size}" }
        return RatesUpdate.Done(quote.date, updated.map { it.code }, (others - updated.toSet()).map { it.code })
    }
}
