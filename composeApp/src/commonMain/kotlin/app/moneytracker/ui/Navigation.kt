package app.moneytracker.ui

import androidx.compose.runtime.mutableStateListOf
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CategoryKind
import kotlinx.datetime.LocalDate

sealed interface Screen {
    /** [captureId]: editing a suggestion from the message inbox; saving marks it done and teaches the model. */
    data class EditTxn(
        val txnId: String? = null,
        val preset: TxnDraft? = null,
        val date: LocalDate? = null,
        val captureId: String? = null,
    ) : Screen
    data class AccountDetail(val accountId: String) : Screen
    data class EditAccount(val accountId: String? = null) : Screen
    data class Categories(val kind: CategoryKind) : Screen
    data class StatsDetail(val mainCategoryId: String, val income: Boolean, val from: LocalDate, val to: LocalDate, val title: String) : Screen
    data class EditNote(val noteId: String? = null) : Screen
    data object AccountList : Screen
    data object Recurring : Screen
    data object Backup : Screen
    data object Configuration : Screen
    data object Budget : Screen
    /** Budget lines for one month ([periodKey] = YearMonth.key), each opening its category's entries. */
    data class BudgetDetail(val periodKey: Int) : Screen
    data object Search : Screen
    data object Templates : Screen
    data object Inbox : Screen
    data object MessageSettings : Screen
    data object Calculator : Screen
    data object Help : Screen
    data object About : Screen
    /** Full text of a bundled license ([id] = file name in assets/licenses without .txt). */
    data class License(val id: String) : Screen
    data object Currencies : Screen
    data object Tags : Screen
    data object Security : Screen
    data object AccountsTrend : Screen
    data object AccountGroups : Screen
}

/** Minimal back stack on top of the tabbed home screen; the platform wires its back button to [pop]. */
class Navigator {
    val stack = mutableStateListOf<Screen>()
    private val backInterceptors = mutableStateListOf<() -> Boolean>()
    /** True when Back has something to do in the app: a screen to close, or an interceptor (e.g. a selection on Home). */
    val canPop get() = stack.isNotEmpty() || backInterceptors.isNotEmpty()

    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.isEmpty()) false else { stack.removeAt(stack.lastIndex); true }

    /**
     * Lets the top screen handle Back first (close a panel, confirm discarding edits).
     * The interceptor returns true when it consumed the event. Returns a remover for onDispose.
     */
    fun interceptBack(handler: () -> Boolean): () -> Unit {
        backInterceptors += handler
        return { backInterceptors -= handler }
    }

    /** System Back and the top-bar arrow both come here. */
    fun back(): Boolean = backInterceptors.lastOrNull()?.invoke() == true || pop()
}
