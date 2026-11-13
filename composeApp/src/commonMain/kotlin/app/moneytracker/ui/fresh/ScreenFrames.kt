package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.tr

// The frame around every new-layout screen: tab headers, header tiles and pushed sub-screens.

/** Top of a main tab: big title, optional line above it, actions on the right. */
@Composable
fun TabHeader(title: String, overline: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter).padding(top = 18.dp, bottom = 8.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (overline != null) Text(overline, color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(title, color = F.ink, fontSize = if (overline != null) 20.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyEye()
            actions()
        }
    }
}

/** The privacy eye on every main tab: hides or shows all amounts, tinted while they're hidden. */
@Composable
fun PrivacyEye() {
    val repo = app.moneytracker.ui.LocalRepo.current
    val on = app.moneytracker.ui.Privacy.hidden
    IconTile(
        if (on) Glyph.EyeOff else Glyph.Eye, if (on) tr("Show amounts") else tr("Hide amounts"),
        { app.moneytracker.ui.Privacy.toggle(repo) },
        tint = if (on) F.accent else F.ink, background = if (on) F.accentTint else F.card,
    )
}

/**
 * 44dp square white button holding an icon (header actions, back). [label] is what TalkBack reads, once, followed by
 * "button"; the icon itself is decoration.
 */
@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0, tint: Color = F.ink, background: Color = F.card) {
    Box(
        modifier.size(44.dp).clip(F.iconShape).background(background).semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).clip(CircleShape).background(F.warn),
            contentAlignment = Alignment.Center,
        ) { Text(if (badge > 9) "9+" else badge.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * A screen opened on top of the tabs: back tile + title, scrolling body, optional bottom action.
 * [scroll] = false when the body manages its own scrolling (lists).
 * [add] puts a "+ label" button at the bottom right, within reach of the thumb.
 */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** A muted line under the title (e.g. "2 to review"). */
    subtitle: String? = null,
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    add: Pair<String, () -> Unit>? = null,
    /** Pass one in to scroll the page from outside (e.g. back to the top). */
    scrollState: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pageScroll = scrollState ?: rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(F.page).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = F.gutter).padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(Glyph.Back, tr("Back"), onBack)
            Column(Modifier.weight(1f)) {
                Text(title, color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // While amounts are hidden, every screen offers the eye, not just the main tabs.
            if (app.moneytracker.ui.Privacy.hidden) PrivacyEye()
            actions()
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(pageScroll) else Modifier)
                .padding(horizontal = if (scroll) F.gutter else 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            if (scroll) Spacer(Modifier.height(if (add != null) 100.dp else 24.dp))
        }
        if (bottom != null) Box(Modifier.fillMaxWidth().background(F.page).padding(horizontal = F.gutter, vertical = 12.dp)) { bottom() }
    }
    if (add != null) Row(
        Modifier.align(Alignment.BottomEnd).padding(end = F.gutter, bottom = 20.dp).height(56.dp)
            .shadow(8.dp, F.fabShape).clip(F.fabShape).background(F.accent)
            .clickable(role = Role.Button, onClick = add.second).padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Glyph.Add, null, tint = F.onAccent, modifier = Modifier.size(22.dp))
        Text(add.first, color = F.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    }
}
