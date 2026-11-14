package app.moneytracker

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.moneytracker.domain.ai.SmartParser
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Smart entry engine on a real device/emulator from the APK's assets: load time, memory, speed, and the same
 * 690 answers as the prototype. Safe on a data-bearing install: it never touches the app's database.
 * Run with `am instrument -e class app.moneytracker.SmartParserDeviceTest` (not connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class SmartParserDeviceTest {
    @Test fun runsOnDevice() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val appAssets = inst.targetContext.assets
        val rt = Runtime.getRuntime()
        System.gc(); val heap0 = rt.totalMemory() - rt.freeMemory(); val native0 = Debug.getNativeHeapAllocatedSize()
        val t0 = System.nanoTime()
        val parser = SmartParser.load { name -> appAssets.open("smart/$name").use { it.readBytes() } }
        val loadMs = (System.nanoTime() - t0) / 1e6
        System.gc(); val heapMb = (rt.totalMemory() - rt.freeMemory() - heap0) / 1e6; val nativeMb = (Debug.getNativeHeapAllocatedSize() - native0) / 1e6

        val gold = JSONObject(inst.context.assets.open("smart_golden.json").bufferedReader().readText())
        val accounts = gold.getJSONArray("accounts").let { a -> List(a.length()) { a.getString(it) } }
        val cases = gold.getJSONArray("cases")
        val expense = listOf("Food", "Groceries", "Transport", "Travel", "Housing", "Bills", "Subscriptions", "Entertainment", "Shopping",
            "Personal care", "Health", "Education", "Gifts", "Family", "Finance", "Other")
        val income = listOf("Salary", "Business", "Bonus", "Interest", "Allowance", "Refund/Cashback", "Other")

        val tFirst = System.nanoTime(); parser.parse("kal 2 dahi puri 50 hdfc card se", expense, income, accounts)
        val firstMs = (System.nanoTime() - tFirst) / 1e6           // includes building the category maps once
        var ok = 0; var okCat = 0; val times = ArrayList<Double>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val t = System.nanoTime()
            val r = parser.parse(c.getString("text"), expense, income, accounts)
            times.add((System.nanoTime() - t) / 1e6)
            val amt = if (c.isNull("amount")) null else c.getDouble("amount")
            val top = c.getJSONArray("top3").let { a -> List(a.length()) { a.getString(it) } }
            if (r.type == c.getString("type") && ((amt == null && r.amount == null) || (amt != null && r.amount != null && abs(r.amount!! - amt) < 1e-6)) &&
                r.account == c.getString("account") && r.daysAgo == c.getInt("daysAgo")) ok++
            if (r.categories.take(3).map { it.name } == top) okCat++
        }
        times.sort()
        val n = cases.length()
        val report = "SMART DEVICE: load ${"%.0f".format(loadMs)} ms · java heap +${"%.1f".format(heapMb)} MB · native +${"%.1f".format(nativeMb)} MB · " +
            "first parse ${"%.0f".format(firstMs)} ms · median ${"%.2f".format(times[n / 2])} ms · p95 ${"%.2f".format(times[n * 95 / 100])} ms · " +
            "fields $ok/$n · categories $okCat/$n"
        Log.i("SmartParser", report); println(report)
        val example = parser.parse("कल पेट्रोल 500 का डलवाया एचडीएफसी कार्ड से", expense, income, accounts)
        Log.i("SmartParser", "EXAMPLE hindi: ${example.type} ${example.amount} ${example.account} days=${example.daysAgo} ${example.categories.take(3)}")
        inst.sendStatus(0, android.os.Bundle().apply { putString("stream", report + "\n") })
        assertTrue(report, ok >= n * 0.99 && okCat >= n * 0.97)
    }
}
