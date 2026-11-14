package app.moneytracker.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** The rule-based text pieces of Smart entry, which must keep matching the training code. */
class SmartTextTest {
    @Test fun labelRunsGroupNeighbours() {
        assertEquals(
            listOf(LabelRun("AMT", 0..0), LabelRun("ITEM", 1..2), LabelRun("O", 3..3), LabelRun("ACC", 4..5)),
            labelRuns(listOf("AMT", "ITEM", "ITEM", "O", "ACC", "ACC")),
        )
        assertEquals(emptyList(), labelRuns(emptyList()))
        assertEquals(listOf(LabelRun("O", 0..0)), labelRuns(listOf("O")))
    }

    @Test fun normKeepsNumbersAndDropsSymbols() {
        assertEquals("rs 1250 for chai", SmartText.norm("₹1,250 for chai!"))
        assertEquals("rs 12.50 ok", SmartText.norm("₹12.50. ok,"))
        assertEquals("a b", SmartText.norm("a , b"))
        assertEquals("tom & jerry's", SmartText.norm("Tom & Jerry's 🍔"))
    }

    @Test fun translitWritesHindiInLatinLetters() {
        assertEquals("petrol", SmartText.translit("पेट्रोल"))
        assertEquals("kal", SmartText.translit("कल"))
        assertEquals("300", SmartText.translit("३००"))
        assertEquals("shoes", SmartText.translit("shoes"))
    }

    @Test fun cleanCategoryDropsEmoji() {
        assertEquals("food", SmartText.cleanCategory("🍔 Food"))
        assertEquals("🍔", SmartText.cleanCategory("🍔"))
    }
}
