package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.WEB_AUTO_STOPS
import app.moneytracker.ui.WEB_PASSWORD_MIN
import app.moneytracker.ui.activeLabel
import app.moneytracker.ui.autoStopLabel
import app.moneytracker.ui.rememberWebDashboardUi
import app.moneytracker.ui.tr

/** The web dashboard in the new layout: start/stop, the address, connected computers, password and auto-stop. */
@Composable
internal fun FreshWebDashboard() {
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val ui = rememberWebDashboardUi { toaster.show(it) }
    var passwordSheet by remember { mutableStateOf(false) }
    val s = ui.state

    SubScreen(tr("Web dashboard"), nav::pop) {
        when {
            !s.running && !s.wifi -> FCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(F.warnTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.WifiOff, null, tint = F.warn, modifier = Modifier.size(26.dp))
                    }
                    Text(tr("Connect to Wi-Fi to use the web dashboard"), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(tr("The computer and this phone need to be on the same Wi-Fi network. Mobile data doesn't work for this."),
                        color = F.muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                    Text(tr("Open Wi-Fi settings"), Modifier.padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(F.accentTint)
                        .clickable(onClick = ui.openWifi).padding(horizontal = 18.dp, vertical = 12.dp), color = F.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            !s.running -> FCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(F.accentTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Computer, null, tint = F.accent, modifier = Modifier.size(22.dp))
                    }
                    Text(tr("Use MoneyTracker from a computer"), color = F.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(tr("Open your entries in a browser on a computer on the same Wi-Fi. See totals and charts, search, and add or edit entries with a full keyboard."),
                        color = F.ink, fontSize = 14.sp, lineHeight = 21.sp)
                    Text(tr("The page comes from this phone. Nothing is sent to the internet."), color = F.muted, fontSize = 12.sp)
                    (s.stopReason ?: s.error)?.let { Text(it, color = F.danger, fontSize = 13.sp) }
                    PrimaryButton(tr("Start"), ui.start, Modifier.padding(top = 6.dp))
                }
            }
            else -> {
                FCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(F.accent))
                            Text(tr("On · Wi-Fi"), color = F.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(tr("On the computer, type this in the browser"), color = F.muted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(F.page).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s.address.orEmpty(), Modifier.weight(1f), color = F.ink, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (ui.copied) tr("Copied") else tr("Copy"), Modifier.clip(RoundedCornerShape(12.dp)).background(F.accentTint).clickable(onClick = ui.copy)
                                .padding(horizontal = 14.dp, vertical = 10.dp), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(tr("Keeps running when you leave the app, with a notification, until you stop it."), color = F.muted, fontSize = 12.sp, lineHeight = 17.sp)
                        Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(F.page).clickable(onClick = ui.stop),
                            contentAlignment = Alignment.Center) { Text(tr("Stop"), color = F.danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
                SectionLabel(tr("Connected computers"))
                FCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        if (s.computers.isEmpty()) Text(tr("None yet. Open the address on a computer."), Modifier.padding(vertical = 18.dp), color = F.muted, fontSize = 13.sp)
                        s.computers.forEachIndexed { i, c ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(F.page), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Computer, null, tint = F.accent, modifier = Modifier.size(18.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(c.name, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(activeLabel(c.lastSeen, ui.now), color = F.muted, fontSize = 12.sp)
                                }
                                Text(tr("Disconnect"), Modifier.clip(RoundedCornerShape(10.dp)).clickable { ui.disconnect(c.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                FRow(tr("Password"), tr("Asked once per visit on the computer"), value = if (ui.passwordSet) tr("On") else tr("Off"), valueColor = F.accent,
                    onClick = { passwordSheet = true })
                Divider()
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Stop automatically"), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(tr("When no computer has visited for a while"), color = F.muted, fontSize = 12.sp)
                    Segmented(WEB_AUTO_STOPS.map { autoStopLabel(it, short = true) }, WEB_AUTO_STOPS.indexOf(ui.autoStop), { ui.setAutoStop(WEB_AUTO_STOPS[it]) })
                }
            }
        }
        Text(tr("Works only on your local network. Without a password, anyone on the same Wi-Fi who knows the address can open it, so set one on shared or public Wi-Fi."),
            Modifier.padding(horizontal = 6.dp), color = F.muted, fontSize = 12.sp, lineHeight = 18.sp)
    }

    if (passwordSheet) {
        var text by remember { mutableStateOf("") }
        val ok = text.length >= WEB_PASSWORD_MIN
        Sheet(tr("Dashboard password"), { passwordSheet = false }, bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(tr("Save password"), { ui.setPassword(text); passwordSheet = false }, enabled = ok)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.passwordSet) SecondaryButton(tr("Turn off"), { ui.setPassword(null); passwordSheet = false }, Modifier.weight(1f), danger = true, filled = true)
                    SecondaryButton(tr("Cancel"), { passwordSheet = false }, Modifier.weight(1f), filled = true)
                }
            }
        }) {
            Text(tr("The computer asks for it before showing anything. It's separate from your app passcode."), color = F.muted, fontSize = 13.sp, lineHeight = 19.sp)
            BasicTextField(
                text, { text = it.take(64) },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, F.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 14.dp),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = F.ink, fontSize = 16.sp), cursorBrush = SolidColor(F.accent),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text(tr("New password"), color = F.muted, fontSize = 16.sp); inner() } },
            )
            Text(tr("At least 4 characters. After 5 wrong tries the computer waits a minute."), color = F.muted, fontSize = 12.sp)
        }
    }
}
