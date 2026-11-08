package app.moneytracker.data

import kotlinx.coroutines.flow.StateFlow

data class LocalBackup(val name: String, val sizeBytes: Long, val createdAtMillis: Long)

/**
 * Offline backup and data exchange. The platform implements the file I/O (Android: app-private
 * snapshots + Storage Access Framework); the UI only talks to this. Row conversion is shared
 * ([TabularImport]) so CSV and Excel behave identically.
 */
interface BackupController {
    /** Snapshots kept in app storage, newest first. */
    val localBackups: StateFlow<List<LocalBackup>>

    /** One-line status for the UI (last result or error), null when idle. */
    val status: StateFlow<String?>

    fun backupNow()

    /**
     * The folder (picked once by the person) where daily and manual backups are also saved, so file managers, cloud
     * apps and a reinstall can reach them. Shown as a readable path; null when none is chosen. [backupFolderProblem]
     * is set when the last copy there failed (folder removed or access taken away).
     */
    val backupFolder: StateFlow<String?>
    val backupFolderProblem: StateFlow<Boolean>
    /** Opens the system folder picker; the chosen folder gets a copy of the newest backup right away. */
    fun pickBackupFolder()
    /** Stops saving to the folder (files already there are left alone). */
    fun clearBackupFolder()
    fun restoreLocal(name: String)
    fun deleteLocal(name: String)

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

    /** Picks a .csv/.tsv/.xlsx file and reads it into [pendingImport] for review. */
    fun importSpreadsheet()

    /** Rows read from a spreadsheet, waiting for the user to confirm (import preview). */
    val pendingImport: StateFlow<TabularImport.Parsed?>
    /** Saves the rows at [keep] (indexes into [pendingImport]'s rows). */
    fun confirmImport(keep: Set<Int>)
    fun cancelImport()

    /** Receipt photos as a .zip (database backups hold only their file names). */
    fun exportPhotos()
    /** Adds photos from such a .zip; existing files are kept. */
    fun importPhotos()

    /** Takes a safety snapshot, then clears entries ([everything] = also accounts, categories and settings). */
    fun reset(everything: Boolean)

    fun clearStatus()
}
