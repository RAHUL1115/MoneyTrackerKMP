package app.moneytracker.core

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Current local time as minutes after midnight. */
fun nowMinutes(): Int {
    val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return t.hour * 60 + t.minute
}

/** "3:02 PM" */
fun formatTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) tr("AM") else tr("PM")}"
}
