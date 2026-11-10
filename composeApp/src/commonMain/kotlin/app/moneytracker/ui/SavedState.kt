package app.moneytracker.ui

import app.moneytracker.data.DraftType
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.RepeatType
import kotlinx.datetime.LocalDate

/**
 * Plain-string form of screens and half-typed entries, for the platform's saved state: when Android closes the app
 * in the background, the open screens and what was typed come back from these strings.
 *
 * Each value is length-prefixed ("5:hello", "~" for null), so notes may hold any character. Tags are fixed strings,
 * not class names, so code shrinking can't break them. A string from an older version that can't be read gives null.
 */
internal class StateWriter {
    private val sb = StringBuilder()
    fun str(v: String?) = apply { if (v == null) sb.append('~') else sb.append(v.length).append(':').append(v) }
    fun int(v: Int?) = str(v?.toString())
    fun long(v: Long?) = str(v?.toString())
    fun double(v: Double?) = str(v?.toString())
    fun bool(v: Boolean) = str(if (v) "1" else "0")
    fun date(v: LocalDate?) = str(v?.toString())
    fun strings(v: List<String>) = apply { int(v.size); v.forEach { str(it) } }
    fun draft(d: TxnDraft?) = apply {
        if (d == null) { str(null); return@apply }
        str("d").str(d.type.name).date(d.date).str(d.accountId).str(d.toAccountId).str(d.categoryId).long(d.amount).long(d.fee)
            .str(d.note).str(d.memo).int(d.installments).str(d.repeat?.name).date(d.repeatEnd).str(d.currency).strings(d.tagIds)
            .double(d.lat).double(d.lng).int(d.timeOfDay).str(d.origin).double(d.rate)
    }
    override fun toString() = sb.toString()
}

internal class StateReader(private val t: String) {
    private var i = 0
    fun str(): String? {
        if (t[i] == '~') { i++; return null }
        val colon = t.indexOf(':', i)
        val n = t.substring(i, colon).toInt()
        return t.substring(colon + 1, colon + 1 + n).also { i = colon + 1 + n }
    }
    fun need(): String = str() ?: error("missing value")
    fun int(): Int? = str()?.toInt()
    fun long(): Long? = str()?.toLong()
    fun double(): Double? = str()?.toDouble()
    fun bool(): Boolean = need() == "1"
    fun date(): LocalDate? = str()?.let { LocalDate.parse(it) }
    fun strings(): List<String> = List(int() ?: 0) { need() }
    fun draft(): TxnDraft? {
        str() ?: return null
        return TxnDraft(
            type = DraftType.valueOf(need()), date = date()!!, accountId = need(), toAccountId = str(), categoryId = str(),
            amount = long()!!, fee = long()!!, note = need(), memo = need(), installments = int()!!,
            repeat = str()?.let { RepeatType.valueOf(it) }, repeatEnd = date(), currency = str(), tagIds = strings(),
            lat = double(), lng = double(), timeOfDay = int(), origin = need(), rate = double(),
        )
    }
    val done get() = i >= t.length

    companion object {
        /** Reads [t] with [block], or null when it doesn't parse (saved by another version). */
        fun <T> read(t: String, block: StateReader.() -> T): T? = runCatching { StateReader(t).block() }.getOrNull()
    }
}

/** For rememberSaveable: dates as ISO text. */
internal val DateSaver = androidx.compose.runtime.saveable.Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })
/** The same for an optional date (a cleared one comes back as its starting value). */
internal val OptionalDateSaver = androidx.compose.runtime.saveable.Saver<LocalDate?, String>(save = { it?.toString() }, restore = { LocalDate.parse(it) })
/** For rememberSaveable: any list of strings as a bundle-safe ArrayList. */
internal val StringListSaver = androidx.compose.runtime.saveable.Saver<List<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it })

/** Back-stack entries as strings: [encode] is the inverse of [decode] for every [Screen]. */
internal object ScreenCodec {
    private val objects: Map<String, Screen> = mapOf(
        "accounts" to Screen.AccountList, "recurring" to Screen.Recurring, "backup" to Screen.Backup,
        "config" to Screen.Configuration, "budget" to Screen.Budget, "search" to Screen.Search,
        "templates" to Screen.Templates, "inbox" to Screen.Inbox, "msgSettings" to Screen.MessageSettings,
        "calculator" to Screen.Calculator, "help" to Screen.Help, "about" to Screen.About,
        "currencies" to Screen.Currencies, "tags" to Screen.Tags, "security" to Screen.Security,
        "accountsTrend" to Screen.AccountsTrend, "accountGroups" to Screen.AccountGroups, "web" to Screen.WebDashboard,
    )
    private val tags: Map<Screen, String> = objects.entries.associate { (k, v) -> v to k }

    /** Null for a screen this table doesn't know yet (it, and what's above it, then isn't restored). */
    fun encode(s: Screen): String? {
        val w = StateWriter()
        when (s) {
            is Screen.EditTxn -> w.str("editTxn").str(s.txnId).draft(s.preset).date(s.date).str(s.captureId)
            is Screen.AccountDetail -> w.str("accountDetail").str(s.accountId)
            is Screen.EditAccount -> w.str("editAccount").str(s.accountId)
            is Screen.Categories -> w.str("categories").str(s.kind.name)
            is Screen.StatsDetail -> w.str("statsDetail").str(s.mainCategoryId).bool(s.income).date(s.from).date(s.to).str(s.title)
            is Screen.EditNote -> w.str("editNote").str(s.noteId)
            is Screen.BudgetDetail -> w.str("budgetDetail").int(s.periodKey)
            is Screen.License -> w.str("license").str(s.id)
            is Screen.MoreHub -> w.str("hub").str(s.id)
            else -> w.str(tags[s] ?: return null)
        }
        return w.toString()
    }

    fun decode(t: String): Screen? = StateReader.read(t) {
        when (val tag = need()) {
            "editTxn" -> Screen.EditTxn(str(), draft(), date(), str())
            "accountDetail" -> Screen.AccountDetail(need())
            "editAccount" -> Screen.EditAccount(str())
            "categories" -> Screen.Categories(CategoryKind.valueOf(need()))
            "statsDetail" -> Screen.StatsDetail(need(), bool(), date()!!, date()!!, need())
            "editNote" -> Screen.EditNote(str())
            "budgetDetail" -> Screen.BudgetDetail(int()!!)
            "license" -> Screen.License(need())
            "hub" -> Screen.MoreHub(need())
            else -> objects[tag]
        }
    }
}
