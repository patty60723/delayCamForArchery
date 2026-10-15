package com.archery.delaycam.viewer

import java.io.BufferedInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Read-only, session-scoped LAN prototype. No file paths, camera APIs or control routes. */
class LanViewerServer(
    private val source: ViewerSegmentSource,
    private val asset: (String) -> ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : AutoCloseable {
    val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val prefix = "/v/$token/"
    private val assets = listOf("index.html", "viewer.js", "viewer.css").associateWith { asset(it) }
    private val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, 0)) }
    val port: Int get() = listener.localPort
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { runnable -> Thread(runnable, "archery-viewer-request").apply { isDaemon = true } })
    @Volatile private var closed = false
    private val acceptor = thread(name = "archery-viewer-listener", isDaemon = true) {
        while (!closed) {
            val client = try { listener.accept() } catch (_: Exception) { break }
            if (closed || !(client.inetAddress.isSiteLocalAddress || client.inetAddress.isLoopbackAddress)) {
                client.close(); continue
            }
            clients.add(client)
            try { workers.execute { client.use { runCatching { serve(it) }; clients.remove(it) } } }
            catch (_: Exception) { clients.remove(client); client.close() }
        }
    }

    fun url(address: String) = "http://$address:$port$prefix"

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(limit: Int): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < limit) {
                val next = input.read()
                if (next == -1) error("EOF")
                if (next == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                bytes.add(next.toByte())
            }
            error("Request too large")
        }
        val request = line(4096).split(' ')
        val headers = linkedMapOf<String, String>()
        var size = 0
        while (true) {
            val header = line(4096); if (header.isEmpty()) break
            size += header.length; check(size <= 16_384)
            val colon = header.indexOf(':'); check(colon > 0)
            headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
        }
        val out = socket.getOutputStream()
        val method = request.getOrNull(0)
        val path = request.getOrNull(1) ?: ""
        val head = method == "HEAD"
        fun respond(code: Int, message: String) = bytes(out, code, "text/plain; charset=utf-8", message.toByteArray(), head)
        if (method != "GET" && !head) { respond(405, "Read only"); return }
        if (!path.startsWith(prefix)) { respond(404, "Not found"); return }
        val origin = headers["origin"]
        if (origin != null && origin != "http://${headers["host"]}") { respond(403, "Forbidden"); return }
        when (val route = path.removePrefix(prefix)) {
            "", "index.html" -> bytes(out, 200, "text/html; charset=utf-8", assets.getValue("index.html"), head)
            "viewer.js" -> bytes(out, 200, "text/javascript; charset=utf-8", assets.getValue(route), head)
            "viewer.css" -> bytes(out, 200, "text/css; charset=utf-8", assets.getValue(route), head)
            "status" -> bytes(out, 200, "application/json", source.status().toByteArray(), head)
            else -> {
                val id = Regex("segment/([0-9]+)\\.mp4").matchEntire(route)?.groupValues?.get(1)?.toLongOrNull()
                if (id == null) { respond(404, "Not found"); return }
                val lease = source.acquire(id)
                if (lease == null) { respond(410, "Segment expired"); return }
                lease.use {
                    RandomAccessFile(it.segments.single().file, "r").use { file ->
                        val length = file.length()
                        val range = parseByteRange(headers["range"], length)
                        if (range == null) {
                            header(out, 416, "video/mp4", 0, "Content-Range: bytes */$length\r\n")
                            return
                        }
                        val partial = headers["range"] != null
                        header(out, if (partial) 206 else 200, "video/mp4", range.length,
                            "Accept-Ranges: bytes\r\n" + if (partial) "Content-Range: bytes ${range.start}-${range.end}/$length\r\n" else "")
                        if (!head) {
                            file.seek(range.start)
                            var remaining = range.length
                            val buffer = ByteArray(16_384)
                            while (remaining > 0 && !closed) {
                                val count = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(count > 0)
                                out.write(buffer, 0, count); remaining -= count
                            }
                        }
                    }
                }
            }
        }
        out.flush()
    }

    private fun bytes(out: OutputStream, code: Int, type: String, data: ByteArray, head: Boolean) {
        header(out, code, type, data.size.toLong())
        if (!head) out.write(data)
    }
    private fun header(out: OutputStream, code: Int, type: String, length: Long, extra: String = "") {
        val reason = when(code) { 200 -> "OK"; 206 -> "Partial Content"; 403 -> "Forbidden"; 404 -> "Not Found";
            405 -> "Method Not Allowed"; 410 -> "Gone"; else -> "Range Not Satisfiable" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; " +
            "connect-src 'self'; media-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'\r\n" +
            extra + "\r\n").toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        closed = true
        listener.close()
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow()
    }
}
