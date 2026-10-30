package app.moneytracker

import app.moneytracker.ui.isPortablePinHash
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.moneytracker.data.BackupController
import app.moneytracker.data.MoneyManagerBackup
import app.moneytracker.data.MoneyManagerExport
import app.moneytracker.data.IosMoneyManagerSource
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.TabularImport
import app.moneytracker.data.LocalBackup
import app.moneytracker.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline backups:
 *  - automatic daily snapshot into app storage (keeps [KEEP] newest),
 *  - manual snapshot / restore from that list,
 *  - export & import of a full copy through the system file picker (any folder, Drive, USB …),
 *  - CSV / Excel export and import (rows mapped by [TabularImport]).
 * Must be constructed in Activity.onCreate (it registers activity-result launchers).
 */
class AndroidBackupController(
    private val activity: ComponentActivity,
    private val db: DatabaseManager,
    private val hashPin: (String) -> String,
    private val portablePinHash: (String) -> String?,
) : BackupController {

    private val dir = File(activity.filesDir, "backups").apply { mkdirs() }
    private val mutex = Mutex()
    private val _local = MutableStateFlow(list())
    private val _status = MutableStateFlow<String?>(null)
    private val _pending = MutableStateFlow<app.moneytracker.data.TabularImport.Parsed?>(null)
    override val pendingImport: StateFlow<app.moneytracker.data.TabularImport.Parsed?> = _pending

    override fun confirmImport(keep: Set<Int>) {
        val parsed = _pending.value ?: return
        _pending.value = null
        runWithMessage {
            val repo = db.repo ?: error(tr("Database not ready"))
            withContext(Dispatchers.IO) {
                snapshot("pre-import")
                val r = repo.importParsed(parsed.copy(rows = parsed.rows.filterIndexed { i, _ -> i in keep }))
                buildString {
                    append(tr("Imported {0} transactions", r.imported))
                    if (r.createdAccounts > 0) append(tr(", {0} new accounts", r.createdAccounts))
                    if (r.createdCategories > 0) append(tr(", {0} new categories", r.createdCategories))
                }
            }
        }
    }
    override fun cancelImport() { _pending.value = null }
    override val localBackups: StateFlow<List<LocalBackup>> = _local
    override val status: StateFlow<String?> = _status

    private val exportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { run(tr("Exported backup")) { exportTo(it) } } }

    private val importLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { runWithMessage { importFrom(it) } } }

    private val mmbakLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { run(tr("Exported for Money Manager")) { exportMoneyManagerTo(it) } } }

    private val csvLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { run(tr("Exported CSV")) { writeTable(it, excel = false) } } }

    private val excelLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME),
    ) { uri -> uri?.let { run(tr("Exported Excel file")) { writeTable(it, excel = true) } } }

    private val sheetImportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { runWithMessage { importTable(it) } } }

    private val photoExportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { runWithMessage { exportPhotosTo(it) } } }

    private val photoImportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { runWithMessage { importPhotosFrom(it) } } }

    override fun backupNow() = run(tr("Backup created")) { snapshot("manual") }

    override fun exportPhotos() = photoExportLauncher.launch("MoneyTracker-photos-${stamp()}.zip")
    override fun importPhotos() = photoImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))

    override fun reset(everything: Boolean) = run(if (everything) tr("Everything was reset (snapshot kept in the list)") else tr("Entries cleared (snapshot kept in the list)")) {
        snapshot("before-reset")
        val repo = db.repo ?: error(tr("Database not open"))
        repo.reset(everything)
        // Photos belong to the deleted entries; the snapshot keeps only their names, so leave the files for re-import.
    }

    override fun restoreLocal(name: String) = runWithMessage {
        val f = File(dir, name)
        DatabaseManager.validate(f)?.let { error(it) }
        listOfNotNull(tr("Restored {0}", name), replaceDatabaseWith(f)).joinToString(". ")
    }

    override fun deleteLocal(name: String) = run(tr("Deleted")) { File(dir, name).delete(); refresh() }

    override fun exportToFile() = exportLauncher.launch("MoneyTracker-${stamp()}.$EXT")
    override fun shareBackup() = runWithMessage {
        val file = withContext(Dispatchers.IO) {
            db.checkpoint()
            val dir = File(activity.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, "MoneyTracker-${stamp()}.$EXT").also { db.dbFile.copyTo(it, overwrite = true) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, tr("MoneyTracker backup {0}", stamp()))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(android.content.Intent.createChooser(send, tr("Share backup")))
        tr("Backup ready to share")
    }
    override fun importFromFile() = importLauncher.launch(arrayOf("*/*"))
    override fun exportCsv() = csvLauncher.launch("MoneyTracker-${stamp()}.csv")
    override fun exportMoneyManager() = mmbakLauncher.launch("MoneyTracker-${stamp()}.mmbak")
    override fun exportExcel() = excelLauncher.launch("MoneyTracker-${stamp()}.xlsx")
    override fun importSpreadsheet() = sheetImportLauncher.launch(arrayOf("text/*", XLSX_MIME, "application/vnd.ms-excel", "application/octet-stream"))
    override fun clearStatus() { _status.value = null }

    /** Called once at startup: snapshot if the newest automatic one is older than 24 h. */
    fun autoBackupIfDue() {
        val newestAuto = dir.listFiles { f -> f.name.startsWith("auto-") }?.maxOfOrNull { it.lastModified() } ?: 0L
        if (System.currentTimeMillis() - newestAuto > 24 * 60 * 60 * 1000L) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                mutex.withLock { runCatching { snapshot("auto") } }
            }
        }
    }

    // ───────────────────────── internals

    private fun run(success: String, block: suspend () -> Unit) = runWithMessage { block(); success }

    private fun runWithMessage(block: suspend () -> String) {
        activity.lifecycleScope.launch {
            mutex.withLock {
                _status.value = tr("Working…")
                _status.value = try {
                    withContext(Dispatchers.IO) { block() }
                } catch (e: Exception) {
                    tr("Failed: {0}", e.message ?: e::class.simpleName)
                }
            }
        }
    }

    private fun snapshot(kind: String): File {
        db.checkpoint()
        val out = File(dir, "$kind-${stamp()}.$EXT")
        db.dbFile.copyTo(out, overwrite = true)
        DatabaseManager.validate(out)?.let { out.delete(); error(it) }
        prune()
        refresh()
        return out
    }

    private fun exportTo(uri: Uri) {
        db.checkpoint()
        activity.contentResolver.openOutputStream(uri, "wt")?.use { out -> db.dbFile.inputStream().use { it.copyTo(out) } }
            ?: error(tr("Cannot write to the selected location"))
    }

    /** Builds the .mmbak in the cache (Money Manager restores it by opening it as its database), then copies it out. */
    private fun exportMoneyManagerTo(uri: Uri) {
        val repo = db.repo ?: error(tr("Database not ready"))
        val data = repo.moneyManagerData()
        val file = File(activity.cacheDir, "export.mmbak").also { it.delete() }
        // No localized collators: android_metadata is written by the exporter, as Money Manager's own files have it.
        val out = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            out.beginTransaction()
            try {
                MoneyManagerExport.write(SqliteRowSink(out), data, TimeZone.currentSystemDefault(), System.currentTimeMillis())
                out.setTransactionSuccessful()
            } finally { out.endTransaction() }
        } finally { out.close() }
        try {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: error(tr("Cannot write to the selected location"))
        } finally { file.delete() }
    }

    private fun exportPhotosTo(uri: Uri): String {
        val files = Photos.dir(activity).listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return tr("No photos to export")
        activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                files.forEach { f ->
                    zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error(tr("Cannot write to the selected location"))
        return tr("Exported {0} photos", files.size)
    }

    private fun importPhotosFrom(uri: Uri): String {
        val dir = Photos.dir(activity)
        var added = 0
        activity.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Flat names only: never follow paths out of the photos folder.
                    val name = File(e.name).name
                    if (!e.isDirectory && name.isNotBlank() && !name.startsWith(".")) {
                        val target = File(dir, name)
                        if (!target.exists()) { target.outputStream().use { zip.copyTo(it) }; added++ }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error(tr("Cannot read the selected file"))
        return tr("Imported {0} photos", added)
    }

    /** Restores our own backup, or converts a Money Manager (.mmbak) backup. */
    private suspend fun importFrom(uri: Uri): String {
        val tmp = File(activity.cacheDir, "import.$EXT")
        activity.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error(tr("Cannot read the selected file"))
        try {
            val ownProblem = DatabaseManager.validate(tmp)
            if (ownProblem == null) return listOfNotNull(tr("Restored backup"), replaceDatabaseWith(tmp)).joinToString(". ")
            val tables = runCatching { SqliteRowSource.open(tmp).use { it.tables() } }.getOrNull() ?: error(ownProblem)
            return when {
                MoneyManagerBackup.isMoneyManager(tables) -> restoreMoneyManager(tmp, ios = false)
                MoneyManagerBackup.isIosMoneyManager(tables) -> restoreMoneyManager(tmp, ios = true)
                else -> error(ownProblem)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Converts a Money Manager backup into this app's data (safety snapshot first). */
    private fun restoreMoneyManager(file: File, ios: Boolean): String {
        val repo = db.repo ?: error(tr("Database not ready"))
        // iPhone backups (Core Data) are first presented as Android tables, so one importer handles both.
        val data = SqliteRowSource.open(file).use { src ->
            if (!ios) MoneyManagerBackup.read(src)
            else IosMoneyManagerSource(src).let { require(it.supported) { tr("This iPhone Money Manager backup is too old to import") }; MoneyManagerBackup.read(it) }
        }
        if (data.txns.isEmpty() && data.accounts.isEmpty()) error(tr("The Money Manager backup is empty"))
        snapshot("pre-restore")
        val r = repo.replaceWithMoneyManager(data)
        // Same passcode as in Money Manager, so the lock screen keeps working after switching apps.
        data.passcodePin?.let { repo.putSetting(MoneyRepository.Keys.PASSCODE, hashPin(it)) }
        repo.generateRecurring()
        android.util.Log.i("MoneyTracker", "Money Manager restore: $r")
        return buildString {
            append(tr("Restored Money Manager backup: {0} transactions, {1} accounts", r.transactions, r.accounts))
            if (r.deletedAccounts > 0) append(tr(" (+{0} deleted, restorable)", r.deletedAccounts))
            append(tr(", {0} categories", r.categories))
            if (r.recurring > 0) append(tr(", {0} recurring", r.recurring))
            if (r.budgets > 0) append(tr(", {0} budget amounts", r.budgets))
            if (data.passcodePin != null) append(tr(", passcode lock"))
            if (r.skipped.isNotEmpty()) append(tr(". Skipped {0}: {1}", r.skipped.size, r.skipped.first()))
        }
    }

    /** Safety snapshot first, then swap files while the database is closed. */
    /** Swaps in [source]; returns a note when the backup's passcode had to give way to this install's. */
    private suspend fun replaceDatabaseWith(source: File): String? {
        snapshot("pre-restore")
        val staged = File(activity.cacheDir, "staged.$EXT")
        source.copyTo(staged, overwrite = true)
        val note = keepWorkingPasscode(staged)
        withContext(Dispatchers.Main) {
            db.withClosed { target ->
                File(target.path + "-wal").delete()
                File(target.path + "-shm").delete()
                File(target.path + "-journal").delete()
                staged.copyTo(target, overwrite = true)
            }
        }
        staged.delete()
        return note
    }

    /**
     * The backup's passcode comes along. Backups from before hashes carried their own salt hold one that only checks out
     * with the salt of the install that made it: it is paired with this install's salt (right when the backup came from
     * this phone), and the passcode in use until now is kept as a fallback, so a restore never locks you out.
     */
    private suspend fun keepWorkingPasscode(staged: File): String? {
        val key = MoneyRepository.Keys.PASSCODE
        val current = db.repo?.setting(key)?.first().orEmpty()
        val out = SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        fun put(k: String, v: String) = out.execSQL("INSERT OR REPLACE INTO Setting(key, value) VALUES (?, ?)", arrayOf(k, v))
        try {
            val restored = out.rawQuery("SELECT value FROM Setting WHERE key = ?", arrayOf(key))
                .use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
            put(MoneyRepository.Keys.PASSCODE_PREVIOUS, "")
            if (restored.isBlank() || isPortablePinHash(restored)) return null
            val portable = portablePinHash(restored)
            if (portable == null) {
                // This install never had a passcode, so the backup's can't be from here and can't be checked.
                put(key, current)
                return if (current.isBlank()) tr("The backup's passcode can't be used on this phone, so the passcode is off. Turn it on again under Passcode.")
                else tr("The backup's passcode can't be used on this phone, so your current passcode stays")
            }
            put(key, portable)
            if (current.isNotBlank() && current != restored) put(MoneyRepository.Keys.PASSCODE_PREVIOUS, portablePinHash(current) ?: current)
        } finally { out.close() }
        return null
    }

    private suspend fun exportRows(): List<List<String>> {
        val repo = db.repo ?: error(tr("Database not ready"))
        return TabularImport.exportRows(
            repo.allTxns(), repo.accounts.first() + repo.deletedAccounts.first(), repo.categories.first(),
            repo.currency.first(), repo.currencies.first(), repo.txnTagNames.first(),
        )
    }

    private suspend fun writeTable(uri: Uri, excel: Boolean) {
        val rows = exportRows()
        activity.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            if (excel) Xlsx.write(rows, out) else out.write(("\uFEFF" + TabularImport.toCsv(rows)).toByteArray())
        } ?: error(tr("Cannot write to the selected location"))
    }

    /** Adds the file's transactions to the current data (after a safety snapshot). */
    private fun importTable(uri: Uri): String {
        val repo = db.repo ?: error(tr("Database not ready"))
        val name = activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }.orEmpty().lowercase()
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error(tr("Cannot read the selected file"))
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val rows = when {
            isZip || name.endsWith(".xlsx") -> Xlsx.read(bytes.inputStream())
            name.endsWith(".xls") -> error(tr("Old .xls files aren't supported. Save as .xlsx or .csv and try again."))
            else -> TabularImport.parseCsv(decodeText(bytes))
        }
        val parsed = repo.parseRows(rows)
        if (parsed.rows.isEmpty()) error(parsed.errors.firstOrNull() ?: tr("No transactions found in the file"))
        _pending.value = parsed
        return tr("Read {0} rows — review them to import", parsed.rows.size)
    }

    /** UTF-8 (with or without BOM) or UTF-16 (as written by some spreadsheet apps). */
    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE).drop(1)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE).drop(1)
        else -> String(bytes, Charsets.UTF_8)
    }

    private fun prune() {
        dir.listFiles()?.groupBy { it.name.substringBefore('-') }?.values?.forEach { files ->
            files.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
        }
    }

    private fun refresh() { _local.value = list() }

    private fun list(): List<LocalBackup> =
        dir.listFiles { f -> f.extension == EXT || f.extension == OLD_EXT }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { LocalBackup(it.name, it.length(), it.lastModified()) }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        const val KEEP = 10
        /** Our own backup format (a copy of the database). Restore tells formats apart by content, not by name. */
        const val EXT = "mtbak"
        /** Backups made before 0.1.7; still listed and restorable. */
        const val OLD_EXT = "mmdb"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
