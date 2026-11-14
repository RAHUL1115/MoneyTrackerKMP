package app.moneytracker.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * A database flow that remembers its latest value, so a screen can draw real data on its first frame instead of
 * an empty list until the query answers. Collecting it still runs the live query (and keeps [current] fresh);
 * [loadNow] reads synchronously the first time nothing has been seen yet. Plain Kotlin: screens collect it with
 * app.moneytracker.core.collectAsState, which starts from [current].
 */
class CachedFlow<T>(private val source: Flow<T>, private val loadNow: () -> T) : Flow<T> {
    @kotlin.concurrent.Volatile private var last: Any? = Unset

    /** The latest value seen, or a synchronous read when there is none yet. */
    @Suppress("UNCHECKED_CAST")
    val current: T get() = last.let { if (it === Unset) loadNow().also { v -> last = v } else it as T }

    override suspend fun collect(collector: FlowCollector<T>) = source.collect { last = it; collector.emit(it) }

    /** A fresh synchronous read with the same mapping as the flow. Blocking; any thread (web dashboard, widgets). */
    fun now(): T = loadNow().also { last = it }

    private object Unset
}

/**
 * Keeps the most recently used [CachedFlow]s per key (e.g. a month's rows), so going back to them is instant.
 * Main thread only: background code reads through the repository's `…Now()` functions, which never touch it.
 */
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
