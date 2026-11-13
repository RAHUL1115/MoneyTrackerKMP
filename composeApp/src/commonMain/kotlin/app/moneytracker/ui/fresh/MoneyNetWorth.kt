package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.Privacy
import app.moneytracker.ui.Screen
import app.moneytracker.ui.accounts.NetWorthPoint
import app.moneytracker.ui.darkTheme
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.tr

/** Tapping the card opens or closes the last six months of net worth. */
@Composable
internal fun NetWorthHero(net: Long, assets: Long, liabilities: Long, trend: NetWorthTrend) {
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxWidth().clip(F.heroShape).background(F.card).expandState(trend.open).clickable(onClick = Privacy.tap(trend.onToggle)).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Net worth · in totals"), Modifier.weight(1f), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(if (trend.open) Glyph.ChevronUp else Glyph.ChevronDown, tr("Net worth trend"), tint = F.muted, modifier = Modifier.size(18.dp))
        }
        Text(currency.format(net), color = F.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
        Gap(12.dp)
        val share = if (assets + liabilities > 0) assets.toFloat() / (assets + liabilities) else 1f
        // Assets in the accent, liabilities in a quiet grey, a small gap between them.
        Row(Modifier.fillMaxWidth().height(8.dp).clip(F.pillShape), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (share > 0f) Box(Modifier.weight(share).height(8.dp).background(F.accent))
            if (share < 1f) Box(Modifier.weight(1f - share).height(8.dp).background(liabilityTone))
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(tr("Assets"), currency.format(assets), F.accent)
            Legend(tr("Liabilities"), currency.format(liabilities), liabilityTone)
        }
        if (trend.open) {
            Gap(16.dp)
            if (trend.points.isNotEmpty()) TrendPreview(trend.points)
        }
    }
}

/** Net worth at the end of each of the last six months (main currency, accounts in totals). */
@Composable
private fun TrendPreview(points: List<NetWorthPoint>) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val change = points.last().net - points.first().net
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Last 6 months · {0} {1} since {2}", if (change >= 0) "▲" else "▼", currency.format(kotlin.math.abs(change)), monthShort(points.first().month.month)),
            Modifier.weight(1f), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(tr("Full trend ›"), Modifier.clip(F.tagShape).clickable(role = Role.Button) { nav.push(Screen.AccountsTrend) }.padding(4.dp),
            color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    Gap(8.dp)
    MoneyBars(points.map { MoneyBar(monthShort(it.month.month), it.net) }, currency.decimals, height = 52.dp)
}

@Composable
private fun Legend(label: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text("  $label ", color = F.muted, fontSize = 12.sp)
        Text(value, color = F.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = F.money, maxLines = 1)
    }
}

/** Liabilities on the net-worth bar: a quiet grey next to the accent. */
private val liabilityTone: Color get() = Color(if (darkTheme) 0xFF3D4642 else 0xFFD5DBD8)
