package app.moneytracker.core

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * A scope for work that must finish after the screen that started it has closed: Undo after a delete, a save when
 * leaving. It is never cancelled, and one failed job doesn't stop the others. Runs on [context] (the main thread
 * unless given), without the job of whatever scope [context] came from.
 */
internal fun outlivingScope(context: CoroutineContext = Dispatchers.Main): CoroutineScope =
    CoroutineScope(context.minusKey(Job) + SupervisorJob())
