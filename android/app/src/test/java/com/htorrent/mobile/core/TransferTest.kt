package com.htorrent.mobile.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TransferTest {
    private val content = ByteArray(6 * 1024 * 1024 + 123).also { Random(7).nextBytes(it) }
    @Volatile private var etag = "\"v1\""
    @Volatile private var ranges = true
    @Volatile private var delayMs = 0L
    private val served = AtomicLong()
    @Volatile private var phase = 0
    private val cookiesSeen = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: MiniHttpServer
    private lateinit var other: MiniHttpServer
    private lateinit var dir: File

    private val base get() = "http://127.0.0.1:${server.port}"

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("ndm-test").toFile()
        server = MiniHttpServer()
        server.createContext("/file.bin") { ex ->
            val myPhase = phase
            cookiesSeen.add(ex.requestHeaders.getFirst("Cookie"))
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            ex.responseHeaders.add("ETag", etag)
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            var start = 0; var end = content.size - 1; var code = 200
            if (ranges && range != null && (ifRange == null || ifRange == etag)) {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)!!
                start = m.groupValues[1].toInt()
                if (m.groupValues[2].isNotEmpty()) end = minOf(end, m.groupValues[2].toInt())
                code = 206
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${content.size}")
            }
            val len = end - start + 1
            ex.sendResponseHeaders(code, len.toLong())
            try {
                ex.responseBody.use { out ->
                    var pos = start
                    while (pos <= end) {
                        val n = minOf(32 * 1024, end - pos + 1)
                        out.write(content, pos, n)
                        if (myPhase == phase) served.addAndGet(n.toLong())
                        pos += n
                        if (delayMs > 0) Thread.sleep(delayMs)
                    }
                }
            } catch (_: Exception) {
            }
        }
        server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.createContext("/same") { ex -> ex.responseHeaders.add("Location", "/file.bin"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.start()
        other = MiniHttpServer()
        other.createContext("/away") { ex ->
            ex.responseHeaders.add("Location", "$base/file.bin"); ex.sendResponseHeaders(302, -1); ex.close()
        }
        other.start()
    }

    @After fun tearDown() {
        server.stop(); other.stop(); dir.deleteRecursively()
    }

    private class Result : TransferListener {
        val latch = CountDownLatch(1)
        @Volatile var state: TransferState? = null
        @Volatile var error: String? = null
        @Volatile var pausedState: TransferState? = null
        val pausedLatch = CountDownLatch(1)
        override fun progress(state: TransferState, bytesPerSecond: Long) {}
        override fun finished(state: TransferState) { this.state = state; latch.countDown() }
        override fun failed(message: String, state: TransferState) { error = message; latch.countDown() }
        override fun paused(state: TransferState) { pausedState = state; pausedLatch.countDown() }
    }

    private fun run(req: DownloadRequest, file: File, connections: Int = 8, initial: TransferState? = null): Result {
        val r = Result()
        Transfer(req, file, connections, initial, r).start()
        assertTrue("timed out", r.latch.await(60, TimeUnit.SECONDS))
        return r
    }

    @Test fun downloadsWithManyConnections() {
        val f = File(dir, "a.bin")
        val r = run(DownloadRequest("$base/file.bin"), f)
        assertNull(r.error)
        assertTrue(r.state!!.segments.size >= 8)
        assertArrayEquals(content, f.readBytes())
    }

    @Test fun downloadsWithoutRanges() {
        ranges = false
        val f = File(dir, "b.bin")
        val r = run(DownloadRequest("$base/file.bin"), f)
        assertNull(r.error)
        assertEquals(1, r.state!!.segments.size)
        assertArrayEquals(content, f.readBytes())
    }

    @Test fun probeReadsSizeNameAndVersion() {
        val p = Http.probe(DownloadRequest("$base/same"))
        assertEquals(content.size.toLong(), p.size)
        assertTrue(p.resumable)
        assertEquals("file.bin", p.fileName)
        assertEquals("\"v1\"", p.validator)
    }

    @Test fun cookiesStayOnTheirOwnSite() {
        // Same-site redirect keeps the cookie.
        run(DownloadRequest("$base/same", cookies = "id=1"), File(dir, "c.bin"), connections = 1)
        assertTrue(cookiesSeen.isNotEmpty() && cookiesSeen.all { it == "id=1" })
        cookiesSeen.clear()
        // A redirect from another server must not carry that server's cookie.
        val f = File(dir, "d.bin")
        run(DownloadRequest("http://127.0.0.1:${other.port}/away", cookies = "secret=1"), f, connections = 2)
        assertTrue(cookiesSeen.isNotEmpty() && cookiesSeen.all { it == null })
        assertArrayEquals(content, f.readBytes())
    }

    @Test fun pausesAndResumesWithoutStartingOver() {
        delayMs = 60
        val f = File(dir, "e.bin")
        val r = Result()
        val t = Transfer(DownloadRequest("$base/file.bin"), f, 4, null, r)
        t.start()
        Thread.sleep(700)
        t.pause()
        assertTrue(r.pausedLatch.await(10, TimeUnit.SECONDS))
        val saved = r.pausedState!!
        assertTrue("written ${saved.written}", saved.written > 0 && saved.written < content.size)
        delayMs = 5 // like a real network: data is not all in flight at once
        phase++ // bytes still pushed into the closed connections don't count
        served.set(0)
        val r2 = run(DownloadRequest("$base/file.bin"), f, 4, saved)
        assertNull(r2.error)
        assertArrayEquals(content, f.readBytes())
        // Only the rest was downloaded again (plus what was in flight when a range was split).
        assertTrue("served ${served.get()} of ${content.size - saved.written}", served.get() <= content.size - saved.written + 512 * 1024)
    }

    @Test fun aChangedFileIsDownloadedAgain() {
        val f = File(dir, "g.bin")
        val plan = SegmentPlanner.plan(content.size.toLong(), 4).toMutableList()
        plan[0].written = 1000
        f.writeBytes(ByteArray(content.size))
        etag = "\"v2\"" // the saved state says v1
        val r = run(DownloadRequest("$base/file.bin"), f, 4, TransferState(content.size.toLong(), true, "\"v1\"", plan))
        assertNull(r.error)
        assertArrayEquals(content, f.readBytes())
        assertEquals("\"v2\"", r.state!!.validator)
    }

    @Test fun missingFileFails() {
        val r = run(DownloadRequest("$base/missing"), File(dir, "h.bin"))
        assertTrue(r.error!!.contains("404"))
    }
}
