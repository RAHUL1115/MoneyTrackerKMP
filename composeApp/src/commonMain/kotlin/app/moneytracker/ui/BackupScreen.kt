package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.Backups
import app.moneytracker.data.DraftType
import app.moneytracker.data.LocalBackup
import app.moneytracker.data.moneymanager.TabularImport
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.BorderColor
import app.moneytracker.designsystem.classic.DangerColor
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.Ink
import app.moneytracker.designsystem.classic.PageBackground
import app.moneytracker.designsystem.classic.RowCheckbox
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.classic.short

/** Classic backup: snapshots, the backup folder, files in and out, Money Manager export and reset. */
@Composable
fun BackupScreen() {
    val nav = LocalNavigator.current
    val backups = LocalBackups.current
    val local by backups.saved.localBackups.collectAsState()
    val pendingImport by backups.spreadsheet.pendingImport.collectAsState()
    var restoring by remember { mutableStateOf<LocalBackup?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<Boolean?>(null) }   // true = everything, false = entries only

    pendingImport?.let { ImportPreviewDialog(it, onCancel = backups.spreadsheet::cancelImport, onImport = backups.spreadsheet::confirmImport) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Backup"), nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                BackupFilesCard(backups) { confirmImport = true }
                MoneyManagerCard(backups)
                ResetCard { resetting = it }
                ConfigSection(tr("Snapshots on this device"))
            }
            if (local.isEmpty()) item { Text(tr("No snapshots yet."), Modifier.padding(24.dp), color = SubtleText) }
            items(local, key = { it.name }) { b ->
                SnapshotLine(b, onRestore = { restoring = b }) { backups.saved.deleteLocal(b.name) }
                HorizontalDivider(color = DividerColor)
            }
        }
    }

    restoring?.let { b ->
        RestoreConfirm(tr("Restore “{0}”?", backupLabel(b.name)), onDismiss = { restoring = null }) {
            restoring = null; backups.saved.restoreLocal(b.name)
        }
    }
    resetting?.let { everything -> ResetConfirm(everything, onDismiss = { resetting = null }) { resetting = null; backups.saved.reset(everything) } }
    if (confirmImport) {
        RestoreConfirm(tr("Restore from a file?"), onDismiss = { confirmImport = false }) {
            confirmImport = false; backups.files.importFromFile()
        }
    }
}

/** The backup folder and every way in and out: back up, export, share, restore, CSV / Excel and photos. */
@Composable
private fun BackupFilesCard(backups: Backups, onRestoreFile: () -> Unit) {
    val status by backups.status.message.collectAsState()
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("A snapshot is taken automatically once a day (last 10 kept). Choose a backup folder and each one is " +
            "also saved there, where file managers and cloud apps can reach it and it survives uninstalling the app."),
            fontSize = 13.sp, color = SubtleText)
        BackupFolderRow(backups)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(tr("Back up now"), Modifier.weight(1f), filled = true, onClick = backups.saved::backupNow)
            BackupButton(tr("Export file"), Modifier.weight(1f), onClick = backups.files::exportToFile)
        }
        BackupButton(tr("Share backup (e-mail, chat…)"), Modifier.fillMaxWidth(), onClick = backups.files::shareBackup)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(tr("Restore file"), Modifier.weight(1f), onClick = onRestoreFile)
            BackupButton(tr("Export CSV"), Modifier.weight(1f), onClick = backups.files::exportCsv)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(tr("Export Excel"), Modifier.weight(1f), onClick = backups.files::exportExcel)
            BackupButton(tr("Import CSV / Excel"), Modifier.weight(1f), onClick = backups.spreadsheet::importSpreadsheet)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(tr("Export photos"), Modifier.weight(1f), onClick = backups.photos::exportPhotos)
            BackupButton(tr("Import photos"), Modifier.weight(1f), onClick = backups.photos::importPhotos)
        }
        Text(tr("Import adds transactions (columns: Date, Type, Account, Category, Subcategory, Amount, Note…). " +
            "Missing accounts and categories are created. A snapshot is taken first. " +
            "Receipt photos travel separately: export them as a .zip next to the backup file."),
            fontSize = 12.sp, color = SubtleText)
        status?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = if (it.startsWith("Failed")) DangerColor else IncomeColor)
                TextButton(onClick = backups.status::clearStatus) { Text(tr("OK")) }
            }
        }
    }
}

/** Backup folder: outside the app, picked once. */
@Composable
private fun BackupFolderRow(backups: Backups) {
    val folder by backups.folder.backupFolder.collectAsState()
    val folderProblem by backups.folder.backupFolderProblem.collectAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PageBackground).clickable(onClick = backups.folder::pickBackupFolder)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = folder
        Column(Modifier.weight(1f)) {
            Text(tr("Backup folder"), fontSize = 15.sp)
            Text(
                when {
                    shown == null -> tr("Not set. Tap to choose, e.g. Documents/MoneyTracker")
                    folderProblem -> tr("Can't reach {0}. Tap to choose again", shown)
                    else -> shown
                },
                fontSize = 12.sp, color = if (folderProblem) DangerColor else if (shown == null) SubtleText else Accent,
            )
        }
        if (shown != null) TextButton(onClick = backups.folder::clearBackupFolder) { Text(tr("Stop"), color = SubtleText) }
        else Text(tr("Choose"), color = Accent, fontSize = 14.sp)
    }
}

@Composable
private fun MoneyManagerCard(backups: Backups) {
    ConfigSection(tr("Money Manager"))
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackupButton(tr("Export for Money Manager (.mmbak)"), Modifier.fillMaxWidth(), onClick = backups.files::exportMoneyManager)
        Text(tr("Restore the file in Money Manager under Backup › Data Recovery. It replaces the data there. " +
            "Accounts, entries (refunds as negative expenses), categories, budgets, repeats, tags and notes are included; " +
            "photos, bookmarks and detected SMS are not. MoneyTracker is not affiliated with Realbyte."),
            fontSize = 12.sp, color = SubtleText)
    }
}

/** [onReset] gets true to reset everything, false to clear entries only. */
@Composable
private fun ResetCard(onReset: (Boolean) -> Unit) {
    ConfigSection(tr("Reset"))
    Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(tr("Clear entries"), Modifier.weight(1f)) { onReset(false) }
            BackupButton(tr("Reset everything"), Modifier.weight(1f)) { onReset(true) }
        }
        Text(tr("A snapshot is saved first, so a reset can be undone from the list below."), fontSize = 12.sp, color = SubtleText)
    }
}

@Composable
private fun SnapshotLine(b: LocalBackup, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(backupLabel(b.name))
            Text(tr("{0} KB", b.sizeBytes / 1024), fontSize = 12.sp, color = SubtleText)
        }
        TextButton(onClick = onRestore) { Text(tr("Restore"), color = Accent) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, tr("Delete snapshot"), tint = SubtleText) }
    }
}

@Composable
private fun ResetConfirm(everything: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (everything) tr("Reset everything?") else tr("Clear all entries?")) },
        text = {
            Text(if (everything) tr("Deletes all entries, accounts, categories, budgets, repeats, bookmarks and tags, and returns to the starting setup. Passcode and settings stay.")
            else tr("Deletes all entries, budgets, repeats, bookmarks and detected SMS. Accounts and categories stay."))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (everything) tr("Reset") else tr("Clear"), color = DangerColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** "pre-import-20261013-105723.mtbak" → "Before import · 13 Oct 2026, 10:57" (older backups end in .mmdb). */
private fun backupLabel(file: String): String {
    val base = file.removeSuffix(".mtbak").removeSuffix(".mmdb")
    val m = Regex("""^(.*)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}$""").find(base) ?: return base
    val (kind, y, mo) = m.destructured
    val (d, h, mi) = m.groupValues.takeLast(3)
    val what = when (kind) {
        "auto" -> tr("Daily backup"); "manual" -> tr("Manual backup"); "pre-restore" -> tr("Before restore"); "pre-import" -> tr("Before import"); "before-reset" -> tr("Before reset")
        else -> kind.replaceFirstChar { it.uppercase() }
    }
    val month = runCatching { monthShort(mo.toInt()) }.getOrDefault(mo)
    return "$what · ${d.toInt()} $month $y, $h:$mi"
}

@Composable
private fun BackupButton(label: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.height(46.dp)
            .then(if (filled) Modifier.background(Accent, shape) else Modifier.border(1.dp, BorderColor, shape))
            .clip(shape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Color.White else Ink) }
}

@Composable
private fun RestoreConfirm(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(tr("Your current data will be replaced. A safety snapshot of it is taken first, so you can undo this from the list.")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Restore"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Spreadsheet import check: every row read from the file, untick any to leave out, then import. */
@Composable
private fun ImportPreviewDialog(parsed: TabularImport.Parsed, onCancel: () -> Unit, onImport: (Set<Int>) -> Unit) {
    var keep by remember(parsed) { mutableStateOf(parsed.rows.indices.toSet()) }
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SurfaceColor)) {
            BackTopBar(tr("Import {0} of {1}", keep.size, parsed.rows.size), onCancel) {
                TextButton(enabled = keep.isNotEmpty(), onClick = { onImport(keep) }) { Text(tr("Import"), color = Accent) }
            }
            HorizontalDivider(color = DividerColor)
            if (parsed.errors.isNotEmpty()) {
                Text(tr("{0} rows couldn't be read and will be skipped: {1}", parsed.errors.size, parsed.errors.take(3).joinToString("; ")),
                    Modifier.fillMaxWidth().background(PageBackground).padding(16.dp), color = DangerColor, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(keep.size == parsed.rows.size, { keep = if (it) parsed.rows.indices.toSet() else emptySet() })
                Text(tr("All"), color = SubtleText)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(parsed.rows.indices.toList(), key = { it }) { i ->
                    ImportRowLine(parsed.rows[i], i in keep) { on -> keep = if (on) keep + i else keep - i }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
    }
}

/** One row of the file, ticked while it will be imported. */
@Composable
private fun ImportRowLine(r: TabularImport.Row, kept: Boolean, onKeep: (Boolean) -> Unit) {
    val currency = LocalCurrency.current
    Row(Modifier.fillMaxWidth().toggleable(kept, role = Role.Checkbox, onValueChange = onKeep).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowCheckbox(kept)
        Column(Modifier.weight(1f)) {
            Text("${r.date.short()} ${r.date.year} · " + when (r.type) {
                DraftType.TRANSFER -> "${r.account} → ${r.toAccount}"
                else -> listOf(r.category, r.subcategory).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "—" } + " · ${r.account}"
            }, fontSize = 14.sp, maxLines = 1)
            if (r.note.isNotBlank()) Text(r.note, fontSize = 12.sp, color = SubtleText, maxLines = 1)
        }
        Text((r.currency?.let { "$it " } ?: "") + currency.format(r.amount), color = when (r.type) {
            DraftType.INCOME, DraftType.REFUND -> IncomeColor
            DraftType.EXPENSE -> ExpenseColor
            else -> Ink
        })
    }
}
