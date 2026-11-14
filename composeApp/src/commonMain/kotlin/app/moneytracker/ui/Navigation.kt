package app.moneytracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    /** Start / stop the same-Wi-Fi web dashboard, its password and connected computers. */
    data object WebDashboard : Screen
    /** A More place that gathers related screens: "labels", "reuse", "sms", "safety" or "help". */
    data class MoreHub(val id: String) : Screen
}

/**
 * The back stack on top of the tabbed home screen; the platform wires its back button to [back].
 *
 * It outlives the screen it draws: [saveState] / [restoreState] carry the stack through the platform's saved state
 * (the app closed in the background), and [Host] keeps each open screen's saved UI state until that screen closes.
 * Screens hand a result to the one below with [setResult]; the one below reads it with [takeResult].
 */
class Navigator {
    val stack = mutableStateListOf<Screen>()
    private val backInterceptors = mutableStateListOf<() -> Boolean>()
    private val results = mutableStateMapOf<String, Any>()
    /** True when Back has something to do in the app: a screen to close, or an interceptor (e.g. a selection on Home). */
    val canPop get() = stack.isNotEmpty() || backInterceptors.isNotEmpty()

    // Class names only: a screen's fields can hold names the person typed.
    fun push(s: Screen) { stack.add(s); app.moneytracker.core.Log.d("Nav") { "→ ${s::class.simpleName} (depth ${stack.size})" } }
    fun pop(): Boolean = if (stack.isEmpty()) false else {
        val gone = stack.removeAt(stack.lastIndex)
        app.moneytracker.core.Log.d("Nav") { "← ${gone::class.simpleName} (depth ${stack.size})" }
        true
    }
    /** Back to the home screen (a shortcut or notification starts from there). */
    fun clear() { stack.clear() }

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

    /** Leaves [value] for whichever screen asks for [key] next (e.g. Search's filter for the Activity tab). */
    fun setResult(key: String, value: Any) { results[key] = value }
    /** The value left under [key], removed so it's used once. Observable: a screen recomposes when one arrives. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> takeResult(key: String): T? = (results[key] as T?)?.also { results.remove(key) }
    /** Peeks without taking (for keying an effect that then calls [takeResult]). */
    fun hasResult(key: String): Boolean = results.containsKey(key)

    /** The stack as strings, bottom first. A screen that can't be written ends the list. */
    fun saveState(): ArrayList<String> = ArrayList(stack.asSequence().map { ScreenCodec.encode(it) }.takeWhile { it != null }.filterNotNull().toList())

    /** Puts back a stack from [saveState]; stops at the first entry that can't be read. */
    fun restoreState(saved: List<String>) {
        stack.clear()
        stack.addAll(saved.asSequence().map { ScreenCodec.decode(it) }.takeWhile { it != null }.filterNotNull())
        app.moneytracker.core.Log.d("Nav") { "restored ${stack.size} of ${saved.size} screens" }
    }

    /**
     * Draws [home] with the top screen over it. Each screen keeps its saved UI state (tab, month, scroll, what was
     * typed) while others sit on top of it and across the app being closed in the background; it's dropped once the
     * screen closes. Home stays composed under other screens (not drawn or touchable) so Back shows it at once.
     *
     * [holder] must outlive anything that can replace the host (the lock screen), so the caller creates it above
     * that: then locking and unlocking keeps every screen's state, typed entries included.
     */
    @Composable
    fun Host(holder: SaveableStateHolder, home: @Composable () -> Unit, screen: @Composable (Screen) -> Unit) {
        val keys = listOf("home") + stack.mapIndexed { i, sc -> "$i:${ScreenCodec.encode(sc) ?: sc.toString()}" }
        val seen = remember { mutableSetOf<String>() }
        LaunchedEffect(keys) {
            (seen - keys.toSet()).forEach { holder.removeState(it) }
            seen.retainAll(keys.toSet()); seen.addAll(keys)
        }
        val top = stack.lastOrNull()
        holder.SaveableStateProvider("home") {
            Box(Modifier.fillMaxSize().then(if (top != null) Modifier.drawWithContent { }.clearAndSetSemantics { } else Modifier)) { home() }
        }
        if (top != null) holder.SaveableStateProvider(keys.last()) {
            // Takes every touch, so none reach Home underneath.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) { screen(top) }
        }
    }
}
