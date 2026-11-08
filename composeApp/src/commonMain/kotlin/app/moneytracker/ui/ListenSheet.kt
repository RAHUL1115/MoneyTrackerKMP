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
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
 * MoneyTracker's own listening sheet (canvas C7.1 classic, 7.1 new layout). Words appear as they're heard and the fields
 * Smart entry understood fill in underneath. When the person is done (or taps Type), [onDone] gets what was understood
 * (null when nothing was heard); the sheet has already closed. Draw it in either layout's look with [look].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenSheet(onDismiss: () -> Unit, look: ListenLook = ListenLook.classic(), startTyping: Boolean = false, onDone: (SmartGuess?) -> Unit) {
    CompositionLocalProvider(LocalListenLook provides look) { ListenSheetBody(onDismiss, startTyping, onDone) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenSheetBody(onDismiss: () -> Unit, startTyping: Boolean, onDone: (SmartGuess?) -> Unit) {
    val platform = LocalPlatform.current
    val repo = LocalRepo.current
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
    // Type mode: the same sheet with a text box; write it the way you'd say it and the fields fill in as you type.
    var typing by remember { mutableStateOf(startTyping) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    val typeFocus = remember { FocusRequester() }

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
            onDone(g)
        }
    }

    LaunchedEffect(Unit) { parser = SmartEntry.parser(platform); reparse(heard) }

    DisposableEffect(attempt, typing) {
        state = ListenState.LISTENING; error = null; level = 0f
        if (typing) return@DisposableEffect onDispose {}
        val s = platform.startSpeech(object : SpeechListener {
            override fun onLevel(level: Float) { setLevel(level) }
            override fun onPartial(text: String) { if (!typing) { heard = text; reparse(text) } }
            override fun onFinal(text: String) {
                if (typing) return
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

    fun startTyping() {
        session?.cancel()
        typed = TextFieldValue(heard, TextRange(heard.length))
        typing = true
    }
    fun startSpeaking() { typing = false; heard = typed.text; attempt++ }
    LaunchedEffect(typing) { if (typing) runCatching { typeFocus.requestFocus() } }

    ModalBottomSheet(
        onDismissRequest = { session?.cancel(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = L.sheet,
        shape = RoundedCornerShape(topStart = L.radius, topEnd = L.radius),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            // Status line
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (typing) {
                    Icon(Icons.Outlined.Keyboard, null, Modifier.size(20.dp), tint = L.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Type it like you’d say it"), color = L.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                } else when (state) {
                    ListenState.ERROR -> Text(errorTitle(error), color = L.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    else -> {
                        SoundBars(if (state == ListenState.LISTENING) level else 0f)
                        Spacer(Modifier.width(10.dp))
                        Text(tr(if (state == ListenState.DONE) "Got it" else "Listening…"), color = L.accent, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }

            // What was heard, or the text box in type mode
            if (typing) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 62.dp).border(2.dp, L.accent, L.button)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (typed.text.isEmpty()) Text(tr("e.g. 1,200 for new shoes on my credit card"), color = L.muted, fontSize = 18.sp, lineHeight = 24.sp)
                    BasicTextField(
                        typed, { v -> typed = v; heard = v.text; reparse(v.text) },
                        Modifier.fillMaxWidth().focusRequester(typeFocus),
                        textStyle = TextStyle(color = L.ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(L.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (typed.text.isNotBlank()) openEditor(typed.text) }),
                    )
                }
            } else Box(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                if (heard.isBlank()) {
                    Text(
                        if (state == ListenState.ERROR) errorHint(error) else tr("Just say it, e.g. “1,200 for new shoes on my credit card” or “Got my salary, 50,000”."),
                        color = L.muted, fontSize = 16.sp, lineHeight = 22.sp,
                    )
                } else Text(heard, color = L.ink, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
            }

            // Understood so far
            Text(tr("Understood so far"), Modifier.padding(top = 10.dp), color = L.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                    shape = L.button, color = L.fill,
                ) { Box(contentAlignment = Alignment.Center) { Text(tr("Cancel"), color = L.inkSoft, fontSize = 16.sp) } }

                if (typing) {
                    // Back to speaking.
                    Surface(onClick = { startSpeaking() }, modifier = Modifier.size(56.dp), shape = CircleShape, color = L.fill) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, tr("Speak instead"), Modifier.size(24.dp), tint = L.accent) }
                    }
                    val ready = typed.text.isNotBlank()
                    Surface(
                        onClick = { if (ready) openEditor(typed.text) }, enabled = ready, modifier = Modifier.weight(1f).height(50.dp),
                        shape = L.button, color = if (ready) L.accent else L.fill,
                    ) { Box(contentAlignment = Alignment.Center) { Text(tr("Done"), color = if (ready) L.onAccent else L.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) } }
                } else {
                    MainButton(state) {
                        when (state) {
                            ListenState.LISTENING -> session?.stop()
                            ListenState.ERROR -> { heard = ""; guess = null; attempt++ }
                            ListenState.DONE -> {}
                        }
                    }

                    Surface(
                        onClick = { startTyping() }, modifier = Modifier.weight(1f).height(50.dp),
                        shape = L.button, color = L.sheet, border = BorderStroke(1.dp, L.border),
                    ) { Box(contentAlignment = Alignment.Center) { Text(L.typeLabel, color = L.accent, fontSize = 16.sp) } }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = L.muted)
                Spacer(Modifier.width(6.dp))
                Text(tr("Understood on this phone. Nothing leaves it."), color = L.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UnderstoodRow(label: String, value: String?, color: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), color = L.muted, fontSize = 14.sp)
        Text(value ?: "—", color = if (value == null) L.muted else color ?: L.ink, fontSize = 15.sp, maxLines = 1)
    }
    HorizontalDivider(color = L.line)
}

/** The round button: stop while listening (pulsing), try again after an error. */
@Composable
private fun MainButton(state: ListenState, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition()
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart))
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (state == ListenState.LISTENING) {
            Box(Modifier.size(72.dp).graphicsLayer { scaleX = 1f + 0.8f * t; scaleY = 1f + 0.8f * t; alpha = 0.45f * (1f - t) }
                .background(L.accent, CircleShape))
        }
        Surface(onClick = onClick, enabled = state != ListenState.DONE, modifier = Modifier.size(72.dp), shape = CircleShape, color = L.accent) {
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    ListenState.ERROR -> Icon(Icons.Filled.Mic, tr("Try again"), Modifier.size(28.dp), tint = L.onAccent)
                    else -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(L.onAccent))
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
            Box(Modifier.width(3.dp).height(18.dp).scale(1f, h).clip(RoundedCornerShape(2.dp)).background(L.accent))
        }
    }
}

@Composable private fun typeColor(t: DraftType): Color = when (t) {
    DraftType.INCOME -> L.income
    DraftType.TRANSFER -> L.inkSoft
    else -> L.expense
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

/** Colours and shapes of the listening sheet, so each layout draws it in its own look. */
data class ListenLook(
    val sheet: Color, val ink: Color, val inkSoft: Color, val muted: Color, val accent: Color, val onAccent: Color,
    val line: Color, val fill: Color, val border: Color, val danger: Color, val income: Color, val expense: Color,
    val radius: Dp, val button: Shape, val typeLabel: String,
) {
    companion object {
        @Composable fun classic() = ListenLook(
            SurfaceColor, Ink, InkSoft, SubtleText, Accent, Color.White, DividerColor, FillColor, BorderColor, DangerColor,
            IncomeColor, ExpenseColor, 20.dp, RoundedCornerShape(12.dp), tr("Type"),
        )
    }
}

private val LocalListenLook = staticCompositionLocalOf<ListenLook> { error("ListenLook not provided") }
private val L: ListenLook @Composable @ReadOnlyComposable get() = LocalListenLook.current
