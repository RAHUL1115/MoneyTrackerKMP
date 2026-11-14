package app.moneytracker.core

import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.moneytracker.app.LocalCurrencies
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalRepo
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.designsystem.classic.Accent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The privacy eye: while [hidden], every amount in the app shows as ₹ •••• (see CurrencyFormat.masked).
 * The eye turns it on or off and the choice is saved; tapping a total only peeks for a few seconds.
 * Entry editors always show real numbers ([Unmasked]); widgets, exports and backups are never masked.
 */
object Privacy {
    var hidden by mutableStateOf(false)
        private set
    var peeking by mutableStateOf(false)
        private set
    /** True while amounts should be masked right now. */
    val masked get() = hidden && !peeking

    private var peekJob: Job? = null

    /** Reads the saved choice (call once the database is open). */
    fun load(repo: MoneyRepository) { hidden = repo.settingsStore.settingNow(Prefs.HIDE_AMOUNTS) }

    /** The eye: hide or show amounts, and remember it. */
    fun toggle(repo: MoneyRepository) {
        hidden = !hidden
        peekJob?.cancel(); peeking = false
        outlivingScope().launch(repo.io) { repo.settingsStore.putSetting(Prefs.HIDE_AMOUNTS, hidden) }
    }

    /** Shows the amounts for a few seconds without turning privacy off. */
    fun peek() {
        if (!hidden) return
        peeking = true
        peekJob?.cancel()
        peekJob = outlivingScope().launch { delay(5_000); peeking = false }
    }

    /** A tap on a total: peeks while amounts are hidden, otherwise does [action]. */
    fun tap(action: () -> Unit): () -> Unit = { if (masked) peek() else action() }
}

/** The classic layout's eye: a top-bar button that hides or shows all amounts, accent while they're hidden. */
@Composable
fun PrivacyEyeButton() {
    val repo = LocalRepo.current
    val on = Privacy.hidden
    androidx.compose.material3.IconButton(onClick = { Privacy.toggle(repo) }) {
        androidx.compose.material3.Icon(
            if (on) androidx.compose.material.icons.Icons.Outlined.VisibilityOff else androidx.compose.material.icons.Icons.Outlined.Visibility,
            if (on) tr("Show amounts") else tr("Hide amounts"),
            tint = if (on) Accent else androidx.compose.material3.LocalContentColor.current,
        )
    }
}

/** Shows [content] with real amounts even while privacy is on (adding and editing an entry). */
@Composable
fun Unmasked(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCurrency provides LocalCurrency.current.copy(masked = false),
        LocalCurrencies provides LocalCurrencies.current.mapValues { it.value.copy(masked = false) },
        content = content,
    )
}
