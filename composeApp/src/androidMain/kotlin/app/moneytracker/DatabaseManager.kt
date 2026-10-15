package app.moneytracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.moneytracker.data.MoneyRepository
import app.moneytracker.db.MoneyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the SQLite connection. [repo] is Compose state: it becomes null while the database file
 * is being replaced (restore), so the UI tears down its queries before the driver closes.
 */
class DatabaseManager(private val context: Context) {
    private var driver: SqlDriver? = null

    var repo: MoneyRepository? by mutableStateOf(null)
        private set

    val dbFile: File get() = context.getDatabasePath(DB_NAME)

    fun open(): MoneyRepository {
        repo?.let { return it }
        val d = AndroidSqliteDriver(MoneyDatabase.Schema, context, DB_NAME)
        driver = d
        val r = MoneyRepository(MoneyDatabase(d), Dispatchers.IO)
        r.seedIfEmpty()
        r.generateRecurring()
        repo = r
        return r
    }

    /** Flushes the write-ahead log into the main file so a plain file copy is a complete snapshot. */
    fun checkpoint() {
        driver?.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { QueryResult.Value(Unit) }, 0)
    }

    /**
     * Hides the repository from the UI, waits for screens to drop their live queries, closes the
     * database, runs [block] with exclusive access to the file, then reopens. Call on the main thread.
     */
    suspend fun <T> withClosed(block: (File) -> T): T {
        repo = null
        delay(400)
        driver?.close()
        driver = null
        try {
            return withContext(Dispatchers.IO) { block(dbFile) }
        } finally {
            open()
        }
    }

    companion object {
        const val DB_NAME = "money.db"

        /** Returns null when [file] is a usable backup of this app's database, else the reason it isn't. */
        fun validate(file: File): String? = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val ok = db.rawQuery("PRAGMA quick_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                if (!ok) return "File is corrupted"
                val tables = db.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('Txn','Account','Category','AccountGroup')",
                    null,
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                if (tables != 4) return "Not a MoneyTracker backup"
                if (db.version > MoneyDatabase.Schema.version) return "Backup is from a newer app version"
                null
            }
        } catch (e: Exception) {
            "Not a valid database file"
        }
    }
}
