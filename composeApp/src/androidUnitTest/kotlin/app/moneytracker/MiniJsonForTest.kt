package app.moneytracker.ai

/** Test access to the engine's JSON reader. */
object MiniJsonForTest { fun parse(text: String): Any? = MiniJson.parse(text) }
