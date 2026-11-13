package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.domain.Calc
import app.moneytracker.domain.Category
import app.moneytracker.domain.CurrencyFormat

// The bottom panels of the classic editor (EditTxnScreen.kt): keypad, categories and accounts.

/** Category / account pickers keep one height however few items there are, so the sheet never jumps. */
private val PanelBodyHeight = 260.dp

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** Bottom panels (keypad, category, account) float over the form as a rounded sheet. */
internal fun Modifier.sheet() = fillMaxWidth().shadow(16.dp, SheetShape).clip(SheetShape).background(SurfaceColor)

@Composable
internal fun PanelHeader(title: String, onEdit: (() -> Unit)?, onClose: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(SurfaceColor)) {
        // Grab handle: the panel reads as a sheet over the form.
        Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).background(FillStrong, RoundedCornerShape(50)).align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            extra?.invoke()
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, tr("Edit"), tint = InkSoft) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, tr("Close"), tint = InkSoft) }
        }
    }
}

/** The open panel: keypad for the amount or fee, the categories, or the accounts. */
@Composable
internal fun EditPanels(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val ui = c.ui
    val close = { ui.panel = Panel.NONE }
    when (ui.panel) {
        Panel.NONE -> Unit
        Panel.AMOUNT, Panel.FEE -> {
            val fee = ui.panel == Panel.FEE
            AmountKeypad(
                title = if (fee) "Fees" else "Amount", currency = c.model.fmt, text = if (fee) f.feeText else f.amountText,
                onChange = { v -> c.model.edit { if (fee) it.copy(feeText = v) else it.copy(amountText = v) } },
                onClose = close,
                onDone = { if (fee) close() else ui.nextAfterAmount(c.model.form) },
            )
        }
        Panel.CATEGORY -> CategoryPanel(
            c.choices.categories.filter { it.kind == f.catKind }, c.choices.useSubcategories, f.categoryId,
            onPick = { cat ->
                c.model.pickCategory(cat)
                ui.panel = if (c.model.form.accountId == null) Panel.ACCOUNT else Panel.NONE
                ui.focusNoteIfDone(c.model.form)
            },
            onEdit = { nav.push(Screen.Categories(f.catKind)) },
            onClose = close,
        )
        Panel.ACCOUNT, Panel.TO_ACCOUNT -> EditAccountPanel(c)
    }
}

@Composable
private fun EditAccountPanel(c: EditContext) {
    val nav = LocalNavigator.current
    val f = c.model.form
    val ui = c.ui
    val from = ui.panel == Panel.ACCOUNT
    val accountFirst = c.model.setup.accountFirst
    AccountPanel(
        title = if (f.isTransfer) (if (from) "From" else "To") else "Accounts",
        accounts = c.choices.accounts.filter { from || it.id != f.accountId },
        groups = c.choices.groups, grouped = c.accountsGrouped, onGrouped = c.hints::groupAccounts,
        selectedIds = setOfNotNull(f.accountId, f.toAccountId),
        onPick = { a ->
            if (from) c.model.pickFrom(a) else c.model.pickTo(a)
            val now = c.model.form
            ui.panel = when {
                from && now.isTransfer && now.toAccountId == null -> Panel.TO_ACCOUNT
                accountFirst && now.amountText.isEmpty() -> Panel.AMOUNT
                else -> Panel.NONE
            }
            ui.focusNoteIfDone(now)
        },
        onEdit = { nav.push(Screen.AccountList) },
        onClose = { ui.panel = Panel.NONE },
    )
}

@Composable
private fun AmountKeypad(title: String, currency: CurrencyFormat, text: String, onChange: (String) -> Unit, onClose: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.sheet()) {
        PanelHeader(title, null, onClose)
        // Live result while an expression is being typed.
        if (Calc.hasOperator(text)) {
            val result = Calc.evaluate(text, currency.decimals)
            Text(
                "= " + (result?.let { currency.parse(it)?.let { m -> currency.format(m) } } ?: "…"),
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp), color = Accent, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
            )
        }
        Column(Modifier.fillMaxWidth().background(PageBackground).padding(6.dp)) {
            val keys = listOf(
                listOf("7", "8", "9", "÷", "⌫"),
                listOf("4", "5", "6", "×", "C"),
                listOf("1", "2", "3", "−", "="),
                listOf("00", "0", ".", "+", "Done"),
            )
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    row.forEach { k ->
                        KeypadKey(k) {
                            if (k != "Done") onChange(pressKey(text, k, currency))
                            // Done settles a pending expression first.
                            else if (!Calc.hasOperator(text)) onDone()
                            else Calc.evaluate(text, currency.decimals)?.let { onChange(it); onDone() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeypadKey(k: String, onClick: () -> Unit) {
    val isDone = k == "Done"
    val isOp = k in Calc.OPS || k == "="
    val isFn = k == "⌫" || k == "C"
    Box(
        Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp))
            .background(when { isDone -> Accent; isOp -> AccentTint; isFn -> FillStrong; else -> SurfaceColor })
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (k) {
            "⌫" -> Icon(Icons.AutoMirrored.Outlined.Backspace, tr("Delete digit"), tint = Ink)
            else -> Text(
                if (isDone) tr(k) else k, fontSize = if (isDone) 16.sp else if (isOp) 24.sp else 22.sp,
                color = if (isDone) Color.White else if (isOp) Accent else Ink,
                fontWeight = if (isDone) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun <T> GridPanel(items: List<T>, label: (T) -> String, onPick: (T) -> Unit, columns: Int = 3) {
    Column(Modifier.fillMaxWidth().background(PageBackground).height(PanelBodyHeight).verticalScroll(rememberScrollState()).padding(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(62.dp)) {
                row.forEach { item ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().padding(3.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceColor)
                            .clickable { onPick(item) }.padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label(item), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryPanel(categories: List<Category>, useSubcategories: Boolean, currentId: String?, onPick: (Category) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    val mains = categories.filter { it.parentId == null }
    Column(Modifier.sheet()) {
        PanelHeader("Category", onEdit, onClose)
        // Sub-categories off: a main category is picked in one tap.
        if (!useSubcategories) GridPanel(mains, { it.name }, onPick)
        else CategoryPanes(categories, mains, currentId, onPick)
    }
}

/** Sub-categories on: two panes — mains on the left, the selected main's subs on the right. */
@Composable
private fun CategoryPanes(categories: List<Category>, mains: List<Category>, currentId: String?, onPick: (Category) -> Unit) {
    val subsOf = categories.filter { it.parentId != null }.groupBy { it.parentId }
    val current = categories.firstOrNull { it.id == currentId }
    var selected by remember { mutableStateOf(current?.parentId ?: current?.id?.takeIf { subsOf.containsKey(it) }) }
    Row(Modifier.fillMaxWidth().height(PanelBodyHeight)) {
        Column(Modifier.weight(1f).background(PageBackground).verticalScroll(rememberScrollState())) {
            mains.forEach { m ->
                val hasSubs = subsOf.containsKey(m.id)
                val isSel = m.id == selected
                Row(
                    Modifier.fillMaxWidth().height(56.dp).background(if (isSel) SurfaceColor else PageBackground)
                        .clickable { if (hasSubs) selected = m.id else onPick(m) }
                        .padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m.name, Modifier.weight(1f), color = if (isSel) Accent else Ink, fontWeight = if (isSel) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hasSubs) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Has sub-categories"), tint = if (isSel) Accent else SubtleText)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            subsOf[selected].orEmpty().forEach { sub ->
                Box(Modifier.fillMaxWidth().height(56.dp).clickable { onPick(sub) }.padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(sub.name, color = if (sub.id == currentId) Accent else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
