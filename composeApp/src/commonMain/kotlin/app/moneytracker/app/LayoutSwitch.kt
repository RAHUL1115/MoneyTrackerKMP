package app.moneytracker.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.settings.rememberSettingsModel

/** True when this run of the app shows the new layout. Fixed until the next restart. */
val LocalNewLayout = compositionLocalOf { false }

/**
 * Confirms a layout switch. The choice is saved only when the user agrees to restart, so cancelling
 * leaves the setting (and the toggle showing it) as it was.
 */
@Composable
fun LayoutSwitchDialog(toNew: Boolean, onDismiss: () -> Unit) {
    val model = rememberSettingsModel()
    val appInfo = LocalPlatform.current.appInfo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toNew) tr("Switch to the new layout?") else tr("Switch to the classic layout?")) },
        text = {
            Text(
                tr("MoneyTracker restarts to change its layout. Your entries, accounts and settings stay the same, and you can switch back any time in Settings."),
            )
        },
        confirmButton = {
            TextButton(onClick = { model.switchLayout(toNew, appInfo::restartApp) }) { Text(tr("Restart now"), color = Accent, fontWeight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Not now")) } },
    )
}
