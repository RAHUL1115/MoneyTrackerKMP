package app.moneytracker.screenshots

import app.moneytracker.app.Screen
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CategoryKind
import org.junit.Test

/** The new layout's main screens over the demo data. */
class NewLayoutScreenshots : ScreenshotTest(newLayout = true) {
    @Test fun home() { show(); shoot("home") }
    @Test fun homeDark() { show(dark = true); shoot("home_dark") }
    @Test fun activity() { show(); tap("Activity"); shoot("activity") }
    @Test fun activityDark() { show(dark = true); tap("Activity"); shoot("activity_dark") }
    @Test fun activityCalendar() { show(); tap("Activity"); tap("Calendar"); shoot("activity_calendar") }
    @Test fun notes() { show(); tap("Activity"); tap("Notes"); shoot("notes") }
    @Test fun insights() { show(); tap("Insights"); shoot("insights") }
    @Test fun accounts() { show(); tap("Accounts"); shoot("accounts") }
    @Test fun search() { show(Screen.Search); type("Coffee"); shoot("search") }
    @Test fun addExpense() { show(Screen.EditTxn(preset = lunchDraft)); shoot("add_expense") }
    @Test fun addExpenseDark() { show(Screen.EditTxn(preset = lunchDraft), dark = true); shoot("add_expense_dark") }
    @Test fun accountDetail() { show(Screen.AccountDetail("acc_bank")); shoot("account_detail") }
    @Test fun budget() { show(Screen.Budget); shoot("budget") }
    @Test fun settings() { show(Screen.Configuration); shoot("settings") }
    @Test fun settingsEntering() { show(Screen.Configuration); scrollToAndTap("Entering"); shoot("settings_entering") }
    @Test fun bankSms() { show(Screen.MessageSettings); shoot("bank_sms") }
    @Test fun repeats() { show(Screen.Recurring); shoot("repeats") }
    @Test fun categories() { show(Screen.Categories(CategoryKind.EXPENSE)); shoot("categories") }
    @Test fun backup() { show(Screen.Backup); shoot("backup") }

    /** An expense being entered, with a fixed time so the form never reads the clock. */
    private val lunchDraft = TxnDraft(DraftType.EXPENSE, TODAY, "acc_card", categoryId = "cat_ex_0_1", amount = 45_000,
        note = "Lunch with team", timeOfDay = 13 * 60)
}
