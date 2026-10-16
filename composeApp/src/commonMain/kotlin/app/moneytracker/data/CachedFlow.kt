package app.moneytracker.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * A database flow that remembers its latest value, so a screen can draw real data on its first frame instead of
 * an empty list until the query answers. Collecting it still runs the live query (and keeps [current] fresh);
 * [loadNow] reads synchronously the first time nothing has been seen yet.
 */
class CachedFlow<T>(private val source: Flow<T>, private val loadNow: () -> T) : Flow<T> {
    @kotlin.concurrent.Volatile private var last: Any? = Unset

    /** The latest value seen, or a synchronous read when there is none yet. */
    @Suppress("UNCHECKED_CAST")
    val current: T get() = last.let { if (it === Unset) loadNow().also { v -> last = v } else it as T }

    override suspend fun collect(collector: FlowCollector<T>) = source.collect { last = it; collector.emit(it) }

    /**
     * Used instead of Compose's `Flow.collectAsState(initial)` (a member wins over that extension), so existing
     * `collectAsState(emptyList())` calls start from [current]; [initial] is ignored.
     */
    @Composable
    fun collectAsState(@Suppress("UNUSED_PARAMETER") initial: T): State<T> =
        (this as Flow<T>).collectAsState(remember(this) { current })

    private object Unset
}

/** Keeps the most recently used [CachedFlow]s per key (e.g. a month's rows), so going back to them is instant. Main thread only. */
internal class FlowCache(private val max: Int) {
    private val map = LinkedHashMap<String, CachedFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, create: () -> CachedFlow<T>): CachedFlow<T> {
        val hit = map.remove(key) as CachedFlow<T>?
        return (hit ?: create()).also {
            map[key] = it   // re-insert as most recent
            while (map.size > max) map.remove(map.keys.first())
        }
    }
}
