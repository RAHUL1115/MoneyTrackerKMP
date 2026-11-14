package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.collectAsState
import app.moneytracker.core.rememberSetting
import app.moneytracker.core.tr
import app.moneytracker.data.ExchangeRates
import app.moneytracker.data.Prefs
import app.moneytracker.data.RatesUpdate
import app.moneytracker.domain.Currency
import kotlinx.datetime.LocalDate

/** Other currencies: wiring for [CurrenciesContent]. */
@Composable
fun CurrenciesScreen() {
    val repo = LocalRepo.current
    val network = LocalPlatform.current.network
    val scope = rememberCoroutineScope()
    val model = remember(repo, network) { CurrenciesModel(repo.currencyStore, repo.settingsStore, network::httpGet, repo.io, scope) }
    val state by model.state.collectAsState()
    val rates = RatesSettings(
        auto = rememberSetting(Prefs.RATES_AUTO).value,
        date = rememberSetting(Prefs.RATES_DATE).value.ifEmpty { null },
        mainCode = rememberSetting(Prefs.CURRENCY_CODE).value,
    )
    CurrenciesContent(state, rates, onUpdate = model::updateRates, onAuto = model::setAutoUpdate, onSave = model::save, onDelete = model::delete)
}

/** The exchange-rate settings the screen shows: daily updates, the ECB date of the rates, and the main currency's code. */
class RatesSettings(val auto: Boolean, val date: String?, val mainCode: String)

@Composable
fun CurrenciesContent(
    state: CurrenciesState,
    rates: RatesSettings,
    onUpdate: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onSave: (Currency) -> Unit,
    onDelete: (String) -> Unit,
) {
    val nav = LocalNavigator.current
    val main = LocalCurrency.current
    var editing by remember { mutableStateOf<Currency?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Other currencies"), nav::pop)
            HorizontalDivider(color = DividerColor)
            Text(tr("Main currency: {0}. Rates are how many {0} one unit is worth; transactions keep the rate from the day they were entered.", main.symbol),
                Modifier.padding(20.dp), color = SubtleText, fontSize = 13.sp)
            if (state.currencies.isNotEmpty()) RatesCard(state, rates, onUpdate, onAuto)
            if (state.currencies.isEmpty()) EmptyState(tr("No other currencies yet. Tap + to add one, e.g. USD for a dollar account."))
            LazyColumn {
                items(state.currencies, key = { it.code }) { c ->
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = c }.padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${c.code}  ${c.symbol}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("1 ${c.code} = ${main.symbol} ${c.rate}", color = SubtleText)
                        IconButton(onClick = { onDelete(c.code) }) { Icon(Icons.Outlined.Delete, tr("Delete {0}", c.code), tint = SubtleText) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Currency("", "", 2, 1.0, state.currencies.size) }
    }
    editing?.let { c -> CurrencyEditDialog(c, onDismiss = { editing = null }) { onSave(it); editing = null } }
}

@Composable
private fun RatesCard(state: CurrenciesState, rates: RatesSettings, onUpdate: () -> Unit, onAuto: (Boolean) -> Unit) {
    HorizontalDivider(color = DividerColor)
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Exchange rates"))
            Text(
                rates.date?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { tr("ECB rates of {0}", "${it.dayOfMonth} ${monthShort(it.monthNumber)} ${it.year}") } }
                    ?: tr("Typed in by hand"),
                fontSize = 12.sp, color = SubtleText,
            )
        }
        TextButton(onClick = onUpdate, enabled = !state.updating) {
            Text(if (state.updating) tr("Updating…") else tr("Update now"), color = if (state.updating) SubtleText else Accent)
        }
    }
    HorizontalDivider(color = DividerColor)
    // On unless turned off: rates are checked once a day, and only while there are other currencies.
    ConfigToggle(tr("Update automatically"), tr("Once a day, when you open MoneyTracker"), rates.auto, onAuto)
    HorizontalDivider(color = DividerColor)
    Text(tr("Rates are the European Central Bank's, from {0}. Only currency codes (like {1} and USD) are sent, nothing about your entries or accounts.",
        ExchangeRates.HOST, rates.mainCode),
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = SubtleText, fontSize = 12.sp)
    state.lastUpdate?.let { Text(ratesMessage(it), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = SubtleText, fontSize = 13.sp) }
    Spacer(Modifier.height(12.dp))
}

/** What an exchange-rate update did, in words. */
private fun ratesMessage(r: RatesUpdate): String = when (r) {
    is RatesUpdate.Done -> listOfNotNull(
        tr("Updated {0}.", r.updated.joinToString(", ")).takeIf { r.updated.isNotEmpty() },
        tr("No ECB rate for {0}; edit it by hand.", r.missing.joinToString(", ")).takeIf { r.missing.isNotEmpty() },
    ).joinToString(" ")
    RatesUpdate.Failed -> tr("Couldn't get rates. Check your connection; ECB rates cover about 30 major currencies.")
    RatesUpdate.NothingToUpdate -> tr("Add a currency first.")
}

@Composable
private fun CurrencyEditDialog(c: Currency, onDismiss: () -> Unit, onSave: (Currency) -> Unit) {
    val main = LocalCurrency.current
    var code by remember(c) { mutableStateOf(c.code) }
    var symbol by remember(c) { mutableStateOf(c.symbol) }
    var decimals by remember(c) { mutableStateOf(c.decimals.toString()) }
    var rate by remember(c) { mutableStateOf(if (c.code.isEmpty()) "" else c.rate.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c.code.isEmpty()) tr("Add currency") else tr("Edit {0}", c.code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it.uppercase().take(3) }, label = { Text(tr("ISO code (e.g. USD)")) }, singleLine = true, enabled = c.code.isEmpty())
                OutlinedTextField(symbol, { symbol = it.take(4) }, label = { Text(tr("Symbol")) }, singleLine = true)
                OutlinedTextField(decimals, { decimals = it.filter(Char::isDigit).take(1) }, label = { Text(tr("Decimal places")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(rate, { rate = it }, label = { Text(tr("1 unit = how many {0}", main.symbol)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(enabled = currencyInputValid(code, symbol, decimals, rate), onClick = {
                onSave(Currency(code.trim(), symbol.trim(), decimals.toInt(), rate.toDouble(), c.sortOrder))
            }) { Text(tr("Save"), color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
