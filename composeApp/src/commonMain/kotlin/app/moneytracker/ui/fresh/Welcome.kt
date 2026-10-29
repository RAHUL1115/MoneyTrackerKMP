package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StartCurrency(val code: String, val symbol: String, val name: String, val decimals: Int)

private val startCurrencies = listOf(
    StartCurrency("INR", "₹", "Indian rupee", 2), StartCurrency("USD", "$", "US dollar", 2), StartCurrency("EUR", "€", "Euro", 2),
    StartCurrency("GBP", "£", "British pound", 2), StartCurrency("JPY", "¥", "Japanese yen", 0),
)

/** First launch: three intro slides, then the main currency. A backup can be restored instead. */
@Composable
internal fun WelcomeScreen() {
    val repo = LocalRepo.current
    val backup = LocalBackup.current
    val scope = rememberCoroutineScope()
    val status by backup.status.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>("INR") }
    var symbol by remember { mutableStateOf("₹") }
    var decimals by remember { mutableIntStateOf(2) }
    var after by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    val slides = listOf(
        Triple("₹", tr("Track every rupee"), tr("Add income, expenses and transfers in a few taps with the built-in keypad.")),
        Triple("◔", tr("See where it goes"), tr("Daily, calendar and monthly views, insights by category, budgets and card statements.")),
        Triple("⌂", tr("Yours, offline"), tr("Everything stays on this phone. Daily backups, export to CSV or Excel, no ads.")),
    )
    val working = tr("Working…")
    val failedPrefix = tr("Failed: {0}", "")
    // A restored backup brings its own data and currency, so the welcome is done.
    LaunchedEffect(status) {
        val s = status ?: return@LaunchedEffect
        if (s != working && !s.startsWith(failedPrefix)) {
            withContext(repo.io) { repo.putSetting(Keys.ONBOARDED, "1") }
            backup.clearStatus()
        }
    }
    fun finish() = scope.launch {
        withContext(repo.io) {
            repo.putSetting(Keys.CURRENCY_SYMBOL, symbol.ifBlank { "₹" })
            repo.putSetting(Keys.CURRENCY_DECIMALS, decimals.toString())
            repo.putSetting(Keys.SYMBOL_AFTER, if (after) "1" else "0")
            code?.let { repo.putSetting(Keys.CURRENCY_CODE, it) }
            repo.putSetting(Keys.ONBOARDED, "1")
        }
    }

    val on = F.onAccent
    val soft = on.copy(alpha = 0.78f)
    Column(Modifier.fillMaxSize().background(F.accent).statusBarsPadding().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MoneyTracker", Modifier.weight(1f), color = on, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (step >= slides.size) tr("Intro") else tr("Skip"),
                Modifier.clip(RoundedCornerShape(12.dp)).background(on.copy(alpha = 0.12f))
                    .clickable { step = if (step >= slides.size) 0 else slides.size }.padding(horizontal = 12.dp, vertical = 10.dp),
                color = on, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            if (step < slides.size) {
                val (glyph, title, body) = slides[step]
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(36.dp)).background(on.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(glyph, color = on, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(20.dp))
                Text(title, color = on, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp)
                Spacer(Modifier.height(16.dp))
                Text(body, color = soft, fontSize = 16.sp, lineHeight = 24.sp)
            } else {
                Text(tr("Your currency"), color = on, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text(tr("You can add more currencies later in Settings."), color = soft, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    startCurrencies.forEach { c ->
                        val sel = code == c.code
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clip(F.buttonShape).background(if (sel) on.copy(alpha = 0.16f) else Color.Transparent)
                                .border(2.dp, if (sel) on else on.copy(alpha = 0.22f), F.buttonShape)
                                .clickable { code = c.code; symbol = c.symbol; decimals = c.decimals }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.symbol, Modifier.width(36.dp), color = on, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${tr(c.name)} (${c.code})", color = on, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                WelcomeOption(tr("Symbol")) {
                    BasicTextField(
                        symbol, { symbol = it.take(4); code = startCurrencies.firstOrNull { c -> c.symbol == symbol }?.code },
                        Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.5.dp, on.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        singleLine = true, textStyle = TextStyle(color = on, fontSize = 15.sp, fontWeight = FontWeight.Bold), cursorBrush = SolidColor(on),
                    )
                    listOf(false to tr("Before"), true to tr("After")).forEach { (v, label) -> WelcomeChoice(label, after == v) { after = v } }
                }
                WelcomeOption(tr("Decimal places")) {
                    (0..3).forEach { d -> WelcomeChoice(d.toString(), decimals == d, Modifier.width(44.dp)) { decimals = d } }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Amounts will look like {0}", CurrencyFormat(symbol.ifBlank { "₹" }, decimals, after).format(125_000L * pow10(decimals))),
                    color = soft, fontSize = 13.sp)
            }
        }
        status?.takeIf { it.startsWith(failedPrefix) || it == working }?.let {
            Text(it, Modifier.padding(bottom = 10.dp), color = on, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(slides.size + 1) { i ->
                    Box(Modifier.width(if (i == step) 24.dp else 8.dp).height(8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (i == step) on else on.copy(alpha = 0.3f)))
                }
            }
            Box(
                Modifier.height(52.dp).clip(F.buttonShape).background(on)
                    .clickable { if (step < slides.size) step++ else finish() }.padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (step < slides.size) tr("Next") else tr("Start"), color = F.accent, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        }
        Text(
            tr("Restore from a backup"),
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { askRestore = true }.padding(12.dp),
            color = soft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    if (askRestore) {
        Confirm(
            tr("Restore from a backup?"),
            tr("Pick a MoneyTracker backup (.mtbak) or a Money Manager backup (.mmbak). Its accounts, entries and settings are loaded on this phone."),
            tr("Choose file"), onDismiss = { askRestore = false }, danger = false,
        ) { backup.importFromFile() }
    }
}

private fun pow10(n: Int): Long { var r = 1L; repeat(n) { r *= 10 }; return r }

@Composable
private fun WelcomeOption(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f), color = F.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun WelcomeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val on = F.onAccent
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) on.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.5.dp, if (selected) on else on.copy(alpha = 0.22f), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = on, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}
