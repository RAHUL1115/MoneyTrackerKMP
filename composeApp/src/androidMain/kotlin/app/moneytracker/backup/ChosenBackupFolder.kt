package app.moneytracker.backup

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import app.moneytracker.core.Log
import app.moneytracker.core.tr
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The folder the person picked for extra copies of their backups (any document provider: phone storage, Drive, USB …).
 * It remembers the folder, keeps the permission to write there, and copies snapshots in.
 */
internal class ChosenBackupFolder(private val resolver: ContentResolver, private val prefs: SharedPreferences) {
    private val _label = MutableStateFlow(savedTree()?.let { folderLabel(it) })
    private val _problem = MutableStateFlow(false)

    /** The folder as the person knows it ("Documents/MoneyTracker"), or null when none is chosen. */
    val label: StateFlow<String?> = _label
    /** The last copy into the folder failed (moved, unplugged, permission gone). */
    val problem: StateFlow<Boolean> = _problem

    /** Remembers [tree] in place of any earlier folder. */
    fun choose(tree: Uri) {
        resolver.takePersistableUriPermission(tree, ACCESS)
        prefs.getString(FOLDER_KEY, null)?.takeIf { it != tree.toString() }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), ACCESS) }
        }
        prefs.edit().putString(FOLDER_KEY, tree.toString()).apply()
        _label.value = folderLabel(tree)
        _problem.value = false
    }

    fun clear() {
        prefs.getString(FOLDER_KEY, null)?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), ACCESS) }
        }
        prefs.edit().remove(FOLDER_KEY).apply()
        _label.value = null
        _problem.value = false
    }

    /**
     * Copies [file] into the folder as MoneyTracker-<name> and keeps the newest [AndroidBackupController.KEEP] of that
     * [kind] there. Any document provider can be behind the folder and each throws its own exceptions, so every
     * failure is logged and shown as [problem] instead of failing the snapshot.
     */
    @Suppress("TooGenericExceptionCaught")
    fun copyIn(file: File, kind: String) {
        val tree = savedTree() ?: return
        try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            writeCopy(tree, parentId, file)
            pruneCopies(tree, parentId, kind)
            _problem.value = false
        } catch (e: Exception) {
            Log.w("Backup", e) { "copy to the chosen folder failed" }
            _problem.value = true
        }
    }

    private fun savedTree(): Uri? = prefs.getString(FOLDER_KEY, null)?.let { Uri.parse(it) }

    private fun writeCopy(tree: Uri, parentId: String, file: File) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val target = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "MoneyTracker-${file.name}") ?: error("create")
        resolver.openOutputStream(target, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("write")
    }

    /** Deletes all but the newest copies of [kind] (only files this app named). */
    private fun pruneCopies(tree: Uri, parentId: String, kind: String) {
        // Names carry the time (yyyyMMdd-HHmmss), so sorting by name is newest-last.
        copiesOf(tree, parentId, kind).sortedByDescending { it.second }.drop(AndroidBackupController.KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /** (document id, name) of this app's copies of [kind] in the folder. */
    private fun copiesOf(tree: Uri, parentId: String, kind: String): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mine = ArrayList<Pair<String, String>>()
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name != null && isCopyOf(name, kind)) mine.add(c.getString(0) to name)
            }
        }
        return mine
    }

    /** "primary:Documents/MoneyTracker" → "Documents/MoneyTracker"; other providers (Drive…) → the folder's name. */
    private fun folderLabel(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull().orEmpty()
        if (id.startsWith("primary:")) return id.removePrefix("primary:").ifEmpty { tr("Phone storage") }
        val doc = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
        val name = doc?.let { d ->
            runCatching {
                resolver.query(d, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        return name ?: id.substringAfter(':').ifEmpty { tr("Chosen folder") }
    }

    companion object {
        private const val FOLDER_KEY = "folderUri"
        private const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** Whether [name] is one of this app's copies of a [kind] snapshot ("MoneyTracker-auto-20261107-0930.mtbak"). */
        fun isCopyOf(name: String, kind: String): Boolean =
            name.startsWith("MoneyTracker-$kind-") && name.endsWith(".${AndroidBackupController.EXT}")
    }
}
