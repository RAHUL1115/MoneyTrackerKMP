package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.ui.LocalNavigator
import app.moneytracker.ui.Screen
import app.moneytracker.ui.tr

// The tray under the receipt card (AddScreen.kt): one tray that follows the row being filled (keypad, categories,
// accounts, then the phone keyboard for the note). Picking moves on to the next empty row; tapping a row jumps to
// it. The tray keeps its height, so nothing above it ever moves.

@Composable
internal fun Tray(c: AddContext, modifier: Modifier) {
    val ui = c.ui
    val accounts = rememberAccountTray(c)
    // Edge to edge, squared off, on the bottom of the screen; a hairline on top separates it from Save.
    Column(modifier.fullBleed().background(F.card)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(F.border))
        Row(Modifier.fillMaxWidth().height(48.dp).background(F.page.copy(alpha = 0.5f)).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (accounts != null && accounts.tabs.size > 1) AccountTabs(accounts) { ui.acctTab = it } else TrayTitle(c)
            NextButton(c)
        }
        // A hairline under the header marks where the choices start.
        Box(Modifier.fillMaxWidth().height(1.dp).background(F.border))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.step) {
                Step.AMOUNT -> TrayKeypad(c)
                Step.CATEGORY -> CategoryChoices(c)
                Step.ACCOUNT, Step.FROM, Step.TO -> if (accounts != null) AccountChoices(c, accounts)
                Step.NOTE, Step.CLOSED -> Unit  // the phone keyboard sits here
            }
        }
    }
}

/** Tabs scroll on their own; a tab that doesn't fit fades out before Next instead of being cut off. */
@Composable
private fun RowScope.AccountTabs(accounts: AccountTray, onPick: (String) -> Unit) {
    val tabScroll = rememberScrollState()
    Row(
        Modifier.weight(1f).fadeEnd(tabScroll.canScrollForward).horizontalScroll(tabScroll).padding(end = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        accounts.tabs.forEach { (id, label) -> TrayTab(label, id == accounts.tab) { onPick(id) } }
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun RowScope.TrayTitle(c: AddContext) {
    val f = c.model.form
    val sub = c.ui.subOf?.let { c.choices.catById[it] }
    val title = when (c.ui.step) {
        Step.AMOUNT -> if (c.ui.feeTarget) tr("Fee") else tr("Amount")
        Step.CATEGORY -> sub?.name ?: tr("Category")
        Step.ACCOUNT -> if (f.type == DraftType.INCOME || f.type == DraftType.REFUND) tr("Paid into") else tr("Pay from")
        Step.FROM -> tr("Move from")
        Step.TO -> tr("Move to")
        Step.NOTE -> tr("Note")
        Step.CLOSED -> ""
    }
    if (sub != null) Icon(Glyph.Back, tr("All categories"), tint = F.muted, modifier = Modifier.size(32.dp).clip(F.innerShape).clickable { c.ui.subOf = null }.padding(7.dp))
    Text(title, Modifier.weight(1f).padding(start = 4.dp), color = F.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NextButton(c: AddContext) {
    val focus = LocalFocusManager.current
    val onNote = c.ui.step == Step.NOTE
    Box(
        Modifier.height(32.dp).clip(F.innerShape).background(F.accentTint)
            .clickable { if (onNote) c.ui.closeTray(focus) else c.advance(focus) }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (onNote) tr("Done") else tr("Next ›"), color = F.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Moves the tray on to the next row still to fill. */
internal fun AddContext.advance(focus: androidx.compose.ui.focus.FocusManager) =
    ui.advance(model.form, model.setup.accountFirst, screen.captureId != null, focus)

@Composable
private fun TrayKeypad(c: AddContext) {
    val f = c.model.form
    val feeTarget = c.ui.feeTarget
    AddKeypad(
        if (feeTarget) f.feeText else f.amountText, c.model.fmt,
        { v -> c.model.edit { if (feeTarget) it.copy(feeText = v) else it.copy(amountText = v) } },
        Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun CategoryChoices(c: AddContext) {
    val f = c.model.form
    val focus = LocalFocusManager.current
    val nav = LocalNavigator.current
    val categories = c.choices.categories
    val sub = c.ui.subOf?.let { c.choices.catById[it] }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        val mains = categories.filter { it.kind == f.catKind && it.parentId == null }
        val current = f.categoryId?.let { c.choices.catById[it] }
        val subs = sub?.let { m -> categories.filter { it.parentId == m.id } }.orEmpty()
        if (sub != null) PickGrid(listOf(sub) + subs) { cat, mod ->
            PickRow(if (cat.id == sub.id) tr("Just {0}", cat.name) else cat.name, cat.id == f.categoryId, mod, icon = categoryIcon(sub.name)) {
                c.model.pickCategory(cat); c.advance(focus)
            }
        } else PickGrid(mains) { m, mod ->
            PickRow(m.name, m.id == (current?.parentId ?: current?.id), mod, icon = categoryIcon(m.name)) {
                c.model.pickCategory(m)
                if (c.choices.useSubcategories && categories.any { it.parentId == m.id }) c.ui.subOf = m.id else c.advance(focus)
            }
        }
        AddLink(tr("Edit categories ›"), Modifier.padding(top = 8.dp)) { nav.push(Screen.Categories(f.catKind)) }
    }
}

@Composable
private fun AccountChoices(c: AddContext, tray: AccountTray) {
    val focus = LocalFocusManager.current
    val nav = LocalNavigator.current
    val accounts = c.choices.accounts
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tray.shown.forEach { a ->
            val (bal, balColor) = balanceOf(a, c.tray.lines)
            AccountPickRow(a.name + (a.currency?.let { "  ($it)" } ?: ""), a.id == tray.current, a.id == tray.usual, bal, balColor) {
                if (tray.forTo) c.model.pickTo(a) else c.model.pickFrom(a)
                c.advance(focus)
            }
        }
        AddLink(if (accounts.isEmpty()) tr("Add an account ›") else tr("Edit accounts ›"), Modifier.padding(top = 4.dp)) {
            nav.push(if (accounts.isEmpty()) Screen.EditAccount() else Screen.AccountList)
        }
    }
}

/** The account tray for the row being filled, or null when the tray isn't on an account row. */
@Composable
private fun rememberAccountTray(c: AddContext): AccountTray? {
    val step = c.ui.step
    val f = c.model.form
    // Keyed on everything the guess reads, so a changed note or amount guesses again.
    val usual = remember(step, f.txnKind, f.categoryId, f.note, f.amountText) { if (step == Step.ACCOUNT) c.model.usualAccount() else null }
    if (step != Step.ACCOUNT && step != Step.FROM && step != Step.TO) return null
    return accountTray(f, step == Step.TO, usual, c.choices, c.tray.recentTxns, c.ui.acctTab)
}

/** Widens a child of the gutter-padded column to the full screen width. */
private fun Modifier.fullBleed(): Modifier = layout { measurable, constraints ->
    val extra = (F.gutter * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val p = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** Fades the last 24dp of a horizontally scrolling strip while more of it is off to the right. */
private fun Modifier.fadeEnd(on: Boolean): Modifier = if (!on) this else
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val w = 24.dp.toPx()
        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width), blendMode = BlendMode.DstIn)
    }
