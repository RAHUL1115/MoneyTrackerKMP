package app.moneytracker.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.moneytracker.app.Screen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Test

/** [ScreenshotTest] plus scrolling without a tap and shots that include sheets. */
abstract class MoneyScreenshotTest(private val newLayout: Boolean) : ScreenshotTest(newLayout) {
    /** Scrolls down until the first element showing [text] is on screen, without tapping it. */
    protected fun scrollTo(text: String) {
        compose.onAllNodesWithText(text).onFirst().performScrollTo()
        compose.waitForIdle()
    }

    /** Scrolls to and taps the first element whose text contains [text] (a name after its emoji). */
    protected fun tapContaining(text: String) {
        compose.onAllNodesWithText(text, substring = true).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Swipes the screen's list up until it can go no further. */
    protected fun scrollToEnd() = scrollToEndOf(hasScrollAction())

    private fun scrollToEndOf(list: SemanticsMatcher) {
        repeat(SWIPES_TO_END) { compose.onAllNodes(list).onFirst().performTouchInput { swipeUp() } }
        compose.waitForIdle()
    }

    /** Scrolls the list that shows [text] all the way down in one jump, whatever its rows look like. */
    protected fun jumpToEndPast(text: String) {
        compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(text))).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, FAR_DOWN) }
        compose.waitForIdle()
    }

    /** Taps the first element whose content description is [description] (an icon button). */
    protected fun tapIcon(description: String) {
        compose.onAllNodesWithContentDescription(description).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Saves every window, sheets included, as `<layout>/<name>.png`. */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun shootScreen(name: String) = captureScreenRoboImage("${if (newLayout) "new" else "classic"}/$name.png")

    private companion object {
        const val SWIPES_TO_END = 6
        const val FAR_DOWN = 100_000f
    }
}

/** The new layout's money screens in the states the main shots don't reach: other tabs, scrolled parts, sheets, forms. */
class NewMoneyScreenshots : MoneyScreenshotTest(newLayout = true) {
    @Test fun accountDetailMonthly() { show(Screen.AccountDetail("acc_bank")); tap("Monthly"); scrollTo("Balance"); shoot("account_detail_monthly") }
    @Test fun accountDetailCard() { show(Screen.AccountDetail("acc_card")); tap("Statement"); shoot("account_detail_card") }
    @Test fun accountEdit() { show(Screen.EditAccount("acc_card")); shoot("account_edit") }
    @Test fun accountEditSwitches() { show(Screen.EditAccount("acc_card")); scrollToEnd(); shoot("account_edit_switches") }
    @Test fun manageAccounts() { show(); tap("Accounts"); tapIcon("Account groups"); shootScreen("manage_accounts") }
    @Test fun insightsBreakdown() { show(); tap("Insights"); tapContaining("Food"); scrollTo("Top entries"); shoot("insights_breakdown") }
    @Test fun insightsTopEntries() { show(); tap("Insights"); jumpToEndPast("Top entries"); shoot("insights_top_entries") }
    @Test fun budgetTotalOpen() { show(Screen.Budget); tap(" · Edit"); shoot("budget_total_open") }
    @Test fun budgetCategoryOpen() { show(Screen.Budget); tapContaining("Transport"); jumpToEndPast("Month overview"); shoot("budget_category_open") }
    @Test fun budgetAddSheet() { show(Screen.Budget); tap("+ Category"); shootScreen("budget_add_sheet") }
}

/** The classic layout's money screens that the main shots don't reach. */
class ClassicMoneyScreenshots : MoneyScreenshotTest(newLayout = false) {
    @Test fun accountDetail() { show(Screen.AccountDetail("acc_bank")); shoot("account_detail") }
    @Test fun accountEdit() { show(Screen.EditAccount("acc_card")); shoot("account_edit") }
    @Test fun accountList() { show(Screen.AccountList); shoot("account_list") }
    @Test fun accountGroups() { show(Screen.AccountGroups); shoot("account_groups") }
    @Test fun statsBreakdown() { show(); tap("Stats"); scrollToEnd(); shoot("stats_breakdown") }
    @Test fun budget() { show(Screen.Budget); shoot("budget") }
}
