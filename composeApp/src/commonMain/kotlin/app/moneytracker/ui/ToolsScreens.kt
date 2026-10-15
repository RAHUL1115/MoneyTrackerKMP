package app.moneytracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Calc

/** Built-in calculator with a tape, and a shortcut to record the result as an expense. */
@Composable
fun CalculatorScreen() {
    val repo = LocalRepo.current
    val nav = LocalNavigator.current
    val currency = LocalCurrency.current
    var expr by rememberSaveable { mutableStateOf("") }
    val tape = remember { mutableStateListOf<String>() }
    val preview = if (Calc.hasOperator(expr)) Calc.evaluate(expr, currency.decimals) else null

    fun press(k: String) {
        expr = when (k) {
            "C" -> ""
            "⌫" -> expr.dropLast(1)
            "=" -> Calc.evaluate(expr, currency.decimals)?.also { tape.add(0, "$expr = $it") } ?: expr
            "+", "−", "×", "÷" -> when {
                expr.isEmpty() -> if (k == "−") k else expr
                expr.last() in Calc.OPS -> expr.dropLast(1) + k
                else -> expr + k
            }
            "." -> if (expr.takeLastWhile { it !in Calc.OPS }.contains('.')) expr else expr + k
            else -> expr + k
        }
    }

    Column(Modifier.fillMaxSize().background(PageBackground)) {
        BackTopBar(tr("Calculator"), nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), reverseLayout = true) {
            items(tape) { Text(it, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = SubtleText, textAlign = TextAlign.End) }
        }
        Column(Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.End) {
            Text(expr.ifEmpty { "0" }, fontSize = 34.sp, fontWeight = FontWeight.Light, color = Ink, maxLines = 2, textAlign = TextAlign.End)
            Text(preview?.let { "= $it" } ?: " ", color = SubtleText, fontSize = 16.sp)
            val value = Calc.evaluate(expr, currency.decimals)
            TextButton(enabled = value != null && (currency.parse(value) ?: 0) > 0, onClick = {
                val amount = value?.let { currency.parse(it) } ?: return@TextButton
                nav.push(Screen.EditTxn(preset = TxnDraft(DraftType.EXPENSE, repo.today(), "", amount = amount)))
            }) { Text(tr("Record as expense"), color = Accent) }
        }
        val rows = listOf(listOf("C", "÷", "×", "⌫"), listOf("7", "8", "9", "−"), listOf("4", "5", "6", "+"), listOf("1", "2", "3", "="), listOf("00", "0", ".", ""))
        Column(Modifier.fillMaxWidth().background(PageBackground).padding(6.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(64.dp)) {
                    row.forEach { k ->
                        val digit = k.firstOrNull()?.isDigit() == true || k == "."
                        Box(
                            Modifier.weight(1f).padding(3.dp).fillMaxSize()
                                .background(if (k.isEmpty()) PageBackground else if (digit) SurfaceColor else AccentTint, RoundedCornerShape(12.dp))
                                .let { if (k.isEmpty()) it else it.clickable { press(k) } },
                            contentAlignment = Alignment.Center,
                        ) { Text(k, fontSize = 22.sp, color = if (digit) Ink else Accent) }
                    }
                }
            }
        }
    }
}


/** Offline help: one expandable topic per feature. */
@Composable
fun HelpScreen() {
    val nav = LocalNavigator.current
    val open = remember { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(tr("Help"), nav::pop)
        HorizontalDivider(color = DividerColor)
        LazyColumn(Modifier.fillMaxSize()) {
            HELP.forEachIndexed { i, (title, body) ->
                item(key = i) {
                    Column(Modifier.fillMaxWidth().background(SurfaceColor).clickable { if (i in open) open.remove(i) else open.add(i) }
                        .padding(horizontal = 24.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(title), Modifier.weight(1f), fontWeight = FontWeight.Medium, color = Ink)
                            Icon(if (i in open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = SubtleText)
                        }
                        if (i in open) Text(tr(body), Modifier.padding(top = 8.dp), color = InkSoft, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                    HorizontalDivider(color = DividerColor)
                }
            }
        }
    }
}

private val HELP = listOf(
    "Adding an entry" to "Tap + on the Trans. tab. Pick Income, Expense or Transfer, then the amount, category and account. " +
        "The keypad has a calculator (÷ × − +). Save closes the form; Continue keeps it open for the next entry. " +
        "Swipe left or right on the Trans. tab to change month (or tab, in Configuration › Swipe).",
    "Editing, copying and bookmarks" to "Tap an entry to view it, then edit. The ⋮ menu copies it (with today's date or the entry's date), " +
        "deletes it or saves it as a bookmark. Bookmarks (☆ on the Trans. tab) are ready-made entries you can add in one tap.",
    "Selecting several entries" to "Long-press an entry to start selecting. The bar shows how many are selected and their sum; " +
        "delete them together or change their date, category, account or note at once. Back clears the selection.",
    "Repeat and installments" to "In the form, tap Rep/Inst. Repeat adds the entry every day, week, month and so on (More › Repeat lists the rules; " +
        "tap one to change it). Installments split a credit-card purchase over several months.",
    "Transfers and fees" to "A transfer moves money between your accounts and is not income or expense. Add a fee to record bank charges. " +
        "Accounts marked “Transfers in count as expense” (savings, investments) turn money moved into them into spending.",
    "Credit cards" to "Set the billing day so statements are split into cycles; the card's page shows what's due, and Pay records the payment. " +
        "Tag a purchase “Due now” to make it due at once, or “Excluded from target” to leave it out of the card's monthly usage target.",
    "Hidden and deleted accounts" to "Hide an account in More › Accounts (eye icon): it leaves lists and pickers but keeps its balance and history. " +
        "In the account picker's group view hidden accounts are greyed but can still be picked. Deleted accounts and groups can be restored there too.",
    "Budgets" to "More › Budget sets a monthly amount per category (or a total). An amount applies from that month onward. " +
        "Trans. › Total shows what's left; tap it for each category.",
    "Stats and filter" to "Stats shows spending by category for a week, month, year or any period; tap a category for its trend and entries. " +
        "The filter on the Trans. tab narrows the lists to chosen categories or accounts.",
    "Bank SMS" to "Configuration › Bank SMS turns on detection. Detected messages wait in Detected SMS " +
        "until you save them, or are saved at once with Save automatically. Block numbers or words, filter out confusing text and import " +
        "older SMS. In an account's settings, set card digits or a sender to file its messages there.",
    "Backup and restore" to "A snapshot is kept every day (last 10). Export or share a backup file to keep a copy off the phone. " +
        "Restore file also reads Money Manager backups from Android and iPhone (.mmbak): accounts, entries, categories, budgets, repeats, " +
        "settings and passcode come across. Photos travel separately with Export photos / Import photos.",
    "Passcode" to "More › Passcode locks the app with a 4-digit code, with fingerprint or face unlock if your phone supports it.",
    "Widgets" to "Long-press your home screen and add MoneyTracker widgets: month summary, one-line totals, quick add and credit cards. " +
        "Configuration › Widget settings changes their colours and transparency.",
)
