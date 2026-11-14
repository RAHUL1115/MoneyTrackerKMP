package app.moneytracker.lists.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.weekday
import app.moneytracker.domain.Note
import app.moneytracker.entry.classic.save
import app.moneytracker.lists.NoteDraft
import app.moneytracker.lists.NoteDraftSaver
import app.moneytracker.lists.NoteEditorModel
import app.moneytracker.lists.NoteOrder
import app.moneytracker.lists.NotesModel
import app.moneytracker.lists.noteDate
import app.moneytracker.lists.noteDay
import kotlinx.datetime.LocalDate

private val NoteColors = listOf(SurfaceColor, Color(0xFFFFF4D6), Color(0xFFE3F2FD), Color(0xFFE8F5E9), Color(0xFFFCE4EC))

/** Classic notes list, in stored order: pinned first, then last changed. */
@Composable
fun NotesView() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { NotesModel(repo.noteStore, scope, NoteOrder.LAST_CHANGED) }
    val notes by model.notes.collectAsState()
    NotesList(notes) { nav.push(Screen.EditNote(it.id)) }
}

@Composable
private fun NotesList(notes: List<Note>, onOpen: (Note) -> Unit) {
    if (notes.isEmpty()) { EmptyState(tr("No notes. Tap + to jot something down.")); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        items(notes, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).background(NoteColors.getOrElse(n.color) { SurfaceColor }, RoundedCornerShape(8.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).clickable { onOpen(n) }.padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title.ifBlank { tr("Untitled") }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.pinned) Icon(Icons.Outlined.PushPin, tr("Pinned"), Modifier.size(16.dp), tint = Accent)
                }
                if (n.content.isNotBlank()) Text(n.content, Modifier.padding(top = 4.dp), color = InkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** Classic note editor: Back, the arrow and Save all save, and the editor closes once the save is done. */
@Composable
fun EditNoteScreen(noteId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    // The editor closes only after a save or delete finishes, so the screen's own scope lives long enough.
    val model = remember(repo) { NoteEditorModel(repo.noteStore, repo.io, scope) }
    val notes by model.notes.collectAsState()
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }
    var draft by rememberSaveable(stateSaver = NoteDraftSaver) { mutableStateOf(NoteDraft.undated) }
    LaunchedEffect(notes) {
        if (loaded) return@LaunchedEffect
        notes.firstOrNull { it.id == noteId }?.let { draft = NoteDraft.of(it); loaded = true }
    }
    fun save() = model.saveThen(noteId, draft, repo.today(), then = nav::pop)
    // System Back saves too, same as the arrow — a note is never silently lost.
    val saveState = rememberUpdatedState(::save)
    DisposableEffect(nav) {
        val remove = nav.interceptBack { saveState.value(); true }
        onDispose { remove() }
    }
    val onDelete = noteId?.let { id -> { model.delete(id, then = nav::pop) } }
    NoteEditorContent(draft, repo.today(), onChange = { draft = it }, onSave = ::save, onDelete = onDelete)
}

/** The editor drawn from [draft]; [onDelete] is null for a new note. */
@Composable
private fun NoteEditorContent(draft: NoteDraft, today: LocalDate, onChange: (NoteDraft) -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().background(NoteColors.getOrElse(draft.color) { SurfaceColor })) {
        BackTopBar(tr("Note"), onSave) {
            IconButton(onClick = { onChange(draft.copy(pinned = !draft.pinned)) }) {
                Icon(Icons.Outlined.PushPin, tr("Pin"), tint = if (draft.pinned) Accent else SubtleText)
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, tr("Delete note")) }
            TextButton(onClick = onSave) { Text(tr("Save"), color = Accent) }
        }
        NoteColorRow(draft.color) { onChange(draft.copy(color = it)) }
        NoteDateChip(draft.dayOr(today)) { onChange(draft.copy(createdAt = it)) }
        NoteText(draft.title, { onChange(draft.copy(title = it)) }, draft.content) { onChange(draft.copy(content = it)) }
    }
}

@Composable
private fun NoteColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NoteColors.forEachIndexed { i, c ->
            val label = tr("Colour {0}", i + 1)
            Box(Modifier.minimumInteractiveComponentSize().size(28.dp).background(c, RoundedCornerShape(14.dp))
                .border(if (i == selected) 2.dp else 1.dp, if (i == selected) Accent else DividerColor, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .selectable(i == selected, role = Role.RadioButton) { onPick(i) }.semantics { contentDescription = label })
        }
    }
}

/** The note's date (notes are shown by day): [createdAt] in epoch ms; a new pick goes to [onPick] the same way. */
@Composable
private fun NoteDateChip(createdAt: Long, onPick: (Long) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    val noteDate = noteDate(createdAt)
    Text("${noteDate.monthNumber}.${noteDate.dayOfMonth} (${noteDate.weekday()})",
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).border(1.dp, SubtleText, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button) { pickingDate = true }.padding(horizontal = 14.dp, vertical = 4.dp), color = InkSoft)
    DatePickerHost(pickingDate, noteDate, { pickingDate = false }, { d -> d?.let { onPick(noteDay(it)) } })
}

@Composable
private fun NoteText(title: String, onTitle: (String) -> Unit, content: String, onContent: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Box {
            if (title.isEmpty()) Text(tr("Title"), fontSize = 22.sp, color = SubtleText)
            BasicTextField(title, onTitle, textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Box {
            if (content.isEmpty()) Text(tr("Write something…"), fontSize = 16.sp, color = SubtleText)
            BasicTextField(content, onContent, textStyle = TextStyle(fontSize = 16.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth().height(400.dp))
        }
    }
}
