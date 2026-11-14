package app.moneytracker.lists

import app.moneytracker.data.TagStore
import app.moneytracker.data.newId
import app.moneytracker.domain.Tag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tags: the list, renaming, deleting, and creating one by name from the entry editor's picker. */
class TagsModel(private val store: TagStore, private val io: CoroutineDispatcher, private val scope: CoroutineScope) {
    val tags: StateFlow<List<Tag>> = store.tags.stateIn(scope, SharingStarted.Eagerly, store.tags.current)

    /** Saves [tag] under [name]; a new tag (empty id) gets one. */
    fun save(tag: Tag, name: String) {
        scope.launch { withContext(io) { store.saveTag(tag.copy(id = tag.id.ifEmpty { newId() }, name = name)) } }
    }

    fun delete(id: String) {
        scope.launch { withContext(io) { store.deleteTag(id) } }
    }

    /** The tag called [name], created if needed; [onReady] gets its id. */
    fun tagNamed(name: String, onReady: (String) -> Unit) {
        scope.launch { onReady(withContext(io) { store.tagNamed(name) }) }
    }
}

