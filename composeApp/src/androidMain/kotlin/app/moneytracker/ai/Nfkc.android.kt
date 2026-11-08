package app.moneytracker.ai

import java.text.Normalizer

internal actual fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
