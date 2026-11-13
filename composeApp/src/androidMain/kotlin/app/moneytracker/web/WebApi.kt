package app.moneytracker.web

import app.moneytracker.data.MoneyRepository
import app.moneytracker.data.Prefs
import java.net.InetAddress
import java.net.UnknownHostException

/** A browser signed in to the dashboard. [id] is safe to show; [token] is the cookie and never leaves this class. */
class WebComputer(val id: String, val name: String, val lastSeen: Long)

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
    onVisit: () -> Unit = {},
) {
    private val signIn = WebSignIn(now, onVisit)
    private val undos = UndoTokens(now)
    /** One API call at a time: the database and its multi-row edits stay simple. */
    private val dbLock = Any()

    /** Last time any browser asked for anything (for auto-stop). */
    @Volatile var lastVisit: Long = now()
        private set

    fun computers(): List<WebComputer> = signIn.computers()

    fun disconnect(id: String) = signIn.disconnect(id)

    /** Signs every browser out (password changed, dashboard stopped). */
    fun signOutAll() = signIn.signOutAll()

    fun handle(req: HttpRequest): HttpResponse {
        refusal(req)?.let { return it }
        lastVisit = now()
        return if (req.path.startsWith("/api/")) apiCall(req) else page(req)
    }

    /** Why [req] is turned away before anything else: not from this network, or not addressed to the phone. */
    private fun refusal(req: HttpRequest): HttpResponse? = when {
        !isLocal(req.remote) -> HttpResponse.error(403, "Only computers on the same Wi-Fi can open this.")
        !req.header("host").equals(host(), ignoreCase = true) -> HttpResponse.error(403, "Open the dashboard by the address shown on the phone.")
        else -> null
    }

    /** A change (anything but GET) must come from the dashboard page itself. */
    private fun isForeignChange(req: HttpRequest): Boolean {
        if (req.method == "GET") return false
        val origin = req.header("origin")
        return req.header("x-requested-with") != CLIENT || (origin != null && origin != "http://" + host())
    }

    private fun apiCall(req: HttpRequest): HttpResponse {
        if (isForeignChange(req)) return HttpResponse.error(403, "Blocked a change that didn't come from the dashboard page.")
        val r = repo() ?: return HttpResponse.error(503, "The phone is busy (restoring a backup?). Try again in a moment.")
        val password = { r.settingsStore.settingNow(Prefs.WEB_PASSWORD) }
        return when (req.path) {
            "/api/session" -> signIn.session(req, needsPassword = !password().isNullOrEmpty())
            "/api/login" -> if (req.method == "POST") signIn.login(req, password()) else HttpResponse.error(405, "POST only")
            "/api/logout" -> signIn.logout(req)
            else -> signedInCall(req, r)
        }
    }

    private fun signedInCall(req: HttpRequest, r: MoneyRepository): HttpResponse {
        if (!signIn.isSignedIn(req)) return HttpResponse.error(401, "Signed out")
        return try {
            synchronized(dbLock) { route(req, r) }
        } catch (e: ApiError) {
            HttpResponse.error(e.status, e.message ?: "Bad request")
        }
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

    private fun route(req: HttpRequest, r: MoneyRepository): HttpResponse {
        val p = req.path
        val get = req.method == "GET"
        return when {
            p == "/api/meta" && get -> HttpResponse.json(r.meta().json())
            p == "/api/txns" && get -> requestedRange(req, r).let { (range, period) -> HttpResponse.json(r.entries().list(range, period)) }
            p == "/api/txns" && req.method == "POST" -> HttpResponse.json(r.entries().create(body(req)))
            p.startsWith("/api/txns/") && req.method == "PUT" -> HttpResponse.json(r.entries().update(p.removePrefix("/api/txns/"), body(req)))
            p.startsWith("/api/txns/") && req.method == "DELETE" -> HttpResponse.json(r.entries().delete(p.removePrefix("/api/txns/")))
            p.startsWith("/api/undo/") && req.method == "POST" -> HttpResponse.json(r.entries().undo(p.removePrefix("/api/undo/")))
            p == "/api/summary" && get -> HttpResponse.json(r.summary().json(req.query))
            p == "/api/accounts" && get -> HttpResponse.json(r.accounts().json())
            p == "/api/export.csv" && get -> r.export().csv(requestedRange(req, r).first, req.query["account"]?.takeIf { it.isNotEmpty() })
            else -> HttpResponse.error(404, "Not found")
        }
    }

    private fun body(req: HttpRequest): Map<*, *> = req.jsonObject() ?: throw ApiError(400, "Bad request body")

    private fun requestedRange(req: HttpRequest, r: MoneyRepository) = requestedRange(req.query, r.settingsStore.periodSettingsNow(), r.today())

    // Each route gets only the stores it reads.
    private fun MoneyRepository.meta() = WebMeta(settingsStore, currencyStore, accountStore, categoryStore, tagStore, today())
    private fun MoneyRepository.summary() = WebSummary(settingsStore, reportStore, categoryStore, views, today())
    private fun MoneyRepository.accounts() = WebAccounts(accountStore, currencyStore, settingsStore, views, today())
    private fun MoneyRepository.export() = WebExport(txnStore, accountStore, categoryStore, settingsStore, currencyStore, tagStore)
    private fun MoneyRepository.entries() = WebEntries(
        txnStore, reportStore, tagStore, entryEditor,
        EntryDraftReader(accountStore, categoryStore, currencyStore, settingsStore, tagStore), undos,
    )

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
            // Only address characters, so nothing that looks like a host name is ever looked up.
            if (ip.isEmpty() || !ip.all { it.isLetterOrDigit() || it in ".:%" }) return false
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

/** The request body as a JSON object, or null when it isn't one. */
internal fun HttpRequest.jsonObject(): Map<*, *>? =
    try { Json.parse(bodyText) as? Map<*, *> } catch (_: IllegalArgumentException) { null }
