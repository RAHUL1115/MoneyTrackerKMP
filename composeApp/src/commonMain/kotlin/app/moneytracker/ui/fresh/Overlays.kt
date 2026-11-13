package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.tr
import kotlinx.coroutines.delay

// Things drawn over every screen of the new layout: the toast and the keyboard-over-content switch.

/**
 * Set while a screen wants the keyboard to draw over its lower part instead of the app resizing around it
 * (the entry editor: the keyboard covers its keypad and Save, and nothing else moves).
 */
object KeyboardOverlay {
    var active by mutableStateOf(false)
}

/** A short message at the bottom of the screen, optionally with Undo. One at a time. */
@Stable
class Toaster {
    var current by mutableStateOf<Toast?>(null)
        private set
    private var seq = 0
    fun show(message: String, undo: (() -> Unit)? = null) { current = Toast(++seq, message, undo) }
    fun dismiss() { current = null }
    data class Toast(val id: Int, val message: String, val undo: (() -> Unit)?)
}

val LocalToaster = compositionLocalOf { Toaster() }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val t = toaster.current ?: return
    LaunchedEffect(t.id) { delay(if (t.undo != null) 5000 else 2500); if (toaster.current?.id == t.id) toaster.dismiss() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.padding(horizontal = F.gutter, vertical = 12.dp).fillMaxWidth().shadow(10.dp, shape)
            .clip(shape).background(F.ink).heightIn(min = 50.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t.message, Modifier.weight(1f), color = F.page, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Undo is a small chip; × closes the toast early.
        if (t.undo != null) Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(F.page.copy(alpha = 0.14f))
                .clickable(onClickLabel = tr("Undo")) { t.undo.invoke(); toaster.dismiss() }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Undo"), color = pick(0xFF8FE0C4, 0xFF0F5E4E), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = tr("Close")) { toaster.dismiss() },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Close, tr("Close"), tint = F.page.copy(alpha = 0.8f), modifier = Modifier.size(16.dp)) }
    }
}
