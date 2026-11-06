package com.htorrent.engine

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class StreamingTest {
    @Test fun `byte ranges handle seeks suffixes and invalid requests`() {
        assertEquals(HttpApi.ByteRange(2, 5), HttpApi.parseRange("bytes=2-5", 10))
        assertEquals(HttpApi.ByteRange(8, 9), HttpApi.parseRange("bytes=-2", 10))
        assertEquals(HttpApi.ByteRange(3, 9), HttpApi.parseRange("bytes=3-", 10))
        assertEquals(HttpApi.ByteRange(3, 9), HttpApi.parseRange("bytes=3-20", 10))
        listOf("bytes=10-", "bytes=-0", "bytes=4-2", "bytes=1-2,4-5", "bytes=-").forEach {
            assertFailsWith<IllegalArgumentException> { HttpApi.parseRange(it, 10) }
        }
    }
    @Test fun `fast resume accepts unchanged files and rejects modified bytes`() {
        val data = randomBytes(1024)
        val meta = Metainfo.fromInfo(Bencode.encode(mapOf("name" to "data", "length" to data.size, "piece length" to 16384, "pieces" to sha1(data))))
        val root = Files.createTempDirectory("htorrent-resume")
        val storage = TorrentStorage(root, meta)
        storage.writePiece(0, data)
        val tracker = PieceTracker(meta).apply { verified(0) }
        val resume = root.resolve("resume.bencode")
        Resume.save(resume, storage, tracker)
        val restored = PieceTracker(meta)
        assertTrue(Resume.load(resume, storage, restored)); assertTrue(restored.has(0))
        Files.write(storage.filePath(0), ByteArray(data.size))
        Files.setLastModifiedTime(storage.filePath(0), FileTime.fromMillis(System.currentTimeMillis() + 5000))
        assertFalse(Resume.load(resume, storage, PieceTracker(meta)))
    }
    @Test fun `HTTP streaming returns exact range bytes HEAD and 416`() {
        val data = randomBytes(50000)
        val meta = Metainfo.fromInfo(Bencode.encode(mapOf("name" to "data", "length" to data.size, "piece length" to data.size, "pieces" to sha1(data))))
        val output = Files.createTempDirectory("htorrent-stream")
        Files.write(output.resolve("data"), data)
        Session(Files.createTempDirectory("htorrent-stream-state"), enableDiscovery = false).use { session ->
            val torrent = session.addTorrent(meta.torrentBytes(), output)
            val deadline = System.currentTimeMillis() + 5000
            while (torrent.status != TorrentStatus.SEEDING) { assertTrue(System.currentTimeMillis() < deadline); Thread.sleep(10) }
            HttpApi(session, { output }).use { api ->
                fun request(method: String, range: String): HttpURLConnection =
                    (URL("http://127.0.0.1:${api.port}/torrents/${torrent.id}/stream/0").openConnection() as HttpURLConnection).apply {
                        requestMethod = method; setRequestProperty("Range", range); readTimeout = 3000
                    }
                request("GET", "bytes=123-456").let { connection ->
                    try {
                        assertEquals(206, connection.responseCode)
                        assertEquals("bytes 123-456/50000", connection.getHeaderField("Content-Range"))
                        assertContentEquals(data.copyOfRange(123, 457), connection.inputStream.use { it.readBytes() })
                    } finally { connection.disconnect() }
                }
                request("HEAD", "bytes=-100").let { connection ->
                    try { assertEquals(206, connection.responseCode); assertEquals(100, connection.contentLength); assertEquals(-1, connection.inputStream.read()) }
                    finally { connection.disconnect() }
                }
                request("GET", "bytes=50000-").let { connection ->
                    try { assertEquals(416, connection.responseCode); assertEquals("bytes */50000", connection.getHeaderField("Content-Range")) }
                    finally { connection.disconnect() }
                }
            }
        }
    }
}
