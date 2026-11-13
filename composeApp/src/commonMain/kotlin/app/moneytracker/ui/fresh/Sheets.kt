package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.tr

// Bottom sheets and dialogs in the new layout's look.

/** Bottom sheet with a title; the body scrolls when tall. [half] opens a long list half-way; drag up for the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, bottom: (@Composable () -> Unit)? = null, half: Boolean = false, radius: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !half),
        containerColor = F.card, shape = RoundedCornerShape(topStart = radius, topEnd = radius),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(title, color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            if (bottom != null) { Spacer(Modifier.height(12.dp)); bottom() }
        }
    }
}

/**
 * One line in a sheet: a choice (pass [selected], true or false) or an action (leave it null).
 * A choice gets a tint and a tick when selected, and TalkBack reads it as a radio button; put a sheet's choices in a
 * [SheetOptionGroup] so they are read as one group. An action is a plain button.
 */
@Composable
fun SheetOption(
    text: String,
    selected: Boolean? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val on = selected == true
    val tap = if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.selectable(on, role = Role.RadioButton, onClick = onClick)
    Row(
        modifier.fillMaxWidth().clip(F.optionShape).background(if (on) F.accentTint else Color.Transparent)
            .then(tap).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(text, color = F.ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = F.muted, fontSize = 12.sp)
        }
        // The selected state is already announced; the tick is only for the eye.
        if (on) Text("✓", Modifier.clearAndSetSemantics {}, color = F.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Groups a sheet's [SheetOption] choices for TalkBack ("1 of 4"). Spaced 4dp like the sheet's own column, so wrapping
 * the options in it inside a [Sheet] changes no pixels.
 */
@Composable
fun SheetOptionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** Ask before something that can't be undone. Opens from the bottom so both buttons sit under the thumb. */
@Composable
fun Confirm(title: String, body: String, confirm: String, onDismiss: () -> Unit, danger: Boolean = true, onConfirm: () -> Unit) {
    Sheet(title, onDismiss, radius = 28.dp, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("Cancel"), onDismiss, Modifier.weight(1f), filled = true)
            PrimaryButton(confirm, { onDismiss(); onConfirm() }, Modifier.weight(1f), danger = danger)
        }
    }) {
        Text(body, color = F.muted, fontSize = 14.sp, lineHeight = 18.sp)
    }
}

/** Empty list message inside a card. */
@Composable
fun EmptyCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    FCard {
        Text(text, color = F.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (action != null && onAction != null) { Spacer(Modifier.height(4.dp)); SecondaryButton(action, onAction) }
    }
}

/** The listening sheet in the new layout's look (canvas 7.1): rounder sheet, its own teal, "Type instead". */
@Composable
internal fun newListenLook() = app.moneytracker.ui.ListenLook(
    sheet = F.card, ink = F.ink, inkSoft = F.ink, muted = F.muted, accent = F.accent, onAccent = F.onAccent,
    line = F.line, fill = F.fill, border = F.border, danger = F.danger, income = F.income, expense = F.expense,
    radius = 28.dp, button = F.buttonShape, typeLabel = tr("Type instead"),
)
