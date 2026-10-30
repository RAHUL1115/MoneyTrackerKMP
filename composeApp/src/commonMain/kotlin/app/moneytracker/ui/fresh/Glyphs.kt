package app.moneytracker.ui.fresh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The new layout's icons: thin line drawings on a 24-unit grid with round ends, the same set the design
 * boards use. [androidx.compose.material3.Icon] tints them like any other icon.
 */
object Glyph {
    private fun glyph(name: String, vararg paths: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    // Navigation and arrows
    val Back by lazy { glyph("Back", "m15 6-6 6 6 6", width = 2f) }
    val ChevronLeft get() = Back
    val ChevronRight by lazy { glyph("ChevronRight", "m9 6 6 6-6 6", width = 2f) }
    val ChevronDown by lazy { glyph("ChevronDown", "m6 9 6 6 6-6", width = 2f) }
    val ChevronUp by lazy { glyph("ChevronUp", "m6 15 6-6 6 6", width = 2f) }
    val Close by lazy { glyph("Close", "M6 6l12 12M18 6 6 18", width = 2f) }
    val Add by lazy { glyph("Add", "M12 5v14M5 12h14", width = 2.2f) }
    val Check by lazy { glyph("Check", "m5 12 5 5 9-10", width = 2f) }
    val Swap by lazy { glyph("Swap", "M7 7h12M15 3l4 4-4 4M17 17H5M9 13l-4 4 4 4", width = 2f) }
    val Backspace by lazy { glyph("Backspace", "M9 6h11v12H9l-5-6zM12.5 9.5l5 5M17.5 9.5l-5 5") }
    val Download by lazy { glyph("Download", "M12 3v12M7 10l5 5 5-5M4 21h16") }

    // Tabs
    val Home by lazy { glyph("Home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Activity by lazy { glyph("Activity", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01") }
    val Insights by lazy { glyph("Insights", "M21 12A9 9 0 1 1 12 3v9z", "M15 3.5A9 9 0 0 1 20.5 9H15z") }
    val Money by lazy { glyph("Money", rect(3f, 6f, 18f, 14f, 2f), "M3 10h18M16 15h2") }

    // Actions
    val Filter by lazy { glyph("Filter", "M4 5h16l-6 7.5V19l-4 1.5v-8z") }
    val Search by lazy { glyph("Search", circle(11f, 11f, 7f), "m20 20-3.5-3.5") }
    val Settings by lazy {
        glyph(
            "Settings", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val Sliders by lazy {
        glyph("Sliders", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f))
    }
    val Edit by lazy { glyph("Edit", "M4 20h4L19 9l-4-4L4 16z", width = 2f) }
    val Delete by lazy { glyph("Delete", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Copy by lazy { glyph("Copy", rect(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3") }
    val Paste by lazy { glyph("Paste", rect(6f, 4f, 12f, 17f, 2f), "M9 4V3h6v1M9 10h6M9 14h4") }
    val Pin by lazy { glyph("Pin", "M9 3h6l-1 6 4 4H6l4-4z", "M12 13v8") }
    val Star by lazy { glyph("Star", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z") }
    val Mic by lazy { glyph("Mic", rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3", width = 2f) }
    val Calendar by lazy { glyph("Calendar", "M4 6.5h16v13H4zM4 10.5h16M8 4v4M16 4v4", width = 2f) }
    val Clock by lazy { glyph("Clock", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 1 0 0-17M12 7.5V12l3 2", width = 2f) }
    val Card by lazy { glyph("Card", "M3.5 6.5h17v11h-17zM3.5 10h17M7 14.5h3") }
    val Note by lazy { glyph("Note", "M5 4.5h10l4 4v11H5zM15 4.5v4h4M8.5 12.5h7M8.5 16h4") }
    val Statement by lazy { glyph("Statement", "M6 3h9l4 4v14H6z", "M14 3v5h5M9 13h7M9 17h5") }
    val Trend by lazy { glyph("Trend", "M3 17l6-6 4 4 8-8", "M15 7h6v6") }
    val Message by lazy { glyph("Message", "M4 5h16v11H8l-4 4z", "M8 9h8M8 12h5") }
    val Backup by lazy { glyph("Backup", "M7 18a5 5 0 0 1-.6-10A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z", "M12 11v6M9.5 13.5 12 11l2.5 2.5") }
    val Lock by lazy { glyph("Lock", rect(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3", width = 2f) }
    val Repeat by lazy { glyph("Repeat", "M17 3l4 4-4 4M3 11V10a3 3 0 0 1 3-3h15M7 21l-4-4 4-4M21 13v1a3 3 0 0 1-3 3H3") }
    val Eye by lazy { glyph("Eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", circle(12f, 12f, 3f)) }
    val EyeOff by lazy {
        glyph("EyeOff", "M10 5.7A9 9 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a16 16 0 0 1-2.3 3.1M6.7 6.7A16 16 0 0 0 2.5 12S6 18.5 12 18.5a9 9 0 0 0 5.3-1.7M9.9 9.9a3 3 0 0 0 4.2 4.2M3 3l18 18")
    }
    val Fingerprint by lazy {
        glyph("Fingerprint", "M5 9.5a7.5 7.5 0 0 1 14 0M7.5 12a4.5 4.5 0 0 1 9 0v1.5M12 12v2.5a7 7 0 0 1-1.5 4.5M7.5 15a10 10 0 0 1-.8 3.5M16.5 16.5a12 12 0 0 1-.8 3M19.5 12.5v.5")
    }
    val Camera by lazy { glyph("Camera", "M4 8h3l1.5-2.5h7L17 8h3v11H4z", circle(12f, 13.5f, 3.5f)) }
    val Place by lazy { glyph("Place", "M12 21s-6.5-5.5-6.5-11a6.5 6.5 0 0 1 13 0c0 5.5-6.5 11-6.5 11z", circle(12f, 10f, 2.5f)) }
    val Error by lazy { glyph("Error", circle(12f, 12f, 9f), "M12 8v5M12 16h.01") }
    val Done by lazy { glyph("Done", circle(12f, 12f, 9f), "m8.5 12 2.5 2.5 4.5-5") }
    val More by lazy { glyph("More", "M6 12h.01M12 12h.01M18 12h.01", width = 2.4f) }

    // Categories
    val Travel by lazy { glyph("Travel", "M5 16v-3.5L6.8 7.6A1.5 1.5 0 0 1 8.2 6.6h7.6a1.5 1.5 0 0 1 1.4 1L19 12.5V16M4 16h16M5 12.5h14M7 16v2M17 16v2M7.5 14.2h.01M16.5 14.2h.01") }
    val Food by lazy { glyph("Food", "M7 3v7M5 3v4.5a2 2 0 0 0 4 0V3M7 10v11M17 21V3c-2.2 1.2-3.5 3.8-3.5 7.5H17") }
    val Household by lazy { glyph("Household", "M5 11V8.5A2.5 2.5 0 0 1 7.5 6h9A2.5 2.5 0 0 1 19 8.5V11M3 13a2 2 0 0 1 4 0v1.5h10V13a2 2 0 0 1 4 0v4.5H3zM6 17.5V19M18 17.5V19") }
    val Bills by lazy { glyph("Bills", "M6.5 3.5h11v17l-2.75-1.6-2.75 1.6-2.75-1.6-2.75 1.6zM9.5 8h5M9.5 11.5h5M9.5 15h3") }
    val Rent by lazy { glyph("Rent", "M3.5 11 12 4.5l8.5 6.5M5.5 9.5V20h13V9.5M10 20v-5.5h4V20") }
    val Clothes by lazy { glyph("Clothes", "M9 4.5 12 6l3-1.5 4.5 3-2 3.5-2-1V20h-7V10l-2 1-2-3.5z") }
    val Gym by lazy { glyph("Gym", "M4.5 9.5h2.5v5H4.5zM17 9.5h2.5v5H17zM7 8h2.5v8H7zM14.5 8H17v8h-2.5zM9.5 12h5M2.5 12h2M19.5 12h2") }
    val Medical by lazy { glyph("Medical", "M6 5.5h12a.5.5 0 0 1 .5.5v12a.5.5 0 0 1-.5.5H6a.5.5 0 0 1-.5-.5V6a.5.5 0 0 1 .5-.5zM12 9v6M9 12h6") }
    val Insurance by lazy { glyph("Insurance", "M12 3.5 18.5 6v5.5c0 4.2-2.7 7.2-6.5 8.9-3.8-1.7-6.5-4.7-6.5-8.9V6zM9.5 12l1.8 1.8 3.4-3.6") }
    val Entertainment by lazy { glyph("Entertainment", "M4.5 6h15v12h-15zM4.5 10h15M8.5 6v4M12 6v4M15.5 6v4") }
    val Recharge by lazy { glyph("Recharge", "M8.5 3.5h7a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1zM11 17.5h2") }
    val Salary by lazy { glyph("Salary", "M3.5 7h17v10h-17zM12 9.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 1 0 0-4.8M6.5 10v4M17.5 10v4") }
    val Bonus by lazy { glyph("Bonus", "M4.5 10.5h15V20h-15zM3.5 7.5h17v3h-17zM12 7.5V20M12 7.5c-1.6-2.8-5-3-4.6-.6.3 1.2 4.6.6 4.6.6zM12 7.5c1.6-2.8 5-3 4.6-.6-.3 1.2-4.6.6-4.6.6z") }
    val Cashback by lazy { glyph("Cashback", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v3.7h-3.7M12 8.5v7M14 10h-3a1.25 1.25 0 0 0 0 2.5h2a1.25 1.25 0 0 1 0 2.5h-3") }
    val School by lazy { glyph("School", "M2.5 9 12 4.5 21.5 9 12 13.5zM6.5 11v5c1.5 1.5 3.5 2.2 5.5 2.2s4-.7 5.5-2.2v-5M21.5 9v5") }
    val Shopping by lazy { glyph("Shopping", "M5 7.5h14l-1 12.5H6zM9 7.5V6a3 3 0 0 1 6 0v1.5") }
    val Flight by lazy { glyph("Flight", "M12 3.5c.9 0 1.5 1 1.5 2.5v3.5l7 4v2l-7-2v4l2 1.5v1.5L12 19.5l-3.5 1v-1.5l2-1.5v-4l-7 2v-2l7-4V6c0-1.5.6-2.5 1.5-2.5z") }
}
