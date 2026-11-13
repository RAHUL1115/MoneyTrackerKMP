package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MoreTab() {
    val nav = LocalNavigator.current
    val appInfo = LocalPlatform.current.appInfo
    val overview by rememberSettingsOverview()
    val places = rememberMorePlaces(newLayout = false, overview)
    Column(Modifier.fillMaxSize().background(PageBackground).verticalScroll(rememberScrollState())) {
        TitleTopBar(tr("Settings")) { Text(appInfo.appVersion.ifEmpty { "" }.let { if (it.isEmpty()) "" else "v$it" }, Modifier.padding(end = 12.dp), color = SubtleText, fontSize = 13.sp) }
        HorizontalDivider(color = DividerColor)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.card().padding(vertical = 8.dp)) {
            places.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { p -> Tile(p.title, p.status, placeIcon(p.id), Modifier.weight(1f), p.open) }
                    // A short last row keeps the grid's columns instead of stretching its tiles.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.card()) {
            ConfigRow(tr("Help")) { nav.push(Screen.Help) }
            HorizontalDivider(color = DividerColor)
            ConfigRow(tr("About MoneyTracker")) { nav.push(Screen.About) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Icon for a settings group. */
internal fun groupIcon(id: String): ImageVector = when (id) {
    "money" -> Icons.Outlined.Payments
    "calendar" -> Icons.Outlined.CalendarMonth
    "look" -> Icons.Outlined.Palette
    "entering" -> Icons.Outlined.EditNote
    "lists" -> Icons.AutoMirrored.Outlined.List
    else -> Icons.Outlined.Notifications
}

/** Icon for a More place or settings hub. */
internal fun placeIcon(id: String): ImageVector = when (id) {
    "labels" -> Icons.Outlined.Category
    "budget" -> Icons.Outlined.EditNote
    "reuse" -> Icons.Outlined.Repeat
    "sms" -> Icons.Outlined.MarkEmailUnread
    "safety" -> Icons.Outlined.Lock
    "web" -> Icons.Outlined.Computer
    "help" -> Icons.AutoMirrored.Outlined.HelpOutline
    else -> Icons.Outlined.Settings
}

@Composable
private fun Tile(label: String, status: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(AccentTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = Accent)
        }
        Text(label, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
        Text(status, Modifier.padding(top = 2.dp), fontSize = 11.sp, color = SubtleText, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
    }
}

/** Categories & tags, Repeat & bookmarks, Backup & security, Help & about. */
@Composable
fun MoreHubScreen(id: String) {
    val nav = LocalNavigator.current
    val overview by rememberSettingsOverview()
    val hub = rememberSettingHubs(rememberSettingsModel(), overview).firstOrNull { it.id == id } ?: return
    Column(Modifier.fillMaxSize().background(PageBackground)) {
        BackTopBar(hub.title, nav::pop)
        HorizontalDivider(color = DividerColor)
        Text(hub.intro, Modifier.padding(horizontal = 24.dp, vertical = 14.dp), fontSize = 13.sp, color = SubtleText)
        HorizontalDivider(color = DividerColor)
        hub.rows.forEach { r -> SettingRowClassic(r, null); HorizontalDivider(color = DividerColor) }
    }
}

/** A shared setting row drawn the classic way: label, hint, and a teal value or a switch. */
@Composable
internal fun SettingRowClassic(r: SettingRow, where: String?) {
    val checked = r.checked
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = r.onClick).heightIn(min = 56.dp).padding(start = 24.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, color = Ink)
            val hint = where ?: r.subtitle
            if (!hint.isNullOrEmpty()) Text(hint, fontSize = 12.sp, color = SubtleText)
        }
        if (checked != null) AppSwitch(checked) { r.onClick() }
        else if (r.value != null) Text(r.value, color = Accent, maxLines = 1)
    }
}

@Composable
internal fun ConfigSection(title: String) {
    Column {
        Text(title, Modifier.fillMaxWidth().background(PageBackground).padding(start = 24.dp, top = 22.dp, bottom = 8.dp),
            color = SubtleText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        HorizontalDivider(color = DividerColor)
    }
}

@Composable
internal fun ConfigToggle(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable { onChange(!checked) }.padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, fontSize = 12.sp, color = SubtleText)
        }
        AppSwitch(checked, onChange)
    }
}

@Composable
internal fun ConfigRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        if (value != null) Text(value, color = Accent)
    }
}

