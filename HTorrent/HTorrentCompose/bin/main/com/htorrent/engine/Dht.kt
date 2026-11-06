// Kotlin adaptation of rqbit/crates/dht: routing_table, dht, peer_store and persistence.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.math.BigInteger
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

internal fun daemonPool(name: String, size: Int): ExecutorService = Executors.newFixedThreadPool(size) { task ->
    Thread(task, name).apply { isDaemon = true }
}
internal fun daemonScheduler(name: String, size: Int = 1): ScheduledExecutorService = Executors.newScheduledThreadPool(size) { task ->
    Thread(task, name).apply { isDaemon = true }
}

internal data class DhtNode(val id: ByteArray, val address: InetSocketAddress, var seen: Long = System.currentTimeMillis(), var failures: Int = 0)
internal class RoutingTable(private val ownId: ByteArray) {
    private data class Bucket(val start: BigInteger, val end: BigInteger, val nodes: MutableList<DhtNode> = mutableListOf(), var changed: Long = System.currentTimeMillis())
    private val buckets = mutableListOf(Bucket(BigInteger.ZERO, BigInteger.ONE.shiftLeft(160)))
    @Synchronized fun add(node: DhtNode) {
        if (node.id.size != 20 || node.id.contentEquals(ownId)) return
        val key = BigInteger(1, node.id)
        while (true) {
            val bucket = buckets.first { key >= it.start && key < it.end }
            bucket.nodes.firstOrNull { it.id.contentEquals(node.id) }?.let {
                if (it.address == node.address) { it.seen = node.seen; it.failures = 0; bucket.changed = node.seen }
                return
            }
            bucket.nodes.removeAll { it.failures >= 2 }
            if (bucket.nodes.size < 8) { bucket.nodes += node; bucket.changed = node.seen; return }
            val own = BigInteger(1, ownId)
            if (own < bucket.start || own >= bucket.end || buckets.size >= 160) return
            val middle = (bucket.start + bucket.end).shiftRight(1)
            buckets.remove(bucket)
            buckets += Bucket(bucket.start, middle, bucket.nodes.filter { BigInteger(1, it.id) < middle }.toMutableList())
            buckets += Bucket(middle, bucket.end, bucket.nodes.filter { BigInteger(1, it.id) >= middle }.toMutableList())
        }
    }
    @Synchronized fun closest(target: ByteArray, count: Int = 8): List<DhtNode> = buckets.flatMap { it.nodes }
        .filter { it.failures < 2 }.sortedBy { BigInteger(1, it.id).xor(BigInteger(1, target)) }.take(count)
    @Synchronized fun failed(address: InetSocketAddress) { buckets.flatMap { it.nodes }.filter { it.address == address }.forEach { it.failures++ } }
    @Synchronized fun all() = buckets.flatMap { it.nodes }.toList()
    @Synchronized fun refreshTargets(): List<ByteArray> = buckets.filter { System.currentTimeMillis() - it.changed > 900_000 }.map {
        val n = it.start + BigInteger(1, randomBytes(20)).mod(it.end - it.start)
        n.toByteArray().takeLast(20).toByteArray().let { bytes -> ByteArray(20 - bytes.size) + bytes }
    }
}

class Dht(
    private val directory: Path,
    port: Int = 0,
    private val bootstrap: List<Pair<String, Int>> = listOf("dht.transmissionbt.com" to 6881, "dht.libtorrent.org" to 25401),
    private val timeoutMillis: Long = 10_000
) : AutoCloseable {
    private val stateFile = directory.resolve("dht.bencode")
    private val saved = runCatching { Bencode.decode(Files.readAllBytes(stateFile)) as BValue.Dict }.getOrNull()
    val id: ByteArray = saved?.bytes("id")?.takeIf { it.size == 20 } ?: randomBytes(20)
    private val v4 = RoutingTable(id)
    private val v6 = RoutingTable(id)
    private val socket = DatagramSocket(port)
    val port: Int get() = socket.localPort
    val nodeCount get() = v4.all().size + v6.all().size
    private val receiver = daemonPool("htorrent-dht-receive", 1)
    private val workers = daemonPool("htorrent-dht-lookup", 4)
    private val timer = daemonScheduler("htorrent-dht-timer")
    private data class Request(val address: InetSocketAddress, val result: CompletableFuture<BValue.Dict>)
    private val pending = ConcurrentHashMap<String, Request>()
    private val serial = AtomicInteger()
    private val peers = ConcurrentHashMap<String, ConcurrentHashMap<InetSocketAddress, Long>>()
    private var secret = randomBytes(20)
    private var previousSecret = secret
    private var rotatedAt = System.currentTimeMillis()
    @Volatile private var closed = false
    private var sentWindow = 0L
    private var sentCount = 0

    init {
        Files.createDirectories(directory)
        receiver.execute { receive() }
        val seeds = saved?.list("nodes").orEmpty().mapNotNull { value ->
            val row = value as? BValue.Dict ?: return@mapNotNull null
            runCatching { InetSocketAddress(row.text("host"), row.long("port")!!.toInt()) }.getOrNull()
        }
        workers.execute {
            seeds.take(64).map { ping(it) }.forEach { runCatching { it.get(timeoutMillis + 500, TimeUnit.MILLISECONDS) } }
            bootstrap.forEach { (host, p) -> runCatching { InetAddress.getAllByName(host).map { ping(InetSocketAddress(it, p)) }.forEach { it.get(timeoutMillis + 500, TimeUnit.MILLISECONDS) } } }
            lookup(id, false, null) {}
        }
        timer.scheduleWithFixedDelay({ runCatching {
            synchronized(this) { if (System.currentTimeMillis() - rotatedAt >= 300_000) { previousSecret = secret; secret = randomBytes(20); rotatedAt = System.currentTimeMillis() } }
            peers.values.forEach { entries -> entries.entries.removeIf { System.currentTimeMillis() - it.value > 1_800_000 } }
            peers.entries.removeIf { it.value.isEmpty() }
            persist()
            if (nodeCount == 0) bootstrap.forEach { (host, p) -> workers.execute { runCatching { ping(InetSocketAddress(host, p)) } } }
            (v4.refreshTargets() + v6.refreshTargets()).take(8).forEach { target -> workers.execute { lookup(target, false, null) {} } }
            (v4.all() + v6.all()).filter { System.currentTimeMillis() - it.seen > 900_000 }.take(32).forEach { ping(it.address) }
        } }, 60, 60, TimeUnit.SECONDS)
    }

    private fun table(address: InetSocketAddress) = if (address.address is Inet6Address) v6 else v4
    fun ping(address: InetSocketAddress): CompletableFuture<BValue.Dict> = query(address, "ping", emptyMap())
    private fun query(address: InetSocketAddress, method: String, args: Map<String, Any>): CompletableFuture<BValue.Dict> {
        val future = CompletableFuture<BValue.Dict>()
        if (closed || address.isUnresolved || address.port == 0 || pending.size >= 1024) {
            future.completeExceptionally(IllegalStateException("DHT unavailable")); return future
        }
        val tid = java.nio.ByteBuffer.allocate(4).putInt(serial.incrementAndGet()).array()
        val key = tid.hex()
        pending[key] = Request(address, future)
        try {
            send(address, mapOf("t" to tid, "y" to "q", "q" to method, "a" to (args + ("id" to id)), "v" to "HT10"))
            timer.schedule({ pending.remove(key)?.let { table(address).failed(address); it.result.completeExceptionally(TimeoutException("DHT response timeout")) } }, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) { pending.remove(key); future.completeExceptionally(e) }
        return future
    }
    @Synchronized private fun send(address: InetSocketAddress, value: Map<String, Any>) {
        val now = System.currentTimeMillis() / 1000
        if (sentWindow != now) { sentWindow = now; sentCount = 0 }
        require(++sentCount <= 250) { "DHT rate limit" }
        val bytes = Bencode.encode(value)
        socket.send(DatagramPacket(bytes, bytes.size, address))
    }
    private fun receive() {
        while (!closed) try {
            val packet = DatagramPacket(ByteArray(65507), 65507)
            socket.receive(packet)
            val address = packet.socketAddress as InetSocketAddress
            val msg = Bencode.decode(packet.data.copyOf(packet.length)) as? BValue.Dict ?: continue
            val tid = msg.bytes("t")?.takeIf { it.size <= 64 } ?: continue
            when (msg.text("y")) {
                "r", "e" -> {
                    val key = tid.hex()
                    val req = pending[key] ?: continue
                    if (req.address != address) continue
                    if (msg.text("y") == "e") {
                        pending.remove(key)?.result?.completeExceptionally(IllegalStateException("DHT remote error")); continue
                    }
                    val response = msg.dict("r") ?: continue
                    val remote = response.bytes("id")?.takeIf { it.size == 20 } ?: continue
                    table(address).add(DhtNode(remote, address))
                    pending.remove(key)?.result?.complete(response)
                }
                "q" -> respond(address, tid, msg)
            }
        } catch (_: Exception) { /* Invalid datagrams must never stop the receiver. */ }
    }
    @Synchronized private fun token(address: InetSocketAddress, old: Boolean = false): ByteArray =
        sha1(address.address.address + if (old) previousSecret else secret)
    private fun respond(address: InetSocketAddress, tid: ByteArray, msg: BValue.Dict) {
        val args = msg.dict("a") ?: return
        if (args.bytes("id")?.size != 20) return
        val response = mutableMapOf<String, Any>("id" to id)
        when (msg.text("q")) {
            "ping" -> Unit
            "find_node", "get_peers" -> {
                val target = args.bytes(if (msg.text("q") == "find_node") "target" else "info_hash")?.takeIf { it.size == 20 } ?: return
                val want = args.list("want").mapNotNull { (it as? BValue.Bytes)?.value?.toString(Charsets.US_ASCII) }
                if ("n4" in want || (want.isEmpty() && address.address !is Inet6Address)) response["nodes"] = v4.closest(target).flatMap { (it.id + compactAddress(it.address)).asList() }.toByteArray()
                if ("n6" in want || (want.isEmpty() && address.address is Inet6Address)) response["nodes6"] = v6.closest(target).flatMap { (it.id + compactAddress(it.address)).asList() }.toByteArray()
                if (msg.text("q") == "get_peers") {
                    response["token"] = token(address)
                    peers[target.hex()]?.keys?.filter { (it.address is Inet6Address) == (address.address is Inet6Address) }?.take(50)?.takeIf { it.isNotEmpty() }?.let {
                        response["values"] = it.map(::compactAddress)
                    }
                }
            }
            "announce_peer" -> {
                val hash = args.bytes("info_hash")?.takeIf { it.size == 20 } ?: return
                val t = args.bytes("token") ?: return
                if (!java.security.MessageDigest.isEqual(t, token(address)) && !java.security.MessageDigest.isEqual(t, token(address, true))) {
                    send(address, mapOf("t" to tid, "y" to "e", "e" to listOf(203, "Invalid token"))); return
                }
                val announcedPort = if (args.long("implied_port") == 1L) address.port.toLong() else args.long("port") ?: return
                if (announcedPort !in 1..65535 || peers.size >= 4096 && !peers.containsKey(hash.hex())) return
                val store = peers.computeIfAbsent(hash.hex()) { ConcurrentHashMap() }
                if (store.size < 100) store[InetSocketAddress(address.address, announcedPort.toInt())] = System.currentTimeMillis()
            }
            else -> { send(address, mapOf("t" to tid, "y" to "e", "e" to listOf(204, "Unknown method"))); return }
        }
        send(address, mapOf("t" to tid, "y" to "r", "r" to response))
    }

    fun findPeers(hash: ByteArray, announcePort: Int, allowed: () -> Boolean = { true }, onPeer: (InetSocketAddress) -> Unit): Future<*> =
        workers.submit { lookup(hash, true, announcePort, allowed, onPeer) }

    private fun lookup(target: ByteArray, getPeers: Boolean, announcePort: Int?, allowed: () -> Boolean = { true }, onPeer: (InetSocketAddress) -> Unit) {
        val candidates = ((v4.closest(target, 32) + v6.closest(target, 32))).associateBy { it.address }.toMutableMap()
        val visited = mutableSetOf<InetSocketAddress>()
        val tokens = mutableListOf<Pair<DhtNode, ByteArray>>()
        while (!closed && allowed() && visited.size < 256 && !Thread.currentThread().isInterrupted) {
            val batch = candidates.values.filter { it.address !in visited }.sortedBy { BigInteger(1, it.id).xor(BigInteger(1, target)) }.take(3)
            if (batch.isEmpty()) break
            val requests = batch.map { node ->
                visited += node.address
                node to query(node.address, if (getPeers) "get_peers" else "find_node",
                    mapOf((if (getPeers) "info_hash" else "target") to target, "want" to listOf("n4", "n6")))
            }
            requests.forEach { (node, future) -> runCatching {
                val response = try { future.get(timeoutMillis + 500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                response.bytes("token")?.let { tokens += node to it }
                response.list("values").forEach { value -> (value as? BValue.Bytes)?.value?.let { data ->
                    if (data.size == 6 || data.size == 18) compactPeers(data, data.size == 18).forEach(onPeer)
                } }
                listOf("nodes" to 26, "nodes6" to 38).forEach nodeFields@ { (field, size) ->
                    val bytes = response.bytes(field) ?: return@nodeFields
                    if (bytes.size % size == 0) bytes.asList().chunked(size).take(32).forEach nodeEntry@ { chunk ->
                        val raw = chunk.toByteArray()
                        val address = compactPeers(raw.copyOfRange(20, size), size == 38).firstOrNull() ?: return@nodeEntry
                        if (candidates.size < 512) candidates.putIfAbsent(address, DhtNode(raw.copyOf(20), address))
                    }
                }
            } }
        }
        if (getPeers && announcePort != null && !closed && allowed() && !Thread.currentThread().isInterrupted) tokens.sortedBy { BigInteger(1, it.first.id).xor(BigInteger(1, target)) }.take(8).forEach { (node, t) ->
            query(node.address, "announce_peer", mapOf("info_hash" to target, "port" to announcePort, "token" to t))
        }
    }
    private fun persist() {
        atomicWrite(stateFile, Bencode.encode(mapOf("id" to id, "nodes" to (v4.all() + v6.all()).filter { it.failures < 2 }.map {
            mapOf("host" to it.address.address.hostAddress, "port" to it.address.port)
        })))
    }
    override fun close() {
        closed = true
        socket.close()
        timer.shutdownNow(); workers.shutdownNow(); receiver.shutdownNow()
        pending.values.forEach { it.result.cancel(true) }; pending.clear()
        runCatching { persist() }
    }
}

internal fun atomicWrite(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    try {
        Files.write(temporary, bytes)
        try { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}
