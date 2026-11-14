package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.Accent
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.domain.Tag

/** A [TagsModel] working in the calling screen's scope. */
@Composable
private fun rememberTagsModel(): TagsModel {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    return remember(repo) { TagsModel(repo.tagStore, repo.io, scope) }
}

@Composable
fun TagsScreen() {
    val model = rememberTagsModel()
    val tags by model.tags.collectAsState()
    TagsContent(tags, onSave = model::save, onDelete = model::delete)
}

@Composable
fun TagsContent(tags: List<Tag>, onSave: (Tag, String) -> Unit, onDelete: (String) -> Unit) {
    val nav = LocalNavigator.current
    var editing by remember { mutableStateOf<Tag?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(tr("Tags"), nav::pop)
            HorizontalDivider(color = DividerColor)
            if (tags.isEmpty()) EmptyState(tr("No tags yet. Add one here or while entering a transaction."))
            LazyColumn {
                items(tags, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceColor).clickable { editing = t }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#${t.name}", Modifier.weight(1f))
                        IconButton(onClick = { onDelete(t.id) }) { Icon(Icons.Outlined.Delete, tr("Delete tag"), tint = SubtleText) }
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
        AddFab(Modifier.align(Alignment.BottomEnd)) { editing = Tag("", "", tags.size) }
    }
    editing?.let { t -> TagNameDialog(t, onDismiss = { editing = null }) { name -> onSave(t, name); editing = null } }
}

@Composable
private fun TagNameDialog(t: Tag, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(t) { mutableStateOf(t.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t.id.isEmpty()) tr("Add tag") else tr("Rename tag")) },
        text = { OutlinedTextField(name, { name = it.removePrefix("#") }, label = { Text(tr("Name")) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text(tr("Save"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Multi-select tag picker with inline "create". */
@Composable
fun TagPickerDialog(selected: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val model = rememberTagsModel()
    val tags by model.tags.collectAsState()
    var chosen by remember { mutableStateOf(selected.toSet()) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tags")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tags.isEmpty()) Text(tr("No tags yet — create one below."), color = SubtleText, fontSize = 13.sp)
                FlowChips(tags.map { it.id to "#" + it.name }, chosen) { id -> chosen = if (id in chosen) chosen - id else chosen + id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.removePrefix("#") }, Modifier.weight(1f), label = { Text(tr("New tag")) }, singleLine = true)
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        val name = newName; newName = ""
                        model.tagNamed(name) { id -> chosen = chosen + id }
                    }) { Text(tr("Add"), color = Accent) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen.toList()) }) { Text(tr("Done"), color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
