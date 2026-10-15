package com.archery.delaycam.viewer

import com.archery.delaycam.camera.*
import com.archery.delaycam.settings.PictureMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

class LanViewerServerTest {
    private fun get(server: LanViewerServer, route: String, range: String? = null, method: String = "GET"): String =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(("$method $route HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n" +
                (range?.let { "Range: $it\r\n" } ?: "") + "\r\n").toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test fun privateSessionHasNoArbitraryFileOrControlAccessAndSupportsMediaRanges() {
        val dir = Files.createTempDirectory("viewer-test").toFile()
        val store = ImmutableSegmentStore(dir, "fixture")
        val file = store.allocate().apply { writeText("0123456789") }; store.publish(file)
        val source = ViewerSegmentSource().apply {
            update(true, 5, PictureMode.FILL); publish(SegmentInfo(file, 1000, 2000, 1000, 4f / 3f), store)
        }
        try {
            LanViewerServer(source, { "fixture-$it".toByteArray() }, InetAddress.getLoopbackAddress()).use { server ->
                val base = "/v/${server.token}/"
                assertTrue(get(server, "/status").startsWith("HTTP/1.1 404"))
                assertTrue(get(server, "${base}../fixture.mp4").startsWith("HTTP/1.1 404"))
                assertTrue(get(server, "${base}control", method = "POST").startsWith("HTTP/1.1 405"))
                val status = get(server, "${base}status")
                assertTrue(status.contains("\"pictureMode\":\"FILL\""))
                assertFalse(status.contains(file.absolutePath))
                assertTrue(get(server, base).contains("fixture-index.html"))
                assertTrue(get(server, "${base}segment/0.mp4", "bytes=0-1,3-4").startsWith("HTTP/1.1 416"))
                val response = get(server, "${base}segment/0.mp4", "bytes=3-5")
                assertTrue(response.startsWith("HTTP/1.1 206"))
                assertTrue(response.contains("Content-Range: bytes 3-5/10"))
                assertTrue(response.endsWith("345"))
                assertTrue(get(server, "${base}segment/0.mp4", "bytes=-2").endsWith("89"))
                assertTrue(get(server, "${base}segment/0.mp4", "bytes=99-").startsWith("HTTP/1.1 416"))
                val head = get(server, "${base}segment/0.mp4", method = "HEAD")
                assertTrue(head.contains("Content-Length: 10")); assertTrue(head.endsWith("\r\n\r\n"))
                // Publishing replacement files can trim old footage; stale URLs must fail instead of reading a new file.
                store.setKeepCount(2)
                repeat(2) { store.publish(store.allocate().apply { writeText("replacement") }) }
                assertTrue(get(server, "${base}segment/0.mp4").startsWith("HTTP/1.1 410"))
                val oldEpoch = source.status(); source.reset()
                assertNotEquals(oldEpoch, source.status()); assertTrue(source.status().contains("\"segments\":[]"))
                assertTrue(get(server, "${base}segment/0.mp4").startsWith("HTTP/1.1 410"))
            }
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test fun computerJoinRequiresExplicitPhoneApprovalAndRejectsDeniedRequests() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<LanViewerServer.JoinRequest>())
        LanViewerServer(ViewerSegmentSource(), { "fixture-$it".toByteArray() },
            InetAddress.getLoopbackAddress(), { requests += it }).use { server ->
            assertTrue(get(server, "/").contains("fixture-join.html"))
            val id = "a".repeat(48)
            assertTrue(get(server, "/join/$id", method = "POST").startsWith("HTTP/1.1 200"))
            assertFalse(get(server, "/join/$id").contains(server.token))
            assertTrue(get(server, "/join/$id").contains("pending"))
            get(server, "/join/$id", method = "POST")
            assertEquals(1, requests.size)
            assertEquals("AAAAAA", requests.single().code)
            server.approveJoin(id, false)
            assertTrue(get(server, "/join/$id").contains("denied"))
            assertFalse(get(server, "/join/$id").contains(server.token))
            val next = "b".repeat(48)
            get(server, "/join/$next", method = "POST")
            server.approveJoin(next, true)
            assertTrue(get(server, "/join/$next").contains("/v/${server.token}/"))
            assertTrue(get(server, "/join/" + "c".repeat(48)).startsWith("HTTP/1.1 404"))
            repeat(4) { i -> get(server, "/join/" + (i+1).toString().repeat(48), method = "POST") }
            assertTrue(get(server, "/join/" + "d".repeat(48), method = "POST").startsWith("HTTP/1.1 429"))
        }
    }

    @Test fun rangesRejectOverflowMultipleOrInvalidValues() {
        assertNull(parseByteRange("bytes=9223372036854775808-", 10))
        assertNull(parseByteRange("bytes=0-1,4-5", 10))
        assertNull(parseByteRange("bytes=-0", 10))
        assertNull(parseByteRange("bytes=8-2", 10))
        assertEquals(ByteWindow(0,9), parseByteRange("bytes=-99", 10))
        assertEquals(ByteWindow(3,9), parseByteRange("bytes=3-99", 10))
    }
}
