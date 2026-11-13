package app.moneytracker.web

import app.moneytracker.data.DraftType
import app.moneytracker.data.EntryEditor
import app.moneytracker.data.EntryProblem
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import app.moneytracker.data.TabularImport
import app.moneytracker.data.TxnDraft
import app.moneytracker.domain.Account
import app.moneytracker.domain.CategoryKind
import app.moneytracker.domain.CurrencyFormat
import app.moneytracker.domain.DateRange
import app.moneytracker.domain.MODIFIED_BALANCE_CATEGORY
import app.moneytracker.domain.MODIFIED_BALANCE_NAME
import app.moneytracker.domain.TAG_PREPAYMENT
import app.moneytracker.domain.TOTAL_BUDGET
import app.moneytracker.domain.Txn
import app.moneytracker.domain.TxnKind
import app.moneytracker.domain.YearMonth
import app.moneytracker.domain.budgetLines
import app.moneytracker.domain.cardStatus
import app.moneytracker.domain.classify
import app.moneytracker.domain.effectiveBudgets
import app.moneytracker.domain.summarize
import app.moneytracker.domain.Fx
import app.moneytracker.ui.byMainCategory
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import app.moneytracker.domain.Flow as MoneyFlow

/** A browser signed in to the dashboard. [id] is safe to show; [token] is the cookie and never leaves this class. */
class WebComputer(val id: String, val name: String, val lastSeen: Long)

/** Salted PBKDF2 hash of the dashboard password, stored as "w1:salt:key" in hex. */
object WebPassword {
    private const val PREFIX = "w1:"
    const val MIN_LENGTH = 4

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return PREFIX + hex(salt) + ":" + hex(derive(password, salt))
    }

    fun check(password: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return false
        val parts = stored.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return false
        val salt = unhex(parts[0]) ?: return false
        val key = unhex(parts[1]) ?: return false
        return MessageDigest.isEqual(derive(password, salt), key)
    }

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(password.toCharArray(), salt, 20_000, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val bytes = s.chunked(2).map { it.toIntOrNull(16) ?: return null }
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }
}

/**
 * Everything the web dashboard's browser page talks to: sign-in, the JSON API and the page files.
 * It has no Android dependencies, so it runs in unit tests against an in-memory database.
 *
 * Safety rules, all enforced here:
 *  - only clients on the local network (private, link-local or loopback addresses);
 *  - the Host header must be the phone's address, so a web page elsewhere can't reach it through DNS tricks;
 *  - changes need a custom header and a same-origin Origin, so other sites can't post to it;
 *  - with a password, 5 wrong tries lock that computer out for a minute; idle sign-ins end after 30 minutes.
 */
class WebApi(
    private val repo: () -> MoneyRepository?,
    private val asset: (String) -> ByteArray?,
    /** "192.168.1.5:8080": what the browser must send as Host. */
    private val host: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Sign-ins or visits changed (the phone's connected-computers list). */
    private val onVisit: () -> Unit = {},
) {
    private class Session(val token: String, val id: String, val name: String, val ip: String, @Volatile var lastSeen: Long)
    private class Lockout(var wrong: Int, var until: Long)
    private class Undo(val deleted: EntryEditor.Deleted, val expires: Long)

    private val sessions = LinkedHashMap<String, Session>()
    private val lockouts = HashMap<String, Lockout>()
    private val undos = HashMap<String, Undo>()
    private val random = SecureRandom()
    /** One API call at a time: the database and its multi-row edits stay simple. */
    private val dbLock = Any()

    /** Last time any browser asked for anything (for auto-stop). */
    @Volatile var lastVisit: Long = now()
        private set

    fun computers(): List<WebComputer> = synchronized(sessions) {
        expire()
        sessions.values.sortedByDescending { it.lastSeen }.map { WebComputer(it.id, it.name, it.lastSeen) }
    }

    fun disconnect(id: String) {
        synchronized(sessions) { sessions.values.removeAll { it.id == id } }
        onVisit()
    }

    /** Signs every browser out (password changed, dashboard stopped). */
    fun signOutAll() {
        synchronized(sessions) { sessions.clear() }
        onVisit()
    }

    fun handle(req: HttpRequest): HttpResponse {
        if (!isLocal(req.remote)) return HttpResponse.error(403, "Only computers on the same Wi-Fi can open this.")
        if (!req.header("host").equals(host(), ignoreCase = true)) return HttpResponse.error(403, "Open the dashboard by the address shown on the phone.")
        lastVisit = now()
        if (!req.path.startsWith("/api/")) return page(req)
        if (req.method != "GET") {
            val origin = req.header("origin")
            if (req.header("x-requested-with") != CLIENT || (origin != null && origin != "http://" + host())) {
                return HttpResponse.error(403, "Blocked a change that didn't come from the dashboard page.")
            }
        }
        val r = repo() ?: return HttpResponse.error(503, "The phone is busy (restoring a backup?). Try again in a moment.")
        return when (req.path) {
            "/api/session" -> session(req, r)
            "/api/login" -> if (req.method == "POST") login(req, r) else HttpResponse.error(405, "POST only")
            "/api/logout" -> logout(req)
            else -> {
                val s = signedIn(req, r) ?: return HttpResponse.error(401, "Signed out")
                try {
                    synchronized(dbLock) { api(req, r, s) }
                } catch (e: ApiError) {
                    HttpResponse.error(e.status, e.message ?: "Bad request")
                }
            }
        }
    }

    // ───────────────────────── sign-in

    private fun passwordSet(r: MoneyRepository) = !r.settingNow(Prefs.WEB_PASSWORD).isNullOrEmpty()

    private fun cookieToken(req: HttpRequest): String? = req.header("cookie")?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')

    private fun signedIn(req: HttpRequest, r: MoneyRepository): Session? {
        val token = cookieToken(req)
        val s = synchronized(sessions) {
            expire()
            token?.let { sessions[it] }
        }
        // No sign-in yet: the page calls /api/session, which signs in at once when there is no password.
        return s?.also { it.lastSeen = now() }
    }

    private fun newSession(req: HttpRequest): Pair<Session, String> {
        val token = randomId(24)
        val s = Session(token, randomId(6), describe(req.header("user-agent").orEmpty()), req.remote, now())
        synchronized(sessions) {
            sessions[token] = s
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        }
        onVisit()
        return s to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"
    }

    private fun session(req: HttpRequest, r: MoneyRepository): HttpResponse {
        val existing = synchronized(sessions) { expire(); cookieToken(req)?.let { sessions[it] } }
        val needsPassword = passwordSet(r)
        if (existing != null) {
            existing.lastSeen = now()
            return HttpResponse.json(mapOf("signedIn" to true, "password" to needsPassword))
        }
        if (!needsPassword) {
            val (_, cookie) = newSession(req)
            return HttpResponse.json(mapOf("signedIn" to true, "password" to false), headers = listOf("Set-Cookie" to cookie))
        }
        return HttpResponse.json(mapOf("signedIn" to false, "password" to true, "wait" to waitSeconds(req.remote)))
    }

    private fun waitSeconds(ip: String): Long = synchronized(lockouts) {
        val l = lockouts[ip] ?: return 0
        ((l.until - now()) / 1000).coerceAtLeast(0)
    }

    private fun login(req: HttpRequest, r: MoneyRepository): HttpResponse {
        val wait = waitSeconds(req.remote)
        if (wait > 0) return HttpResponse.json(mapOf("error" to "Too many tries. Wait a minute, then try again.", "wait" to wait), 429)
        val body = jsonObject(req)
        val password = body?.get("password") as? String ?: return HttpResponse.error(400, "Enter the password")
        val stored = r.settingNow(Prefs.WEB_PASSWORD)
        if (stored.isNullOrEmpty() || WebPassword.check(password, stored)) {
            synchronized(lockouts) { lockouts.remove(req.remote) }
            val (_, cookie) = newSession(req)
            return HttpResponse.json(mapOf("signedIn" to true), headers = listOf("Set-Cookie" to cookie))
        }
        val left = synchronized(lockouts) {
            val l = lockouts.getOrPut(req.remote) { Lockout(0, 0) }
            if (l.until in 1..now()) l.wrong = 0
            l.wrong++
            if (l.wrong >= MAX_WRONG) { l.until = now() + LOCKOUT_MS; l.wrong = 0; 0 } else MAX_WRONG - l.wrong
        }
        return if (left == 0) HttpResponse.json(mapOf("error" to "Too many tries. Wait a minute, then try again.", "wait" to LOCKOUT_MS / 1000), 429)
        else HttpResponse.json(mapOf("error" to "Wrong password. $left ${if (left == 1) "try" else "tries"} left before a 1-minute wait.", "left" to left), 401)
    }

    private fun logout(req: HttpRequest): HttpResponse {
        cookieToken(req)?.let { t -> synchronized(sessions) { sessions.remove(t) } }
        onVisit()
        return HttpResponse.json(mapOf("signedIn" to false), headers = listOf("Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
    }

    private fun expire() {
        val cutoff = now() - IDLE_MS
        sessions.values.removeAll { it.lastSeen < cutoff }
    }

    // ───────────────────────── page files

    private fun page(req: HttpRequest): HttpResponse {
        if (req.method != "GET" && req.method != "HEAD") return HttpResponse.error(405, "GET only")
        val (file, type) = when (req.path) {
            "/", "/index.html" -> "index.html" to "text/html; charset=utf-8"
            "/app.js" -> "app.js" to "text/javascript; charset=utf-8"
            "/app.css" -> "app.css" to "text/css; charset=utf-8"
            "/icon.svg", "/favicon.ico" -> "icon.svg" to "image/svg+xml"
            else -> return HttpResponse.error(404, "Not found")
        }
        val bytes = asset(file) ?: return HttpResponse.error(404, "Not found")
        return HttpResponse(200, type, bytes)
    }

    // ───────────────────────── API

    private class ApiError(val status: Int, message: String) : Exception(message)

    private fun api(req: HttpRequest, r: MoneyRepository, s: Session): HttpResponse {
        val p = req.path
        return when {
            p == "/api/meta" && req.method == "GET" -> HttpResponse.json(meta(r))
            p == "/api/txns" && req.method == "GET" -> HttpResponse.json(txns(r, range(req, r)))
            p == "/api/txns" && req.method == "POST" -> HttpResponse.json(create(r, body(req)))
            p.startsWith("/api/txns/") && req.method == "PUT" -> HttpResponse.json(update(r, p.removePrefix("/api/txns/"), body(req)))
            p.startsWith("/api/txns/") && req.method == "DELETE" -> HttpResponse.json(delete(r, p.removePrefix("/api/txns/")))
            p.startsWith("/api/undo/") && req.method == "POST" -> HttpResponse.json(undo(r, p.removePrefix("/api/undo/")))
            p == "/api/summary" && req.method == "GET" -> HttpResponse.json(summary(r, req))
            p == "/api/accounts" && req.method == "GET" -> HttpResponse.json(accounts(r))
            p == "/api/export.csv" && req.method == "GET" -> export(r, req)
            else -> HttpResponse.error(404, "Not found")
        }
    }

    private fun body(req: HttpRequest): Map<*, *> = jsonObject(req) ?: throw ApiError(400, "Bad request body")

    /** The request body as a JSON object, or null when it isn't one. */
    private fun jsonObject(req: HttpRequest): Map<*, *>? =
        try { Json.parse(req.bodyText) as? Map<*, *> } catch (_: IllegalArgumentException) { null }

    private fun meta(r: MoneyRepository): Map<String, Any?> {
        val fmt = r.currencyFormatNow()
        val ps = r.periodSettingsNow()
        val groups = r.groupsNow()
        val groupType = groups.associate { it.id to it.type }
        return mapOf(
            "currency" to mapOf("symbol" to fmt.symbol, "decimals" to fmt.decimals, "after" to fmt.symbolAfter,
                "code" to (r.settingNow(Prefs.CURRENCY_CODE) ?: "INR")),
            "currencies" to r.currenciesNow().map { mapOf("code" to it.code, "symbol" to it.symbol, "decimals" to it.decimals) },
            "today" to r.today().toString(),
            "month" to ps.labelFor(r.today()).key,
            "groups" to groups.map { mapOf("id" to it.id, "name" to it.name, "type" to it.type.name) },
            "accounts" to r.accountsNow().map {
                mapOf("id" to it.id, "name" to it.name, "groupId" to it.groupId, "hidden" to it.hidden, "currency" to it.currency,
                    "card" to (groupType[it.groupId]?.isCreditCard == true))
            },
            "categories" to r.categoriesNow().map { mapOf("id" to it.id, "name" to it.name, "kind" to it.kind.name, "parentId" to it.parentId) } +
                mapOf("id" to MODIFIED_BALANCE_CATEGORY, "name" to MODIFIED_BALANCE_NAME, "kind" to "SYSTEM", "parentId" to null),
            "tags" to r.tagsNow().map { mapOf("id" to it.id, "name" to it.name) },
            "subcategories" to r.settingNow(Prefs.USE_SUBCATEGORIES),
        )
    }

    /** ?period=month|3m|year&ym=202611, or ?from=2026-11-01&to=2026-11-30. */
    private fun range(req: HttpRequest, r: MoneyRepository): Pair<DateRange, String> {
        val ps = r.periodSettingsNow()
        val ym = req.query["ym"]?.toIntOrNull()?.takeIf { it % 100 in 1..12 }?.let(YearMonth::fromKey) ?: ps.labelFor(r.today())
        return when (req.query["period"]) {
            "3m" -> DateRange(ps.month(ym.plus(-2)).start, ps.month(ym).endInclusive) to "3m"
            "year" -> ps.year(ym.year) to "year"
            "custom" -> {
                val from = req.query["from"]?.let(LocalDate.Formats.ISO::parseOrNull) ?: throw ApiError(400, "Pick a start date")
                val to = req.query["to"]?.let(LocalDate.Formats.ISO::parseOrNull) ?: throw ApiError(400, "Pick an end date")
                if (to < from) throw ApiError(400, "The end date is before the start date")
                if (from.plus(DatePeriod(years = 10)) < to) throw ApiError(400, "Pick 10 years or less")
                DateRange(from, to) to "custom"
            }
            else -> ps.month(ym) to "month"
        }
    }

    private fun txns(r: MoneyRepository, rp: Pair<DateRange, String>): Map<String, Any?> {
        val (range, period) = rp
        val flows = r.reportRowsNow(range).associate { it.txnId to classify(it) }
        val tagIds = HashMap<String, List<String>>()
        val tagByName = r.tagsNow().associate { it.name to it.id }
        r.txnTagNamesNow().forEach { (id, names) -> tagIds[id] = names.mapNotNull { tagByName[it] } }
        val rows = r.txnsNow(range).filter { it.kind.isListed }.map { t -> row(t, flows[t.id], tagIds[t.id].orEmpty()) }
        return mapOf("from" to range.start.toString(), "to" to range.endInclusive.toString(), "period" to period, "rows" to rows)
    }

    private fun row(t: Txn, flow: MoneyFlow?, tags: List<String>): Map<String, Any?> {
        val lock = when {
            t.kind.isAdjustment -> "Balance adjustments are edited on the phone."
            t.installmentGroupId != null -> "Instalment plans are edited on the phone."
            t.transferGroupId != null && t.kind == TxnKind.EXPENSE -> "This is a transfer fee: edit the transfer instead."
            else -> null
        }
        return mapOf(
            "id" to t.id, "kind" to t.kind.name, "date" to t.date.toString(), "time" to t.timeOfDay,
            "account" to t.accountId, "to" to t.toAccountId, "category" to t.categoryId,
            "amount" to t.amount, "entered" to t.enteredAmount, "currency" to t.currency, "accountAmount" to t.accountValue,
            "note" to t.note, "memo" to t.memo, "tags" to tags, "transfer" to (t.transferGroupId != null),
            "installment" to t.installmentLabel, "repeat" to (t.recurringRuleId != null),
            "flow" to when (flow) { MoneyFlow.INCOME -> "income"; MoneyFlow.EXPENSE -> "expense"; else -> "none" },
            "lock" to lock,
        )
    }

    // ───────────────────────── changes

    private fun str(b: Map<*, *>, key: String) = (b[key] as? String)?.trim().orEmpty()

    private fun draft(r: MoneyRepository, b: Map<*, *>, existing: Txn?): TxnDraft {
        val type = DraftType.entries.firstOrNull { it.name == str(b, "type") } ?: throw ApiError(400, "Pick income, expense or transfer")
        val date = LocalDate.Formats.ISO.parseOrNull(str(b, "date")) ?: throw ApiError(400, "Pick a date")
        val accounts = r.accountsNow().associateBy { it.id }
        val account = accounts[str(b, "account")] ?: throw ApiError(400, if (type == DraftType.TRANSFER) "Pick the account to move from" else "Pick an account")
        val base = existing?.let { EntryEditor(r).open(it.id)?.draft }
        val currency = if (base != null && base.currency != null) base.currency else account.currency
        val decimals = currency?.let { c -> r.currenciesNow().firstOrNull { it.code == c }?.decimals } ?: r.currencyFormatNow().decimals
        val amount = CurrencyFormat(decimals = decimals).parse(str(b, "amount"))?.takeIf { it > 0 } ?: throw ApiError(400, "Enter an amount above zero")
        var to: Account? = null
        var category: String? = null
        if (type == DraftType.TRANSFER) {
            to = accounts[str(b, "to")] ?: throw ApiError(400, "Pick the account to move to")
            if (to.id == account.id) throw ApiError(400, "Pick two different accounts")
        } else {
            category = str(b, "category").ifEmpty { null }
            // Listed only to name past corrections; it isn't a category an entry can pick.
            if (category == MODIFIED_BALANCE_CATEGORY) throw ApiError(400, "Balance corrections are made on the phone. Pick a category")
            if (category != null) {
                val c = r.categoriesNow().firstOrNull { it.id == category } ?: throw ApiError(400, "That category was deleted on the phone")
                val wanted = if (type == DraftType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
                if (c.kind != wanted) throw ApiError(400, "Pick a category for this type")
            }
        }
        // Same rule as the phone: every entry needs a category (transfers have none of their own).
        if (EntryEditor.check(type, amount, 0, account.id, to?.id, category) == EntryProblem.NO_CATEGORY) throw ApiError(400, "Pick a category")
        val note = str(b, "note").take(500)
        val memo = str(b, "memo").take(2000)
        val tagIds = ((b["tags"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { (it as? String)?.trim()?.removePrefix("#")?.trim()?.takeIf { n -> n.isNotEmpty() }?.take(60) }
            .distinctBy { it.lowercase() }.take(20).map { r.tagNamed(it) }
        val fee = if (type == DraftType.TRANSFER && base?.type == DraftType.TRANSFER) base.fee else 0L
        return (base ?: TxnDraft(type, date, account.id, amount = amount)).copy(
            type = type, date = date, accountId = account.id, toAccountId = to?.id, categoryId = category,
            amount = amount, fee = fee, note = note, memo = memo, installments = 1, repeat = null, repeatEnd = null,
            currency = currency, tagIds = tagIds,
        )
    }

    private fun create(r: MoneyRepository, b: Map<*, *>): Map<String, Any?> {
        val id = editor(r).save(draft(r, b, null))
        return mapOf("id" to id)
    }

    private fun editor(r: MoneyRepository) = EntryEditor(r)

    private fun editable(r: MoneyRepository, id: String): Txn {
        val t = r.liveTxn(id) ?: throw ApiError(404, "This entry was deleted on the phone. Reload to see the latest.")
        row(t, null, emptyList())["lock"]?.let { throw ApiError(409, it.toString()) }
        return t
    }

    private fun update(r: MoneyRepository, id: String, b: Map<*, *>): Map<String, Any?> {
        val t = editable(r, id)
        val newId = editor(r).save(draft(r, b, t), existing = t)   // keeps its photos and location
        return mapOf("id" to newId)
    }

    private fun delete(r: MoneyRepository, id: String): Map<String, Any?> {
        val t = editable(r, id)
        val gone = editor(r).delete(t) ?: return emptyMap()   // balance corrections are locked above, so never null here
        val token = randomId(12)
        synchronized(undos) {
            undos.values.removeAll { it.expires < now() }
            undos[token] = Undo(gone, now() + UNDO_MS)
        }
        return mapOf("undo" to token)
    }

    private fun undo(r: MoneyRepository, token: String): Map<String, Any?> {
        val u = synchronized(undos) { undos.remove(token) }?.takeIf { it.expires >= now() } ?: throw ApiError(410, "Too late to undo")
        val id = editor(r).restore(u.deleted)
        return mapOf("id" to id)
    }

    // ───────────────────────── analytics and accounts

    private fun summary(r: MoneyRepository, req: HttpRequest): Map<String, Any?> {
        val ps = r.periodSettingsNow()
        val ym = req.query["ym"]?.toIntOrNull()?.takeIf { it % 100 in 1..12 }?.let(YearMonth::fromKey) ?: ps.labelFor(r.today())
        val trend = (5 downTo 0).map { back ->
            val m = ym.plus(-back)
            val t = r.totalsNow(ps.month(m))
            mapOf("ym" to m.key, "income" to t.income, "expense" to t.expense)
        }
        val range = ps.month(ym)
        val catById = r.categoryByIdNow()
        val lines = r.views.budgetNow(ym).lines   // same budget numbers as the phone
        val today = r.today()
        val daysSoFar = when {
            today < range.start -> 0
            today > range.endInclusive -> range.days()
            else -> (today.toEpochDays() - range.start.toEpochDays() + 1)
        }
        return mapOf(
            "ym" to ym.key, "from" to range.start.toString(), "to" to range.endInclusive.toString(),
            "trend" to trend, "daysSoFar" to daysSoFar, "days" to range.days(),
            "budgets" to lines.map { l ->
                mapOf("category" to l.categoryId.ifEmpty { null }, "name" to if (l.categoryId == TOTAL_BUDGET) "All spending" else catById[l.categoryId]?.name ?: "Category",
                    "budget" to l.budget, "spent" to l.spent)
            },
        )
    }

    private fun DateRange.days() = endInclusive.toEpochDays() - start.toEpochDays() + 1

    private fun accounts(r: MoneyRepository): Map<String, Any?> {
        val groups = r.groupsNow()
        val currencies = r.currenciesNow()
        val mainDecimals = r.currencyFormatNow().decimals
        val today = r.today()
        val (lines, worth) = r.views.accountsNow()   // same account summary as the phone
        val byGroup = lines.filter { !it.account.hidden }.groupBy { it.group.id }
        return mapOf(
            "today" to today.toString(),
            "assets" to worth.assets, "liabilities" to worth.liabilities, "net" to worth.net,
            "groups" to groups.mapNotNull { g ->
                val list = byGroup[g.id] ?: return@mapNotNull null
                mapOf(
                    "name" to g.name, "type" to g.type.name, "liability" to g.type.isLiability, "debit" to g.type.isDebitCard,
                    "total" to list.filter { it.account.includeInTotals && !g.type.isDebitCard }
                        .sumOf { Fx.toMain(it.effectiveBalance, currencies.firstOrNull { c -> c.code == it.account.currency }, mainDecimals) },
                    "accounts" to list.map { l ->
                        val card = l.card
                        mapOf(
                            "id" to l.account.id, "name" to l.account.name, "memo" to l.account.memo,
                            "balance" to l.effectiveBalance, "currency" to l.account.currency, "inTotals" to l.account.includeInTotals,
                            "card" to card?.let { mapOf("due" to (it.statementDue + it.overdue), "outstanding" to it.outstanding, "payDay" to l.account.cardPaymentDay, "closeDay" to l.account.cardSettlementDay) },
                        )
                    },
                )
            },
        )
    }

    private fun export(r: MoneyRepository, req: HttpRequest): HttpResponse {
        val (range, _) = range(req, r)
        val account = req.query["account"]?.takeIf { it.isNotEmpty() }
        val txns = r.txnsNow(range).filter { account == null || it.accountId == account || it.toAccountId == account }
        val rows = TabularImport.exportRows(txns, r.accountsNow(), r.categoriesNow(), r.currencyFormatNow(), r.currenciesNow(), r.txnTagNamesNow())
        val name = "MoneyTracker-${range.start}-to-${range.endInclusive}.csv"
        return HttpResponse.text(200, "﻿" + TabularImport.toCsv(rows), "text/csv; charset=utf-8",
            listOf("Content-Disposition" to "attachment; filename=\"$name\""))
    }

    // ───────────────────────── helpers

    private fun randomId(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val COOKIE = "mt_session"
        const val CLIENT = "MoneyTracker"
        const val MAX_WRONG = 5
        const val LOCKOUT_MS = 60_000L
        const val IDLE_MS = 30 * 60_000L
        const val UNDO_MS = 60_000L
        const val MAX_SESSIONS = 8

        /** Private (10/8, 172.16/12, 192.168/16), link-local, loopback, or the IPv6 equivalents. */
        fun isLocal(ip: String): Boolean {
            if (ip.isEmpty() || ip.any { !(it.isLetterOrDigit() || it == '.' || it == ':' || it == '%') }) return false
            // Link-local IPv6 comes with its interface ("fe80::1%wlan0"); the scope doesn't matter here.
            val a = try { InetAddress.getByName(ip.substringBefore('%')) } catch (_: UnknownHostException) { return false }
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            // IPv6 unique-local (fc00::/7).
            return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
        }

        /** "Chrome on Windows" from a User-Agent. */
        fun describe(ua: String): String {
            val browser = when {
                "Edg/" in ua -> "Edge"
                "OPR/" in ua || "Opera" in ua -> "Opera"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua || "CriOS" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> "Browser"
            }
            val os = when {
                "Windows" in ua -> "Windows"
                "iPhone" in ua -> "iPhone"
                "iPad" in ua -> "iPad"
                "Macintosh" in ua || "Mac OS X" in ua -> "Mac"
                "CrOS" in ua -> "ChromeOS"
                "Android" in ua -> "Android"
                "Linux" in ua -> "Linux"
                else -> null
            }
            return if (os == null) browser else "$browser on $os"
        }
    }
}
