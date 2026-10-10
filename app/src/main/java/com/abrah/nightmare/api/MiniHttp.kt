package com.abrah.nightmare.api

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ⭐⭐ The Nightmare API's HTTP server — HTTP/1.1, one request per connection, nothing
 * else (`docs/AGENT-API.md` §3: NanoHTTPD-size, not Ktor). Pure `java.net`, so it is
 * tested on the JVM.
 *
 * ⚠ [allow] is asked about every peer BEFORE a byte is read: the server binds every
 * interface (a phone on Wi-Fi has no other way to be reached), and "LAN only" is
 * enforced here ([isLocalPeer]), not by the bind.
 * ⚠ Bounded: headers 16 KB, body [MAX_BODY] (base64 pictures), 30 s to send a request.
 */
class MiniHttp(
    private val port: Int,
    private val handler: (Request, Response) -> Unit,
    private val allow: (InetAddress) -> Boolean = ::isLocalPeer,
) {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        /** Lower-cased names. */
        val headers: Map<String, String>,
        val body: ByteArray,
        val peer: InetAddress,
    ) {
        fun text() = String(body, Charsets.UTF_8)
    }

    class Response internal constructor(private val out: OutputStream) {
        @Volatile var started = false
            private set

        fun send(code: Int, type: String, body: ByteArray) {
            check(!started) { "response already started" }
            started = true
            val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }

        fun json(code: Int, json: String) = send(code, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

        /**
         * ⭐ Server-sent events: the head now, then [Sse.event] per line of the run log.
         * ⚠ A write to a client that has gone is swallowed — the run it watches carries on
         * and its results stay fetchable.
         */
        fun sse(): Sse {
            check(!started) { "response already started" }
            started = true
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
            )
            out.flush()
            return Sse(out)
        }
    }

    class Sse internal constructor(private val out: OutputStream) {
        @Volatile var gone = false
            private set

        @Synchronized
        fun event(name: String, data: String) {
            if (gone) return
            try {
                val lines = data.split('\n').joinToString("") { "data: $it\n" }
                out.write("event: $name\n$lines\n".toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                gone = true
            }
        }
    }

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val boundPort: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port), 16)
        server = s
        val p = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "nm-api").apply { isDaemon = true } }
        pool = p
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                if (!allow(c.inetAddress)) {
                    runCatching { c.close() }
                    continue
                }
                p.execute { serve(c) }
            }
        }, "nm-api-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            val out = sock.getOutputStream()
            val res = Response(out)
            val req = try {
                read(BufferedInputStream(sock.getInputStream()), sock.inetAddress)
            } catch (e: BadRequest) {
                runCatching { res.json(e.code, err(e.message ?: "bad request")) }
                return
            } catch (e: IOException) {
                return
            }
            try {
                handler(req, res)
                if (!res.started) res.json(500, err("no response"))
            } catch (e: Exception) {
                if (!res.started) runCatching { res.json(500, err(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private class BadRequest(val code: Int, message: String) : Exception(message)

    private fun read(input: InputStream, peer: InetAddress): Request {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            head.write(b)
            if (head.size() > MAX_HEAD) throw BadRequest(431, "headers too large")
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(' ') ?: throw BadRequest(400, "no request line")
        if (first.size < 2) throw BadRequest(400, "bad request line")
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length > MAX_BODY) throw BadRequest(413, "body over ${MAX_BODY / (1024 * 1024)} MB")
        val body = ByteArray(length.toInt())
        var got = 0
        while (got < body.size) {
            val n = input.read(body, got, body.size - got)
            if (n < 0) throw IOException("body cut short")
            got += n
        }
        val target = first[1]
        val q = target.indexOf('?')
        val path = URLDecoder.decode(if (q >= 0) target.substring(0, q) else target, "UTF-8")
        val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&').filter { it.isNotEmpty() }.associate {
            val e = it.indexOf('=')
            if (e < 0) URLDecoder.decode(it, "UTF-8") to ""
            else URLDecoder.decode(it.substring(0, e), "UTF-8") to URLDecoder.decode(it.substring(e + 1), "UTF-8")
        }
        return Request(first[0].uppercase(), path, query, headers, body, peer)
    }

    companion object {
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 64L * 1024 * 1024
        const val READ_TIMEOUT_MS = 30_000
        /** ⚠ Small: one render runs at a time ([com.abrah.nightmare.RunLock]); the rest are reads. */
        const val THREADS = 6

        fun err(message: String) = org.json.JSONObject().put("error", message).toString()

        fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
            else -> "Status"
        }

        /**
         * ⭐⭐ Who may connect: loopback, the private ranges, link-local, and 100.64/10 —
         * carrier-grade NAT, which is where Tailscale puts a tailnet (so a PC on the
         * owner's tailnet reaches the phone). ⚠ Anything routable is refused before a
         * byte is read, token or not.
         */
        fun isLocalPeer(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            if (b.size == 4) return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            // IPv6 unique-local fc00::/7; an IPv4-mapped address is judged as IPv4.
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
            val mapped = b.size == 16 && b.take(10).all { it.toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
            return mapped && isLocalPeer(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        }
    }
}
