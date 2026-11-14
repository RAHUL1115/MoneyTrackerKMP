package app.moneytracker.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState as composeCollectAsState
import androidx.compose.runtime.remember
import app.moneytracker.data.CachedFlow
import app.moneytracker.data.Setting
import app.moneytracker.data.SettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * Collects a [CachedFlow] starting from its [CachedFlow.current] value, so the first frame shows real data instead of
 * a placeholder. Files import this next to Compose's `collectAsState`; for a CachedFlow this more specific one wins,
 * so existing `collectAsState(emptyList())` calls keep the instant first frame ([initial] is ignored).
 */
@Composable
fun <T> CachedFlow<T>.collectAsState(@Suppress("UNUSED_PARAMETER") initial: T): State<T> = collectAsState()

/** The same without a placeholder. */
@Composable
fun <T> CachedFlow<T>.collectAsState(): State<T> {
    val start = remember(this) { current }
    return (this as Flow<T>).composeCollectAsState(start)
}

/** A stored setting as Compose state, starting from [initial] (its default unless said otherwise). */
@Composable
fun <T> rememberSetting(s: Setting<T>, initial: T = s.default, settings: SettingsStore = LocalRepo.current.settingsStore): State<T> =
    remember(settings, s) { settings.setting(s) }.composeCollectAsState(initial)
