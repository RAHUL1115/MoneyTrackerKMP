package app.moneytracker.ui.fresh

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.moneytracker.core.tr

// TalkBack state for the money screens' open / close headers (account groups, net worth, breakdown parts, budgets).

/** Tells TalkBack whether the tapped header's content is showing ("Expanded") or not ("Collapsed"); draws nothing. */
internal fun Modifier.expandState(expanded: Boolean): Modifier =
    semantics { stateDescription = if (expanded) tr("Expanded") else tr("Collapsed") }
