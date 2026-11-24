package me.minecraftauth.lib

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny in-process stand-in for the MinecraftAuth.me API. */
class FakeApi : AutoCloseable {

    data class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, String?>, val body: String)

    data class Reply(val status: Int, val body: String, val contentType: String = "application/json")

    val requests = CopyOnWriteArrayList<Recorded>()
    var handler: (Recorded) -> Reply = { Reply(404, """{"error":"not found","status":404}""") }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val recorded = Recorded(
            exchange.requestMethod,
            exchange.requestURI.rawPath,
            exchange.requestURI.rawQuery,
            listOf("User-Agent", "Authorization", "Content-Type").associateWith { exchange.requestHeaders.getFirst(it) },
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
        )
        requests += recorded
        val reply = handler(recorded)
        val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", reply.contentType)
        exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    fun client(credentials: Credentials = Credentials.ServerToken("tok")) =
        MinecraftAuth(MinecraftAuthConfig("Test/1.0", credentials, baseUrl))

    override fun close() = server.stop(0)
}

fun error(status: Int) = FakeApi.Reply(status, """{"error":"nope $status","status":$status}""")
