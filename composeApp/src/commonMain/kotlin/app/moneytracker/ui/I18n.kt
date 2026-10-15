package app.moneytracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Translations keyed by the English text. [table] is snapshot state, so every screen that called [tr]
 * redraws when the language changes. Missing entries fall back to English.
 */
object I18n {
    var table by mutableStateOf<Map<String, String>>(emptyMap())
    /** Right-to-left language picked (Arabic, Hebrew, Persian, Urdu): the whole layout mirrors. */
    var rtl by mutableStateOf(false)
    val RTL = setOf("ar", "he", "fa", "ur")

    /** Languages offered in Configuration › Language: tag → name in that language. "" = follow the phone. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "" to "Phone language", "en" to "English",
        "af" to "Afrikaans", "am" to "አማርኛ", "ar" to "العربية", "as" to "অসমীয়া", "az" to "Azərbaycan", "be" to "Беларуская",
        "bg" to "Български", "bn" to "বাংলা", "bs" to "Bosanski", "ca" to "Català", "cs" to "Čeština", "da" to "Dansk",
        "de" to "Deutsch", "el" to "Ελληνικά", "es" to "Español", "es-419" to "Español (Latinoamérica)", "et" to "Eesti",
        "eu" to "Euskara", "fa" to "فارسی", "fi" to "Suomi", "fr" to "Français", "fr-CA" to "Français (Canada)", "gl" to "Galego",
        "gu" to "ગુજરાતી", "he" to "עברית", "hi" to "हिन्दी", "hr" to "Hrvatski", "hu" to "Magyar", "hy" to "Հայերեն",
        "id" to "Bahasa Indonesia", "is" to "Íslenska", "it" to "Italiano", "ja" to "日本語", "ka" to "ქართული", "kk" to "Қазақ",
        "km" to "ខ្មែរ", "kn" to "ಕನ್ನಡ", "ko" to "한국어", "ky" to "Кыргызча", "lo" to "ລາວ", "lt" to "Lietuvių", "lv" to "Latviešu",
        "mk" to "Македонски", "ml" to "മലയാളം", "mn" to "Монгол", "mr" to "मराठी", "ms" to "Bahasa Melayu", "my" to "မြန်မာ",
        "nb" to "Norsk bokmål", "ne" to "नेपाली", "nl" to "Nederlands", "or" to "ଓଡ଼ିଆ", "pa" to "ਪੰਜਾਬੀ", "pl" to "Polski",
        "pt-BR" to "Português (Brasil)", "pt-PT" to "Português (Portugal)", "ro" to "Română", "ru" to "Русский", "si" to "සිංහල",
        "sk" to "Slovenčina", "sl" to "Slovenščina", "sq" to "Shqip", "sr" to "Српски", "sr-Latn" to "Srpski (latinica)",
        "sv" to "Svenska", "sw" to "Kiswahili", "ta" to "தமிழ்", "te" to "తెలుగు", "th" to "ไทย", "tl" to "Filipino", "tr" to "Türkçe",
        "uk" to "Українська", "ur" to "اردو", "uz" to "Oʻzbek", "vi" to "Tiếng Việt", "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文（台灣）", "zh-HK" to "繁體中文（香港）", "zu" to "isiZulu",
    )

    /** Best shipped table for a phone locale such as "pt-BR", "zh-Hant-TW" or "hi-IN". */
    fun resolve(tag: String): String {
        val shipped = LANGUAGES.map { it.first }.filter { it.isNotEmpty() }.toSet()
        val parts = tag.replace('_', '-').split('-')
        val lang = when (parts[0]) { "in" -> "id"; "iw" -> "he"; "nn", "no" -> "nb"; else -> parts[0] }
        val region = parts.drop(1).firstOrNull { it.length == 2 && it.all(Char::isUpperCase) }
        val script = parts.drop(1).firstOrNull { it.length == 4 }
        return listOfNotNull(
            if (lang == "zh") when { region == "TW" -> "zh-TW"; region == "HK" || region == "MO" -> "zh-HK"; script == "Hant" -> "zh-TW"; else -> "zh-CN" } else null,
            if (lang == "pt") if (region == "PT") "pt-PT" else "pt-BR" else null,
            if (lang == "sr" && script == "Latn") "sr-Latn" else null,
            region?.let { "$lang-$it" }, if (lang == "es" && region != null && region != "ES") "es-419" else null, lang,
        ).firstOrNull { it in shipped } ?: "en"
    }
}

/** Translated text; {0}, {1}… are replaced by [args]. */
fun tr(text: String, vararg args: Any?): String {
    val t = I18n.table[text] ?: text
    return if (args.isEmpty()) t else args.foldIndexed(t) { i, acc, a -> acc.replace("{$i}", a.toString()) }
}
