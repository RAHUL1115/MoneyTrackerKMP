package app.moneytracker.web

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class HttpServerTest {
    private val server = HttpServer { HttpResponse.text(200, "ok") }

    @AfterTest fun tearDown() = server.stop()

    /** Browsers open spare connections and send nothing on them; they must not hold up a real request. */
    @Test fun idleConnectionsDoNotDelayARealRequest() {
        val loopback = InetAddress.getLoopbackAddress()
        val port = server.start(loopback, 18080..18099)
        val idle = List(6) { Socket().apply { connect(InetSocketAddress(loopback, port), 2_000) } }
        try {
            Thread.sleep(200) // let the server take the idle sockets first
            val started = System.nanoTime()
            val reply = Socket().use { s ->
                s.connect(InetSocketAddress(loopback, port), 2_000)
                s.soTimeout = 5_000
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                s.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
            assertTrue(ms < 2_000, "took $ms ms behind idle connections")
        } finally {
            idle.forEach { it.close() }
        }
    }
}
