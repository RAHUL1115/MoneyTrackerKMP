package app.moneytracker.ui.fresh

import app.moneytracker.ui.outlivingScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Note
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.NoteDraft
import app.moneytracker.ui.NoteEditorModel
import app.moneytracker.ui.NotesModel
import app.moneytracker.ui.Screen
import app.moneytracker.ui.collectAsState
import app.moneytracker.ui.darkTheme
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.noteDate
import app.moneytracker.ui.noteDay
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.datetime.LocalDate

/** Note colours by index (0 = plain card), stored with the note; soft tints that keep text readable in both themes. */
private val noteTints = listOf(
    0L to 0L, 0xFFFFF4D6 to 0xFF3A3218, 0xFFE3EEFB to 0xFF1E2A44, 0xFFE3F2E7 to 0xFF1B3322, 0xFFFBE4EE to 0xFF3A1E31,
)

private fun noteColor(i: Int): Color {
    val (light, dark) = noteTints.getOrElse(i) { 0L to 0L }
    return if (light == 0L) F.card else Color(if (darkTheme) dark else light)
}

private fun LocalDate.noteLabel(today: LocalDate) =
    if (this == today) tr("Today") else "$dayOfMonth ${monthShort(monthNumber)}" + if (year != today.year) " $year" else ""

/** Notes view of the Activity tab: pinned first, newest next. */
@Composable
internal fun ActivityNotesView() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { NotesModel(repo.noteStore, scope) }
    val notes by model.notes.collectAsState()
    NotesList(notes, repo.today()) { nav.push(Screen.EditNote(it.id)) }
}

@Composable
private fun NotesList(notes: List<Note>, today: LocalDate, onOpen: (Note) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (notes.isEmpty()) item {
            FCard(padding = 28.dp) {
                Text(tr("No notes yet"), Modifier.fillMaxWidth(), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(tr("Tap + Note to jot something down, like a bill to pay or a plan for the month."), Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        items(notes, key = { it.id }) { n -> NoteCard(n, today) { onOpen(n) } }
    }
}

@Composable
private fun NoteCard(n: Note, today: LocalDate, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(F.cardShape).background(noteColor(n.color)).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.title.ifBlank { tr("Untitled") }, Modifier.weight(1f), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.pinned) Text(tr("Pinned"), Modifier.clip(F.pillShape).background(F.accentTint).padding(horizontal = 8.dp, vertical = 3.dp),
                color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        if (n.content.isNotBlank()) Text(n.content, color = F.ink.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text((if (n.pinned) tr("Pinned") + " · " else "") + noteDate(n.createdAt).noteLabel(today), color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private val NoteDraftSaver = listSaver<NoteDraft, Any>(
    save = { listOf(it.title, it.content, it.color, it.pinned, it.createdAt) },
    restore = { NoteDraft(it[0] as String, it[1] as String, it[2] as Int, it[3] as Boolean, it[4] as Long) },
)

/** New or existing note: title, text, colour, day and pin. Back saves, so a note is never silently lost. */
@Composable
internal fun FreshEditNote(noteId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    // Saving and Undo run after the editor has closed, so they need a scope that outlives it.
    val model = remember(repo) { NoteEditorModel(repo.noteStore, repo.io, outlivingScope()) }
    val notes by model.notes.collectAsState()
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }
    var original by remember { mutableStateOf<Note?>(null) }
    var draft by rememberSaveable(stateSaver = NoteDraftSaver) { mutableStateOf(NoteDraft.blank(repo.today())) }
    LaunchedEffect(notes) {
        // After the app was closed in the background the typed text comes back from saved state; only the stored
        // note (to tell what changed) is read again.
        val stored = notes.firstOrNull { it.id == noteId }
        if (loaded) {
            if (original == null) original = stored
            return@LaunchedEffect
        }
        stored?.let { original = it; draft = NoteDraft.of(it); loaded = true }
    }

    fun save() {
        val saved = model.saveOnLeave(noteId, draft, original, loaded)
        nav.pop()
        if (saved) toaster.show(if (noteId == null) tr("Note saved") else tr("Changes saved"))
    }
    val saveState = rememberUpdatedState(::save)
    DisposableEffect(nav) {
        val remove = nav.interceptBack { saveState.value(); true }
        onDispose { remove() }
    }
    NoteEditorContent(noteId == null, draft, original, repo.today(), onChange = { draft = it }, onSave = ::save) {
        val id = noteId ?: return@NoteEditorContent
        val before = original
        model.delete(id)
        nav.pop()
        toaster.show(tr("Note deleted"), undo = before?.let { n -> { model.restore(n) } })
    }
}

@Composable
private fun NoteEditorContent(
    isNew: Boolean, draft: NoteDraft, original: Note?, today: LocalDate, onChange: (NoteDraft) -> Unit, onSave: () -> Unit, onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    SubScreen(
        if (isNew) tr("New note") else tr("Edit note"), onBack = onSave,
        actions = { PinButton(draft.pinned) { onChange(draft.copy(pinned = !draft.pinned)) } },
        bottom = { NoteEditorButtons(isNew, draft.filled, onSave, onDelete = { confirmDelete = true }) },
    ) {
        NotePaper(draft, original, today, onChange)
        NoteOptionsCard(draft, onChange, onPickDate = { pickingDate = true })
    }
    DatePickerHost(pickingDate, noteDate(draft.createdAt), { pickingDate = false }, { d -> d?.let { onChange(draft.copy(createdAt = noteDay(it))) } })
    if (confirmDelete) Confirm(
        tr("Delete this note?"), tr("“{0}” will be removed. You can undo this right after.", draft.title.ifBlank { tr("Untitled") }), tr("Delete"),
        onDismiss = { confirmDelete = false }, onConfirm = onDelete,
    )
}

@Composable
private fun PinButton(pinned: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(F.iconShape).background(if (pinned) F.accentTint else F.card)
            .toggleable(pinned, role = Role.Switch, onValueChange = { onToggle() }).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Glyph.Pin, null, tint = if (pinned) F.accent else F.ink, modifier = Modifier.size(18.dp))
        Text(if (pinned) tr("Pinned") else tr("Pin"), color = if (pinned) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoteEditorButtons(isNew: Boolean, filled: Boolean, onSave: () -> Unit, onDelete: () -> Unit) {
    val deleteLabel = tr("Delete note")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!isNew) Box(
            Modifier.size(52.dp).clip(F.buttonShape).background(F.danger.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onDelete).semantics { contentDescription = deleteLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Delete, null, tint = F.danger) }
        PrimaryButton(
            when { isNew && !filled -> tr("Write something first"); isNew -> tr("Save note"); else -> tr("Save changes") },
            onClick = onSave, modifier = Modifier.weight(1f), enabled = filled || !isNew,
        )
    }
}

/** The note itself on its colour: title, text and when it was last changed. */
@Composable
private fun NotePaper(draft: NoteDraft, original: Note?, today: LocalDate, onChange: (NoteDraft) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 420.dp).clip(F.cardShape).background(noteColor(draft.color)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            if (draft.title.isEmpty()) Text(tr("Title"), color = F.muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(draft.title, { onChange(draft.copy(title = it)) }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = F.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(F.accent))
        }
        Box {
            if (draft.content.isEmpty()) Text(tr("Write something…"), color = F.muted, fontSize = 14.sp)
            BasicTextField(draft.content, { onChange(draft.copy(content = it)) }, Modifier.fillMaxWidth().heightIn(min = 300.dp),
                textStyle = TextStyle(color = F.ink, fontSize = 14.sp, lineHeight = 23.sp), cursorBrush = SolidColor(F.accent))
        }
        Text(
            if (original == null) tr("New · today") else tr("Last edited {0}", noteDate(original.updatedAt).noteLabel(today)),
            color = F.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun NoteOptionsCard(draft: NoteDraft, onChange: (NoteDraft) -> Unit, onPickDate: () -> Unit) {
    // The card's parts pad themselves, so the Day row's highlight reaches the card's edges.
    val inset = F.cardPadding
    FListCard {
        Row(Modifier.padding(start = inset, end = inset, top = inset), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Colour"), Modifier.weight(1f), color = F.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                noteTints.indices.forEach { i ->
                    val on = i == draft.color
                    val label = tr("Colour {0}", i + 1)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(noteColor(i))
                            .border(if (on) 2.dp else 1.dp, if (on) F.accent else F.border, CircleShape)
                            .selectable(on, role = Role.RadioButton) { onChange(draft.copy(color = i)) }
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
        Divider(inset = inset)
        val d = noteDate(draft.createdAt)
        FRow(tr("Day"), value = "${d.weekday()} ${d.dayOfMonth} ${monthShort(d.monthNumber)} ${d.year}", onClick = onPickDate,
            inset = inset, extraBottom = inset)
    }
}
