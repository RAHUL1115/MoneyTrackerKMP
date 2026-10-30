package app.moneytracker.ui.fresh

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.ui.Accent
import app.moneytracker.ui.LocalPlatform
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.tr
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True when this run of the app shows the new layout. Fixed until the next restart. */
val LocalNewLayout = compositionLocalOf { false }

/**
 * Confirms a layout switch. The choice is saved only when the user agrees to restart, so cancelling
 * leaves the setting (and the toggle showing it) as it was.
 */
@Composable
fun LayoutSwitchDialog(toNew: Boolean, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toNew) tr("Try the new layout?") else tr("Switch to the classic layout?")) },
        text = {
            Text(
                (if (toNew) tr("The new layout is experimental: some screens may still change or have rough edges. ") else "") +
                    tr("MoneyTracker restarts to change its layout. Your entries, accounts and settings stay the same, and you can switch back any time in Settings."),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    withContext(repo.io) { repo.putSetting(Keys.UI_LAYOUT, if (toNew) "NEW" else "CLASSIC") }
                    platform.restartApp()
                }
            }) { Text(tr("Restart now"), color = Accent, fontWeight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}
