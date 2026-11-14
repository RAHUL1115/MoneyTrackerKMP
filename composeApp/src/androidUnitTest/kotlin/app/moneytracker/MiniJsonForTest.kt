package app.moneytracker.ai

import app.moneytracker.domain.ai.MiniJson

/** Test access to the engine's JSON reader. */
object MiniJsonForTest { fun parse(text: String): Any? = MiniJson.parse(text) }
