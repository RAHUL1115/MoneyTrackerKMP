package app.moneytracker.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Calls [onChange] after every write to any table on [driver]: once per transaction, on the writing thread.
 * For what lives outside the screens but shows the data (home-screen widgets), so no save path has to remember to
 * tell it. Writes made through the generated queries count; raw SQL on the driver doesn't.
 *
 * A write to the settings table counts only when a setting outside [ignoredSettings] actually changed, so
 * bookkeeping (when the inbox was last read) and saving a value that's already there don't count.
 * Returns a function that stops watching.
 */
fun watchAllTables(driver: SqlDriver, ignoredSettings: Set<String> = emptySet(), onChange: () -> Unit): () -> Unit {
    val tables = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", { c ->
        val names = mutableListOf<String>()
        while (c.next().value) c.getString(0)?.let(names::add)
        QueryResult.Value(names)
    }, 0).value
    val others = tables.filter { it != SETTINGS_TABLE }.toTypedArray()
    val listener = Query.Listener { onChange() }
    driver.addListener(*others, listener = listener)

    if (SETTINGS_TABLE !in tables) return { driver.removeListener(*others, listener = listener) }
    // Compared on the writing thread; two writers racing can at worst cause one extra call.
    var last = settingsSnapshot(driver, ignoredSettings)
    val settingsListener = Query.Listener {
        val now = settingsSnapshot(driver, ignoredSettings)
        if (now != last) { last = now; onChange() }
    }
    driver.addListener(SETTINGS_TABLE, listener = settingsListener)
    return {
        driver.removeListener(*others, listener = listener)
        driver.removeListener(SETTINGS_TABLE, listener = settingsListener)
    }
}

private const val SETTINGS_TABLE = "Setting"

private fun settingsSnapshot(driver: SqlDriver, ignored: Set<String>): Map<String, String?> =
    driver.executeQuery(null, "SELECT key, value FROM $SETTINGS_TABLE", { c ->
        val rows = mutableMapOf<String, String?>()
        while (c.next().value) { val k = c.getString(0) ?: continue; if (k !in ignored) rows[k] = c.getString(1) }
        QueryResult.Value(rows)
    }, 0).value
