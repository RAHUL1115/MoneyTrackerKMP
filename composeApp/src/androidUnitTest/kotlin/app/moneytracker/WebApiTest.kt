package app.moneytracker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.moneytracker.data.DraftType
import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.MoneyRepository.Keys
import app.moneytracker.data.TxnDraft
import app.moneytracker.db.MoneyDatabase
import app.moneytracker.web.HttpRequest
import app.moneytracker.web.HttpResponse
import app.moneytracker.web.Json
import app.moneytracker.web.WebApi
import app.moneytracker.web.WebPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebApiTest {
    private val today = LocalDate.parse("2026-11-07")
    private var clock = 1_000_000L
    private lateinit var repo: MoneyRepository
    private lateinit var api: WebApi
    private var changes = 0
    private val host = "192.168.1.5:8080"

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MoneyDatabase.Schema.create(driver)
        repo = MoneyRepository(MoneyDatabase(driver), Dispatchers.Unconfined, today = { today })
        repo.seedIfEmpty()
        api = WebApi({ repo }, { name -> if (name == "index.html") "<html>".toByteArray() else null }, { host }, now = { clock }, onChange = { changes++ })
    }

    private fun call(
        method: String, path: String, body: Any? = null, cookie: String? = null, query: Map<String, String> = emptyMap(),
        remote: String = "192.168.1.20", hostHeader: String = host, client: Boolean = true, origin: String? = null,
    ): HttpResponse {
        val headers = buildMap {
            put("host", hostHeader)
            put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            if (cookie != null) put("cookie", cookie)
            if (client) put("x-requested-with", "MoneyTracker")
            if (origin != null) put("origin", origin)
        }
        return api.handle(HttpRequest(method, path, query, headers, body?.let { Json.write(it).toByteArray() } ?: ByteArray(0), remote))
    }

    private fun HttpResponse.json(): Map<*, *> = Json.parse(body.toString(Charsets.UTF_8)) as Map<*, *>
    private fun HttpResponse.cookie(): String? = headers.firstOrNull { it.first == "Set-Cookie" }?.second?.substringBefore(';')

    private fun signIn(): String {
        val r = call("GET", "/api/session")
        assertEquals(200, r.status)
        return assertNotNull(r.cookie())
    }

    @Test fun jsonRoundTrips() {
        val v = mapOf("a" to listOf(1L, 2.5, true, null), "s" to "quote \" slash \\ line\n<tag> ₹ 😀")
        assertEquals(v.toString(), (Json.parse(Json.write(v)) as Map<*, *>).let { m ->
            mapOf("a" to (m["a"] as List<*>).map { if (it is Double && it == 1.0) 1L else it }, "s" to m["s"]).toString()
        })
        assertFalse(Json.write("</script>").contains("</"))
    }

    @Test fun refusesOtherNetworksAndHosts() {
        assertEquals(403, call("GET", "/api/session", remote = "8.8.8.8").status)
        assertEquals(403, call("GET", "/api/session", hostHeader = "evil.example:8080").status)
        assertEquals(200, call("GET", "/", remote = "10.0.0.7").status)
        assertEquals(200, call("GET", "/", remote = "fe80::1%wlan0").status)
    }

    @Test fun withoutPasswordAVisitSignsInAndShowsOnThePhone() {
        val cookie = signIn()
        assertEquals(200, call("GET", "/api/meta", cookie = cookie).status)
        assertEquals(401, call("GET", "/api/meta").status)
        val c = api.computers().single()
        assertEquals("Chrome on Windows", c.name)
        api.disconnect(c.id)
        assertEquals(401, call("GET", "/api/meta", cookie = cookie).status)
    }

    @Test fun passwordIsCheckedAndWrongTriesLockOut() {
        repo.putSetting(Keys.WEB_PASSWORD, WebPassword.hash("secret1"))
        val s = call("GET", "/api/session").json()
        assertEquals(false, s["signedIn"])
        assertEquals(true, s["password"])
        repeat(4) { assertEquals(401, call("POST", "/api/login", mapOf("password" to "nope")).status) }
        assertEquals(429, call("POST", "/api/login", mapOf("password" to "nope")).status)
        // Even the right one waits out the minute.
        assertEquals(429, call("POST", "/api/login", mapOf("password" to "secret1")).status)
        clock += 61_000
        val ok = call("POST", "/api/login", mapOf("password" to "secret1"))
        assertEquals(200, ok.status)
        assertEquals(200, call("GET", "/api/meta", cookie = ok.cookie()).status)
    }

    @Test fun idleSignInsExpire() {
        val cookie = signIn()
        clock += WebApi.IDLE_MS + 1
        assertEquals(401, call("GET", "/api/meta", cookie = cookie).status)
    }

    @Test fun changesNeedTheDashboardHeaderAndSameOrigin() {
        val cookie = signIn()
        val body = mapOf("type" to "EXPENSE", "date" to "2026-11-05", "amount" to "12.50", "account" to "acc_cash", "category" to "cat_ex_0")
        assertEquals(403, call("POST", "/api/txns", body, cookie, client = false).status)
        assertEquals(403, call("POST", "/api/txns", body, cookie, origin = "http://evil.example").status)
        assertEquals(200, call("POST", "/api/txns", body, cookie, origin = "http://$host").status)
    }

    @Test fun addEditDeleteAndUndo() {
        val cookie = signIn()
        val created = call("POST", "/api/txns", mapOf("type" to "EXPENSE", "date" to "2026-11-05", "amount" to "221", "account" to "acc_cash",
            "category" to "cat_ex_0", "note" to "Coffee", "tags" to listOf("goa", "#Goa", "work")), cookie)
        assertEquals(200, created.status, created.body.toString(Charsets.UTF_8))
        val id = created.json()["id"] as String
        val t = repo.txn(id)!!
        assertEquals(22_100L, t.amount)
        assertEquals(2, repo.tagsOf(id).size)       // "goa" and "#Goa" are the same tag

        val listed = call("GET", "/api/txns", cookie = cookie, query = mapOf("period" to "month", "ym" to "202611")).json()
        val row = (listed["rows"] as List<*>).map { it as Map<*, *> }.single { it["id"] == id }
        assertEquals("expense", row["flow"])
        assertNull(row["lock"])

        val edited = call("PUT", "/api/txns/$id", mapOf("type" to "EXPENSE", "date" to "2026-11-06", "amount" to "250.5", "account" to "acc_cash",
            "category" to "cat_ex_0", "note" to "Coffee x2", "tags" to listOf("goa")), cookie)
        assertEquals(200, edited.status)
        val newId = edited.json()["id"] as String
        assertEquals(25_050L, repo.txn(newId)!!.amount)
        assertEquals("Coffee x2", repo.txn(newId)!!.note)

        val undo = call("DELETE", "/api/txns/$newId", cookie = cookie).json()["undo"] as String
        assertNull(repo.liveTxn(newId))
        val restored = call("POST", "/api/undo/$undo", emptyMap<String, Any>(), cookie).json()["id"] as String
        assertEquals(25_050L, repo.txn(restored)!!.amount)
        assertEquals(410, call("POST", "/api/undo/$undo", emptyMap<String, Any>(), cookie).status)
        assertTrue(changes >= 4)
    }

    @Test fun rejectsBadEntries() {
        val cookie = signIn()
        fun post(vararg pairs: Pair<String, Any?>) = call("POST", "/api/txns", mapOf(*pairs), cookie)
        assertEquals(400, post("type" to "EXPENSE", "date" to "2026-11-05", "amount" to "0", "account" to "acc_cash").status)
        assertEquals(400, post("type" to "EXPENSE", "date" to "nope", "amount" to "5", "account" to "acc_cash").status)
        assertEquals(400, post("type" to "INCOME", "date" to "2026-11-05", "amount" to "5", "account" to "acc_cash", "category" to "cat_ex_0").status)
        assertEquals(400, post("type" to "TRANSFER", "date" to "2026-11-05", "amount" to "5", "account" to "acc_cash", "to" to "acc_cash").status)
        assertEquals(400, post("type" to "EXPENSE", "date" to "2026-11-05", "amount" to "5", "account" to "missing").status)
    }

    @Test fun instalmentsAndTransferFeesStayOnThePhone() {
        val cookie = signIn()
        val plan = repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_card", categoryId = "cat_ex_3", amount = 30_000, installments = 3))
        val move = repo.create(TxnDraft(DraftType.TRANSFER, today, "acc_bank", toAccountId = "acc_cash", amount = 5_000, fee = 50))
        assertEquals(409, call("DELETE", "/api/txns/$plan", cookie = cookie).status)
        val fee = repo.transferGroup(repo.txn(move)!!.transferGroupId!!).first { it.kind == app.moneytracker.domain.TxnKind.EXPENSE }
        assertEquals(409, call("DELETE", "/api/txns/${fee.id}", cookie = cookie).status)
        // Editing the transfer itself keeps its fee.
        val r = call("PUT", "/api/txns/$move", mapOf("type" to "TRANSFER", "date" to today.toString(), "amount" to "60", "account" to "acc_bank", "to" to "acc_cash"), cookie)
        assertEquals(200, r.status, r.body.toString(Charsets.UTF_8))
        val group = repo.transferGroup(repo.txn(r.json()["id"] as String)!!.transferGroupId!!)
        assertEquals(50L, group.first { it.kind == app.moneytracker.domain.TxnKind.EXPENSE }.amount)
    }

    @Test fun summaryAccountsAndExport() {
        val cookie = signIn()
        repo.create(TxnDraft(DraftType.INCOME, today, "acc_bank", categoryId = "cat_in_0", amount = 100_000))
        repo.create(TxnDraft(DraftType.EXPENSE, today, "acc_cash", categoryId = "cat_ex_0", amount = 2_500, note = "=cmd"))
        val s = call("GET", "/api/summary", cookie = cookie, query = mapOf("ym" to "202611")).json()
        val last = (s["trend"] as List<*>).last() as Map<*, *>
        assertEquals(100_000.0, last["income"])
        assertEquals(2_500.0, last["expense"])
        val a = call("GET", "/api/accounts", cookie = cookie).json()
        assertEquals(97_500.0, a["net"])
        val csv = call("GET", "/api/export.csv", cookie = cookie, query = mapOf("period" to "month", "ym" to "202611"))
        assertEquals(200, csv.status)
        assertTrue(csv.body.toString(Charsets.UTF_8).contains("Income"))
    }
}
