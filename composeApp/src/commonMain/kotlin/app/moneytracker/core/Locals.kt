package app.moneytracker.core

import androidx.compose.runtime.compositionLocalOf
import app.moneytracker.data.MoneyRepository
import app.moneytracker.domain.CurrencyFormat

val LocalRepo = compositionLocalOf<MoneyRepository> { error("No repository") }
val LocalCurrency = compositionLocalOf { CurrencyFormat() }
/** Non-main currencies by ISO code, for rendering foreign amounts. */
val LocalCurrencies = compositionLocalOf<Map<String, app.moneytracker.domain.Currency>> { emptyMap() }
