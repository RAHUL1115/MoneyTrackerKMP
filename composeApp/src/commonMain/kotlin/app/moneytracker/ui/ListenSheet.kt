package app.moneytracker.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ai.SmartParser
import app.moneytracker.data.DraftType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ListenState { LISTENING, DONE, ERROR }

/**
 * Classic layout: MoneyTracker's own listening sheet (canvas C7.1). Words appear as they're heard and the fields
 * Smart entry understood fill in underneath; "done" opens the Add screen with everything filled in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenSheet(onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    val categories by repo.categories.collectAsState(emptyList())
    val accounts by repo.accounts.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val learned: suspend (List<String>) -> app.moneytracker.data.MoneyRepository.Suggestion? = { words -> withContext(repo.io) { repo.suggest(words) } }

    var heard by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(ListenState.LISTENING) }
    var error by remember { mutableStateOf<SpeechError?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    fun setLevel(v: Float) { if (state == ListenState.LISTENING) level = v }
    fun fail(e: SpeechError) { state = ListenState.ERROR; error = e }
    var guess by remember { mutableStateOf<SmartGuess?>(null) }
    var parser by remember { mutableStateOf<SmartParser?>(null) }
    var session by remember { mutableStateOf<SpeechSession?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var parseJob by remember { mutableStateOf<Job?>(null) }

    fun reparse(text: String, then: (SmartGuess?) -> Unit = {}) {
        val p = parser
        parseJob?.cancel()
        if (p == null || text.isBlank()) { then(guess); return }
        parseJob = scope.launch {
            val g = SmartEntry.guess(p, text, repo.today(), currency, categories, accounts, learned)
            guess = g; then(g)
        }
    }

    fun openEditor(text: String) {
        session?.cancel()
        scope.launch {
            val p = parser ?: SmartEntry.parser(platform)
            val g = if (p != null && text.isNotBlank()) SmartEntry.guess(p, text, repo.today(), currency, categories, accounts, learned) else null
            onDismiss()
            nav.push(Screen.EditTxn(preset = g?.draft))
        }
    }

    LaunchedEffect(Unit) { parser = SmartEntry.parser(platform); reparse(heard) }

    DisposableEffect(attempt) {
        state = ListenState.LISTENING; error = null; level = 0f
        val s = platform.startSpeech(object : SpeechListener {
            override fun onLevel(level: Float) { setLevel(level) }
            override fun onPartial(text: String) { heard = text; reparse(text) }
            override fun onFinal(text: String) {
                heard = text.ifBlank { heard }
                if (heard.isBlank()) { fail(SpeechError.NO_MATCH); return }
                state = ListenState.DONE
                openEditor(heard)
            }
            override fun onError(error: SpeechError) = fail(error)
        })
        if (s == null) { state = ListenState.ERROR; error = SpeechError.OTHER }
        session = s
        onDispose { s?.cancel() }
    }

    ModalBottomSheet(
        onDismissRequest = { session?.cancel(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceColor,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            // Status line
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    ListenState.ERROR -> Text(errorTitle(error), color = DangerColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    else -> {
                        SoundBars(if (state == ListenState.LISTENING) level else 0f)
                        Spacer(Modifier.width(10.dp))
                        Text(tr(if (state == ListenState.DONE) "Got it" else "Listening…"), color = Accent, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }

            // What was heard
            Box(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                if (heard.isBlank()) {
                    Text(
                        if (state == ListenState.ERROR) errorHint(error) else tr("Just say it, e.g. “1,200 for new shoes on my credit card” or “Got my salary, 50,000”."),
                        color = SubtleText, fontSize = 16.sp, lineHeight = 22.sp,
                    )
                } else Text(heard, color = Ink, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
            }

            // Understood so far
            Text(tr("Understood so far"), Modifier.padding(top = 10.dp), color = SubtleText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            val g = guess.takeIf { heard.isNotBlank() }
            val d = g?.draft
            UnderstoodRow(tr("Amount"), d?.amount?.takeIf { it > 0 }?.let { currency.format(it) }, d?.type?.let { typeColor(it) })
            UnderstoodRow(tr("Category"), g?.categoryName?.let { if (g.categoryLearned) it + " · " + tr("from your entries") else it })
            UnderstoodRow(tr("Note"), d?.note?.takeIf { it.isNotBlank() })
            UnderstoodRow(tr("Account"), g?.accountName)
            UnderstoodRow(tr("Date"), g?.let { dayLabel(it.daysAgo) })

            // Actions
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    onClick = { session?.cancel(); onDismiss() }, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp), color = FillColor,
                ) { Box(contentAlignment = Alignment.Center) { Text(tr("Cancel"), color = InkSoft, fontSize = 16.sp) } }

                MainButton(state) {
                    when (state) {
                        ListenState.LISTENING -> session?.stop()
                        ListenState.ERROR -> { heard = ""; guess = null; attempt++ }
                        ListenState.DONE -> {}
                    }
                }

                Surface(
                    onClick = { openEditor(heard) }, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp), color = SurfaceColor, border = BorderStroke(1.dp, BorderColor),
                ) { Box(contentAlignment = Alignment.Center) { Text(tr("Type"), color = Accent, fontSize = 16.sp) } }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = SubtleText)
                Spacer(Modifier.width(6.dp))
                Text(tr("Understood on this phone. Nothing leaves it."), color = SubtleText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UnderstoodRow(label: String, value: String?, color: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), color = SubtleText, fontSize = 14.sp)
        Text(value ?: "—", color = if (value == null) SubtleText else color ?: Ink, fontSize = 15.sp, maxLines = 1)
    }
    HorizontalDivider(color = DividerColor)
}

/** The round button: stop while listening (pulsing), try again after an error. */
@Composable
private fun MainButton(state: ListenState, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition()
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart))
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (state == ListenState.LISTENING) {
            Box(Modifier.size(72.dp).graphicsLayer { scaleX = 1f + 0.8f * t; scaleY = 1f + 0.8f * t; alpha = 0.45f * (1f - t) }
                .background(Accent, CircleShape))
        }
        Surface(onClick = onClick, enabled = state != ListenState.DONE, modifier = Modifier.size(72.dp), shape = CircleShape, color = Accent) {
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    ListenState.ERROR -> Icon(Icons.Filled.Mic, tr("Try again"), Modifier.size(28.dp), tint = Color.White)
                    else -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                }
            }
        }
    }
}

/** Five bars that rise with the voice; they keep a gentle idle motion so it's clear the sheet is live. */
@Composable
private fun SoundBars(level: Float) {
    val smooth by animateFloatAsState(level, tween(120))
    val wave = rememberInfiniteTransition()
    val phase by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val shift = (phase + i * 0.2f) % 1f
            val h = (0.45f + 0.25f * shift + 0.3f * smooth * (0.6f + 0.4f * shift)).coerceIn(0.3f, 1f)
            Box(Modifier.width(3.dp).height(18.dp).scale(1f, h).clip(RoundedCornerShape(2.dp)).background(Accent))
        }
    }
}

@Composable private fun typeColor(t: DraftType): Color = when (t) {
    DraftType.INCOME -> IncomeColor
    DraftType.TRANSFER -> InkSoft
    else -> ExpenseColor
}

private fun dayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> tr("Today")
    1 -> tr("Yesterday")
    else -> tr("%d days ago").replace("%d", daysAgo.toString())
}

private fun errorTitle(e: SpeechError?): String = when (e) {
    SpeechError.NO_PERMISSION -> tr("Microphone is off")
    SpeechError.NO_MATCH -> tr("Didn’t catch that")
    else -> tr("Can’t listen right now")
}

private fun errorHint(e: SpeechError?): String = when (e) {
    SpeechError.NO_PERMISSION -> tr("Allow the microphone for MoneyTracker, or tap Type.")
    SpeechError.NO_MATCH -> tr("Tap the mic and try again, or tap Type.")
    SpeechError.NETWORK -> tr("Speech recognition on this phone needs a download or a connection. Tap Type instead.")
    SpeechError.BUSY -> tr("The phone’s speech service is busy. Try again in a moment.")
    else -> tr("This phone has no speech recognition. Tap Type instead.")
}
