package app.moneytracker.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Calls [onChange] after every write to any table on [driver]: once per transaction, on the writing thread.
 * For what lives outside the screens but shows the data (home-screen widgets), so no save path has to remember to
 * tell it. Writes made through the generated queries count; raw SQL on the driver doesn't. Returns a function that
 * stops watching.
 */
fun watchAllTables(driver: SqlDriver, onChange: () -> Unit): () -> Unit {
    val tables = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", { c ->
        val names = mutableListOf<String>()
        while (c.next().value) c.getString(0)?.let(names::add)
        QueryResult.Value(names)
    }, 0).value.toTypedArray()
    val listener = Query.Listener { onChange() }
    driver.addListener(*tables, listener = listener)
    return { driver.removeListener(*tables, listener = listener) }
}
