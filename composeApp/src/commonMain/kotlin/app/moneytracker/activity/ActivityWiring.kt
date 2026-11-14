package app.moneytracker.activity

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import app.moneytracker.app.LocalRepo
import app.moneytracker.data.MoneyRepository

/*
 * Builds the Activity tab's holders for both layouts. Each view's holder gets its own scope, so its queries stop
 * when the view is left.
 */

/** The Activity tab's holder; its month and quick filter survive the tab being covered or recreated. */
@Composable
fun rememberActivityModel(): ActivityModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    fun create(saved: List<Int>?) = ActivityModel(
        repo.settingsStore, repo.categoryStore, repo.accountStore, repo.tagStore, repo.captureStore, repo.today, scope, saved,
    )
    return rememberSaveable(repo, saver = ActivityModel.saver(::create)) { create(null) }
}

/** The entry lists every Activity view reads. */
fun MoneyRepository.entryFeed() =
    EntryFeed(txnStore, reportStore, categoryStore, accountStore, tagStore, photoStore, settingsStore, io)

@Composable
fun rememberEntryListModel(activity: ActivityModel): EntryListModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, activity) {
        EntryListModel(activity.state, repo.entryFeed(), repo.repeatStore, repo.currencyStore, repo.settingsStore, scope)
    }
}

@Composable
fun rememberCalendarModel(activity: ActivityModel): CalendarModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, activity) { CalendarModel(activity.state, repo.entryFeed(), scope) }
}

@Composable
fun rememberMonthsModel(activity: ActivityModel): MonthsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo, activity) { MonthsModel(activity.state, repo.entryFeed(), repo.views, repo.today, scope) }
}

/**
 * A horizontal swipe of more than a short distance calls [onStep] with +1 (towards the left: next) or −1 (previous).
 * [keys] restart the gesture detector when what a step means changes.
 */
fun Modifier.swipeSteps(vararg keys: Any?, onStep: (Int) -> Unit): Modifier = pointerInput(*keys) {
    var dx = 0f
    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
        val dir = when {
            dx < -120f -> 1
            dx > 120f -> -1
            else -> 0
        }
        if (dir != 0) onStep(dir)
    }) { _, amount -> dx += amount }
}
