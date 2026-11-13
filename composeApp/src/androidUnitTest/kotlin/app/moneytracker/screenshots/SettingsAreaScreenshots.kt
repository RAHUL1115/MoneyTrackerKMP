package app.moneytracker.screenshots

import app.moneytracker.data.Prefs
import app.moneytracker.ui.Screen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test

/**
 * Settings, SMS and the smaller feature screens of both layouts, plus their choice sheets and dialogs. Sheets and
 * dialogs open in their own window, so those shots take the whole screen instead of the app's root.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun ScreenshotTest.shootScreen(newLayout: Boolean, name: String) =
    captureScreenRoboImage("${if (newLayout) "new" else "classic"}/$name.png")

class NewSettingsAreaScreenshots : ScreenshotTest(newLayout = true) {
    @Test fun noteEditor() { show(Screen.EditNote("note_demo")); shoot("note_editor") }
    @Test fun webDashboard() { show(Screen.WebDashboard); shoot("web_dashboard") }
    @Test fun smsReview() { show(Screen.Inbox); shoot("sms_review") }
    @Test fun settingsLanguage() { show(Screen.Configuration); scrollToAndTap("Look"); tap("Language"); shootScreen(true, "settings_language") }
    @Test fun settingsAccent() { show(Screen.Configuration); scrollToAndTap("Look"); tap("Accent colour"); shootScreen(true, "settings_accent") }
    @Test fun settingsTheme() { show(Screen.Configuration); scrollToAndTap("Look"); tap("Theme"); shootScreen(true, "settings_theme") }
    @Test fun settingsLayout() { show(Screen.Configuration); scrollToAndTap("Look"); tap("Layout"); shootScreen(true, "settings_layout") }
    @Test fun repeatsTiming() { show(Screen.Recurring); tap("Repeat entries are added"); shootScreen(true, "repeats_timing") }
}

class ClassicSettingsAreaScreenshots : ScreenshotTest(newLayout = false) {
    @Test fun onboarding() { repo.settingsStore.putSetting(Prefs.ONBOARDED, false); show(); shoot("onboarding") }
    @Test fun security() { show(Screen.Security); shoot("security") }
    @Test fun notes() { show(); tap("Note"); shoot("notes") }
    @Test fun noteEditor() { show(Screen.EditNote("note_demo")); shoot("note_editor") }
    @Test fun backup() { show(Screen.Backup); shoot("backup") }
    @Test fun webDashboard() { show(Screen.WebDashboard); shoot("web_dashboard") }
    @Test fun messageSettings() { show(Screen.MessageSettings); shoot("message_settings") }
    @Test fun inbox() { show(Screen.Inbox); shoot("inbox") }
    @Test fun currencies() { show(Screen.Currencies); shoot("currencies") }
    @Test fun tags() { show(Screen.Tags); shoot("tags") }
    @Test fun bookmarks() { show(Screen.Templates); shoot("bookmarks") }
    @Test fun about() { show(Screen.About); shoot("about") }
    @Test fun help() { show(Screen.Help); shoot("help") }
    @Test fun calculator() { show(Screen.Calculator); shoot("calculator") }
    @Test fun configurationLayout() { show(Screen.Configuration); tap("Look"); tap("Layout"); shootScreen(false, "configuration_layout") }
    @Test fun configurationAccent() { show(Screen.Configuration); tap("Look"); tap("Accent colour"); shootScreen(false, "configuration_accent") }
    @Test fun configurationTheme() { show(Screen.Configuration); tap("Look"); tap("Theme"); shootScreen(false, "configuration_theme") }
}
