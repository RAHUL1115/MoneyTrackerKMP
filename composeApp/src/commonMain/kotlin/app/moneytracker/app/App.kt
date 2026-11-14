package app.moneytracker.app

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.accounts.classic.AccountDetailScreen
import app.moneytracker.accounts.classic.AccountGroupsScreen
import app.moneytracker.accounts.classic.AccountListScreen
import app.moneytracker.accounts.classic.AccountsTab
import app.moneytracker.accounts.classic.AccountsTrendScreen
import app.moneytracker.accounts.classic.EditAccountScreen
import app.moneytracker.activity.classic.SearchScreen
import app.moneytracker.activity.classic.TransactionsTab
import app.moneytracker.core.I18n
import app.moneytracker.core.Languages
import app.moneytracker.core.LaunchAction
import app.moneytracker.core.LocalPlatform
import app.moneytracker.core.Network
import app.moneytracker.core.Platform
import app.moneytracker.core.Privacy
import app.moneytracker.core.Unmasked
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.Backups
import app.moneytracker.data.ColorSet
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.Setting
import app.moneytracker.data.SettingsStore
import app.moneytracker.data.ThemeMode
import app.moneytracker.data.TimeInput
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.AccentChoice
import app.moneytracker.designsystem.classic.AccentTint
import app.moneytracker.designsystem.classic.DangerColor
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.FillColor
import app.moneytracker.designsystem.classic.HeaderBackground
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.ListPrefs
import app.moneytracker.designsystem.classic.LocalListPrefs
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.accentChoice
import app.moneytracker.designsystem.classic.colorsSwapped
import app.moneytracker.designsystem.classic.darkTheme
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.entry.classic.EditTxnScreen
import app.moneytracker.entry.classic.ListenLook
import app.moneytracker.entry.classic.ListenSheet
import app.moneytracker.insights.classic.BudgetDetailScreen
import app.moneytracker.insights.classic.BudgetScreen
import app.moneytracker.insights.classic.StatsDetailScreen
import app.moneytracker.insights.classic.StatsTab
import app.moneytracker.ui.AboutScreen
import app.moneytracker.ui.BackupScreen
import app.moneytracker.ui.CalculatorScreen
import app.moneytracker.ui.CategoriesScreen
import app.moneytracker.ui.ConfigurationScreen
import app.moneytracker.ui.CurrenciesScreen
import app.moneytracker.ui.EditNoteScreen
import app.moneytracker.ui.HelpScreen
import app.moneytracker.ui.InboxScreen
import app.moneytracker.ui.LicenseScreen
import app.moneytracker.ui.LockScreen
import app.moneytracker.ui.MessageSettingsScreen
import app.moneytracker.ui.MoreHubScreen
import app.moneytracker.ui.MoreTab
import app.moneytracker.ui.OnboardingScreen
import app.moneytracker.ui.RecurringScreen
import app.moneytracker.ui.SecurityScreen
import app.moneytracker.ui.TagsScreen
import app.moneytracker.ui.TemplatesScreen
import app.moneytracker.ui.WebDashboardScreen
import kotlinx.coroutines.withContext

val LocalRepo = compositionLocalOf<MoneyRepository> { error("No repository") }
val LocalCurrency = compositionLocalOf { CurrencyFormat() }
val LocalNavigator = compositionLocalOf<Navigator> { error("No navigator") }
val LocalBackups = compositionLocalOf<Backups> { error("No backups") }
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
    backups: Backups,
    navigator: Navigator,
    platform: Platform,
    locked: Boolean,
    onUnlocked: () -> Unit,
    launchAction: LaunchAction?,
    onLaunchHandled: () -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit = {},
    newLayout: Boolean = false,
) {
    FollowAppearance(repo?.settingsStore, platform.languages, onDarkThemeChanged)
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            if (I18n.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = scheme(), typography = AppTypography) {
            Surface(Modifier.fillMaxSize(), color = PageBackground) {
                if (repo == null) {
                    // Database is being swapped (restore). Nothing may query it right now.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val session = AppSession(navigator, locked, onUnlocked, launchAction, onLaunchHandled, newLayout)
                    AppContent(repo, backups, platform, session)
                }
            }
        }
    }
}

/** What the platform tells the app about this run: navigation, the lock, and a pending launch request. */
private class AppSession(
    val navigator: Navigator,
    val locked: Boolean,
    val onUnlocked: () -> Unit,
    val launchAction: LaunchAction?,
    val onLaunchHandled: () -> Unit,
    val newLayout: Boolean,
)

/** Reads [setting] as Compose state, starting from its stored value (or [initial] while there is no database). */
@Composable
private fun <T> SettingsStore?.observe(setting: Setting<T>, initial: T): T {
    val flow = remember(this) { this?.setting(setting) ?: kotlinx.coroutines.flow.flowOf(initial) }
    return flow.collectAsState(remember(this) { this?.settingNow(setting) ?: initial }).value
}

/** Theme, accent colour and language follow Configuration, or the phone when nothing is pinned. */
@Composable
private fun FollowAppearance(settings: SettingsStore?, languages: Languages, onDarkThemeChanged: (Boolean) -> Unit) {
    val themeSetting = settings.observe(Prefs.THEME, Prefs.THEME.default)
    val wantDark = when (themeSetting) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    if (darkTheme != wantDark) darkTheme = wantDark
    val accentSetting = settings.observe(Prefs.ACCENT, Prefs.ACCENT.default)
    val wantAccent = AccentChoice.entries.firstOrNull { it.name == accentSetting } ?: AccentChoice.TEAL
    if (accentChoice != wantAccent) accentChoice = wantAccent
    val languageSetting = settings.observe(Prefs.LANGUAGE, Prefs.LANGUAGE.default)
    LaunchedEffect(languageSetting) {
        val tag = languageSetting.takeIf { it.isNotEmpty() } ?: I18n.resolve(languages.systemLanguage())
        I18n.rtl = tag.substringBefore('-') in I18n.RTL
        I18n.table = if (tag == "en") emptyMap() else withContext(kotlinx.coroutines.Dispatchers.Default) { languages.translations(tag) }
    }
    LaunchedEffect(wantDark) { onDarkThemeChanged(wantDark) }
}

/** Exchange rates, once a day unless "Update automatically" is off. No request is made without other currencies. */
@Composable
private fun RefreshRatesDaily(repo: MoneyRepository, network: Network) {
    LaunchedEffect(repo) {
        withContext(repo.io) {
            val settings = repo.settingsStore
            val dayAgo = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - 20 * 3_600_000L
            val due = settings.settingNow(Prefs.RATES_CHECKED) < dayAgo
            if (settings.settingNow(Prefs.RATES_AUTO) && due) repo.currencyStore.updateRates(network::httpGet)
        }
    }
}

@Composable
private fun AppContent(repo: MoneyRepository, backups: Backups, platform: Platform, session: AppSession) {
    val settings = repo.settingsStore
    // Above the lock screen: open screens keep their state (and what was typed) while the app is locked.
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val toaster = remember { app.moneytracker.designsystem.fresh.Toaster() }
    val currency by settings.currency.collectAsState(CurrencyFormat())
    val currencies by repo.currencyStore.currencies.collectAsState(emptyList())
    // null until read, so neither the lock screen nor onboarding flashes up.
    val passcode by remember(repo) { settings.setting(Prefs.PASSCODE) }.collectAsState(null as String?)
    val onboarded by remember(repo) { settings.setting(Prefs.ONBOARDED) }.collectAsState(null as Boolean?)
    val colorSet = settings.observe(Prefs.COLOR_SET, Prefs.COLOR_SET.default)
    LaunchedEffect(colorSet) { colorsSwapped = colorSet == ColorSet.SWAPPED }
    val timeInput by remember(repo) { settings.setting(Prefs.TIME_INPUT) }.collectAsState(Prefs.TIME_INPUT.default)
    val showDescription by remember(repo) { settings.setting(Prefs.SHOW_DESCRIPTION) }.collectAsState(false)
    // Privacy eye: the saved choice, then every amount below is masked while it's on.
    LaunchedEffect(repo) { Privacy.load(repo) }
    RefreshRatesDaily(repo, platform.network)
    val masked = Privacy.masked
    CompositionLocalProvider(
        LocalRepo provides repo,
        LocalCurrency provides currency.copy(masked = masked),
        LocalNavigator provides session.navigator,
        LocalBackups provides backups,
        LocalPlatform provides platform,
        LocalCurrencies provides currencies.associate { it.code to it.copy(masked = masked) },
        LocalListPrefs provides ListPrefs(showTime = timeInput == TimeInput.LIST, showDescription = showDescription),
        app.moneytracker.app.LocalNewLayout provides session.newLayout,
        app.moneytracker.designsystem.fresh.LocalToaster provides toaster,
    ) {
        // Content stays above the gesture bar and, when typing, above the keyboard.
        val keyboardPadding = if (app.moneytracker.designsystem.fresh.KeyboardOverlay.active) Modifier else Modifier.imePadding()
        Box(Modifier.fillMaxSize().navigationBarsPadding().then(keyboardPadding)) {
            val pin = passcode
            when {
                pin == null || onboarded == null -> Unit
                // One lock screen for both layouts: the classic PIN pad.
                session.locked && pin.isNotBlank() -> LockScreen(pin, session.onUnlocked)
                // First launch always uses the classic welcome screens; both layouts start after them.
                onboarded != true -> OnboardingScreen()
                else -> UnlockedApp(repo.today, screenState, toaster, session)
            }
        }
    }
}

@Composable
private fun UnlockedApp(
    today: () -> kotlinx.datetime.LocalDate,
    screenState: androidx.compose.runtime.saveable.SaveableStateHolder,
    toaster: app.moneytracker.designsystem.fresh.Toaster,
    session: AppSession,
) {
    val navigator = session.navigator
    val newLayout = session.newLayout
    // You're in: a passcode that appears now (turned on, or brought by a restore) asks only after the next restart or a while away.
    if (session.locked) SideEffect { session.onUnlocked() }
    var voiceSheet by remember { mutableStateOf(false) }
    if (voiceSheet) {
        val look = if (newLayout) app.moneytracker.designsystem.fresh.newListenLook() else ListenLook.classic()
        ListenSheet(onDismiss = { voiceSheet = false }, look = look) { g -> navigator.push(Screen.EditTxn(preset = g?.draft)) }
    }
    LaunchedEffect(session.launchAction) {
        val action = session.launchAction ?: return@LaunchedEffect
        openLaunchAction(action, navigator, today, openVoice = { voiceSheet = true })
        session.onLaunchHandled()
    }
    Box(Modifier.fillMaxSize()) {
        navigator.Host(screenState, home = { if (newLayout) app.moneytracker.app.FreshHome() else Home() }) { top ->
            val fresh = if (newLayout) app.moneytracker.app.freshScreen(top) else null
            if (fresh != null) fresh() else ClassicScreen(top)
        }
        // Both layouts: Undo after adding a detected payment, and the new layout's other toasts.
        app.moneytracker.designsystem.fresh.ToastHost(toaster, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

/** Opens what a shortcut, widget or notification asked for, on top of a fresh back stack. */
private fun openLaunchAction(action: LaunchAction, navigator: Navigator, today: () -> kotlinx.datetime.LocalDate, openVoice: () -> Unit) {
    navigator.clear()
    when (action) {
        LaunchAction.ADD_EXPENSE -> navigator.push(Screen.EditTxn())
        LaunchAction.ADD_INCOME -> navigator.push(Screen.EditTxn(preset = TxnDraft(DraftType.INCOME, today(), "", amount = 0)))
        LaunchAction.ADD_TRANSFER -> navigator.push(Screen.EditTxn(preset = TxnDraft(DraftType.TRANSFER, today(), "", amount = 0)))
        LaunchAction.VOICE -> openVoice()
        LaunchAction.INBOX -> navigator.push(Screen.Inbox)
        LaunchAction.SEARCH -> navigator.push(Screen.Search)
        LaunchAction.WEB_DASHBOARD -> navigator.push(Screen.WebDashboard)
    }
}

/** The classic layout's screens; the new layout routes its own through `freshScreen`. */
@Composable
private fun ClassicScreen(top: Screen) {
    when (top) {
        is Screen.EditTxn -> Unmasked { EditTxnScreen(top) }
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
        is Screen.MoreHub -> MoreHubScreen(top.id)
        Screen.Security -> SecurityScreen()
        Screen.AccountsTrend -> AccountsTrendScreen()
        Screen.AccountGroups -> AccountGroupsScreen()
        Screen.WebDashboard -> WebDashboardScreen()
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
            Tab.entries.forEach { t -> TabButton(t, selected = t == tab, onClick = { tab = t }) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabButton(t: Tab, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Accent else SubtleText
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(onClick = onClick).padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Selected tab: filled icon on a soft accent pill.
        Box(
            Modifier.size(width = 56.dp, height = 30.dp)
                .background(if (selected) AccentTint else Color.Transparent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tabIcon(t, selected), contentDescription = tr(t.title), tint = color, modifier = Modifier.size(22.dp))
        }
        Text(tr(t.title), Modifier.padding(top = 3.dp), color = if (selected) Ink else SubtleText, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private fun tabIcon(t: Tab, selected: Boolean) = when (t) {
    Tab.TRANS -> if (selected) Icons.AutoMirrored.Filled.MenuBook else Icons.AutoMirrored.Outlined.MenuBook
    Tab.STATS -> if (selected) Icons.Filled.BarChart else Icons.Outlined.BarChart
    Tab.ACCOUNTS -> if (selected) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet
    Tab.MORE -> Icons.Outlined.MoreHoriz
}
