package app.moneytracker.activity.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.activity.BulkEditModel
import app.moneytracker.activity.BulkResult
import app.moneytracker.app.LocalCurrency
import app.moneytracker.app.LocalRepo
import app.moneytracker.core.collectAsState
import app.moneytracker.core.outlivingScope
import app.moneytracker.core.tr
import app.moneytracker.designsystem.classic.DatePickerHost
import app.moneytracker.designsystem.classic.flattenCategories
import app.moneytracker.designsystem.classic.monthShort
import app.moneytracker.designsystem.fresh.Confirm
import app.moneytracker.designsystem.fresh.F
import app.moneytracker.designsystem.fresh.LocalToaster
import app.moneytracker.designsystem.fresh.Sheet
import app.moneytracker.domain.Account
import app.moneytracker.domain.AccountGroup
import app.moneytracker.domain.Category
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.Txn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private enum class BulkSheet { DATE, CATEGORY, ACCOUNT, DELETE }

private fun entries(count: Int) = if (count == 1) tr("1 entry") else tr("{0} entries", count)

/** The dark panel over the list while selecting: count and net, then Date, Category, Account and Delete for all at once. */
@Composable
internal fun SelectionPanel(selected: List<Txn>, onDone: () -> Unit, modifier: Modifier) {
    val repo = LocalRepo.current
    val toaster = LocalToaster.current
    // Undo can come after the tab is left, so the changes run in a scope that outlives it.
    val model = remember(repo) { BulkEditModel(repo.txnStore, repo.io, outlivingScope()) }
    val categories by repo.categoryStore.categories.collectAsState()
    val accounts by repo.accountStore.accounts.collectAsState()
    val groups by repo.accountStore.groups.collectAsState()
    var sheet by remember { mutableStateOf<BulkSheet?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val n = selected.size

    /** Shows what changed with Undo; a count below [n] means some entries could not take the change. */
    fun done(label: String): (BulkResult) -> Unit = { r ->
        val msg = if (r.partial) tr("{0} of {1} entries changed (transfers keep their accounts and have no category).", r.changed, n)
            else tr("{0} for {1}", label, entries(n))
        toaster.show(msg) { r.undo() }
    }
    val actions = BulkActions(
        setDate = { d -> sheet = null; model.setDate(selected, d, done(tr("Date changed to {0}", "${d.dayOfMonth} ${monthShort(d.monthNumber)}"))); onDone() },
        setCategory = { c -> sheet = null; model.setCategory(selected, c.id, done(tr("Category set to {0}", c.name))); onDone() },
        setAccount = { a -> sheet = null; model.setAccount(selected, a.id, done(tr("Account set to {0}", a.name))); onDone() },
        delete = { model.delete(selected) { toaster.show(tr("{0} deleted", entries(n))) }; onDone() },
    )
    PanelBar(selected, modifier, onSheet = { sheet = it })
    BulkSheets(sheet, selected, categories, accounts, groups, actions, onClose = { sheet = null }, onPickDate = { sheet = null; pickDate = true })
    DatePickerHost(pickDate, selected.firstOrNull()?.date, { pickDate = false }, { d -> if (d != null) actions.setDate(d) })
}

/** What each bulk sheet does once a choice is made. */
private class BulkActions(
    val setDate: (LocalDate) -> Unit,
    val setCategory: (Category) -> Unit,
    val setAccount: (Account) -> Unit,
    val delete: () -> Unit,
)

@Composable
private fun PanelBar(selected: List<Txn>, modifier: Modifier, onSheet: (BulkSheet) -> Unit) {
    val currency = LocalCurrency.current
    val n = selected.size
    val net = selected.filter { it.kind.isListed }.sumOf { it.signedAmount }
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().clip(F.cardShape).background(F.ink).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (n == 0) tr("Tap entries to select them") else tr("{0} selected · {1}", n, currency.format(net, showSign = true)),
            Modifier.padding(start = 4.dp), color = F.page, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(tr("Date"), n > 0) { onSheet(BulkSheet.DATE) }
            PanelButton(tr("Category"), n > 0) { onSheet(BulkSheet.CATEGORY) }
            PanelButton(tr("Account"), n > 0) { onSheet(BulkSheet.ACCOUNT) }
            PanelButton(tr("Delete"), n > 0, danger = true) { onSheet(BulkSheet.DELETE) }
        }
    }
}

@Composable
private fun BulkSheets(
    sheet: BulkSheet?, selected: List<Txn>, categories: List<Category>, accounts: List<Account>, groups: List<AccountGroup>,
    actions: BulkActions, onClose: () -> Unit, onPickDate: () -> Unit,
) {
    when (sheet) {
        BulkSheet.DATE -> DateSheet(selected.firstOrNull()?.date, actions.setDate, onClose, onPickDate)
        BulkSheet.CATEGORY -> Sheet(tr("Change category"), onClose) {
            Text(tr("Transfers have no category and stay as they are."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            val byId = categories.associateBy { it.id }
            ActOptionGrid(flattenCategories(categories).map { c ->
                val main = c.parentId?.let { byId[it]?.name }
                ActOption(c.name, main ?: tr(if (c.kind == CategoryKind.INCOME) "Income" else "Expense"), null, markName = main ?: c.name) { actions.setCategory(c) }
            })
        }
        BulkSheet.ACCOUNT -> Sheet(tr("Change account"), onClose) {
            Text(tr("Transfers keep their own accounts."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            val groupName = groups.associate { it.id to it.name }
            ActOptionGrid(accounts.filter { !it.hidden }.map { a -> ActOption(a.name, groupName[a.groupId].orEmpty(), null) { actions.setAccount(a) } })
        }
        BulkSheet.DELETE -> Confirm(
            tr("Delete {0}?", entries(selected.size)),
            if (selected.any { it.transferGroupId != null }) tr("Transfers are deleted with both sides.") else tr("This can't be undone."),
            tr("Delete"), onDismiss = onClose, onConfirm = actions.delete,
        )
        null -> {}
    }
}

@Composable
private fun DateSheet(firstSelected: LocalDate?, setDate: (LocalDate) -> Unit, onClose: () -> Unit, onPickDate: () -> Unit) {
    val today = LocalRepo.current.today()
    val first = firstSelected ?: today
    val plain = LocalDate(1970, 1, 1)
    Sheet(tr("Change date"), onClose) {
        Text(tr("Moves the selected entries to another day."), color = F.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        ActOptionGrid(
            listOf(
                ActOption(tr("Today"), today.actDayLabel(plain), today.dayOfMonth.toString()) { setDate(today) },
                today.minus(DatePeriod(days = 1)).let { y -> ActOption(tr("Yesterday"), y.actDayLabel(plain), y.dayOfMonth.toString()) { setDate(y) } },
                LocalDate(first.year, first.monthNumber, 1).let { f -> ActOption("1 ${monthShort(f.monthNumber)}", f.actDayLabel(plain), "1") { setDate(f) } },
                ActOption(tr("Pick a date…"), tr("Any day"), "…", onClick = onPickDate),
            ),
        )
    }
}

@Composable
private fun RowScope.PanelButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (danger) F.danger else F.page.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = (if (danger) Color.White else F.page).copy(alpha = if (enabled) 1f else 0.45f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
