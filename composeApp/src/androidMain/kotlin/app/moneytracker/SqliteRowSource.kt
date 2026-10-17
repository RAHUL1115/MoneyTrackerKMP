package app.moneytracker

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import app.moneytracker.data.RowSink
import app.moneytracker.data.RowSource
import java.io.File

/** Read-only view of another SQLite file (e.g. a Money Manager .mmbak) for the shared importers. */
class SqliteRowSource private constructor(private val db: SQLiteDatabase) : RowSource, AutoCloseable {
    override fun tables(): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    override fun rows(table: String): List<Map<String, String?>> =
        db.rawQuery("SELECT * FROM \"${table.replace("\"", "")}\"", null).use { c ->
            val cols = c.columnNames
            buildList {
                while (c.moveToNext()) add(cols.indices.associate { i -> cols[i] to (if (c.isNull(i)) null else c.getString(i)) })
            }
        }

    override fun close() = db.close()

    companion object {
        fun open(file: File) = SqliteRowSource(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
    }
}

/** Writes rows into an SQLite file being built (the Money Manager export). */
class SqliteRowSink(private val db: SQLiteDatabase) : RowSink {
    override fun exec(sql: String) = db.execSQL(sql)
    override fun insert(table: String, values: Map<String, Any?>) {
        val cv = ContentValues()
        values.forEach { (k, v) ->
            when (v) {
                null -> cv.putNull(k)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Double -> cv.put(k, v)
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> cv.put(k, v.toString())
            }
        }
        db.insertOrThrow(table, null, cv)
    }
}
