package app.moneytracker.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import app.moneytracker.ui.Screen
import org.junit.Test

/** Shots that scroll a long list to its end or long-press an entry before saving. */
abstract class ActivityStateShots(newLayout: Boolean) : ScreenshotTest(newLayout) {
    /** Scrolls every vertical list on screen to its end, which lands on the same place every time. */
    protected fun scrollToEnd() {
        val lists = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        repeat(SCROLLS_TO_END) {
            lists.fetchSemanticsNodes().indices.forEach { i -> lists[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, FAR) } }
            compose.waitForIdle()
        }
    }

    /** Long-presses the first element showing [text] (starts selecting entries). */
    protected fun longPress(text: String) {
        compose.onAllNodesWithText(text).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private companion object {
        const val SCROLLS_TO_END = 3
        const val FAR = 100_000f
    }
}

/** Activity, search and calendar states the main shots don't reach: list ends, an open month, selecting entries. */
class NewActivityStateScreenshots : ActivityStateShots(newLayout = true) {
    @Test fun activityListEnd() { show(); tap("Activity"); scrollToEnd(); shoot("activity_list_end") }
    @Test fun activitySelecting() { show(); tap("Activity"); longPress("Taxi"); shoot("activity_selecting") }
    @Test fun activityCalendarDayEnd() { show(); tap("Activity"); tap("Calendar"); scrollToEnd(); shoot("activity_calendar_day_end") }
    @Test fun activityMonthsOpen() { show(); tap("Activity"); tap("Months"); scrollToEnd(); shoot("activity_months_open") }
    @Test fun searchEnd() { show(Screen.Search); type("Coffee"); scrollToEnd(); shoot("search_end") }
}

/** The classic Daily list's end and its selection mode. */
class ClassicActivityStateScreenshots : ActivityStateShots(newLayout = false) {
    @Test fun transactionsDailyEnd() { show(); scrollToEnd(); shoot("transactions_daily_end") }
    @Test fun transactionsSelecting() { show(); longPress("Taxi"); shoot("transactions_selecting") }
}
