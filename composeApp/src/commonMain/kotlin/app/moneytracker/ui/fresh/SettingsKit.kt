package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Small pieces shared by the Settings, Categories, Repeats and Backup screens of the new layout.


/** Label above a field or a group of chips inside a sheet. */
@Composable
internal fun StgLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 10.dp, bottom = 6.dp), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

/** Wrapping chips, one selected; TalkBack reads them as one group of radio buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> StgChips(options: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> Pill(label(o), o == selected, { onPick(o) }, role = Role.RadioButton) }
    }
}

@Composable
internal fun StgField(
    value: String, onChange: (String) -> Unit, label: String? = null, placeholder: String? = null,
    number: Boolean = false, error: Boolean = false, singleLine: Boolean = true,
) = Column {
    if (label != null) StgLabel(label)
    // White field with a hairline, like the entry editor's; the hairline turns accent while typing and red on a mistake.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = F.controlShape
    val textStyle = LocalTextStyle.current.copy(color = F.ink)
    BasicTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, minLines = if (singleLine) 1 else 2,
        textStyle = textStyle, cursorBrush = SolidColor(F.accent), interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(F.card)
                    .border(1.dp, when { error -> F.danger; focused -> F.accent; else -> F.border }, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = F.muted)
                inner()
            }
        },
    )
}

/** Small filled text button inside a row ("Restore", "Turn on"): 32 tall, radius 10, on the page grey. */
@Composable
internal fun StgTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = F.accent) =
    Box(
        modifier.heightIn(min = 32.dp).clip(F.innerShape).background(F.page).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
