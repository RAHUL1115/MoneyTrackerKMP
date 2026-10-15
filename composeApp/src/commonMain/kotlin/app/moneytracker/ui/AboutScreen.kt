package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where the source and project documents live. */
private const val REPO_URL = "https://github.com/RAHUL1115/MoneyTrackerKMP"
private fun doc(file: String) = "$REPO_URL/blob/main/$file"

/** Libraries shipped in the app, all under the Apache License 2.0 (text bundled as "Apache-2.0"). */
private val LIBRARIES = listOf(
    "Kotlin standard library" to "JetBrains",
    "kotlinx.coroutines" to "JetBrains",
    "kotlinx-datetime" to "JetBrains",
    "Compose Multiplatform" to "JetBrains",
    "Jetpack Compose, Material 3 and Material icons" to "Google / AndroidX",
    "AndroidX Activity, Biometric, Core and Fragment" to "Google / AndroidX",
    "SQLDelight" to "Cash App",
)

/** App name and version, the GitHub project and its documents, the licence and the libraries used. */
@Composable
fun AboutScreen() {
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    Column(Modifier.fillMaxSize().background(PageBackground)) {
        BackTopBar(tr("About"), nav::pop)
        HorizontalDivider(color = DividerColor)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Column(Modifier.card().fillMaxWidth().padding(20.dp)) {
                Text("MoneyTracker", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (platform.appVersion.isNotEmpty()) Text(tr("Version {0}", platform.appVersion), color = SubtleText, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Free and open source under the GNU GPL v3. No ads, no accounts and no internet access: your data stays on this phone."),
                    color = InkSoft, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.card()) {
                AboutLink(GitHubMark, tr("Source code on GitHub"), REPO_URL.removePrefix("https://")) { platform.openUrl(REPO_URL) }
                AboutLink(Icons.Outlined.Description, tr("Read me")) { platform.openUrl(doc("README.md")) }
                AboutLink(Icons.Outlined.PrivacyTip, tr("Privacy policy")) { platform.openUrl(doc("PRIVACY.md")) }
                AboutLink(Icons.Outlined.Gavel, tr("License (GPL-3.0)")) { platform.openUrl(doc("LICENSE")) }
                AboutLink(Icons.Outlined.Security, tr("Report a security issue")) { platform.openUrl(doc("SECURITY.md")) }
                AboutLink(Icons.Outlined.Groups, tr("Contributing"), divider = false) { platform.openUrl(doc("CONTRIBUTING.md")) }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.card()) {
                Text(tr("Open-source libraries"), Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), fontWeight = FontWeight.Medium, color = Ink)
                LIBRARIES.forEach { (name, by) ->
                    AboutLink(Icons.Outlined.Code, name, "$by · ${tr("Apache License 2.0")}", external = false) { nav.push(Screen.License("Apache-2.0")) }
                }
                AboutLink(Icons.Outlined.Gavel, tr("License (GPL-3.0)"), tr("Full text, offline"), external = false, divider = false) {
                    nav.push(Screen.License("GPL-3.0"))
                }
            }
            Text(
                tr("Inspired by Money Manager by Realbyte Inc. MoneyTracker is an independent app, not affiliated with or endorsed by Realbyte, and can import Money Manager backups."),
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp), color = SubtleText, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun AboutLink(
    icon: ImageVector, label: String, detail: String? = null,
    external: Boolean = true, divider: Boolean = true, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink)
            if (detail != null) Text(detail, color = SubtleText, fontSize = 12.sp)
        }
        if (external) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp), tint = SubtleText)
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 58.dp), color = DividerColor)
}

/** A bundled licence text, readable offline. */
@Composable
fun LicenseScreen(id: String) {
    val nav = LocalNavigator.current
    val platform = LocalPlatform.current
    val text = remember(id) { platform.licenseText(id) }
    Column(Modifier.fillMaxSize().background(SurfaceColor)) {
        BackTopBar(if (id == "GPL-3.0") "GNU GPL v3" else "Apache License 2.0", nav::pop)
        HorizontalDivider(color = DividerColor)
        SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(text ?: tr("No data available."), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = InkSoft)
        }
    }
}

/** GitHub's mark (Octicons "mark-github", MIT), used only to link to the project on GitHub. */
private val GitHubMark: ImageVector by lazy {
    ImageVector.Builder("GitHubMark", 16.dp, 16.dp, 16f, 16f).addPath(
        PathParser().parsePathString(
            "M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 " +
                "0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 " +
                "0 0-.67-.22-2.2.82-.64-.18-1.32-.27-2-.27-.68 0-1.36.09-2 .27-1.53-1.03-2.2-.82-2.2-.82-.44 1.1-.16 " +
                "1.92-.08 2.12-.51.56-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66" +
                "-.15-.24-.6-.83-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 " +
                "0 .21-.15.45-.55.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z"
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}
