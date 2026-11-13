package app.moneytracker.data

import kotlinx.coroutines.flow.StateFlow

data class LocalBackup(val name: String, val sizeBytes: Long, val createdAtMillis: Long)

/** The one-line status every backup action reports to. */
interface BackupStatus {
    /** The last result or error, for the UI; null when idle. */
    val message: StateFlow<String?>
    fun clearStatus()
}

/** Backups kept in app storage. */
interface SavedBackups {
    /** Saved backups, newest first. */
    val localBackups: StateFlow<List<LocalBackup>>
    fun backupNow()
    fun restoreLocal(name: String)
    fun deleteLocal(name: String)
    /** Takes a safety snapshot, then clears entries ([everything] = also accounts, categories and settings). */
    fun reset(everything: Boolean)
}

/**
 * The folder (picked once by the person) where daily and manual backups are also saved, so file managers, cloud
 * apps and a reinstall can reach them.
 */
interface BackupFolder {
    /** Shown as a readable path; null when none is chosen. */
    val backupFolder: StateFlow<String?>
    /** Set when the last copy there failed (folder removed or access taken away). */
    val backupFolderProblem: StateFlow<Boolean>
    /** Opens the system folder picker; the chosen folder gets a copy of the newest backup right away. */
    fun pickBackupFolder()
    /** Stops saving to the folder (files already there are left alone). */
    fun clearBackupFolder()
}

/** Backups and exports through the system file picker and share sheet. */
interface FileTransfer {
    /** Opens a system "save as" dialog and writes a full database copy (.mtbak). */
    fun exportToFile()
    /** Sends a full database copy through the share sheet (e-mail, messaging, file apps). */
    fun shareBackup()
    /** Opens a system file picker, validates the file and replaces the current data. */
    fun importFromFile()
    /** Writes a Money Manager backup (.mmbak) through a "save as" dialog, for restoring in that app. */
    fun exportMoneyManager()
    /** Writes all transactions as CSV / Excel (.xlsx) through a "save as" dialog. */
    fun exportCsv()
    fun exportExcel()
}

/** CSV and Excel import, with a preview the person confirms. Row conversion is shared ([TabularImport]). */
interface SpreadsheetImport {
    /** Picks a .csv/.tsv/.xlsx file and reads it into [pendingImport] for review. */
    fun importSpreadsheet()
    /** Rows read from a spreadsheet, waiting for the user to confirm (import preview). */
    val pendingImport: StateFlow<TabularImport.Parsed?>
    /** Saves the rows at [keep] (indexes into [pendingImport]'s rows). */
    fun confirmImport(keep: Set<Int>)
    fun cancelImport()
}

/** Receipt photos as a .zip (database backups hold only their file names). */
interface PhotoArchive {
    fun exportPhotos()
    /** Adds photos from such a .zip; existing files are kept. */
    fun importPhotos()
}

/**
 * Offline backup and data exchange, one small capability per field. The platform implements the file I/O
 * (Android: app-private backups + Storage Access Framework); the UI only talks to these.
 */
data class Backups(
    val status: BackupStatus,
    val saved: SavedBackups,
    val folder: BackupFolder,
    val files: FileTransfer,
    val spreadsheet: SpreadsheetImport,
    val photos: PhotoArchive,
)
