package app.moneytracker.designsystem.classic

import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.Privacy
import app.moneytracker.core.tr

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
