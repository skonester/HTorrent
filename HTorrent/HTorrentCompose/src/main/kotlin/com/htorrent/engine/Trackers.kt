// Kotlin adaptation of rqbit/crates/tracker_comms HTTP and UDP announce protocols.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

data class Announce(val hash: ByteArray, val peerId: ByteArray, val port: Int, val downloaded: Long,
                    val uploaded: Long, val left: Long, val event: String = "")
data class TrackerResponse(val peers: List<InetSocketAddress>, val intervalSeconds: Long)

object Trackers {
    fun announce(url: String, fields: Announce): TrackerResponse = when (URI(url).scheme?.lowercase()) {
        "http", "https" -> http(url, fields)
        "udp" -> udp(URI(url), fields)
        else -> error("Unsupported tracker scheme")
    }
    private fun http(url: String, f: Announce): TrackerResponse {
        fun encoded(b: ByteArray) = b.joinToString("") { "%%%02X".format(it.toInt() and 255) }
        val query = "info_hash=${encoded(f.hash)}&peer_id=${encoded(f.peerId)}&port=${f.port}&uploaded=${f.uploaded}" +
            "&downloaded=${f.downloaded}&left=${f.left}&compact=1&numwant=100" + if (f.event.isNotEmpty()) "&event=${f.event}" else ""
        val connection = URL(url + if ('?' in url) "&$query" else "?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "HTorrent/1.0")
        try {
            require(connection.responseCode in 200..299) { "Tracker HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
            require(bytes.size <= 2 * 1024 * 1024) { "Tracker response too large" }
            val response = Bencode.decode(bytes) as BValue.Dict
            response.text("failure reason")?.let { error(it) }
            val peers = mutableListOf<InetSocketAddress>()
            when (val value = response["peers"]) {
                is BValue.Bytes -> peers += compactPeers(value.value)
                is BValue.ListValue -> value.value.forEach { entry ->
                    val peer = entry as? BValue.Dict ?: return@forEach
                    val port = peer.long("port") ?: return@forEach
                    if (port in 1..65535) peer.text("ip")?.let { peers += InetSocketAddress(it, port.toInt()) }
                }
                else -> Unit
            }
            response.bytes("peers6")?.let { peers += compactPeers(it, true) }
            return TrackerResponse(peers.distinct(), maxOf(response.long("interval") ?: 1800, response.long("min interval") ?: 0, 30))
        } finally { connection.disconnect() }
    }
    private fun udp(uri: URI, f: Announce): TrackerResponse {
        require(uri.port in 1..65535) { "UDP tracker has no valid port" }
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port))
            fun exchange(request: ByteArray, transaction: Int, action: Int): ByteBuffer {
                for (attempt in 0..2) {
                    socket.send(DatagramPacket(request, request.size))
                    val deadline = System.nanoTime() + (1500L shl attempt) * 1_000_000
                    while (System.nanoTime() < deadline) {
                        socket.soTimeout = maxOf(1, ((deadline - System.nanoTime()) / 1_000_000).toInt())
                        val packet = DatagramPacket(ByteArray(65507), 65507)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                        if (packet.length < 8) continue
                        val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
                        val receivedAction = buffer.int
                        if (buffer.int != transaction) continue
                        if (receivedAction == 3) error(Charsets.UTF_8.decode(buffer).toString())
                        require(receivedAction == action) { "Unexpected UDP tracker action" }
                        return buffer
                    }
                }
                throw SocketTimeoutException("UDP tracker timed out")
            }
            val connectId = ThreadLocalRandom.current().nextInt()
            val connected = exchange(ByteBuffer.allocate(16).putLong(0x41727101980L).putInt(0).putInt(connectId).array(), connectId, 0)
            require(connected.remaining() >= 8)
            val connectionId = connected.long
            val transaction = ThreadLocalRandom.current().nextInt()
            val event = when (f.event) { "completed" -> 1; "started" -> 2; "stopped" -> 3; else -> 0 }
            val request = ByteBuffer.allocate(98).putLong(connectionId).putInt(1).putInt(transaction)
                .put(f.hash).put(f.peerId).putLong(f.downloaded).putLong(f.left).putLong(f.uploaded)
                .putInt(event).putInt(0).putInt(f.peerId.contentHashCode()).putInt(100).putShort(f.port.toShort()).array()
            val response = exchange(request, transaction, 1)
            require(response.remaining() >= 12)
            val interval = response.int.toLong() and 0xffffffffL
            response.int; response.int
            val peers = ByteArray(response.remaining()).also { response.get(it) }
            return TrackerResponse(compactPeers(peers, socket.inetAddress is Inet6Address), interval.coerceAtLeast(30))
        }
    }
}
