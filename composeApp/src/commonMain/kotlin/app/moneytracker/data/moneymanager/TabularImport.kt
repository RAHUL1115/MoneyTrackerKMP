package app.moneytracker.data.moneymanager

import app.moneytracker.core.Log
import app.moneytracker.data.DraftType
import app.moneytracker.data.toLong
import app.moneytracker.domain.Account
import app.moneytracker.domain.Category
import app.moneytracker.domain.Currency
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import kotlinx.datetime.LocalDate

/**
 * Spreadsheet rows ⇄ transactions. Shared by CSV and Excel (.xlsx): the platform only turns files into
 * `List<List<String>>` and back. Header names decide the column mapping, so exports from this app and
 * simple hand-made sheets (Date, Type, Account, Category, Amount, Note) both import.
 */
object TabularImport {
    data class Row(
        val date: LocalDate,
        val type: DraftType,
        val account: String,
        val toAccount: String?,
        val category: String,
        val subcategory: String,
        val amount: Long,
        val note: String,
        val memo: String,
        val currency: String?,
        val tags: List<String>,
    )

    data class Parsed(val rows: List<Row>, val errors: List<String>)

    private val synonyms = mapOf(
        "date" to listOf("date", "day", "transaction date"),
        "type" to listOf("type", "income/expense", "income/expenses", "kind"),
        "account" to listOf("account", "from account", "asset", "wallet"),
        "to" to listOf("to account", "to"),
        "category" to listOf("category", "main category"),
        "subcategory" to listOf("subcategory", "sub category", "sub-category"),
        "amount" to listOf("amount", "value", "sum"),
        "note" to listOf("note", "payee", "title", "content"),
        "memo" to listOf("memo", "description", "details", "comment"),
        "currency" to listOf("currency", "ccy"),
        "tags" to listOf("tags", "tag", "labels"),
    )

    fun parse(rows: List<List<String>>, mainDecimals: Int, currencies: List<Currency>): Parsed {
        if (rows.isEmpty()) return Parsed(emptyList(), listOf("The file is empty"))
        val header = rows.first().map { it.trim().lowercase() }
        fun col(key: String) = synonyms.getValue(key).firstNotNullOfOrNull { s -> header.indexOf(s).takeIf { it >= 0 } } ?: -1
        val columns = synonyms.keys.associateWith { col(it) }
        if (columns.getValue("date") < 0 || columns.getValue("amount") < 0 || columns.getValue("account") < 0) {
            return Parsed(emptyList(), listOf("Missing required columns. Need at least: Date, Account, Amount (found: ${rows.first().joinToString()})"))
        }
        val out = mutableListOf<Row>()
        val errors = mutableListOf<String>()
        rows.drop(1).forEachIndexed { i, raw ->
            if (raw.all { it.isBlank() }) return@forEachIndexed
            val row = readRow(Cells(columns, raw), mainDecimals, currencies) { errors += "Row ${i + 2}: $it" }
            if (row != null) out += row
        }
        return Parsed(out, errors)
    }

    /** One sheet row read through the header's column positions; a missing column reads as "". */
    private class Cells(private val columns: Map<String, Int>, private val raw: List<String>) {
        operator fun get(key: String) = columns.getValue(key).let { if (it >= 0) raw.getOrNull(it).orEmpty().trim() else "" }
    }

    /** An amount in minor units, its currency (null = main) and whether it was written negative. */
    private class SheetAmount(val minor: Long, val currency: Currency?, val negative: Boolean)

    /** The entry on one row, or null when it is skipped; [problem] hears why, except for transfer-in mirrors. */
    private fun readRow(v: Cells, mainDecimals: Int, currencies: List<Currency>, problem: (String) -> Unit): Row? {
        val date = parseDate(v["date"]) ?: return rejected(problem, "unreadable date '${v["date"]}'")
        val amount = readAmount(v, mainDecimals, currencies, problem) ?: return null
        val type = readType(v, amount.negative, problem) ?: return null
        return Row(
            date, type, v["account"].ifBlank { "Imported" }, v["to"].ifBlank { null }, v["category"], v["subcategory"], kotlin.math.abs(amount.minor),
            v["note"], v["memo"], amount.currency?.code, v["tags"].split(';', '|').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /** Reports [reason] and gives null, for a row that can't be imported. */
    private fun <T> rejected(problem: (String) -> Unit, reason: String): T? {
        problem(reason)
        return null
    }

    private fun readAmount(v: Cells, mainDecimals: Int, currencies: List<Currency>, problem: (String) -> Unit): SheetAmount? {
        val rawAmount = v["amount"]
        val negative = rawAmount.trim().startsWith("-") || (rawAmount.startsWith("(") && rawAmount.endsWith(")"))
        val code = v["currency"].uppercase().ifBlank { null }
        val currency = code?.let { k -> currencies.firstOrNull { it.code == k } }
        return when (val minor = parseAmount(rawAmount, currency?.decimals ?: mainDecimals)) {
            null -> rejected(problem, "unreadable amount '$rawAmount'")
            0L -> rejected(problem, "amount is zero")
            else -> SheetAmount(minor, currency, negative)
        }
    }

    private fun readType(v: Cells, negative: Boolean, problem: (String) -> Unit): DraftType? {
        val typeText = v["type"].lowercase()
        val hasTo = v["to"].isNotBlank()
        if (typeText.startsWith("transfer-in") || typeText == "transfer_in") return null   // mirror of a transfer-out row
        val type = draftType(typeText, hasTo, negative) ?: return rejected(problem, "unknown type '${v["type"]}'")
        if (type == DraftType.TRANSFER && !hasTo) return rejected(problem, "transfer without 'To account'")
        return type
    }

    /** The entry type for a sheet's Type text (lower case; not a transfer-in mirror); null when it isn't one we know. */
    internal fun draftType(typeText: String, hasTo: Boolean, negative: Boolean): DraftType? = when {
        typeText.startsWith("transfer") || hasTo -> DraftType.TRANSFER
        typeText.startsWith("inc") || typeText == "income balance" || typeText == "adjust_up" -> DraftType.INCOME
        typeText.startsWith("refund") -> DraftType.REFUND
        // A negative expense is how Money Manager and many sheets write a refund.
        typeText.startsWith("exp") && negative -> DraftType.REFUND
        typeText.startsWith("exp") || typeText == "adjust_down" -> DraftType.EXPENSE
        typeText.isBlank() -> if (negative) DraftType.EXPENSE else DraftType.INCOME
        else -> null
    }

    /** Accepts 2026-10-13, 13/10/2026, 10/13/2026 (if unambiguous), 13.10.2026, 2026/10/13, and Excel serial numbers. */
    fun parseDate(s: String): LocalDate? {
        val t = s.trim().substringBefore(' ').substringBefore('T')
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { serial ->
            // Excel serial day: 1 = 1900-01-01 (with the 1900 leap-year bug), 25569 = 1970-01-01.
            if (serial > 20000 && serial < 80000) return LocalDate.fromEpochDays(serial.toInt() - 25569)
        }
        val parts = t.split('-', '/', '.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        return when {
            parts[0] > 31 -> dateOrNull(parts[0], parts[1], parts[2])                          // yyyy-mm-dd
            parts[2] < 100 -> null
            parts[1] > 12 -> dateOrNull(parts[2], parts[0], parts[1])                          // mm/dd/yyyy
            else -> dateOrNull(parts[2], parts[1], parts[0])                                   // dd/mm/yyyy (default)
        }
    }

    /** The date, or null when that day doesn't exist (31 April, month 13). */
    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate(year, month, day)
        } catch (e: IllegalArgumentException) {
            Log.d("Import") { "no such date $year-$month-$day (${e.message})" }
            null
        }

    /** "1,234.50", "₹ 1,234.50", "-12.3", "(45.00)", "1.234,50" (European) → minor units. */
    fun parseAmount(s: String, decimals: Int): Long? {
        var t = s.trim().removePrefix("(").removeSuffix(")").filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        t = when {
            lastComma > lastDot && t.length - lastComma - 1 in 1..2 -> t.replace(".", "").replace(',', '.')   // European decimal comma
            else -> t.replace(",", "")
        }
        val value = t.toDoubleOrNull() ?: return null
        return kotlin.math.round(value * CurrencyFormat.pow10(decimals)).toLong()
    }

    // ───────────────────────── export

    val exportHeader = listOf("Date", "Type", "Account", "To account", "Category", "Subcategory", "Amount", "Currency", "Note", "Description", "Tags")

    /** One row per user-visible transaction (transfer-in mirrors are skipped). Amounts use the entered currency. */
    fun exportRows(
        txns: List<Txn>,
        accounts: List<Account>,
        categories: List<Category>,
        main: CurrencyFormat,
        currencies: List<Currency>,
        tagNames: Map<String, List<String>>,
    ): List<List<String>> {
        val accountName = accounts.associate { it.id to it.name }
        val byId = categories.associateBy { it.id }
        val curByCode = currencies.associateBy { it.code }
        return listOf(exportHeader) + txns.filter { it.kind.isListed }.map { t ->
            val cat = t.categoryId?.let { byId[it] }
            val mainCat = cat?.parentId?.let { byId[it] } ?: cat
            val sub = if (cat?.parentId != null) cat else null
            val fmt = t.currency?.let { curByCode[it]?.format } ?: main
            listOf(
                t.date.toString(),
                when (t.kind) {
                    TxnKind.INCOME -> "Income"; TxnKind.EXPENSE -> if (t.isRefund) "Refund" else "Expense"; TxnKind.TRANSFER_OUT -> "Transfer"
                    TxnKind.ADJUST_UP -> "Income"; TxnKind.ADJUST_DOWN -> "Expense"; TxnKind.TRANSFER_IN -> "Transfer-In"
                },
                accountName[t.accountId].orEmpty(),
                if (t.kind == TxnKind.TRANSFER_OUT) accountName[t.toAccountId].orEmpty() else "",
                mainCat?.name.orEmpty(), sub?.name.orEmpty(),
                fmt.toInput(kotlin.math.abs(t.enteredAmount)), t.currency.orEmpty(), t.note, t.memo,
                tagNames[t.id].orEmpty().joinToString("; "),
            )
        }
    }

    // ───────────────────────── CSV text

    fun toCsv(rows: List<List<String>>): String = buildString {
        rows.forEach { r -> appendLine(r.joinToString(",") { escape(it) }) }
    }

    private fun escape(v: String): String =
        if (v.any { it in CSV_SPECIAL }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Characters that make a CSV cell need quotes. */
    private const val CSV_SPECIAL = ",\"\n\r"

    /** RFC-4180 CSV (also accepts ';' or tab separators, detected from the header line). */
    fun parseCsv(text: String): List<List<String>> {
        val body = text.removePrefix("﻿")
        val firstLine = body.substringBefore('\n')
        val sep = listOf(',', ';', '\t').maxBy { s -> firstLine.count { it == s } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                quoted && ch == '"' && body.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    rows += row; row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
