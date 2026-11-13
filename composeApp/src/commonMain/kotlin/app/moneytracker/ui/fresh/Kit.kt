package app.moneytracker.ui.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moneytracker.ui.Accent
import app.moneytracker.ui.AccentChoice
import app.moneytracker.ui.AccentTint
import app.moneytracker.ui.accentChoice
import app.moneytracker.ui.colorsSwapped
import app.moneytracker.ui.darkTheme

// The new layout's look: calm greens on an off-white page, big rounded cards, one clear action per screen.
// Colours follow the app's dark style, accent choice and income/expense colour setting like the classic layout.

internal fun pick(light: Long, dark: Long) = Color(if (darkTheme) dark else light)

object F {
    val page: Color get() = pick(0xFFF3F4F1, 0xFF0F1211)
    val card: Color get() = pick(0xFFFFFFFF, 0xFF1A1E1C)
    val ink: Color get() = pick(0xFF16201D, 0xFFE9EEEB)
    val muted: Color get() = pick(0xFF5D6964, 0xFF9AA6A1)
    val line: Color get() = pick(0xFFECEEEA, 0xFF2A302D)
    val border: Color get() = pick(0xFFE3E6E2, 0xFF333A36)
    val fill: Color get() = pick(0xFFEEF0EC, 0xFF252B28)
    /** Track of segmented controls and progress bars. */
    val track: Color get() = pick(0xFFE6E8E4, 0xFF2A302D)
    /** A switch that is off. */
    val switchOff: Color get() = pick(0xFFC9CFCC, 0xFF46504B)
    private val teal get() = accentChoice == AccentChoice.TEAL || (accentChoice == AccentChoice.SYSTEM && app.moneytracker.ui.systemAccent == null)
    /** Primary actions, selection, the hero card. */
    val accent: Color get() = if (teal) pick(0xFF0F5E4E, 0xFF4FC2A6) else Accent
    val accentTint: Color get() = if (teal) pick(0xFFE3F2EE, 0xFF173A31) else AccentTint
    /** Text and icons on [accent]. */
    val onAccent: Color get() = if (darkTheme && teal) Color(0xFF07231C) else Color.White
    /** Over budget and the like: one soft warm tone, used sparingly. */
    val warn: Color get() = pick(0xFFC26B4E, 0xFFE59A7C)
    /** Soft background for things waiting on the user (bank SMS to review). */
    val warnTint: Color get() = pick(0xFFFFF1E6, 0xFF35241A)
    /** Delete and overspending share the expense red, so the app has one red. */
    val danger: Color get() = pick(0xFFE5484D, 0xFFF87171)
    val dangerTint: Color get() = pick(0xFFFDECEC, 0xFF3A1F21)
    private val blue get() = pick(0xFF2563EB, 0xFF60A5FA)
    // The same red as the classic layout, so spending looks the same in both.
    private val red get() = pick(0xFFE5484D, 0xFFF87171)
    // Money in is blue and money out is red. Swapped colours keep their old meaning.
    val income: Color get() = if (colorsSwapped) red else blue
    val expense: Color get() = if (colorsSwapped) blue else red

    val cardShape = RoundedCornerShape(20.dp)
    val heroShape = RoundedCornerShape(24.dp)
    val buttonShape = RoundedCornerShape(16.dp)
    val iconShape = RoundedCornerShape(14.dp)
    /** Side margin of every screen: small, so cards use nearly the full width. */
    val gutter = 8.dp

    val money = TextStyle(fontFeatureSettings = "tnum")
}

/** Category and account marks share one soft grey-green; the letters tell them apart. */
@Suppress("UNUSED_PARAMETER")
fun markColors(name: String): Pair<Color, Color> = pick(0xFFEEF1ED, 0xFF232927) to pick(0xFF46524D, 0xFFB7C2BD)

/** A line icon for common category names ("Food", "Travel", "Bills"…), or null to show the letters instead. */
fun categoryIcon(name: String?): ImageVector? {
    val n = name?.lowercase() ?: return null
    fun has(vararg words: String) = words.any { it in n }
    return when {
        has("food", "grocer", "eat", "restaurant", "lunch", "dinner", "breakfast", "snack", "drink", "beverage") -> Glyph.Food
        has("travel", "transport", "fuel", "petrol", "car", "taxi", "cab", "bus", "train", "metro", "subway") -> Glyph.Travel
        has("household", "furniture", "appliance") -> Glyph.Household
        has("rent", "house", "home") -> Glyph.Rent
        has("bill", "utilit", "electric", "water", "gas") -> Glyph.Bills
        has("cloth", "apparel", "fashion", "shoe") -> Glyph.Clothes
        has("gym", "fitness", "sport", "workout") -> Glyph.Gym
        has("medic", "health", "doctor", "pharma", "hospital") -> Glyph.Medical
        has("insur") -> Glyph.Insurance
        has("entertain", "movie", "fun", "game") -> Glyph.Entertainment
        has("recharge", "phone", "mobile", "internet", "wifi") -> Glyph.Recharge
        has("salary", "wage", "pay") -> Glyph.Salary
        has("bonus", "gift") -> Glyph.Bonus
        has("cashback", "refund", "interest", "saving") -> Glyph.Cashback
        has("educat", "school", "course", "book") -> Glyph.School
        has("shop") -> Glyph.Shopping
        has("trip", "holiday", "flight") -> Glyph.Flight
        has("other", "misc") -> Glyph.More
        else -> null
    }
}

/** Two-letter mark: "Food" → "FO", "Credit card" → "CC"; a name with its own emoji ("🍔 Food") shows the emoji. */
fun markText(name: String): String {
    val parts = name.trim().split(' ', '/', '-', '&').filter { it.isNotBlank() }
    // Taking one Char of an emoji splits its surrogate pair and draws "�".
    parts.firstOrNull { w -> w.none { it.isLetterOrDigit() } }?.let { return it }
    val words = parts
    return when {
        words.isEmpty() -> "·"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
fun Mark(name: String, size: Dp = 36.dp, icon: ImageVector? = null) {
    val (bg, fg) = markColors(name)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.31f)).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.5f))
        else Text(markText(name), color = fg, fontSize = (size.value * 0.33f).sp, fontWeight = FontWeight.SemiBold)
    }
}
