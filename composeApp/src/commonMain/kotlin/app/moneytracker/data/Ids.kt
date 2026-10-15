package app.moneytracker.data

import kotlin.random.Random

/** Random 128-bit hex id (UUID-like); avoids platform UUID APIs so it stays common code. */
fun newId(): String = buildString {
    repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) }
}
