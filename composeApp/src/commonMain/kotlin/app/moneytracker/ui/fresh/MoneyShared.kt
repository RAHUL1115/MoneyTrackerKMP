package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.AccountLine
import app.moneytracker.domain.AccountType
import app.moneytracker.domain.Fx
import app.moneytracker.domain.NetWorth
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.summarize
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// Pieces shared by the Insights, Budget and Money screens of the new layout.

/** Accounts + balances + card statements, recomputed whenever balances change (same cache as the classic Accounts tab). */
@Composable
internal fun rememberMoneySummary(): Pair<List<AccountLine>, NetWorth>? {
    val repo = LocalRepo.current
    val groups by repo.groups.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val balanceFlow = remember(repo) { repo.balances }
    val balances by balanceFlow.collectAsState(emptyMap())
    val currencies by repo.currencies.collectAsState(emptyList())
    val mainDecimals = LocalCurrency.current.decimals
    val tagged by repo.txnTagNames.collectAsState(emptyMap())
    @Suppress("UNCHECKED_CAST")
    val last = repo.screenCache["accountSummary"] as Pair<List<AccountLine>, NetWorth>?
    val summary by produceState(last, groups, accounts, balances, currencies, tagged) {
        value = withContext(repo.io) {
            val prepaid = repo.txnIdsWithTag(TAG_PREPAYMENT)
            summarize(
                groups, accounts, balances,
                cardStatusOf = { a -> cardStatus(repo.accountTxns(a.id), a.cardSettlementDay ?: 1, repo.today(), prepaid) },
                toMain = { a, v -> Fx.toMain(v, currencies.firstOrNull { it.code == a.currency }, mainDecimals) },
            )
        }.also { repo.screenCache["accountSummary"] = it }
    }
    return summary
}

internal val AccountType.moneyLabel: String get() = when (this) {
    AccountType.CREDIT_CARD -> tr("Credit card")
    AccountType.DEBIT_CARD -> tr("Debit card")
    AccountType.OVERDRAFT -> tr("Overdraft")
    AccountType.PREPAID -> tr("Top-up / prepaid")
    else -> tr(name.lowercase().replaceFirstChar { it.uppercase() })
}

/** Compact label for bar tops: thousands as k, millions as M. */
internal fun compactAmount(minor: Long, decimals: Int): String {
    val major = minor / pow10(decimals)
    val a = abs(major)
    val sign = if (major < 0) "−" else ""
    return when {
        a >= 1_000_000 -> sign + ((a / 100_000) / 10.0).toString().removeSuffix(".0") + "M"
        a >= 1_000 -> sign + (a / 1000.0).roundToInt() + "k"
        a == 0L -> ""
        else -> sign + a
    }
}

private fun pow10(n: Int): Long { var r = 1L; repeat(n) { r *= 10 }; return r }

/** One bar of [MoneyBars]. */
internal class MoneyBar(val label: String, val value: Long)

/**
 * Small column chart with the value over each bar; the last (or [highlight]) bar is solid, the rest tinted.
 * Negative values (net) use the expense colour. [onTap] makes the bars tappable.
 */
@Composable
internal fun MoneyBars(
    bars: List<MoneyBar>, decimals: Int, modifier: Modifier = Modifier, highlight: Int = bars.lastIndex,
    height: Dp = 64.dp, color: Color = F.accent, onDark: Boolean = false, signed: Boolean = false, onTap: ((Int) -> Unit)? = null,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { abs(it.value) }.coerceAtLeast(1)
    val ink = if (onDark) F.onAccent else F.ink
    val soft = if (onDark) F.onAccent.copy(alpha = 0.75f) else F.muted
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEachIndexed { i, b ->
            val on = i == highlight
            val base = if (signed && b.value < 0) F.expense else if (signed) F.income else color
            val fill = when {
                onDark -> if (on) F.onAccent else F.onAccent.copy(alpha = 0.3f)
                on -> base
                else -> base.copy(alpha = 0.35f)
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(compactAmount(b.value, decimals), color = if (on) ink else soft, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val h = (abs(b.value).toFloat() / max * height.value).coerceAtLeast(4f)
                Box(Modifier.padding(top = 2.dp).fillMaxWidth(0.7f).height(h.dp).clip(RoundedCornerShape(6.dp)).background(fill))
                Text(b.label, Modifier.padding(top = 4.dp), color = if (on) ink else soft, fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center)
            }
        }
    }
}

/** "▲ 12% vs Sep" style badge; green when the change is good. */
@Composable
internal fun ChangeBadge(current: Long, previous: Long, upIsGood: Boolean, against: String, signedValues: Boolean = false) {
    val usable = if (signedValues) previous != 0L else previous > 0L
    val (text, good) = if (!usable) "— " + tr("vs {0}", against) to null else {
        val pct = ((current - previous) * 100.0 / abs(previous)).roundToInt()
        "${if (pct < 0) "▼" else if (pct > 0) "▲" else "="} ${abs(pct)}% " + tr("vs {0}", against) to (if (pct == 0) null else (pct > 0) == upIsGood)
    }
    val fg = when (good) { null -> F.muted; true -> F.income; false -> F.expense }
    Text(text, Modifier.clip(RoundedCornerShape(999.dp)).background(if (good == null) F.fill else fg.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/** Labelled text box in the new layout's style. */
@Composable
internal fun MoneyTextField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
) {
    Column(modifier) {
        Text(label.uppercase(), Modifier.padding(start = 4.dp, bottom = 6.dp), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value, onChange, singleLine = singleLine, minLines = if (singleLine) 1 else 2,
            textStyle = TextStyle(color = F.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(F.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(F.fill).padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = F.muted, fontSize = 16.sp)
                    inner()
                }
            },
        )
    }
}

/** Row with a title, hint and a switch. */
@Composable
internal fun MoneySwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, color = F.muted, fontSize = 12.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = F.onAccent, checkedTrackColor = F.accent, checkedBorderColor = F.accent,
                uncheckedThumbColor = F.muted, uncheckedTrackColor = F.fill, uncheckedBorderColor = F.border,
            ),
        )
    }
}

/** ‹ label › row for stepping through periods; tapping the label calls [onLabel]. */
@Composable
internal fun PeriodStepper(label: String, onPrev: () -> Unit, onNext: () -> Unit, onLabel: (() -> Unit)? = null, sub: String? = null) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous"), onPrev)
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (onLabel != null) Modifier.clickable(onClick = onLabel) else Modifier).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (sub != null) Text(sub, color = F.muted, fontSize = 11.sp, maxLines = 1)
        }
        StepButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next"), onNext)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(F.card).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = F.ink, modifier = Modifier.size(22.dp))
    }

/** Small selectable chip (dark when on), for in-sheet choices. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) F.ink else F.fill).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        color = if (selected) F.page else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/** Circular-ish percent badge (used for budget use). */
@Composable
internal fun PercentRing(percent: Int, over: Boolean, size: Dp = 56.dp) {
    val color = if (over) F.warn else F.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.12f
            val d = this.size.minDimension - stroke
            val tl = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            val sz = androidx.compose.ui.geometry.Size(d, d)
            drawArc(F.fill, 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(color, -90f, 360f * (percent.coerceIn(0, 100) / 100f), false, tl, sz,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("$percent%", color = F.ink, fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** Runs a repository write off the main thread, independent of any screen (so an Undo still works after the screen closed). */
internal fun app.moneytracker.data.MoneyRepository.moneyIo(block: suspend () -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io).launch { block() }
}

/** Back on the main thread (to show a toast after a background write). */
internal suspend fun onMain(block: () -> Unit) = withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
