package app.moneytracker

import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.RepeatType
import app.moneytracker.ui.Navigator
import app.moneytracker.ui.Screen
import app.moneytracker.ui.ScreenCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** The back stack survives the app being closed in the background: every screen writes to a string and back. */
class NavigationTest {
    private val day = LocalDate.parse("2026-11-10")

    /** One of every screen, with awkward text (separators, digits, colons, emoji, empty) where a screen holds any. */
    private val every: List<Screen> = listOf(
        Screen.EditTxn(),
        Screen.EditTxn(txnId = "t1", date = day, captureId = "c:9"),
        Screen.EditTxn(preset = TxnDraft(
            DraftType.TRANSFER, day, "acc_bank", toAccountId = "acc_cash", categoryId = null, amount = 12_345, fee = 50,
            note = "3:abc~ ~ \u001F lunch 🍕", memo = "", installments = 3, repeat = RepeatType.MONTHLY, repeatEnd = day,
            currency = "USD", tagIds = listOf("t:1", "", "~"), lat = 12.9716, lng = -77.5946, timeOfDay = 615, origin = "sms 10:30", rate = 83.25,
        )),
        Screen.EditTxn(preset = TxnDraft(DraftType.REFUND, day, "", amount = 0)),
        Screen.AccountDetail("acc_card"), Screen.EditAccount(), Screen.EditAccount("acc_x"),
        Screen.Categories(CategoryKind.INCOME), Screen.StatsDetail("cat_ex_0", true, day, day, "Food: Nov"),
        Screen.EditNote(), Screen.EditNote("n1"), Screen.BudgetDetail(202611), Screen.License("apache-2.0"), Screen.MoreHub("safety"),
        Screen.AccountList, Screen.Recurring, Screen.Backup, Screen.Configuration, Screen.Budget, Screen.Search, Screen.Templates,
        Screen.Inbox, Screen.MessageSettings, Screen.Calculator, Screen.Help, Screen.About, Screen.Currencies, Screen.Tags,
        Screen.Security, Screen.AccountsTrend, Screen.AccountGroups, Screen.WebDashboard,
    )

    @Test fun everyScreenRoundTrips() {
        every.forEach { s ->
            val text = ScreenCodec.encode(s)
            kotlin.test.assertNotNull(text, "no tag for $s")
            assertEquals(s, ScreenCodec.decode(text), text)
        }
    }

    @Test fun everyScreenTypeIsCovered() {
        // A new screen must be added to ScreenCodec (and to the list above) or it can't come back after the app is closed.
        val covered = every.map { it.javaClass.name }.toSet()
        val all = Screen::class.java.declaredClasses.filter { Screen::class.java.isAssignableFrom(it) }.map { it.name }.toSet()
        assertTrue(all.size >= 27)
        assertEquals(emptySet(), all - covered)
    }

    @Test fun stackSavesAndRestoresInOrder() {
        val nav = Navigator()
        nav.push(Screen.Search); nav.push(Screen.EditTxn(txnId = "t1")); nav.push(Screen.MoreHub("help"))
        val saved = nav.saveState()
        val again = Navigator()
        again.restoreState(saved)
        assertEquals(nav.stack.toList(), again.stack.toList())
    }

    @Test fun unreadableEntryEndsTheRestoredStack() {
        val nav = Navigator()
        nav.restoreState(listOf(ScreenCodec.encode(Screen.Budget)!!, "9:not-a-tag", ScreenCodec.encode(Screen.Help)!!))
        assertEquals(listOf<Screen>(Screen.Budget), nav.stack.toList())
        nav.restoreState(listOf("garbage"))
        assertTrue(nav.stack.isEmpty())
    }

    @Test fun backAsksTheTopInterceptorFirst() {
        val nav = Navigator()
        nav.push(Screen.Search)
        var panelOpen = true
        val remove = nav.interceptBack { if (panelOpen) { panelOpen = false; true } else false }
        assertTrue(nav.back()); assertEquals(1, nav.stack.size)       // closed the panel only
        assertTrue(nav.back()); assertTrue(nav.stack.isEmpty())       // then the screen
        remove()
        assertFalse(nav.back())                                       // nothing left: the platform handles it
    }

    @Test fun resultIsTakenOnce() {
        val nav = Navigator()
        nav.setResult("k", 42)
        assertTrue(nav.hasResult("k"))
        assertEquals(42, nav.takeResult<Int>("k"))
        assertNull(nav.takeResult<Int>("k"))
    }
}
