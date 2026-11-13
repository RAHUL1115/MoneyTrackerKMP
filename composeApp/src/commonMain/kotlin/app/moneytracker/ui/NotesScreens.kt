package app.moneytracker.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.newId
import app.moneytracker.domain.Note
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

private val NoteColors = listOf(SurfaceColor, Color(0xFFFFF4D6), Color(0xFFE3F2FD), Color(0xFFE8F5E9), Color(0xFFFCE4EC))

private const val DAY_MS = 86_400_000L

@Composable
fun NotesView() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val notes by repo.noteStore.notes.collectAsState()
    if (notes.isEmpty()) { EmptyState(tr("No notes. Tap + to jot something down.")); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        items(notes, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).background(NoteColors.getOrElse(n.color) { SurfaceColor }, RoundedCornerShape(8.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(8.dp)).clickable { nav.push(Screen.EditNote(n.id)) }.padding(14.dp),
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

@Composable
fun EditNoteScreen(noteId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val notes by repo.noteStore.notes.collectAsState()
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(0) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var createdAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(notes) {
        if (loaded) return@LaunchedEffect
        notes.firstOrNull { it.id == noteId }?.let { title = it.title; content = it.content; color = it.color; pinned = it.pinned; createdAt = it.createdAt; loaded = true }
    }
    fun save() {
        if (title.isBlank() && content.isBlank()) { nav.pop(); return }
        val n = Note(noteId ?: newId(), title.trim(), content, color, pinned, if (createdAt == 0L) repo.today().toEpochDays() * DAY_MS else createdAt, 0)
        scope.launch { withContext(repo.io) { repo.noteStore.saveNote(n) }; nav.pop() }
    }
    // System Back saves too, same as the arrow — a note is never silently lost.
    val saveState = rememberUpdatedState(::save)
    DisposableEffect(nav) {
        val remove = nav.interceptBack { saveState.value(); true }
        onDispose { remove() }
    }
    Column(Modifier.fillMaxSize().background(NoteColors.getOrElse(color) { SurfaceColor })) {
        BackTopBar(tr("Note"), ::save) {
            IconButton(onClick = { pinned = !pinned }) { Icon(Icons.Outlined.PushPin, tr("Pin"), tint = if (pinned) Accent else SubtleText) }
            if (noteId != null) IconButton(onClick = { scope.launch { withContext(repo.io) { repo.noteStore.deleteNote(noteId) }; nav.pop() } }) {
                Icon(Icons.Outlined.Delete, tr("Delete note"))
            }
            TextButton(onClick = ::save) { Text(tr("Save"), color = Accent) }
        }
        NoteColorRow(color) { color = it }
        NoteDateChip(if (createdAt == 0L) repo.today().toEpochDays() * DAY_MS else createdAt) { createdAt = it }
        NoteText(title, { title = it }, content) { content = it }
    }
}

@Composable
private fun NoteColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NoteColors.forEachIndexed { i, c ->
            Box(Modifier.minimumInteractiveComponentSize().size(28.dp).background(c, RoundedCornerShape(14.dp))
                .border(if (i == selected) 2.dp else 1.dp, if (i == selected) Accent else DividerColor, RoundedCornerShape(14.dp)).clickable { onPick(i) })
        }
    }
}

/** The note's date (notes are shown by day): [createdAt] in epoch ms; a new pick goes to [onPick] the same way. */
@Composable
private fun NoteDateChip(createdAt: Long, onPick: (Long) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    val noteDate = LocalDate.fromEpochDays((createdAt / DAY_MS).toInt())
    Text("${noteDate.monthNumber}.${noteDate.dayOfMonth} (${noteDate.weekday()})",
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).border(1.dp, SubtleText, RoundedCornerShape(4.dp))
            .clickable { pickingDate = true }.padding(horizontal = 14.dp, vertical = 4.dp), color = InkSoft)
    DatePickerHost(pickingDate, noteDate, { pickingDate = false }, { d -> d?.let { onPick(it.toEpochDays() * DAY_MS) } })
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
