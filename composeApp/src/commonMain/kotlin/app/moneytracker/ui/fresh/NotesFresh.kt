package app.moneytracker.ui.fresh

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.newId
import app.moneytracker.domain.Note
import app.moneytracker.ui.DatePickerHost
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.LocalRepo
import app.moneytracker.ui.Screen
import app.moneytracker.ui.darkTheme
import app.moneytracker.ui.monthShort
import app.moneytracker.ui.tr
import app.moneytracker.ui.weekday
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

private const val DAY_MS = 86_400_000L

/** Note colours by index (0 = plain card), stored with the note; soft tints that keep text readable in both themes. */
private val noteTints = listOf(
    0L to 0L, 0xFFFFF4D6 to 0xFF3A3218, 0xFFE3EEFB to 0xFF1E2A44, 0xFFE3F2E7 to 0xFF1B3322, 0xFFFBE4EE to 0xFF3A1E31,
)

private fun noteColor(i: Int): Color {
    val (light, dark) = noteTints.getOrElse(i) { 0L to 0L }
    return if (light == 0L) F.card else Color(if (darkTheme) dark else light)
}

private fun noteDate(createdAt: Long) = LocalDate.fromEpochDays((createdAt / DAY_MS).toInt())

private fun LocalDate.noteLabel(today: LocalDate) =
    if (this == today) tr("Today") else "$dayOfMonth ${monthShort(monthNumber)}" + if (year != today.year) " $year" else ""

/** Notes view of the Activity tab: pinned first, newest next. */
@Composable
internal fun ActivityNotesView() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val notes by repo.notes.collectAsState(emptyList())
    val today = repo.today()
    val list = notes.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt }.thenByDescending { it.updatedAt })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = F.gutter, end = F.gutter, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (list.isEmpty()) item {
            FCard(padding = 28.dp) {
                Text(tr("No notes yet"), Modifier.fillMaxWidth(), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Text(tr("Tap + Note to jot something down, like a bill to pay or a plan for the month."), Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = F.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        items(list, key = { it.id }) { n ->
            Column(
                Modifier.fillMaxWidth().clip(F.cardShape).background(noteColor(n.color)).clickable { nav.push(Screen.EditNote(n.id)) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n.title.ifBlank { tr("Untitled") }, Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.pinned) Text(tr("Pinned"), Modifier.clip(RoundedCornerShape(999.dp)).background(F.accentTint).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = F.accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                if (n.content.isNotBlank()) Text(n.content, color = F.ink.copy(alpha = 0.8f), fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text((if (n.pinned) tr("Pinned") + " · " else "") + noteDate(n.createdAt).noteLabel(today), color = F.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** New or existing note: title, text, colour, day and pin. Back saves, so a note is never silently lost. */
@Composable
internal fun FreshEditNote(noteId: String?) {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val toaster = LocalToaster.current
    val notes by repo.notes.collectAsState(emptyList())
    var loaded by remember { mutableStateOf(noteId == null) }
    var original by remember { mutableStateOf<Note?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0) }
    var pinned by remember { mutableStateOf(false) }
    var createdAt by remember { mutableStateOf(repo.today().toEpochDays() * DAY_MS) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    LaunchedEffect(notes) {
        if (loaded) return@LaunchedEffect
        notes.firstOrNull { it.id == noteId }?.let {
            original = it; title = it.title; content = it.content; color = it.color; pinned = it.pinned; createdAt = it.createdAt; loaded = true
        }
    }
    val filled = title.isNotBlank() || content.isNotBlank()
    val isNew = noteId == null
    val changed = original.let { o -> o == null || o.title != title.trim() || o.content != content || o.color != color || o.pinned != pinned || o.createdAt != createdAt }

    fun save() {
        if (!filled || !changed || !loaded) { nav.pop(); return }
        val n = Note(noteId ?: newId(), title.trim(), content, color, pinned, createdAt, 0)
        activityLaunch { withContext(repo.io) { repo.saveNote(n) } }
        nav.pop()
        toaster.show(if (isNew) tr("Note saved") else tr("Changes saved"))
    }
    val saveState = rememberUpdatedState(::save)
    DisposableEffect(nav) {
        val remove = nav.interceptBack { saveState.value(); true }
        onDispose { remove() }
    }

    SubScreen(
        if (isNew) tr("New note") else tr("Edit note"), onBack = ::save,
        actions = {
            Row(
                Modifier.height(44.dp).clip(F.iconShape).background(if (pinned) F.accentTint else F.card).clickable { pinned = !pinned }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.PushPin, null, tint = if (pinned) F.accent else F.ink, modifier = Modifier.size(18.dp))
                Text(if (pinned) tr("Pinned") else tr("Pin"), color = if (pinned) F.accent else F.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) Box(
                    Modifier.size(52.dp).clip(F.buttonShape).background(F.danger.copy(alpha = 0.12f))
                        .clickable(onClickLabel = tr("Delete note")) { confirmDelete = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Delete, tr("Delete note"), tint = F.danger) }
                PrimaryButton(
                    when { isNew && !filled -> tr("Write something first"); isNew -> tr("Save note"); else -> tr("Save changes") },
                    onClick = ::save, modifier = Modifier.weight(1f), enabled = filled || !isNew,
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 420.dp).clip(F.cardShape).background(noteColor(color)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                if (title.isEmpty()) Text(tr("Title"), color = F.muted, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                BasicTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = F.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold), cursorBrush = SolidColor(F.accent))
            }
            Box {
                if (content.isEmpty()) Text(tr("Write something…"), color = F.muted, fontSize = 15.sp)
                BasicTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    textStyle = TextStyle(color = F.ink, fontSize = 15.sp, lineHeight = 23.sp), cursorBrush = SolidColor(F.accent))
            }
            val o = original
            Text(
                if (o == null) tr("New · today") else tr("Last edited {0}", LocalDate.fromEpochDays((o.updatedAt / DAY_MS).toInt()).noteLabel(repo.today())),
                color = F.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        FCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Colour"), Modifier.weight(1f), color = F.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    noteTints.indices.forEach { i ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(noteColor(i))
                                .border(if (i == color) 2.dp else 1.dp, if (i == color) F.accent else F.border, CircleShape)
                                .clickable(onClickLabel = tr("Colour {0}", i + 1)) { color = i },
                        )
                    }
                }
            }
            Divider()
            val d = noteDate(createdAt)
            FRow(tr("Day"), value = "${d.weekday()} ${d.dayOfMonth} ${monthShort(d.monthNumber)} ${d.year}", onClick = { pickingDate = true })
        }
    }
    DatePickerHost(pickingDate, noteDate(createdAt), { pickingDate = false }, { d -> d?.let { createdAt = it.toEpochDays() * DAY_MS } })
    if (confirmDelete) Confirm(
        tr("Delete this note?"), tr("“{0}” will be removed. You can undo this right after.", title.ifBlank { tr("Untitled") }), tr("Delete"),
        onDismiss = { confirmDelete = false },
    ) {
        val id = noteId ?: return@Confirm
        val before = original
        activityLaunch { withContext(repo.io) { repo.deleteNote(id) } }
        nav.pop()
        toaster.show(tr("Note deleted"), undo = before?.let { n -> { activityLaunch { withContext(repo.io) { repo.saveNote(n) } } } })
    }
}
