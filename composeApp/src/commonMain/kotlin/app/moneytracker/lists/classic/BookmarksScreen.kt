package app.moneytracker.lists.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.app.LocalNavigator
import app.moneytracker.app.Screen
import app.moneytracker.core.LocalCurrency
import app.moneytracker.core.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.tr
import app.moneytracker.data.AccountStore
import app.moneytracker.data.BookmarkStore
import app.moneytracker.data.CategoryStore
import app.moneytracker.data.DraftType
import app.moneytracker.data.Template
import app.moneytracker.data.TxnDraft
import app.moneytracker.designsystem.classic.BackTopBar
import app.moneytracker.designsystem.classic.DividerColor
import app.moneytracker.designsystem.classic.EmptyState
import app.moneytracker.designsystem.classic.ExpenseColor
import app.moneytracker.designsystem.classic.IncomeColor
import app.moneytracker.designsystem.classic.InkSoft
import app.moneytracker.designsystem.classic.SubtleText
import app.moneytracker.designsystem.classic.SurfaceColor
import app.moneytracker.designsystem.classic.categoryPath
import app.moneytracker.domain.Category
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** The bookmarks, with the names their rows show. */
data class BookmarksState(val bookmarks: List<Template>, val categoryById: Map<String, Category>, val accountNames: Map<String, String>)

/** Saved entries added in one tap: listing, deleting, and turning one into a new entry dated today. */
class BookmarksModel(
    private val bookmarks: BookmarkStore,
    categories: CategoryStore,
    accounts: AccountStore,
    private val today: () -> LocalDate,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<BookmarksState> = combine(bookmarks.templates, categories.categoryById, accounts.accountNames, ::BookmarksState)
        .stateIn(scope, SharingStarted.Eagerly,
            BookmarksState(bookmarks.templates.current, categories.categoryById.current, accounts.accountNames.current))

    /** A new entry filled from [t], dated today. */
    fun draftOf(t: Template) = TxnDraft(t.type, today(), t.accountId.orEmpty(), t.toAccountId, t.categoryId, t.amount, note = t.note, memo = t.memo)

    fun delete(id: String) {
        scope.launch { withContext(io) { bookmarks.deleteTemplate(id) } }
    }
}

@Composable
fun TemplatesScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model = remember(repo) { BookmarksModel(repo.bookmarkStore, repo.categoryStore, repo.accountStore, repo.today, repo.io, scope) }
    val state by model.state.collectAsState()
    BookmarksContent(state, onUse = { t -> nav.pop(); nav.push(Screen.EditTxn(preset = model.draftOf(t))) }, onDelete = model::delete)
}

@Composable
fun BookmarksContent(state: BookmarksState, onUse: (Template) -> Unit, onDelete: (String) -> Unit) {
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Bookmarks"), nav::pop)
        HorizontalDivider(color = DividerColor)
        if (state.bookmarks.isEmpty()) {
            EmptyState(tr("No bookmarks yet. Open a transaction and tap ☆ to save it as one."), Icons.Outlined.StarOutline)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.bookmarks, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().background(SurfaceColor).clickable { onUse(t) }.padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium)
                        val detail = listOfNotNull(
                            t.categoryId?.let { categoryPath(it, state.categoryById) },
                            t.accountId?.let { state.accountNames[it] },
                            if (t.type == DraftType.TRANSFER) "→ ${state.accountNames[t.toAccountId].orEmpty()}" else null,
                        )
                        Text(detail.joinToString(" · "), color = SubtleText, fontSize = 13.sp)
                    }
                    if (t.amount > 0) Text(currency.format(t.amount), color = if (t.type == DraftType.INCOME || t.type == DraftType.REFUND) IncomeColor else if (t.type == DraftType.EXPENSE) ExpenseColor else InkSoft)
                    IconButton(onClick = { onDelete(t.id) }) { Icon(Icons.Outlined.Delete, tr("Delete bookmark"), tint = SubtleText) }
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    }
}
