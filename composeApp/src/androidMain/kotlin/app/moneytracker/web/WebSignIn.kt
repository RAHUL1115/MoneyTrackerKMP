package app.moneytracker.web

import java.security.SecureRandom

/**
 * Who is signed in to the dashboard: one cookie per browser, ended after [WebApi.IDLE_MS] idle, and a one-minute
 * lockout per computer after [WebApi.MAX_WRONG] wrong passwords.
 */
internal class WebSignIn(
    private val now: () -> Long,
    /** Sign-ins or visits changed (the phone's connected-computers list). */
    private val onVisit: () -> Unit,
) {
    private class Session(val token: String, val id: String, val name: String, val ip: String, @Volatile var lastSeen: Long)
    private class Lockout(var wrong: Int, var until: Long)

    private val sessions = LinkedHashMap<String, Session>()
    private val lockouts = HashMap<String, Lockout>()
    private val random = SecureRandom()

    fun computers(): List<WebComputer> = synchronized(sessions) {
        expire()
        sessions.values.sortedByDescending { it.lastSeen }.map { WebComputer(it.id, it.name, it.lastSeen) }
    }

    fun disconnect(id: String) {
        synchronized(sessions) { sessions.values.removeAll { it.id == id } }
        onVisit()
    }

    fun signOutAll() {
        synchronized(sessions) { sessions.clear() }
        onVisit()
    }

    /** Whether [req] carries a live sign-in; a visit keeps it alive. */
    fun isSignedIn(req: HttpRequest): Boolean {
        // No sign-in yet: the page calls /api/session, which signs in at once when there is no password.
        val s = current(req) ?: return false
        s.lastSeen = now()
        return true
    }

    /** /api/session: whether this browser is signed in; without a password it is signed in on the spot. */
    fun session(req: HttpRequest, needsPassword: Boolean): HttpResponse {
        val existing = current(req)
        if (existing != null) {
            existing.lastSeen = now()
            return HttpResponse.json(mapOf("signedIn" to true, "password" to needsPassword))
        }
        if (!needsPassword) {
            return HttpResponse.json(mapOf("signedIn" to true, "password" to false), headers = listOf("Set-Cookie" to newSession(req)))
        }
        return HttpResponse.json(mapOf("signedIn" to false, "password" to true, "wait" to waitSeconds(req.remote)))
    }

    /** /api/login against the [stored] password hash (none set lets anyone in). */
    fun login(req: HttpRequest, stored: String?): HttpResponse {
        val wait = waitSeconds(req.remote)
        if (wait > 0) return HttpResponse.json(mapOf("error" to "Too many tries. Wait a minute, then try again.", "wait" to wait), 429)
        val password = req.jsonObject()?.get("password") as? String ?: return HttpResponse.error(400, "Enter the password")
        if (stored.isNullOrEmpty() || WebPassword.check(password, stored)) {
            synchronized(lockouts) { lockouts.remove(req.remote) }
            return HttpResponse.json(mapOf("signedIn" to true), headers = listOf("Set-Cookie" to newSession(req)))
        }
        val left = countWrongTry(req.remote)
        return if (left == 0) HttpResponse.json(mapOf("error" to "Too many tries. Wait a minute, then try again.", "wait" to WebApi.LOCKOUT_MS / 1000), 429)
        else HttpResponse.json(mapOf("error" to "Wrong password. $left ${if (left == 1) "try" else "tries"} left before a 1-minute wait.", "left" to left), 401)
    }

    fun logout(req: HttpRequest): HttpResponse {
        cookieToken(req)?.let { t -> synchronized(sessions) { sessions.remove(t) } }
        onVisit()
        return HttpResponse.json(mapOf("signedIn" to false), headers = listOf("Set-Cookie" to "${WebApi.COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
    }

    private fun current(req: HttpRequest): Session? {
        val token = cookieToken(req)
        return synchronized(sessions) {
            expire()
            token?.let { sessions[it] }
        }
    }

    private fun cookieToken(req: HttpRequest): String? = req.header("cookie")?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("${WebApi.COOKIE}=") }?.substringAfter('=')

    /** Signs [req]'s browser in; returns the Set-Cookie value. */
    private fun newSession(req: HttpRequest): String {
        val token = random.hexId(24)
        val s = Session(token, random.hexId(6), WebApi.describe(req.header("user-agent").orEmpty()), req.remote, now())
        synchronized(sessions) {
            sessions[token] = s
            while (sessions.size > WebApi.MAX_SESSIONS) sessions.remove(sessions.keys.first())
        }
        onVisit()
        return "${WebApi.COOKIE}=$token; Path=/; HttpOnly; SameSite=Strict"
    }

    private fun waitSeconds(ip: String): Long = synchronized(lockouts) {
        val l = lockouts[ip] ?: return 0
        ((l.until - now()) / 1000).coerceAtLeast(0)
    }

    /** Counts a wrong password from [ip]; returns the tries left, 0 once it is locked out. */
    private fun countWrongTry(ip: String): Int = synchronized(lockouts) {
        val l = lockouts.getOrPut(ip) { Lockout(0, 0) }
        if (l.until in 1..now()) l.wrong = 0
        l.wrong++
        if (l.wrong >= WebApi.MAX_WRONG) {
            l.until = now() + WebApi.LOCKOUT_MS
            l.wrong = 0
            0
        } else {
            WebApi.MAX_WRONG - l.wrong
        }
    }

    private fun expire() {
        val cutoff = now() - WebApi.IDLE_MS
        sessions.values.removeAll { it.lastSeen < cutoff }
    }
}

/** [bytes] random bytes as hex: cookies, computer ids and undo tokens. */
internal fun SecureRandom.hexId(bytes: Int): String = ByteArray(bytes).also { nextBytes(it) }.joinToString("") { "%02x".format(it) }
