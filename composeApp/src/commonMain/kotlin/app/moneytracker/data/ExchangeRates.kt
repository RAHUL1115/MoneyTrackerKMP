package app.moneytracker.data

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Exchange rates from Frankfurter (api.frankfurter.dev), a free, open-source service that publishes the
 * European Central Bank's daily reference rates. Fetched once a day (unless turned off) and only while the user
 * has other currencies; the request carries nothing but currency codes.
 */
object ExchangeRates {
    const val HOST = "api.frankfurter.dev"

    /** Latest rates: how many of each [codes] one unit of [main] buys. */
    fun url(main: String, codes: List<String>): String =
        "https://$HOST/v1/latest?base=${main.uppercase()}&symbols=${codes.joinToString(",") { it.uppercase() }}"

    /** [date]: the ECB publishing day (yyyy-mm-dd); [perMain]: units of each currency per 1 main unit. */
    data class Quote(val date: String, val perMain: Map<String, Double>)

    private val DATE = Regex(""""date"\s*:\s*"(\d{4}-\d{2}-\d{2})"""")
    // Both braces escaped: Android's regex engine rejects a bare "}" that the desktop JVM accepts.
    private val RATES = Regex(""""rates"\s*:\s*\{([^\}]*)\}""")
    private val PAIR = Regex(""""([A-Z]{3})"\s*:\s*([0-9.eE+-]+)""")

    /** Reads a reply like {"amount":1.0,"base":"INR","date":"2026-11-03","rates":{"USD":0.0119}}; null if it isn't one. */
    fun parse(json: String): Quote? {
        val date = DATE.find(json)?.groupValues?.get(1) ?: return null
        val body = RATES.find(json)?.groupValues?.get(1) ?: return null
        val rates = PAIR.findAll(body).mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1] to it } }.toMap()
        return if (rates.isEmpty()) null else Quote(date, rates)
    }

    /**
     * The app stores how many main units one unit of a currency is worth, so the quote is inverted
     * (0.0119 USD per rupee → 1 USD = 84.0336 rupees). Kept to 4 decimals, or 6 for currencies worth less than 1.
     */
    fun mainPerUnit(perMain: Double): Double {
        val v = 1.0 / perMain
        val places = if (v >= 1) 4 else 6
        val f = 10.0.pow(places)
        return (v * f).roundToLong() / f
    }
}

/** What an update did: [updated] codes got new rates; [missing] codes have no ECB rate. */
sealed interface RatesUpdate {
    data class Done(val date: String, val updated: List<String>, val missing: List<String>) : RatesUpdate
    /** No connection, or the service has no rates for the main currency. */
    data object Failed : RatesUpdate
    data object NothingToUpdate : RatesUpdate
}
