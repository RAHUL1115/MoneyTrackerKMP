package app.moneytracker.ui.fresh

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Small pieces shared by the Settings, Categories, Repeats and Backup screens of the new layout.

@Composable
internal fun StgSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = F.accent, checkedThumbColor = F.onAccent, checkedBorderColor = F.accent,
        uncheckedTrackColor = F.fill, uncheckedBorderColor = F.border, uncheckedThumbColor = F.muted,
    ),
)

/** Label above a field or a group of chips inside a sheet. */
@Composable
internal fun StgLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 10.dp, bottom = 6.dp), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)

/** Wrapping chips, one selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> StgChips(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Pill(label(o), o == selected, { onPick(o) }) }
    }
}

@Composable
internal fun StgField(
    value: String, onChange: (String) -> Unit, label: String? = null, placeholder: String? = null,
    number: Boolean = false, error: Boolean = false, singleLine: Boolean = true,
) = Column {
    if (label != null) StgLabel(label)
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, isError = error,
        placeholder = placeholder?.let { { Text(it, color = F.muted) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        shape = F.iconShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = F.accent, unfocusedBorderColor = F.border, cursorColor = F.accent,
            focusedTextColor = F.ink, unfocusedTextColor = F.ink, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            errorBorderColor = F.danger,
        ),
    )
}

/** Small outlined text button inside a row ("Restore", "Turn on"). */
@Composable
internal fun StgTextButton(text: String, onClick: () -> Unit, color: Color = F.accent) =
    Text(
        text,
        Modifier.clip(F.iconShape).border(1.dp, F.border, F.iconShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
    )
