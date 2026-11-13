package app.moneytracker.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.moneytracker.db.MoneyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * What every store shares: the database and its queries, the dispatcher live queries run on, the clock, and the
 * cache of recently viewed periods. Transactions run straight on the database, so nested store writes join them.
 */
class Db(
    private val database: MoneyDatabase,
    val io: CoroutineDispatcher,
    val now: () -> Long,
    val today: () -> LocalDate,
) : Transacter by database {
    val q get() = database.moneyQueries

    /** The most recently viewed periods' rows (months, years, custom ranges), so switching back to one is instant. */
    internal val rangeCache = FlowCache(max = 24)

    /** A live list query that also remembers its latest result (see [CachedFlow]). */
    fun <R : Any, T> cached(query: () -> Query<R>, map: (List<R>) -> T): CachedFlow<T> =
        CachedFlow(query().asFlow().mapToList(io).map { map(it) }) { map(query().executeAsList()) }

    /** Saves a new order in one transaction: [setOrder] writes each id's position (0 = first) and the change time. */
    fun saveOrder(idsInOrder: List<String>, setOrder: (position: Long, changedAt: Long, id: String) -> Unit) =
        transaction { idsInOrder.forEachIndexed { i, id -> setOrder(i.toLong(), now(), id) } }
}
