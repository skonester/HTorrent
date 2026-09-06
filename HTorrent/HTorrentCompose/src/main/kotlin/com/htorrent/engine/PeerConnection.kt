// Kotlin adaptation of rqbit's peer_binary_protocol, peer_info_reader and live peer loop.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.UUID

internal class PeerConnection(
    private val torrent: ManagedTorrent, val address: InetSocketAddress, val source: String,
    private val epoch: Long, accepted: Socket? = null, private val receivedHandshake: Handshake? = null
) : AutoCloseable {
    private val socket = accepted ?: Socket()
    private val key = UUID.randomUUID().toString()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var closed = false
    private var choking = true
    private val available = BitSet()
    private var remoteMetadataId = 0
    private var remotePexId = 0
    private var metadataBytes: ByteArray? = null
    private val metadataHave = BitSet()
    private val metadataRequested = mutableMapOf<Int, Long>()
    private var currentPiece: Int? = null
    private var pieceBytes: ByteArray? = null
    private var nextOffset = 0
    private var receivedBytes = 0
    private val pending = mutableMapOf<Int, Pair<Int, Long>>()
    private var pieceStarted = 0L
    private var averageMillis: Long? = null
    private var lastPex = 0L
    private var lastSent = 0L
    private var lastReceived = System.currentTimeMillis()
    private var initialBitfield = false
    private var advertised = BitSet()
    private var extensions = false
    private fun active() = !closed && torrent.isCurrent(epoch)

    fun run() {
        try {
            torrent.connections[key] = this
            if (!active()) return
            if (!socket.isConnected) socket.connect(address, 10_000)
            socket.soTimeout = 15_000; socket.tcpNoDelay = true
            input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            output.write(handshake(torrent.hash, torrent.session.peerId, torrent.metadata?.private != true)); output.flush()
            val remote = receivedHandshake ?: readHandshake(input)
            require(remote.hash.contentEquals(torrent.hash) && !remote.peerId.contentEquals(torrent.session.peerId)) { "Wrong torrent or self connection" }
            extensions = remote.reserved[5].toInt() and 0x10 != 0
            if (extensions) sendExtensions()
            if (remote.reserved[7].toInt() and 1 != 0 && torrent.metadata?.private != true) torrent.session.dht?.let {
                send(9, ByteBuffer.allocate(2).putShort(it.port.toShort()).array())
            }
            publishHaves()
            send(2) // Interested; rqbit continues the same connection after metadata resolution.
            send(1) // Unchoke uploads of verified pieces.
            while (active()) {
                publishHaves()
                requestMetadata()
                requestBlocks()
                sendPex()
                if (System.currentTimeMillis() - lastSent > 60_000) { output.writeInt(0); output.flush(); lastSent = System.currentTimeMillis() }
                if (System.currentTimeMillis() - lastReceived > 180_000) error("Peer idle timeout")
                val frame = readFrame() ?: continue
                lastReceived = System.currentTimeMillis()
                if (frame.isEmpty()) continue
                val body = frame.copyOfRange(1, frame.size)
                when (frame[0].toInt() and 255) {
                    0 -> { require(body.isEmpty()); choking = true; releasePiece() }
                    1 -> { require(body.isEmpty()); choking = false }
                    2, 3 -> require(body.isEmpty())
                    4 -> {
                        require(body.size == 4)
                        val index = ByteBuffer.wrap(body).int
                        require(index in 0 until (torrent.metadata?.pieceCount ?: 8_000_000))
                        available.set(index)
                    }
                    5 -> {
                        val count = torrent.metadata?.pieceCount
                        require(body.size <= 1_000_000 && (count == null || body.size == (count + 7) / 8))
                        available.clear()
                        body.forEachIndexed { i, byte -> repeat(8) { bit -> if (byte.toInt() and (128 ushr bit) != 0) available.set(i * 8 + bit) } }
                        if (count != null) require(available.length() <= count) { "Invalid bitfield spare bits" }
                    }
                    6 -> upload(body)
                    7 -> receivePiece(body)
                    8 -> require(body.size == 12) // Uploads are written immediately, so queued cancels have nothing to remove.
                    9 -> {
                        require(body.size == 2)
                        val port = ByteBuffer.wrap(body).short.toInt() and 65535
                        if (port > 0 && torrent.metadata?.private != true) torrent.session.dht?.ping(InetSocketAddress(address.address, port))
                    }
                    20 -> extended(body)
                    else -> error("Unsupported peer message")
                }
            }
        } catch (e: Exception) {
            if (active()) torrent.discoveryError = e.message
        } finally {
            releasePiece(); close(); torrent.connections.remove(key)
        }
    }
    private fun send(id: Int, bytes: ByteArray = byteArrayOf()) {
        output.writeInt(bytes.size + 1); output.writeByte(id); output.write(bytes); output.flush()
        lastSent = System.currentTimeMillis()
    }
    private fun extendedSend(id: Int, bytes: ByteArray) { if (id in 1..255) send(20, byteArrayOf(id.toByte()) + bytes) }
    private fun sendExtensions() {
        val map = mutableMapOf<String, Any>("m" to mapOf("ut_metadata" to 1, "ut_pex" to if (torrent.metadata?.private == true) 0 else 2),
            "v" to "HTorrent Kotlin", "p" to torrent.session.port, "reqq" to 32)
        torrent.metadata?.let { map["metadata_size"] = it.infoBytes.size }
        send(20, byteArrayOf(0) + Bencode.encode(map))
    }
    private fun readFrame(): ByteArray? {
        socket.soTimeout = 1000
        val first = try { input.read() } catch (_: SocketTimeoutException) { return null }
        if (first < 0) throw EOFException()
        socket.soTimeout = 30_000
        val length = (first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        require(length in 0..(1024 * 1024 + 32)) { "Invalid peer message length" }
        return ByteArray(length).also { input.readFully(it) }
    }
    private fun publishHaves() {
        val tracker = torrent.pieces ?: return
        val meta = torrent.metadata ?: return
        if (!initialBitfield) {
            val bits = tracker.bitfield()
            send(5, bits)
            bits.forEachIndexed { i, b -> repeat(8) { bit -> if (b.toInt() and (128 ushr bit) != 0) advertised.set(i * 8 + bit) } }
            initialBitfield = true
            if (extensions) sendExtensions()
        } else {
            for (index in 0 until meta.pieceCount) if (tracker.has(index) && !advertised[index]) {
                send(4, ByteBuffer.allocate(4).putInt(index).array()); advertised.set(index)
            }
        }
    }
    private fun extended(body: ByteArray) {
        require(body.isNotEmpty())
        val ext = body[0].toInt() and 255
        val data = body.copyOfRange(1, body.size)
        when (ext) {
            0 -> {
                val hello = Bencode.decode(data) as BValue.Dict
                hello.dict("m")?.let { m ->
                    m.long("ut_metadata")?.let { require(it in 0..255); remoteMetadataId = it.toInt() }
                    m.long("ut_pex")?.let { require(it in 0..255); remotePexId = it.toInt() }
                }
                val size = hello.long("metadata_size")
                if (torrent.metadata == null && size != null && remoteMetadataId > 0) {
                    require(size in 1..(32 * 1024 * 1024)) { "Invalid metadata size" }
                    if (metadataBytes == null) metadataBytes = ByteArray(size.toInt())
                    require(metadataBytes!!.size.toLong() == size) { "Peer changed metadata size" }
                }
            }
            1 -> metadataMessage(data)
            2 -> if (torrent.metadata?.private != true) {
                val pex = Bencode.decode(data) as BValue.Dict
                pex.bytes("added")?.let { compactPeers(it).take(50).forEach { peer -> torrent.offerPeer(peer, "pex") } }
                pex.bytes("added6")?.let { compactPeers(it, true).take(50).forEach { peer -> torrent.offerPeer(peer, "pex") } }
            }
        }
    }
    private fun requestMetadata() {
        if (torrent.metadata != null) { metadataBytes = null; return }
        val bytes = metadataBytes ?: return
        if (remoteMetadataId == 0) return
        if (metadataRequested.values.any { System.currentTimeMillis() - it > 20_000 }) error("Metadata request timed out")
        for (piece in 0 until (bytes.size + BLOCK_SIZE - 1) / BLOCK_SIZE) {
            if (metadataRequested.size >= 4) break
            if (!metadataHave[piece] && piece !in metadataRequested) {
                extendedSend(remoteMetadataId, Bencode.encode(mapOf("msg_type" to 0, "piece" to piece)))
                metadataRequested[piece] = System.currentTimeMillis()
            }
        }
    }
    private fun metadataMessage(data: ByteArray) {
        val reader = Bencode.Reader(data)
        val header = reader.read() as BValue.Dict
        val indexLong = header.long("piece") ?: error("Missing metadata piece")
        require(indexLong in 0..Int.MAX_VALUE.toLong())
        val index = indexLong.toInt()
        when (header.long("msg_type")) {
            0L -> {
                require(reader.position == data.size)
                val info = torrent.metadata?.infoBytes
                if (info == null || index >= (info.size + BLOCK_SIZE - 1) / BLOCK_SIZE) {
                    extendedSend(remoteMetadataId, Bencode.encode(mapOf("msg_type" to 2, "piece" to index)))
                } else {
                    val start = index * BLOCK_SIZE
                    extendedSend(remoteMetadataId, Bencode.encode(mapOf("msg_type" to 1, "piece" to index, "total_size" to info.size)) +
                        info.copyOfRange(start, minOf(start + BLOCK_SIZE, info.size)))
                }
            }
            1L -> {
                if (torrent.metadata != null) return
                val bytes = metadataBytes ?: error("Unexpected metadata")
                require(index in metadataRequested && header.long("total_size") == bytes.size.toLong()) { "Unexpected metadata piece" }
                val offset = index * BLOCK_SIZE
                require(offset < bytes.size && data.size - reader.position == minOf(BLOCK_SIZE, bytes.size - offset))
                data.copyInto(bytes, offset, reader.position)
                metadataHave.set(index); metadataRequested.remove(index)
                if (metadataHave.cardinality() == (bytes.size + BLOCK_SIZE - 1) / BLOCK_SIZE) {
                    torrent.receiveMetadata(bytes, epoch); metadataBytes = null
                }
            }
            2L -> error("Peer rejected metadata request")
            else -> error("Invalid metadata message")
        }
    }
    private fun requestBlocks() {
        val tracker = torrent.pieces ?: return
        val meta = torrent.metadata ?: return
        val piece = currentPiece
        if (piece != null && !tracker.owns(key, piece)) {
            pending.forEach { (offset, request) -> send(8, ByteBuffer.allocate(12).putInt(piece).putInt(offset).putInt(request.first).array()) }
            releasePiece()
        }
        if (choking) return
        if (pending.values.any { System.currentTimeMillis() - it.second > 30_000 }) error("Piece request timed out")
        if (currentPiece == null) {
            currentPiece = tracker.acquire(key, available, averageMillis) ?: return
            pieceBytes = ByteArray(meta.pieceSize(currentPiece!!))
            nextOffset = 0; receivedBytes = 0; pieceStarted = System.currentTimeMillis()
        }
        val bytes = pieceBytes!!
        while (pending.size < 32 && nextOffset < bytes.size) {
            val length = minOf(BLOCK_SIZE, bytes.size - nextOffset)
            send(6, ByteBuffer.allocate(12).putInt(currentPiece!!).putInt(nextOffset).putInt(length).array())
            pending[nextOffset] = length to System.currentTimeMillis()
            nextOffset += length
        }
    }
    private fun receivePiece(body: ByteArray) {
        require(body.size in 9..(BLOCK_SIZE + 8))
        val buffer = ByteBuffer.wrap(body)
        val index = buffer.int
        val offset = buffer.int
        val tracker = torrent.pieces ?: error("Data before metadata")
        if (index != currentPiece || !tracker.owns(key, index)) return // Late reply to a canceled or stolen request.
        val request = pending.remove(offset) ?: error("Unrequested piece block")
        require(request.first == buffer.remaining()) { "Unexpected block size" }
        torrent.session.downloadLimiter.consume(request.first, ::active)
        if (!active()) return
        buffer.get(pieceBytes!!, offset, request.first)
        torrent.downloaded.addAndGet(request.first.toLong()); receivedBytes += request.first
        if (receivedBytes == pieceBytes!!.size) {
            try {
                if (!tracker.commit(key, index, pieceBytes!!, torrent.storage!!)) error("Piece verification failed")
            } catch (e: IOException) { torrent.fail(e, epoch); throw e }
            val elapsed = maxOf(1, System.currentTimeMillis() - pieceStarted)
            averageMillis = averageMillis?.let { (it * 3 + elapsed) / 4 } ?: elapsed
            releasePiece(); torrent.refreshStatus()
        }
    }
    private fun upload(body: ByteArray) {
        require(body.size == 12)
        val buffer = ByteBuffer.wrap(body)
        val index = buffer.int; val offset = buffer.int; val length = buffer.int
        val meta = torrent.metadata ?: return
        require(index in meta.hashes.indices && offset >= 0 && length in 1..BLOCK_SIZE && offset.toLong() + length <= meta.pieceSize(index)) { "Invalid upload request" }
        if (torrent.pieces?.has(index) != true) return
        torrent.session.uploadLimiter.consume(length, ::active)
        if (!active()) return
        val bytes = torrent.storage!!.read(index.toLong() * meta.pieceLength + offset, length)
        send(7, ByteBuffer.allocate(8).putInt(index).putInt(offset).array() + bytes)
        torrent.uploaded.addAndGet(length.toLong())
    }
    private fun sendPex() {
        if (remotePexId == 0 || torrent.metadata?.private != false || System.currentTimeMillis() - lastPex < 60_000) return
        lastPex = System.currentTimeMillis()
        val addresses = torrent.connections.values.filter { it !== this && it.source != "incoming" }.map { it.address }.distinct().take(50)
        val v4 = addresses.filter { it.address !is Inet6Address }
        val v6 = addresses.filter { it.address is Inet6Address }
        extendedSend(remotePexId, Bencode.encode(mapOf("added" to v4.flatMap { compactAddress(it).asList() }.toByteArray(),
            "added.f" to ByteArray(v4.size), "added6" to v6.flatMap { compactAddress(it).asList() }.toByteArray(), "added6.f" to ByteArray(v6.size))))
    }
    private fun releasePiece() { torrent.pieces?.release(key); currentPiece = null; pieceBytes = null; pending.clear() }
    override fun close() { closed = true; runCatching { socket.close() } }
    data class Handshake(val hash: ByteArray, val peerId: ByteArray, val reserved: ByteArray)
    companion object {
        const val BLOCK_SIZE = 16384
        fun handshake(hash: ByteArray, peerId: ByteArray, dht: Boolean): ByteArray {
            require(hash.size == 20 && peerId.size == 20)
            val reserved = ByteArray(8).apply { this[5] = 0x10; this[7] = if (dht) 1 else 0 }
            return byteArrayOf(19) + "BitTorrent protocol".toByteArray() + reserved + hash + peerId
        }
        fun readHandshake(stream: InputStream): Handshake {
            val input = DataInputStream(stream)
            require(input.readUnsignedByte() == 19) { "Invalid BitTorrent handshake" }
            val protocol = ByteArray(19).also { input.readFully(it) }
            require(protocol.contentEquals("BitTorrent protocol".toByteArray()))
            val reserved = ByteArray(8).also { input.readFully(it) }
            val hash = ByteArray(20).also { input.readFully(it) }
            val peer = ByteArray(20).also { input.readFully(it) }
            return Handshake(hash, peer, reserved)
        }
    }
}
