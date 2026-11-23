package app.moneytracker.activity.fresh

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import app.moneytracker.activity.swipeSteps
import kotlinx.coroutines.delay

/** Whether the latest page change came from a swipe; only swipes animate (taps, arrows and pickers change instantly). */
internal class SwipeFeedback {
    var fromSwipe by mutableStateOf(false)
}

@Composable
internal fun rememberSwipeFeedback() = remember { SwipeFeedback() }

/**
 * Swipeable pages: nothing moves while dragging; when a swipe counts, [onStep] gets +1 (swiped left: next) or −1 and
 * the new page slides in from that side. Changes from anything else (tabs, arrows, pickers) swap instantly. [order]
 * ranks each [page] for the slide direction; [enabled] turns the gesture off (e.g. while selecting entries).
 */
@Composable
internal fun <T> SwipePages(
    page: T,
    order: (T) -> Int,
    enabled: Boolean,
    feedback: SwipeFeedback,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    SwipeSlide(page, order, feedback, modifier.swipeGesture(enabled, feedback, page, onStep), content)
}

/** The swipe gesture alone, for when the swiped area is bigger than the part that slides (see [SwipeSlide]). */
internal fun Modifier.swipeGesture(enabled: Boolean, feedback: SwipeFeedback, key: Any?, onStep: (Int) -> Unit): Modifier =
    if (enabled) swipeSteps(key) { dir -> feedback.fromSwipe = true; onStep(dir) } else this

/** The sliding part alone: after a swipe the new [page] slides in from that side; any other change swaps instantly. */
@Composable
internal fun <T> SwipeSlide(page: T, order: (T) -> Int, feedback: SwipeFeedback, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    // The swipe flag lasts for this one transition, so a later tap or arrow press swaps instantly.
    LaunchedEffect(page) { if (feedback.fromSwipe) { delay(260); feedback.fromSwipe = false } }
    Box(modifier.clipToBounds()) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = (order(targetState) - order(initialState)).coerceIn(-1, 1)
                // Pages draw current data, so the old copy only fades; the new one slides in from the swipe side.
                if (!feedback.fromSwipe || dir == 0) EnterTransition.None togetherWith ExitTransition.None
                else (slideInHorizontally(tween(200)) { w -> dir * w / 4 } + fadeIn(tween(200))) togetherWith fadeOut(tween(80))
            },
            modifier = Modifier.fillMaxSize(),
            label = "swipe pages",
        ) { p -> content(p) }
    }
}
