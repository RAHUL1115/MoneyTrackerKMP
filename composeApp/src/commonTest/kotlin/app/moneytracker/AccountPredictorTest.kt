package app.moneytracker

import app.moneytracker.domain.AccountPredictor
import app.moneytracker.domain.AccountPredictor.Ask
import app.moneytracker.domain.AccountPredictor.Past
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountPredictorTest {
    private val today = 20_000L
    private fun past(acc: String, cat: String?, note: String = "", amount: Long = 100_00, daysAgo: Long = 1) =
        Past(acc, cat, cat, note, amount, today - daysAgo)
    private fun best(history: List<Past>, cat: String?, note: String = "", amount: Long? = null, allowed: Set<String>? = null) =
        AccountPredictor.rank(history, Ask(cat, cat, note, amount, today), allowed).firstOrNull()?.first

    @Test fun noHistoryNoGuess() = assertEquals(null, best(emptyList(), "food"))

    @Test fun categoryEnvelopeBeatsOverallHabit() {
        val h = List(10) { past("card", "food", daysAgo = 10L - it) } + List(3) { past("clothes_env", "clothes", daysAgo = 5) }
        assertEquals("card", best(h, "food"))
        assertEquals("clothes_env", best(h, "clothes"))
    }

    @Test fun noteWordsSplitOneCategory() {
        val h = List(5) { past("fuel_card", "travel", "for fuel") } + List(5) { past("bank", "travel", "train ticket") }
        assertEquals("fuel_card", best(h, "travel", "fuel"))
        assertEquals("bank", best(h, "travel", "ticket to pune"))
    }

    @Test fun unseenClueChangesNothingAndRareAccountDoesNotWin() {
        val h = List(8) { past("card", "food") } + past("odd_card", "bills")
        assertEquals("card", best(h, "food", "something never typed before"))
        assertEquals("card", best(h, "gifts"))
    }

    @Test fun amountSizeSeparatesEverydayFromBigSpends() {
        val h = List(6) { listOf(past("card", "other", amount = 45_00), past("bank", "other", amount = 50_000_00)) }.flatten()
        fun card(amount: Long?) = AccountPredictor.rank(h, Ask("other", "other", "", amount, today)).first { it.first == "card" }.second
        assertTrue(card(60_00) > card(null), "a small amount points to the everyday card")
        assertTrue(card(60_000_00) < card(null), "a big amount points away from it")
    }

    @Test fun recentSwitchWinsOverOldHabit() {
        val h = List(6) { past("old_card", "food", daysAgo = 200) } + List(3) { past("new_card", "food", daysAgo = 3) }
        assertEquals("new_card", best(h, "food"))
    }

    @Test fun onlyAllowedAccountsAndOnlyTheLastYear() {
        val h = List(5) { past("hidden", "food") } + past("visible", "food", daysAgo = 30) + List(9) { past("ancient", "food", daysAgo = 500) }
        assertEquals("visible", best(h, "food", allowed = setOf("visible", "ancient")))
        assertEquals(null, best(List(3) { past("ancient", "food", daysAgo = 500) }, "food"))
    }

    @Test fun confidencesAddUpToOne() {
        val r = AccountPredictor.rank(List(3) { past("a", "x") } + past("b", "y"), Ask("x", "x", "", null, today))
        assertTrue(kotlin.math.abs(r.sumOf { it.second } - 1.0) < 1e-9)
        assertEquals("a", r.first().first)
    }
}
