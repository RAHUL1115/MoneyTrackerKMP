package app.moneytracker.designsystem.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.moneytracker.domain.Txn

private val square = CornerSize(0)

/**
 * One row's slice of a card that is drawn as separate lazy items: the card's top corners on the first row, its
 * bottom corners on the last and square edges in between, so the rows together look like one [FListCard].
 */
internal fun cardSliceShape(first: Boolean, last: Boolean): Shape = when {
    first && last -> F.cardShape
    first -> F.cardShape.copy(bottomStart = square, bottomEnd = square)
    last -> F.cardShape.copy(topStart = square, topEnd = square)
    else -> RectangleShape
}

/**
 * A card of entries with one lazy item per entry, so a long list only builds the rows on screen. Rows after the
 * first get a [Divider] with the same [inset] as the row's content; each row's tap highlight fills its slice.
 * [gapAbove] is the space between the card and the item before it.
 */
internal fun LazyListScope.entryCardItems(
    entries: List<Txn>,
    keyPrefix: String,
    inset: Dp,
    gapAbove: Dp = 0.dp,
    row: @Composable (Txn) -> Unit,
) {
    itemsIndexed(entries, key = { _, t -> keyPrefix + t.id }, contentType = { _, _ -> "entry" }) { i, t ->
        val slice = cardSliceShape(first = i == 0, last = i == entries.lastIndex)
        Column(Modifier.padding(top = if (i == 0) gapAbove else 0.dp).fillMaxWidth().clip(slice).background(F.card)) {
            if (i > 0) Divider(inset)
            row(t)
        }
    }
}
