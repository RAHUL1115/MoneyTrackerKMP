package app.moneytracker.web

import app.moneytracker.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** [headers] keys are lower-case. [remote] is the client's IP address. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: String,
) {
    fun header(name: String) = headers[name.lowercase()]
    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

class HttpResponse(
    val status: Int,
    val type: String,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8", headers: List<Pair<String, String>> = emptyList()) =
            HttpResponse(status, type, text.toByteArray(Charsets.UTF_8), headers)
        fun json(value: Any?, status: Int = 200, headers: List<Pair<String, String>> = emptyList()) =
            text(status, Json.write(value), "application/json; charset=utf-8", headers)
        fun error(status: Int, message: String) = json(mapOf("error" to message), status)
    }
}

/**
 * A small HTTP/1.1 server for the local network: one request per connection, bodies up to [MAX_BODY].
 * It only ever listens on the address it is given (the phone's Wi-Fi address), never on every interface.
 */
class HttpServer(private val handler: (HttpRequest) -> HttpResponse) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** Listens on [address] at the first free port of [ports]; returns that port. Throws when none is free. */
    fun start(address: InetAddress, ports: IntRange): Int {
        stop()
        var last: IOException? = null
        for (port in ports) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(address, port), 16)
            } catch (e: IOException) {
                s.closeLogged("unbound socket")
                last = e
                continue
            }
            socket = s
            val p = Executors.newFixedThreadPool(4) { r -> Thread(r, "web-dashboard").apply { isDaemon = true } }
            pool = p
            Thread({ accept(s, p) }, "web-dashboard-accept").apply { isDaemon = true }.start()
            return port
        }
        throw checkNotNull(last) { "No port" }
    }

    fun stop() {
        socket?.closeLogged("server socket")
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun accept(s: ServerSocket, p: ExecutorService) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: SocketException) {
                break // stop() closed the socket
            } catch (e: IOException) {
                Log.d("Web") { "accept failed: ${e::class.simpleName}" }
                null
            }
            client?.let { dispatch(it, p) }
        }
    }

    private fun dispatch(client: Socket, p: ExecutorService) {
        try {
            p.execute { serve(client) }
        } catch (_: RejectedExecutionException) {
            client.closeLogged("rejected connection") // stop() shut the pool down
        }
    }

    private fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val res = try {
            val req = read(input, c.inetAddress?.hostAddress.orEmpty()) ?: return@use
            respond(req)
        } catch (e: BadRequest) {
            Log.w("Web") { "bad request: ${e.status}" }
            HttpResponse.error(e.status, e.message ?: "Bad request")
        } catch (e: IOException) {
            Log.d("Web") { "connection dropped: ${e::class.simpleName}" }
            return@use
        }
        try { write(c, res) } catch (e: IOException) { Log.d("Web") { "reply not sent: ${e::class.simpleName}" } }
    }

    /** The handler is the whole app behind the page: whatever it throws, answer 500 and keep the server up. */
    @Suppress("TooGenericExceptionCaught")
    private fun respond(req: HttpRequest): HttpResponse = try {
        handler(req).also { r -> Log.d("Web") { "${req.method} ${req.path} → ${r.status}" } }
    } catch (e: Exception) {
        Log.e("Web", e) { "${req.method} ${req.path} crashed" }
        HttpResponse.error(500, "Something went wrong on the phone.")
    }

    private fun Closeable.closeLogged(what: String) {
        try { close() } catch (e: IOException) { Log.d("Web") { "closing the $what failed: ${e::class.simpleName}" } }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    private fun read(input: InputStream, remote: String): HttpRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = requestLine(lines.first())
        val headers = headers(lines.drop(1))
        val body = readBody(input, bodyLength(headers))
        val target = parts[1]
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return HttpRequest(parts[0].uppercase(), path, query, headers, body, remote)
    }

    /** "GET /path HTTP/1.1" → its three parts. */
    private fun requestLine(line: String): List<String> {
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest(400, "Bad request line")
        return parts
    }

    /** Header lines → a map with lower-case names. */
    private fun headers(lines: List<String>): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in lines.filter { it.isNotEmpty() }) {
            val idx = line.indexOf(':')
            if (idx <= 0) throw BadRequest(400, "Bad header")
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    /** Only bodies with a Content-Length up to [MAX_BODY]; chunked uploads are refused. */
    private fun bodyLength(headers: Map<String, String>): Int {
        if (headers["transfer-encoding"] != null) throw BadRequest(411, "Send a Content-Length")
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > MAX_BODY) throw BadRequest(413, "Too large")
        return length.toInt()
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw BadRequest(400, "Body cut short")
            read += n
        }
        return body
    }

    /** Reads up to the blank line that ends the headers. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw BadRequest(400, "Headers cut short")
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) break
            if (buf.size() > MAX_HEAD) throw BadRequest(431, "Headers too large")
        }
        return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    /** Percent-decodes [s]; a malformed escape leaves it as it came. */
    private fun decode(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }

    private fun write(c: Socket, r: HttpResponse) {
        val out = c.getOutputStream()
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append("Content-Type: ").append(r.type).append("\r\n")
            .append("Content-Length: ").append(r.body.size).append("\r\n")
            .append("Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("X-Frame-Options: DENY\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'\r\n")
        r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(r.body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"; else -> if (code < 400) "OK" else "Error"
    }

    companion object {
        const val MAX_BODY = 256 * 1024
        const val MAX_HEAD = 16 * 1024
    }
}
