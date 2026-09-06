package com.htorrent.engine

import java.net.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.BitSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class EngineTest {
    private fun torrent(data: ByteArray, name: String = "payload.bin", pieceLength: Int = 32768): ByteArray = Bencode.encode(mapOf("info" to mapOf(
        "name" to name, "length" to data.size, "piece length" to pieceLength,
        "pieces" to data.asList().chunked(pieceLength).flatMap { sha1(it.toByteArray()).asList() }.toByteArray())))
    private fun await(seconds: Int = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (!condition()) { if (System.nanoTime() > deadline) fail("Timed out waiting for transfer state"); Thread.sleep(25) }
    }
    @Test fun `bencode rejects malformed and deeply nested input`() {
        listOf("i-0e", "i01e", "03:abc", "4:abc", "d1:ai1e1:ai2ee", "letrailing", "l".repeat(100) + "e".repeat(100)).forEach {
            assertFails { Bencode.decode(it.toByteArray()) }
        }
    }
    @Test fun `hash uses original info bytes even when key ordering is noncanonical`() {
        val bytes = "d4:name1:x6:lengthi0e12:piece lengthi16384e6:pieces0:e".toByteArray()
        val meta = Metainfo.parse("d4:info".toByteArray() + bytes + "e".toByteArray())
        assertContentEquals(sha1(bytes), meta.hash)
        assertContentEquals(meta.hash, Metainfo.parse(meta.torrentBytes()).hash)
    }
    @Test fun `reject torrent paths escaping output directory`() {
        listOf("../escape", "..", "C:escape", "CON", "x.", "a\\b").forEach { name ->
            assertFails { Metainfo.parse(torrent(byteArrayOf(1), name)) }
        }
    }
    @Test fun `piece ownership releases on disconnect and refuses corrupt writes`() {
        val data = randomBytes(50000)
        val meta = Metainfo.parse(torrent(data))
        val tracker = PieceTracker(meta)
        val available = BitSet().apply { set(0, meta.pieceCount) }
        val storage = TorrentStorage(Files.createTempDirectory("htorrent-pieces"), meta)
        assertEquals(0, tracker.acquire("one", available))
        assertEquals(1, tracker.acquire("two", available))
        tracker.release("one")
        assertEquals(0, tracker.acquire("three", available))
        assertFalse(tracker.commit("three", 0, ByteArray(meta.pieceSize(0)), storage))
        assertFalse(Files.exists(storage.filePath(0)))
        assertEquals(0, tracker.acquire("three", available))
        assertTrue(tracker.commit("three", 0, data.copyOfRange(0, 32768), storage))
        assertFalse(tracker.commit("one", 0, data.copyOfRange(0, 32768), storage))
    }
    @Test fun `pieces span multiple files and synthesize padding`() {
        val data = byteArrayOf(1, 2, 3, 0, 0, 4, 5, 6)
        val info = mapOf("name" to "folder", "piece length" to 8, "pieces" to sha1(data), "files" to listOf(
            mapOf("path" to listOf("a"), "length" to 3), mapOf("path" to listOf("padding"), "length" to 2, "attr" to "p"),
            mapOf("path" to listOf("b"), "length" to 3)))
        val meta = Metainfo.parse(Bencode.encode(mapOf("info" to info)))
        val storage = TorrentStorage(Files.createTempDirectory("htorrent-files"), meta)
        storage.writePiece(0, data)
        assertContentEquals(data, storage.read(0, data.size))
        assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(storage.filePath(2)))
        assertFalse(Files.exists(storage.filePath(1)))
    }
    @Test fun `routing table splits only own buckets and orders by xor`() {
        val table = RoutingTable(ByteArray(20))
        for (index in 1..100) {
            val id = ByteArray(20).apply { this[0] = index.toByte() }
            table.add(DhtNode(id, InetSocketAddress("127.0.0.1", 10000 + index)))
        }
        assertTrue(table.all().size < 100)
        assertEquals(1, table.closest(ByteArray(20)).first().id[0].toInt())
        val first = table.closest(ByteArray(20)).first()
        table.failed(first.address); table.failed(first.address)
        assertFalse(table.closest(ByteArray(20)).any { it.address == first.address })
    }
    @Test fun `DHT token protects announces and local lookup returns stored peers`() {
        Dht(Files.createTempDirectory("htorrent-dht"), bootstrap = emptyList(), timeoutMillis = 500).use { dht ->
            DatagramSocket().use { socket ->
                socket.soTimeout = 2000
                fun query(method: String, args: Map<String, Any>): BValue.Dict {
                    val bytes = Bencode.encode(mapOf("t" to "ab", "y" to "q", "q" to method, "a" to (args + ("id" to randomBytes(20)))))
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), dht.port))
                    val packet = DatagramPacket(ByteArray(4096), 4096); socket.receive(packet)
                    return Bencode.decode(packet.data.copyOf(packet.length)) as BValue.Dict
                }
                val hash = randomBytes(20)
                val token = query("get_peers", mapOf("info_hash" to hash)).dict("r")!!.bytes("token")!!
                assertEquals("e", query("announce_peer", mapOf("info_hash" to hash, "port" to 51413, "token" to "bad")).text("y"))
                assertEquals("r", query("announce_peer", mapOf("info_hash" to hash, "port" to 51413, "token" to token)).text("y"))
                val peers = query("get_peers", mapOf("info_hash" to hash)).dict("r")!!.list("values")
                assertEquals(51413, compactPeers((peers.single() as BValue.Bytes).value).single().port)
            }
            Dht(Files.createTempDirectory("htorrent-dht-client"), bootstrap = emptyList(), timeoutMillis = 500).use { client ->
                client.ping(InetSocketAddress("127.0.0.1", dht.port)).get(2, TimeUnit.SECONDS)
                assertTrue(client.nodeCount > 0)
            }
        }
    }
    @Test fun `UDP tracker validates transaction and sends actual listening port`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { socket ->
            socket.soTimeout = 5000
            val server = CompletableFuture.runAsync {
                repeat(2) { step ->
                    val packet = DatagramPacket(ByteArray(1024), 1024); socket.receive(packet)
                    val request = ByteBuffer.wrap(packet.data, 0, packet.length)
                    val connection = request.long; val action = request.int; val transaction = request.int
                    val response = if (step == 0) {
                        assertEquals(0x41727101980L, connection); assertEquals(0, action)
                        ByteBuffer.allocate(16).putInt(0).putInt(transaction).putLong(1234).array()
                    } else {
                        assertEquals(1234, connection); assertEquals(1, action); assertEquals(98, packet.length)
                        assertEquals(51413, ByteBuffer.wrap(packet.data, 96, 2).short.toInt() and 65535)
                        ByteBuffer.allocate(26).putInt(1).putInt(transaction).putInt(60).putInt(0).putInt(1)
                            .put(compactAddress(InetSocketAddress("127.0.0.1", 6000))).array()
                    }
                    socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
            }
            val result = Trackers.announce("udp://127.0.0.1:${socket.localPort}/announce", Announce(randomBytes(20), randomBytes(20), 51413, 0, 0, 100, "started"))
            assertEquals(6000, result.peers.single().port)
            server.get(5, TimeUnit.SECONDS)
        }
    }
    @Test fun `magnet resolves downloads verified data and survives pause and restart`() {
        val data = randomBytes(300_000)
        val torrent = torrent(data)
        val seedPath = Files.createTempDirectory("htorrent-seed")
        Files.write(seedPath.resolve("payload.bin"), data)
        val output = Files.createTempDirectory("htorrent-download")
        val state = Files.createTempDirectory("htorrent-session")
        Session(Files.createTempDirectory("htorrent-seed-session"), enableDiscovery = false).use { seeder ->
            val seed = seeder.addTorrent(torrent, seedPath)
            await { seed.status == TorrentStatus.SEEDING }
            Session(state, enableDiscovery = false).use { session ->
                val leecher = session.addMagnet("magnet:?xt=urn:btih:${seed.id}", output)
                leecher.addPeer(InetSocketAddress("127.0.0.1", seeder.port))
                await { leecher.status == TorrentStatus.SEEDING }
                assertContentEquals(data, Files.readAllBytes(output.resolve("payload.bin")))
                assertTrue(seed.uploaded.get() > 0)
                leecher.pause(); assertEquals(TorrentStatus.PAUSED, leecher.status)
                leecher.start(); await { leecher.status == TorrentStatus.SEEDING }
            }
            Session(state, enableDiscovery = false).use { restored ->
                restored.restore()
                await { restored.snapshots().single().status == TorrentStatus.SEEDING }
                assertEquals(100.0, restored.snapshots().single().progress)
            }
        }
    }
    @Test fun `private torrents refuse peers from DHT PEX and LAN discovery`() {
        val info = mapOf("name" to "private.bin", "length" to 1, "piece length" to 16384, "pieces" to sha1(byteArrayOf(1)), "private" to 1)
        Session(Files.createTempDirectory("htorrent-private-state"), enableDiscovery = false).use { session ->
            val torrent = session.addTorrent(Bencode.encode(mapOf("info" to info)), Files.createTempDirectory("htorrent-private"))
            torrent.offerPeer(InetSocketAddress("127.0.0.1", 1), "dht")
            torrent.offerPeer(InetSocketAddress("127.0.0.1", 2), "pex")
            Thread.sleep(1100)
            assertEquals(0, torrent.connections.size)
        }
    }
}
