package app.moneytracker.settings

import app.moneytracker.data.CurrencyStore
import app.moneytracker.data.Prefs
import app.moneytracker.data.RatesUpdate
import app.moneytracker.data.SettingsStore
import app.moneytracker.domain.Currency
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The other currencies, and how the last exchange-rate update went ([lastUpdate] is null until one ran). */
data class CurrenciesState(
    val currencies: List<Currency>,
    val updating: Boolean = false,
    val lastUpdate: RatesUpdate? = null,
)

/** The Other currencies screen: add, edit and delete currencies, and fetch ECB rates for them. */
class CurrenciesModel(
    private val store: CurrencyStore,
    private val settings: SettingsStore,
    /** An HTTPS GET, null on any failure (the platform's network). */
    private val httpGet: suspend (String) -> String?,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val rates = MutableStateFlow(CurrenciesState(emptyList()))

    val state: StateFlow<CurrenciesState> = combine(store.currencies, rates) { list, r -> r.copy(currencies = list) }
        .stateIn(scope, SharingStarted.Eagerly, CurrenciesState(store.currencies.current))

    fun updateRates() {
        rates.update { it.copy(updating = true, lastUpdate = null) }
        scope.launch {
            val result = withContext(io) { store.updateRates(httpGet) }
            rates.update { it.copy(updating = false, lastUpdate = result) }
        }
    }

    /** Daily updates on or off; turning them on also updates now. */
    fun setAutoUpdate(on: Boolean) {
        scope.launch(io) { settings.putSetting(Prefs.RATES_AUTO, on) }
        if (on) updateRates()
    }

    fun save(c: Currency) {
        scope.launch { withContext(io) { store.saveCurrency(c) } }
    }

    fun delete(code: String) {
        scope.launch { withContext(io) { store.deleteCurrency(code) } }
    }
}

/** A currency as typed: a 3-letter code, a symbol, 0–3 decimals and a rate above zero. */
fun currencyInputValid(code: String, symbol: String, decimals: String, rate: String): Boolean =
    code.trim().length == 3 && symbol.isNotBlank() && decimals.toIntOrNull() in 0..3 && (rate.toDoubleOrNull() ?: 0.0) > 0
