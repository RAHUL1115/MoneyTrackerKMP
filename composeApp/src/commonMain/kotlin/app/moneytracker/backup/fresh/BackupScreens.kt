package app.moneytracker.backup.fresh

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.moneytracker.app.LocalBackups
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.tr
import app.moneytracker.data.BackupFolder
import app.moneytracker.data.Backups
import app.moneytracker.data.DraftType
import app.moneytracker.data.LocalBackup
import app.moneytracker.data.moneymanager.TabularImport
import app.moneytracker.designsystem.classic.RowCheckbox
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.classic.short
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.Divider
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.FCard
import app.moneytracker.designsystem.fresh.FListCard
import app.moneytracker.designsystem.fresh.FRow
import app.moneytracker.designsystem.fresh.Gap
import app.moneytracker.designsystem.fresh.Glyph
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.PrimaryButton
import app.moneytracker.designsystem.fresh.StgTextButton
import app.moneytracker.designsystem.fresh.SubScreen
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private val SNAPSHOT_NAME = Regex("""^(.*)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}$""")

private fun snapshotBase(file: String) = file.removeSuffix(".mtbak").removeSuffix(".mmdb")

/** "auto-20261013-105723.mtbak" → "Today, 10:57" / "Yesterday, 10:57" / "13 Oct 2026, 10:57". */
internal fun snapshotWhen(file: String, today: LocalDate): String {
    val g = SNAPSHOT_NAME.find(snapshotBase(file))?.groupValues ?: return snapshotBase(file)
    val y = g[2]; val mo = g[3]; val d = g[4]
    val time = "${g[5]}:${g[6]}"
    val date = try { LocalDate(y.toInt(), mo.toInt(), d.toInt()) } catch (_: IllegalArgumentException) { null }
    return when (date) {
        today -> tr("Today, {0}", time)
        today.minus(DatePeriod(days = 1)) -> tr("Yesterday, {0}", time)
        else -> "${d.toInt()} ${mo.toInt().takeIf { it in 1..12 }?.let(::monthShort) ?: mo} $y, $time"
    }
}

/** What made a snapshot: daily, manual, before a restore / import / reset. */
private fun snapshotKind(file: String): String = when (val prefix = SNAPSHOT_NAME.find(snapshotBase(file))?.groupValues?.get(1)) {
    "auto" -> tr("Daily backup"); "manual" -> tr("Manual backup"); "pre-restore" -> tr("Before restore")
    "pre-import" -> tr("Before import"); "before-reset" -> tr("Before reset")
    null -> tr("Backup")
    else -> prefix.replaceFirstChar { it.uppercase() }
}

/** Backup & data. Everything here goes through the platform's [Backups] capabilities; each card asks only for its own. */
@Composable
internal fun FreshBackup() {
    val nav = LocalNavigator.current
    val repo = LocalRepo.current
    val backups = LocalBackups.current
    val local by backups.saved.localBackups.collectAsState()
    val pendingImport by backups.spreadsheet.pendingImport.collectAsState()
    var confirmFile by remember { mutableStateOf(false) }
    val today = repo.today()

    pendingImport?.let { ImportPreview(it, onCancel = backups.spreadsheet::cancelImport, onImport = backups.spreadsheet::confirmImport) }

    SubScreen(tr("Backup & data"), nav::pop) {
        BackupStatusCard(backups, local.firstOrNull(), today)
        BackupFolderCard(backups.folder)
        DataActionsCard(backups, onRestoreFile = { confirmFile = true })
        Text(
            tr("Import adds transactions (columns: Date, Type, Account, Category, Subcategory, Amount, Note…). Missing accounts and categories are created. A snapshot is taken first."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
        )
        SnapshotsCard(local, today, backups)
        StartOverCard(backups)
    }

    if (confirmFile) Confirm(tr("Restore from a file?"), replaceBody(), tr("Choose file"), { confirmFile = false }, danger = false) { backups.files.importFromFile() }
}

private fun replaceBody() = tr("Your current data will be replaced. A safety snapshot of it is taken first, so you can undo this from the list.")

/** The last snapshot or the last action's result, with Back up now and Share. */
@Composable
private fun BackupStatusCard(backups: Backups, latest: LocalBackup?, today: LocalDate) {
    val s by backups.status.message.collectAsState()
    val failed = s?.startsWith("Failed") == true
    // All is well: the card wears the accent tint (success uses the accent, not a separate green). A failure goes soft red.
    Column(Modifier.fillMaxWidth().clip(F.cardShape).background(if (failed) F.dangerTint else F.accentTint).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (failed) Glyph.Error else Glyph.Done, null,
                tint = if (failed) F.danger else F.accent, modifier = Modifier.size(18.dp))
            Text(
                s ?: latest?.let { tr("Last snapshot: {0}", snapshotWhen(it.name, today)) } ?: tr("No snapshots yet."),
                Modifier.weight(1f), color = if (failed) F.danger else F.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            if (s != null) StgTextButton(tr("OK"), backups.status::clearStatus)
        }
        Text(
            tr("A snapshot is taken automatically once a day (last 10 kept). Choose a backup folder and each one is also saved there, where file managers and cloud apps can reach it and it survives uninstalling the app."),
            Modifier.padding(top = 8.dp, bottom = 14.dp), color = F.muted, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Back up now"), backups.saved::backupNow, Modifier.weight(1f), F.accent, F.onAccent, FontWeight.SemiBold)
            CardButton(tr("Share a copy…"), backups.files::shareBackup, Modifier.weight(1f), F.page, F.ink)
        }
    }
}

/** Backup folder: outside the app, picked once. */
@Composable
private fun BackupFolderCard(folder: BackupFolder) {
    val path by folder.backupFolder.collectAsState()
    val problem by folder.backupFolderProblem.collectAsState()
    FListCard {
        val shown = path
        FRow(
            tr("Backup folder"),
            when {
                shown == null -> tr("Not set. Choose one, e.g. Documents/MoneyTracker or a Drive folder")
                problem -> tr("Can't reach {0}. Choose again", shown)
                else -> tr("Daily and manual backups are also saved to {0}", shown)
            },
            onClick = folder::pickBackupFolder,
            inset = ROW_INSET, extraTop = CARD_EDGE, extraBottom = if (shown == null) CARD_EDGE else 0.dp,
        )
        if (shown != null) {
            Divider(inset = ROW_INSET)
            FRow(tr("Stop saving to this folder"), tr("Files already there are kept"), onClick = folder::clearBackupFolder,
                inset = ROW_INSET, extraBottom = CARD_EDGE)
        }
    }
}

@Composable
private fun DataActionsCard(backups: Backups, onRestoreFile: () -> Unit) {
    val actions = listOf<Triple<String, String, () -> Unit>>(
        Triple(tr("Restore from a file"), tr("Replaces current data · reads .mtbak and .mmbak · a snapshot is saved first"), onRestoreFile),
        Triple(tr("Save a backup file"), tr("To another folder, SD card or cloud drive"), backups.files::exportToFile),
        Triple(tr("Export CSV"), tr("All entries, for any spreadsheet app"), backups.files::exportCsv),
        Triple(tr("Export Excel"), tr("All entries as .xlsx"), backups.files::exportExcel),
        Triple(tr("Import CSV / Excel"), tr("Preview rows before adding"), backups.spreadsheet::importSpreadsheet),
        Triple(tr("Export as .mmbak"), tr("To open your data in another money app"), backups.files::exportMoneyManager),
        Triple(tr("Export receipt photos"), tr("A .zip to keep next to the backup file"), backups.photos::exportPhotos),
        Triple(tr("Import receipt photos"), tr("From a photos .zip; existing photos are kept"), backups.photos::importPhotos),
    )
    FListCard {
        actions.forEachIndexed { i, (title, sub, go) ->
            if (i > 0) Divider(inset = ROW_INSET)
            FRow(title, sub, onClick = go, inset = ROW_INSET, extraTop = if (i == 0) CARD_EDGE else 0.dp,
                extraBottom = if (i == actions.lastIndex) CARD_EDGE else 0.dp)
        }
    }
}

/** This screen's rows start 18dp in from their card's edge. */
private val ROW_INSET = 18.dp

/** The cards' top and bottom spacing, kept inside the first and last rows so their highlight reaches the rounded edge. */
private val CARD_EDGE = 4.dp

/** Snapshots on this phone, each with Restore and Delete (both confirmed first). The label sits inside its card. */
@Composable
private fun SnapshotsCard(local: List<LocalBackup>, today: LocalDate, backups: Backups) {
    val toaster = LocalToaster.current
    var restoring by remember { mutableStateOf<LocalBackup?>(null) }
    var deleting by remember { mutableStateOf<LocalBackup?>(null) }
    FCard(Modifier, padding = 0.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InCardLabel(tr("Snapshots on this phone · last 10 kept"))
            if (local.isEmpty()) Text(tr("No snapshots yet."), Modifier.padding(vertical = 8.dp), color = F.muted, fontSize = 14.sp)
            local.forEach { b -> SnapshotRow(b, today, onRestore = { restoring = b }, onDelete = { deleting = b }) }
        }
    }
    restoring?.let { b ->
        Confirm(tr("Restore “{0}”?", snapshotWhen(b.name, today)), replaceBody(), tr("Restore"), { restoring = null }, danger = false) { backups.saved.restoreLocal(b.name) }
    }
    deleting?.let { b ->
        Confirm(tr("Delete this snapshot?"), tr("“{0}” is removed from this phone. This can't be undone.", "${snapshotKind(b.name)} · ${snapshotWhen(b.name, today)}"),
            tr("Delete"), { deleting = null }) { backups.saved.deleteLocal(b.name); toaster.show(tr("Snapshot deleted")) }
    }
}

@Composable
private fun SnapshotRow(b: LocalBackup, today: LocalDate, onRestore: () -> Unit, onDelete: () -> Unit) {
    val deleteLabel = tr("Delete snapshot")
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(snapshotWhen(b.name, today), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${snapshotKind(b.name)} · ${tr("{0} KB", b.sizeBytes / 1024)}", color = F.muted, fontSize = 12.sp)
        }
        StgTextButton(tr("Restore"), onRestore, color = F.ink)
        Box(
            Modifier.size(32.dp).clip(F.innerShape).background(F.page).clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = deleteLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Delete, null, tint = F.muted, modifier = Modifier.size(18.dp)) }
    }
}

/** Clear entries or reset everything, confirmed first; a snapshot is taken before either. */
@Composable
private fun StartOverCard(backups: Backups) {
    var resetting by remember { mutableStateOf<Boolean?>(null) }   // true = everything, false = entries only
    FCard(Modifier, padding = 0.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InCardLabel(tr("Start over · a snapshot is saved first, so this can be undone"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Red words on white with a soft warm edge: noticeable, not alarming.
                val edge = F.warn.copy(alpha = 0.3f)
                CardButton(tr("Clear entries"), { resetting = false }, Modifier.weight(1f), F.card, F.danger, height = 44.dp, radius = 12.dp, border = edge)
                CardButton(tr("Reset everything"), { resetting = true }, Modifier.weight(1f), F.card, F.danger, height = 44.dp, radius = 12.dp, border = edge)
            }
        }
    }
    resetting?.let { everything ->
        Confirm(
            if (everything) tr("Reset everything?") else tr("Clear all entries?"),
            if (everything) tr("Deletes all entries, accounts, categories, budgets, repeats, bookmarks and tags, and returns to the starting setup. Passcode and settings stay. A snapshot is saved first.")
            else tr("Deletes all entries, budgets, repeats, bookmarks and detected SMS. Accounts and categories stay. A snapshot is saved first, so this can be undone from the list below."),
            if (everything) tr("Reset") else tr("Clear"), { resetting = null },
        ) { backups.saved.reset(everything) }
    }
}

/** Grey upper-case label at the top of a card. */
@Composable
private fun InCardLabel(text: String) =
    Text(text.uppercase(), color = F.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)

/** A button sized for inside a card: shorter than the screen's main buttons. */
@Composable
private fun CardButton(
    text: String, onClick: () -> Unit, modifier: Modifier, background: Color, color: Color, weight: FontWeight = FontWeight.Medium,
    height: Dp = 46.dp, radius: Dp = 14.dp, border: Color? = null,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.fillMaxWidth().height(height).clip(shape).background(background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 14.sp, fontWeight = weight) }
}

/** Spreadsheet import check: every row read from the file, untick any to leave out, then import. */
@Composable
private fun ImportPreview(parsed: TabularImport.Parsed, onCancel: () -> Unit, onImport: (Set<Int>) -> Unit) {
    var keep by remember(parsed) { mutableStateOf(parsed.rows.indices.toSet()) }
    val all = parsed.rows.indices.toSet()
    val boxColors = CheckboxDefaults.colors(checkedColor = F.accent, checkmarkColor = F.onAccent, uncheckedColor = F.muted)
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(F.page)) {
            SubScreen(
                tr("Import {0} of {1}", keep.size, parsed.rows.size), onCancel, scroll = false,
                bottom = { PrimaryButton(tr("Import {0} rows", keep.size), { onImport(keep) }, enabled = keep.isNotEmpty()) },
            ) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = F.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(tr("Import adds entries; nothing is replaced. Untick rows to leave them out."), color = F.muted, fontSize = 13.sp)
                    }
                    if (parsed.errors.isNotEmpty()) item {
                        FCard {
                            Text(tr("{0} rows couldn't be read and will be skipped: {1}", parsed.errors.size, parsed.errors.take(3).joinToString("; ")),
                                color = F.danger, fontSize = 13.sp)
                        }
                    }
                    item { AllRowsLine(keep.size == all.size, boxColors) { on -> keep = if (on) all else emptySet() } }
                    items(parsed.rows.indices.toList(), key = { it }) { i ->
                        ImportRowLine(parsed.rows[i], i in keep, boxColors) { on -> keep = if (on) keep + i else keep - i }
                    }
                    item { Gap(16.dp) }
                }
            }
        }
    }
}

@Composable
private fun AllRowsLine(checked: Boolean, colors: CheckboxColors, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).toggleable(checked, role = Role.Checkbox, onValueChange = onCheck).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RowCheckbox(checked, colors = colors)
        Text(tr("All"), color = F.ink, fontWeight = FontWeight.Medium)
    }
}

/** One spreadsheet row: date and where it goes, its note and amount. */
@Composable
private fun ImportRowLine(r: TabularImport.Row, checked: Boolean, colors: CheckboxColors, onCheck: (Boolean) -> Unit) {
    val currency = LocalCurrency.current
    Row(
        Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).toggleable(checked, role = Role.Checkbox, onValueChange = onCheck)
            .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowCheckbox(checked, colors = colors)
        Column(Modifier.weight(1f)) {
            Text("${r.date.short()} ${r.date.year} · " + when (r.type) {
                DraftType.TRANSFER -> "${r.account} → ${r.toAccount}"
                else -> listOf(r.category, r.subcategory).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "—" } + " · ${r.account}"
            }, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.note.isNotBlank()) Text(r.note, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text((r.currency?.let { "$it " } ?: "") + currency.format(r.amount), style = F.money, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            color = when (r.type) {
                DraftType.INCOME, DraftType.REFUND -> F.income
                DraftType.EXPENSE -> F.expense
                else -> F.ink
            })
    }
}
