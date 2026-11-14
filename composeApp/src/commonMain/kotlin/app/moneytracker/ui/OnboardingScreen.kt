package app.moneytracker.ui

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BorderColor
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor

/** The first-run choices: currency symbol, decimals and layout. */
private class OnboardingForm {
    var page by mutableStateOf(0)
    var symbol by mutableStateOf("₹")
    var decimals by mutableStateOf(2)
    var newLayout by mutableStateOf(true)
}

/** Welcome pages, then currency, then layout; Start saves the choices. */
@Composable
fun OnboardingScreen() {
    val model = rememberSettingsModel()
    val appInfo = LocalPlatform.current.appInfo
    val f = remember { OnboardingForm() }
    val pages = listOf(
        tr("Track every rupee") to tr("Add income, expenses and transfers in a few taps with the built-in keypad."),
        tr("See where it goes") to tr("Daily, calendar and monthly views, stats by category, budgets and card statements."),
        tr("Yours, offline") to tr("Everything stays on this phone. Daily backups, export to CSV or Excel, no ads."),
    )
    Column(Modifier.fillMaxSize().background(SurfaceColor).statusBarsPadding().padding(28.dp)) {
        Spacer(Modifier.height(48.dp))
        when {
            f.page < pages.size -> {
                val (title, body) = pages[f.page]
                Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(body, Modifier.padding(top = 16.dp), fontSize = 17.sp, color = InkSoft)
            }
            f.page == pages.size + 1 -> LayoutPage(f)
            else -> CurrencyPage(f)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size + 2) { i -> Box(Modifier.padding(4.dp).size(8.dp).background(if (i == f.page) Accent else DividerColor, CircleShape)) }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp).background(Accent, NextShape).clip(NextShape).clickable(role = Role.Button) {
                // The layout is chosen when the app starts, so the new one needs a quick restart.
                if (f.page <= pages.size) f.page++ else model.finishOnboarding(f.symbol, f.decimals, f.newLayout, appInfo::restartApp)
            },
            contentAlignment = Alignment.Center,
        ) { Text(if (f.page <= pages.size) tr("Next") else tr("Start"), color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center) }
        if (f.page < pages.size) {
            Text(tr("Skip"), Modifier.fillMaxWidth().clickable(role = Role.Button) { f.page = pages.size }.padding(14.dp), textAlign = TextAlign.Center, color = SubtleText)
        }
    }
}

@Composable
private fun LayoutPage(f: OnboardingForm) {
    Text(tr("Choose your layout"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(tr("You can switch any time in Settings › Look › Layout."), Modifier.padding(top = 12.dp), color = SubtleText)
    Column(Modifier.padding(top = 24.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LayoutChoice(tr("New (recommended)"), tr("Home with today's spending and Smart add, then Activity, Insights and Accounts. Clean, simple screens."), f.newLayout) { f.newLayout = true }
        LayoutChoice(tr("Classic"), tr("The familiar tabs: Trans., Stats, Accounts and More, with daily, calendar and monthly views."), !f.newLayout) { f.newLayout = false }
    }
}

@Composable
private fun CurrencyPage(f: OnboardingForm) {
    Text(tr("Your currency"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(tr("You can add more currencies later in Configuration."), Modifier.padding(top = 12.dp), color = SubtleText)
    Row(Modifier.padding(top = 24.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("₹" to 2, "$" to 2, "€" to 2, "£" to 2, "¥" to 0).forEach { (s, d) ->
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier.size(52.dp).border(1.5.dp, if (f.symbol == s) Accent else BorderColor, shape).clip(shape)
                    .selectable(f.symbol == s, role = Role.RadioButton) { f.symbol = s; f.decimals = d },
                contentAlignment = Alignment.Center,
            ) { Text(s, fontSize = 22.sp, color = if (f.symbol == s) Accent else InkSoft) }
        }
    }
    OutlinedTextField(f.symbol, { f.symbol = it.take(4) }, Modifier.padding(top = 20.dp), label = { Text(tr("Symbol")) }, singleLine = true)
    Row(Modifier.padding(top = 12.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Decimal places"), Modifier.weight(1f))
        for (d in 0..3) {
            val shape = RoundedCornerShape(6.dp)
            Text(
                d.toString(),
                Modifier.padding(start = 8.dp).border(1.dp, if (f.decimals == d) Accent else BorderColor, shape).clip(shape)
                    .selectable(f.decimals == d, role = Role.RadioButton) { f.decimals = d }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (f.decimals == d) Accent else InkSoft,
            )
        }
    }
}

/** One layout to pick on the last welcome page. */
@Composable
private fun LayoutChoice(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) Accent else BorderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Accent else Ink)
            // The selected state is already announced; the tick is only for the eye.
            if (selected) Text("✓", Modifier.clearAndSetSemantics {}, color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(body, Modifier.padding(top = 6.dp), fontSize = 14.sp, color = InkSoft)
    }
}

/** The Next / Start button's corners. */
private val NextShape = RoundedCornerShape(6.dp)
