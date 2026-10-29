package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.moneytracker.data.DraftType
import app.moneytracker.data.TabularImport
import app.moneytracker.ui.LocalBackup
import app.moneytracker.ui.LocalCurrency
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.short
import app.moneytracker.ui.tr
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private val SNAPSHOT_NAME = Regex("""^(.*)-(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}$""")

private fun snapshotBase(file: String) = file.removeSuffix(".mtbak").removeSuffix(".mmdb")

/** "auto-20261013-105723.mtbak" → "Today, 10:57" / "Yesterday, 10:57" / "13 Oct 2026, 10:57". */
internal fun snapshotWhen(file: String, today: LocalDate): String {
    val m = SNAPSHOT_NAME.find(snapshotBase(file)) ?: return snapshotBase(file)
    val (_, y, mo, d, h, mi) = m.destructured
    val date = runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    return when (date) {
        today -> tr("Today, {0}", "$h:$mi")
        today.minus(DatePeriod(days = 1)) -> tr("Yesterday, {0}", "$h:$mi")
        else -> "${d.toInt()} ${runCatching { monthShort(mo.toInt()) }.getOrDefault(mo)} $y, $h:$mi"
    }
}

/** What made a snapshot: daily, manual, before a restore / import / reset. */
private fun snapshotKind(file: String): String = when (SNAPSHOT_NAME.find(snapshotBase(file))?.groupValues?.get(1)) {
    "auto" -> tr("Daily backup"); "manual" -> tr("Manual backup"); "pre-restore" -> tr("Before restore")
    "pre-import" -> tr("Before import"); "before-reset" -> tr("Before reset")
    null -> tr("Backup")
    else -> SNAPSHOT_NAME.find(snapshotBase(file))!!.groupValues[1].replaceFirstChar { it.uppercase() }
}

@Composable
internal fun FreshBackup() {
    val nav = LocalNavigator.current
    val repo = LocalRepo.current
    val backup = LocalBackup.current
    val toaster = LocalToaster.current
    val local by backup.localBackups.collectAsState()
    val status by backup.status.collectAsState()
    val pendingImport by backup.pendingImport.collectAsState()
    var restoring by remember { mutableStateOf<app.moneytracker.data.LocalBackup?>(null) }
    var deleting by remember { mutableStateOf<app.moneytracker.data.LocalBackup?>(null) }
    var confirmFile by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<Boolean?>(null) }   // true = everything, false = entries only
    val today = repo.today()

    pendingImport?.let { ImportPreview(it, onCancel = backup::cancelImport, onImport = backup::confirmImport) }

    SubScreen(tr("Backup & data"), nav::pop) {
        FCard {
            val s = status
            val failed = s != null && s.startsWith("Failed")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null,
                    tint = if (failed) F.danger else F.accent, modifier = Modifier.size(18.dp))
                Text(
                    s ?: local.firstOrNull()?.let { tr("Last snapshot: {0}", snapshotWhen(it.name, today)) } ?: tr("No snapshots yet."),
                    Modifier.weight(1f), color = if (failed) F.danger else F.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                if (s != null) StgTextButton(tr("OK"), backup::clearStatus)
            }
            Text(
                tr("Everything stays on this phone. A snapshot is taken automatically once a day (last 10 kept). Keep a copy somewhere else in case you lose or reset the phone."),
                Modifier.padding(top = 8.dp, bottom = 14.dp), color = F.muted, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(tr("Back up now"), backup::backupNow, Modifier.weight(1f))
                SecondaryButton(tr("Share a copy…"), backup::shareBackup, Modifier.weight(1f))
            }
        }

        val actions = listOf<Triple<String, String, () -> Unit>>(
            Triple(tr("Restore from a file"), tr("Replaces current data · reads .mtbak and .mmbak · a snapshot is saved first")) { confirmFile = true },
            Triple(tr("Save a backup file"), tr("To another folder, SD card or cloud drive"), backup::exportToFile),
            Triple(tr("Export CSV"), tr("All entries, for any spreadsheet app"), backup::exportCsv),
            Triple(tr("Export Excel"), tr("All entries as .xlsx"), backup::exportExcel),
            Triple(tr("Import CSV / Excel"), tr("Preview rows before adding"), backup::importSpreadsheet),
            Triple(tr("Export as .mmbak"), tr("To open your data in another money app"), backup::exportMoneyManager),
            Triple(tr("Export receipt photos"), tr("A .zip to keep next to the backup file"), backup::exportPhotos),
            Triple(tr("Import receipt photos"), tr("From a photos .zip; existing photos are kept"), backup::importPhotos),
        )
        FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                actions.forEachIndexed { i, (title, sub, go) ->
                    if (i > 0) Divider()
                    FRow(title, sub, onClick = go)
                }
            }
        }
        Text(
            tr("Import adds transactions (columns: Date, Type, Account, Category, Subcategory, Amount, Note…). Missing accounts and categories are created. A snapshot is taken first."),
            Modifier.padding(horizontal = 4.dp), color = F.muted, fontSize = 12.sp,
        )

        SectionLabel(tr("Snapshots on this phone · last 10 kept"))
        if (local.isEmpty()) EmptyCard(tr("No snapshots yet."))
        else FCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                local.forEachIndexed { i, b ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(snapshotWhen(b.name, today), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("${snapshotKind(b.name)} · ${tr("{0} KB", b.sizeBytes / 1024)}", color = F.muted, fontSize = 12.sp)
                        }
                        StgTextButton(tr("Restore"), { restoring = b })
                        Box(Modifier.size(40.dp).clip(F.iconShape).clickable(onClickLabel = tr("Delete snapshot")) { deleting = b }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Delete, tr("Delete snapshot"), tint = F.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        SectionLabel(tr("Start over · a snapshot is saved first, so this can be undone"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("Clear entries"), { resetting = false }, Modifier.weight(1f), danger = true)
            SecondaryButton(tr("Reset everything"), { resetting = true }, Modifier.weight(1f), danger = true)
        }
    }

    val replaceBody = tr("Your current data will be replaced. A safety snapshot of it is taken first, so you can undo this from the list.")
    if (confirmFile) Confirm(tr("Restore from a file?"), replaceBody, tr("Choose file"), { confirmFile = false }, danger = false) { backup.importFromFile() }
    restoring?.let { b ->
        Confirm(tr("Restore “{0}”?", snapshotWhen(b.name, today)), replaceBody, tr("Restore"), { restoring = null }, danger = false) { backup.restoreLocal(b.name) }
    }
    deleting?.let { b ->
        Confirm(tr("Delete this snapshot?"), tr("“{0}” is removed from this phone. This can't be undone.", "${snapshotKind(b.name)} · ${snapshotWhen(b.name, today)}"),
            tr("Delete"), { deleting = null }) { backup.deleteLocal(b.name); toaster.show(tr("Snapshot deleted")) }
    }
    resetting?.let { everything ->
        Confirm(
            if (everything) tr("Reset everything?") else tr("Clear all entries?"),
            if (everything) tr("Deletes all entries, accounts, categories, budgets, repeats, bookmarks and tags, and returns to the starting setup. Passcode and settings stay. A snapshot is saved first.")
            else tr("Deletes all entries, budgets, repeats, bookmarks and detected SMS. Accounts and categories stay. A snapshot is saved first, so this can be undone from the list below."),
            if (everything) tr("Reset") else tr("Clear"), { resetting = null },
        ) { backup.reset(everything) }
    }
}

/** Spreadsheet import check: every row read from the file, untick any to leave out, then import. */
@Composable
private fun ImportPreview(parsed: TabularImport.Parsed, onCancel: () -> Unit, onImport: (Set<Int>) -> Unit) {
    val currency = LocalCurrency.current
    var keep by remember(parsed) { mutableStateOf(parsed.rows.indices.toSet()) }
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
                    item {
                        Row(Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable {
                            keep = if (keep.size == parsed.rows.size) emptySet() else parsed.rows.indices.toSet()
                        }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(keep.size == parsed.rows.size, { keep = if (it) parsed.rows.indices.toSet() else emptySet() }, colors = boxColors)
                            Text(tr("All"), color = F.ink, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(parsed.rows.indices.toList(), key = { it }) { i ->
                        val r = parsed.rows[i]
                        Row(
                            Modifier.fillMaxWidth().clip(F.cardShape).background(F.card).clickable { keep = if (i in keep) keep - i else keep + i }
                                .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(i in keep, { keep = if (it) keep + i else keep - i }, colors = boxColors)
                            Column(Modifier.weight(1f)) {
                                Text("${r.date.short()} ${r.date.year} · " + when (r.type) {
                                    DraftType.TRANSFER -> "${r.account} → ${r.toAccount}"
                                    else -> listOf(r.category, r.subcategory).filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "—" } + " · ${r.account}"
                                }, color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (r.note.isNotBlank()) Text(r.note, color = F.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text((r.currency?.let { "$it " } ?: "") + currency.format(r.amount), style = F.money, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                color = when (r.type) {
                                    DraftType.INCOME, DraftType.REFUND -> F.income
                                    DraftType.EXPENSE -> F.expense
                                    else -> F.ink
                                })
                        }
                    }
                    item { Gap(16.dp) }
                }
            }
        }
    }
}
