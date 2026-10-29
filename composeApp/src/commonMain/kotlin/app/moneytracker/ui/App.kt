package app.moneytracker.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.BackupController
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CurrencyFormat

val LocalRepo = compositionLocalOf<MoneyRepository> { error("No repository") }
val LocalCurrency = compositionLocalOf { CurrencyFormat() }
val LocalNavigator = compositionLocalOf<Navigator> { error("No navigator") }
val LocalBackup = compositionLocalOf<BackupController> { error("No backup controller") }
/** Non-main currencies by ISO code, for rendering foreign amounts. */
val LocalCurrencies = compositionLocalOf<Map<String, app.moneytracker.domain.Currency>> { emptyMap() }

/** Material scheme built from the app palette (which already follows [darkTheme]). */
private fun scheme() = (if (darkTheme) androidx.compose.material3.darkColorScheme() else lightColorScheme()).copy(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = AccentTint, onPrimaryContainer = Ink,
    secondary = IncomeColor, background = PageBackground, surface = SurfaceColor,
    onBackground = Ink, onSurface = Ink,
    surfaceVariant = HeaderBackground, onSurfaceVariant = SubtleText, outline = DividerColor, outlineVariant = DividerColor,
    surfaceContainerHigh = SurfaceColor, surfaceContainerHighest = FillColor, surfaceContainer = SurfaceColor,
    surfaceContainerLow = SurfaceColor, surfaceContainerLowest = SurfaceColor, error = DangerColor,
)

/** Compact scale: 14sp body, 12–13sp secondary, 15sp titles (Material defaults run ~2sp larger). */
private val AppTypography = androidx.compose.material3.Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = t.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        headlineSmall = t.headlineSmall.copy(fontSize = 19.sp, lineHeight = 26.sp),   // dialog titles
    )
}

enum class Tab(val title: String) { TRANS("Trans."), STATS("Stats"), ACCOUNTS("Accounts"), MORE("More") }

/**
 * @param locked platform-managed: true at cold start and after the app was in the background a while.
 * @param launchAction a pending request from a shortcut, widget or notification; [onLaunchHandled] clears it.
 */
@Composable
fun App(
    repo: MoneyRepository?,
    backup: BackupController,
    navigator: Navigator,
    platform: PlatformServices,
    locked: Boolean,
    onUnlocked: () -> Unit,
    launchAction: LaunchAction?,
    onLaunchHandled: () -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit = {},
    newLayout: Boolean = false,
) {
    // Style: follow the system unless Configuration pins light or dark.
    val themeSetting by remember(repo) { repo?.setting(Keys.THEME) ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(repo?.settingNow(Keys.THEME))
    val wantDark = when (themeSetting) { "DARK" -> true; "LIGHT" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    if (darkTheme != wantDark) darkTheme = wantDark
    val accentSetting by remember(repo) { repo?.setting(Keys.ACCENT) ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(repo?.settingNow(Keys.ACCENT))
    val wantAccent = AccentChoice.entries.firstOrNull { it.name == accentSetting } ?: AccentChoice.TEAL
    if (accentChoice != wantAccent) accentChoice = wantAccent
    // Language: Configuration › Language, or the phone's language.
    val platformForLanguage = platform
    val languageSetting by remember(repo) { repo?.setting(Keys.LANGUAGE) ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(repo?.settingNow(Keys.LANGUAGE))
    LaunchedEffect(languageSetting) {
        val tag = languageSetting?.takeIf { it.isNotEmpty() } ?: I18n.resolve(platformForLanguage.systemLanguage())
        I18n.rtl = tag.substringBefore('-') in I18n.RTL
        I18n.table = if (tag == "en") emptyMap() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { platformForLanguage.translations(tag) }
    }
    LaunchedEffect(wantDark) { onDarkThemeChanged(wantDark) }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            if (I18n.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
    MaterialTheme(colorScheme = scheme(), typography = AppTypography) {
        Surface(Modifier.fillMaxSize(), color = PageBackground) {
            if (repo == null) {
                // Database is being swapped (restore). Nothing may query it right now.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Surface
            }
            val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val toaster = remember { app.moneytracker.ui.fresh.Toaster() }
            val currency by repo.currency.collectAsState(CurrencyFormat())
            val currencies by repo.currencies.collectAsState(emptyList())
            val passcode by remember(repo) { repo.setting(Keys.PASSCODE) }.collectAsState("loading")
            val onboarded by remember(repo) { repo.setting(Keys.ONBOARDED) }.collectAsState("loading")
            val colorSet by remember(repo) { repo.setting(Keys.COLOR_SET) }.collectAsState(repo.settingNow(Keys.COLOR_SET))
            LaunchedEffect(colorSet) { colorsSwapped = colorSet == "B" }
            val timeInput by remember(repo) { repo.setting(Keys.TIME_INPUT) }.collectAsState(null)
            val showDescription by remember(repo) { repo.flag(Keys.SHOW_DESCRIPTION) }.collectAsState(false)
            CompositionLocalProvider(
                LocalRepo provides repo,
                LocalCurrency provides currency,
                LocalNavigator provides navigator,
                LocalBackup provides backup,
                LocalPlatform provides platform,
                LocalCurrencies provides currencies.associateBy { it.code },
                LocalListPrefs provides ListPrefs(showTime = timeInput == "LIST", showDescription = showDescription),
                app.moneytracker.ui.fresh.LocalNewLayout provides newLayout,
                app.moneytracker.ui.fresh.LocalToaster provides toaster,
            ) {
                // Content stays above the gesture bar and, when typing, above the keyboard.
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    when {
                        passcode == "loading" || onboarded == "loading" -> Unit
                        locked && !passcode.isNullOrBlank() ->
                            if (newLayout) app.moneytracker.ui.fresh.FreshLock(passcode!!, onUnlocked) else LockScreen(passcode!!, onUnlocked)
                        // First launch always uses the classic welcome screens; both layouts start after them.
                        onboarded != "1" -> OnboardingScreen()
                        else -> {
                            LaunchedEffect(launchAction) {
                                val a = launchAction ?: return@LaunchedEffect
                                navigator.stack.clear()
                                when (a) {
                                    LaunchAction.ADD_EXPENSE -> navigator.push(Screen.EditTxn())
                                    LaunchAction.ADD_INCOME -> navigator.push(Screen.EditTxn(preset = TxnDraft(DraftType.INCOME, repo.today(), "", amount = 0)))
                                    LaunchAction.ADD_TRANSFER -> navigator.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, repo.today(), "", amount = 0)))
                                    LaunchAction.VOICE -> platform.listen { text -> navigator.push(Screen.EditTxn(preset = VoiceParser.parse(text, repo.today(), currency))) }
                                    LaunchAction.INBOX -> navigator.push(Screen.Inbox)
                                    LaunchAction.SEARCH -> navigator.push(Screen.Search)
                                }
                                onLaunchHandled()
                            }
                            // Each screen keeps its saved UI state (selected tab, month, scroll) while others sit on top
                            // of it, so Back returns exactly where you were. State is dropped once a screen is closed.
                            val keys = listOf("home") + navigator.stack.mapIndexed { i, sc -> "$i:$sc" }
                            val seen = remember { mutableSetOf<String>() }
                            LaunchedEffect(keys) {
                                (seen - keys.toSet()).forEach { stateHolder.removeState(it) }
                                seen.retainAll(keys.toSet()); seen.addAll(keys)
                            }
                            val top = navigator.stack.lastOrNull()
                            Box(Modifier.fillMaxSize()) {
                            // Home stays composed (not drawn, not touchable, hidden from accessibility) under other
                            // screens, so going back shows it at once with its data already loaded instead of blank.
                            stateHolder.SaveableStateProvider("home") {
                                Box(Modifier.fillMaxSize().then(if (top != null) Modifier.drawWithContent { }.clearAndSetSemantics { } else Modifier)) { if (newLayout) app.moneytracker.ui.fresh.FreshHome() else Home() }
                            }
                            if (top != null) stateHolder.SaveableStateProvider(keys.last()) {
                            // Takes every touch, so none reach Home underneath.
                            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                            val fresh = if (newLayout) app.moneytracker.ui.fresh.freshScreen(top) else null
                            if (fresh != null) fresh() else when (top) {
                                is Screen.EditTxn -> EditTxnScreen(top)
                                is Screen.AccountDetail -> AccountDetailScreen(top.accountId)
                                is Screen.EditAccount -> EditAccountScreen(top.accountId)
                                is Screen.Categories -> CategoriesScreen(top.kind)
                                is Screen.StatsDetail -> StatsDetailScreen(top)
                                is Screen.EditNote -> EditNoteScreen(top.noteId)
                                Screen.AccountList -> AccountListScreen()
                                Screen.Recurring -> RecurringScreen()
                                Screen.Backup -> BackupScreen()
                                Screen.Configuration -> ConfigurationScreen()
                                Screen.Budget -> BudgetScreen()
                                is Screen.BudgetDetail -> BudgetDetailScreen(top.periodKey)
                                Screen.Search -> SearchScreen()
                                Screen.Templates -> TemplatesScreen()
                                Screen.Inbox -> InboxScreen()
                                Screen.MessageSettings -> MessageSettingsScreen()
                                Screen.Calculator -> CalculatorScreen()
                                Screen.Help -> HelpScreen()
                                Screen.About -> AboutScreen()
                                is Screen.License -> LicenseScreen(top.id)
                                Screen.Currencies -> CurrenciesScreen()
                                Screen.Tags -> TagsScreen()
                                Screen.Security -> SecurityScreen()
                                Screen.AccountsTrend -> AccountsTrendScreen()
                                Screen.AccountGroups -> AccountGroupsScreen()
                            }
                            }
                            }
                            if (newLayout) app.moneytracker.ui.fresh.ToastHost(toaster, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Home() {
    var tab by rememberSaveable { mutableStateOf(Tab.TRANS) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.TRANS -> TransactionsTab()
                Tab.STATS -> StatsTab()
                Tab.ACCOUNTS -> AccountsTab()
                Tab.MORE -> MoreTab()
            }
        }
        HorizontalDivider(color = DividerColor)
        Row(Modifier.fillMaxWidth().background(SurfaceColor).height(64.dp)) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                val color = if (selected) Accent else SubtleText
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { tab = t }.padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Selected tab: filled icon on a soft accent pill.
                    Box(
                        Modifier.size(width = 56.dp, height = 30.dp)
                            .background(if (selected) AccentTint else Color.Transparent, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (t) {
                                Tab.TRANS -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
                                Tab.STATS -> if (selected) Icons.Filled.BarChart else Icons.Outlined.BarChart
                                Tab.ACCOUNTS -> if (selected) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet
                                Tab.MORE -> Icons.Outlined.MoreHoriz
                            },
                            contentDescription = tr(t.title), tint = color, modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(tr(t.title), Modifier.padding(top = 3.dp), color = if (selected) Ink else SubtleText, fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}
