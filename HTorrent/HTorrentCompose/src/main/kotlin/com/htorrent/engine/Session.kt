// Kotlin adaptation of rqbit's Session and initializing/live/paused torrent states.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

enum class TorrentStatus { INITIALIZING, METADATA, DOWNLOADING, SEEDING, FINISHED, PAUSED, STOPPED, ERROR }
data class TorrentSnapshot(val id: String, val name: String, val progress: Double, val downloaded: Long,
                           val uploaded: Long, val peers: Int, val status: TorrentStatus, val error: String?, val output: String)

class Session(val stateDirectory: Path, listenPort: Int = 0, private val enableDiscovery: Boolean = true) : AutoCloseable {
    val peerId = "-HT1000-".toByteArray() + randomBytes(12)
    private val listener = ServerSocket(listenPort)
    val port = listener.localPort
    val dht = if (enableDiscovery) Dht(stateDirectory) else null
    internal val transfers = daemonPool("htorrent-peer", 64)
    internal val discovery = daemonPool("htorrent-discovery", 8)
    internal val scheduler = daemonScheduler("htorrent-session", 2)
    private val acceptor = daemonPool("htorrent-listen", 1)
    private val torrents = ConcurrentHashMap<String, ManagedTorrent>()
    private val handshakes = ConcurrentHashMap.newKeySet<Socket>()
    val downloadLimiter = RateLimiter()
    val uploadLimiter = RateLimiter()
    @Volatile private var closed = false
    private val localDiscovery = if (enableDiscovery) runCatching { LocalDiscovery(this) }.getOrNull() else null
    private val portMapping = if (enableDiscovery) PortMapping(port, dht?.port) else null

    init {
        Files.createDirectories(stateDirectory)
        acceptor.execute {
            while (!closed) try {
                val socket = listener.accept()
                if (handshakes.size >= 16) { socket.close(); continue }
                handshakes += socket
                discovery.execute handshakeTask@ {
                    try {
                        socket.soTimeout = 10_000
                        val handshake = PeerConnection.readHandshake(socket.getInputStream())
                        val torrent = torrents[handshake.hash.hex()] ?: error("Unknown torrent")
                        if (!torrent.running || torrent.connections.size >= 40) { socket.close(); return@handshakeTask }
                        torrent.accept(socket, handshake)
                    } catch (_: Exception) { runCatching { socket.close() } }
                    finally { handshakes -= socket }
                }
            } catch (_: Exception) { }
        }
        scheduler.scheduleWithFixedDelay({ torrents.values.forEach { torrent ->
            if (torrent.running) runCatching { torrent.tick() }.onFailure { torrent.discoveryError = it.message }
        } }, 1, 1, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ runCatching { save() } }, 10, 10, TimeUnit.SECONDS)
    }

    fun addTorrent(bytes: ByteArray, output: Path, start: Boolean = true): ManagedTorrent {
        val meta = Metainfo.parse(bytes)
        return add(meta.hash, meta.name, meta.trackerTiers, output, null, meta, null, start)
    }
    fun addMagnet(link: String, output: Path, start: Boolean = true): ManagedTorrent {
        val magnet = Magnet.parse(link)
        return add(magnet.hash, magnet.name ?: magnet.hash.hex(), magnet.trackers.map { listOf(it) }, output, link, null, magnet.onlyFiles, start)
    }
    private fun add(hash: ByteArray, name: String, tiers: List<List<String>>, output: Path, magnet: String?, meta: Metainfo?, files: Set<Int>?, start: Boolean): ManagedTorrent {
        check(!closed) { "Session closed" }
        val torrent = ManagedTorrent(this, hash, name, tiers, output.toAbsolutePath().normalize(), magnet, meta, files)
        torrents.putIfAbsent(hash.hex(), torrent)?.let { return it }
        if (meta != null) atomicWrite(stateDirectory.resolve("${hash.hex()}.torrent"), meta.torrentBytes())
        if (start) torrent.start()
        save()
        return torrent
    }
    fun snapshots(): List<TorrentSnapshot> = torrents.values.sortedBy { it.created }.map { it.snapshot() }
    fun all(): List<ManagedTorrent> = torrents.values.toList()
    fun get(id: String) = torrents[id]
    fun remove(id: String) { torrents.remove(id)?.pause(); save() }
    @Synchronized fun save() {
        if (closed) return
        torrents.values.forEach { it.checkpoint() }
        atomicWrite(stateDirectory.resolve("session.bencode"), Bencode.encode(torrents.values.map {
            mapOf("hash" to it.hash, "name" to it.name, "output" to it.output.toString(), "magnet" to (it.magnet ?: ""),
                "running" to if (it.running) 1 else 0, "status" to it.status.name,
                "files" to (it.onlyFiles?.sorted() ?: listOf(-1)))
        }))
    }
    fun restore() {
        val file = stateDirectory.resolve("session.bencode")
        if (!Files.exists(file)) return
        val entries = (Bencode.decode(Files.readAllBytes(file)) as BValue.ListValue).value
        entries.forEach { entry ->
            val row = entry as BValue.Dict
            val hash = row.bytes("hash") ?: error("Session entry has no hash")
            require(hash.size == 20)
            val metadataFile = stateDirectory.resolve("${hash.hex()}.torrent")
            val meta = if (Files.exists(metadataFile)) Metainfo.parse(Files.readAllBytes(metadataFile)) else null
            require(meta == null || meta.hash.contentEquals(hash))
            val magnet = row.text("magnet")?.takeIf { it.isNotEmpty() }
            val parsed = magnet?.let(Magnet::parse)
            val files = row.list("files").map { (it as BValue.Number).value.toInt() }.takeUnless { -1 in it }?.toSet()
            // Add paused first: preserve every saved row before the first persistence write.
            val torrent = ManagedTorrent(this, hash, row.text("name") ?: hash.hex(), meta?.trackerTiers ?: parsed?.trackers.orEmpty().map { listOf(it) },
                Path.of(row.text("output") ?: error("Missing output folder")), magnet, meta, files)
            torrents.putIfAbsent(hash.hex(), torrent)
            if (row.long("running") == 1L) torrent.start()
        }
    }
    override fun close() {
        if (closed) return
        save()
        closed = true
        listener.close(); handshakes.forEach { runCatching { it.close() } }
        torrents.values.forEach { it.pause(persist = false) }
        localDiscovery?.close(); portMapping?.close(); dht?.close()
        scheduler.shutdownNow(); transfers.shutdownNow(); discovery.shutdownNow(); acceptor.shutdownNow()
    }
}

class ManagedTorrent internal constructor(
    internal val session: Session, val hash: ByteArray, @Volatile var name: String,
    internal var trackerTiers: List<List<String>>, val output: Path, val magnet: String?,
    initialMetadata: Metainfo?, @Volatile var onlyFiles: Set<Int>?
) {
    val id = hash.hex()
    val created = System.nanoTime()
    @Volatile var metadata: Metainfo? = initialMetadata; private set
    @Volatile internal var pieces: PieceTracker? = null
    @Volatile internal var storage: TorrentStorage? = null
    @Volatile var status = TorrentStatus.PAUSED; private set
    @Volatile var error: String? = null; private set
    @Volatile var discoveryError: String? = null
    @Volatile var running = false; private set
    internal val downloaded = AtomicLong()
    internal val uploaded = AtomicLong()
    internal val connections = ConcurrentHashMap<String, PeerConnection>()
    private val knownPeers = ConcurrentHashMap<InetSocketAddress, String>()
    private val attempts = ConcurrentHashMap<InetSocketAddress, Long>()
    private val connecting = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val generation = AtomicLong()
    private var dhtRequest: Future<*>? = null
    private var lastDht = 0L
    private var lastLsd = 0L
    private val trackerBusy = ConcurrentHashMap.newKeySet<Int>()
    private val trackerNext = ConcurrentHashMap<Int, Long>()
    private val trackerStarted = ConcurrentHashMap.newKeySet<String>()
    private val trackerCompleted = ConcurrentHashMap.newKeySet<String>()
    private val initialized = Any()

    @Synchronized fun start() {
        if (running) return
        running = true; error = null
        val epoch = generation.incrementAndGet()
        lastDht = 0; trackerNext.clear(); trackerStarted.clear(); trackerCompleted.clear()
        status = if (metadata == null) TorrentStatus.METADATA else TorrentStatus.INITIALIZING
        session.discovery.execute {
            try { if (pieces != null) refreshStatus() else metadata?.let { initialize(it, epoch) } }
            catch (e: Exception) { fail(e, epoch) }
        }
    }
    @Synchronized fun pause(stopped: Boolean = false, persist: Boolean = true) {
        running = false; generation.incrementAndGet()
        dhtRequest?.cancel(true)
        connections.values.forEach { it.close() }
        connections.clear()
        pieces?.let { tracker -> connecting.forEach { tracker.release(it.toString()) } }
        status = if (stopped) TorrentStatus.STOPPED else TorrentStatus.PAUSED
        trackerStarted.toList().forEach { url -> session.discovery.execute { runCatching { Trackers.announce(url, announce("stopped")) } } }
        if (persist) checkpoint()
    }
    internal fun fail(e: Exception, epoch: Long = generation.get()) {
        synchronized(this) {
            if (generation.get() != epoch || !running) return
            running = false; generation.incrementAndGet(); error = e.message ?: e.javaClass.simpleName; status = TorrentStatus.ERROR
            connections.values.forEach { it.close() }
        }
    }
    internal fun isCurrent(epoch: Long) = running && generation.get() == epoch
    internal fun epoch() = generation.get()
    internal fun receiveMetadata(bytes: ByteArray, epoch: Long) {
        require(sha1(bytes).contentEquals(hash)) { "Magnet metadata hash mismatch" }
        initialize(Metainfo.fromInfo(bytes, trackerTiers), epoch)
    }
    private fun initialize(meta: Metainfo, epoch: Long) = synchronized(initialized) {
        if (!isCurrent(epoch) || pieces != null) return@synchronized
        metadata = meta; name = meta.name; trackerTiers = meta.trackerTiers
        if (meta.private) {
            dhtRequest?.cancel(true)
            knownPeers.entries.removeIf { it.value != "tracker" && it.value != "manual" }
            connections.values.filter { it.source != "tracker" && it.source != "incoming" && it.source != "manual" }.forEach { it.close() }
        } else meta.nodes.forEach { (host, p) -> session.dht?.ping(InetSocketAddress(host, p)) }
        status = TorrentStatus.INITIALIZING
        val disk = TorrentStorage(output, meta)
        val tracker = PieceTracker(meta, onlyFiles)
        disk.createEmptyFiles()
        // Trust the saved bitmap only when every backing file still matches its fingerprint.
        val resumed = Resume.load(session.stateDirectory.resolve("$id.resume"), disk, tracker)
        for (piece in if (resumed) IntRange.EMPTY else meta.hashes.indices) {
            if (!isCurrent(epoch)) return@synchronized
            val bytes = runCatching { disk.read(piece.toLong() * meta.pieceLength, meta.pieceSize(piece)) }.getOrNull()
            if (bytes != null && sha1(bytes).contentEquals(meta.hashes[piece])) tracker.verified(piece)
        }
        if (!isCurrent(epoch)) return@synchronized
        atomicWrite(session.stateDirectory.resolve("$id.torrent"), meta.torrentBytes())
        storage = disk; pieces = tracker
        refreshStatus()
    }
    internal fun refreshStatus() {
        if (!running) return
        val tracker = pieces ?: return
        val previousStatus = status
        status = when {
            tracker.completedBytes() == metadata!!.totalLength -> TorrentStatus.SEEDING
            tracker.finished() -> TorrentStatus.FINISHED
            else -> TorrentStatus.DOWNLOADING
        }
        if (status == TorrentStatus.SEEDING && previousStatus != TorrentStatus.SEEDING) trackerNext.clear()
    }
    fun selectFiles(files: Set<Int>?) {
        metadata?.let { require(files == null || files.all { index -> index in it.files.indices }) }
        onlyFiles = files; pieces?.select(files); refreshStatus(); session.save()
    }
    fun addPeer(address: InetSocketAddress) = offerPeer(address, "manual")
    internal fun offerPeer(address: InetSocketAddress, source: String) {
        if (address.isUnresolved || address.port == 0 || address.address.isAnyLocalAddress || address.address.isMulticastAddress) return
        if (metadata?.private == true && source !in listOf("tracker", "manual")) return
        if (knownPeers.size < 2000) knownPeers.merge(address, source) { old, new -> if (new == "tracker") new else old }
    }
    internal fun tick() {
        if (!running || status == TorrentStatus.INITIALIZING) return
        refreshStatus()
        val now = System.currentTimeMillis()
        val epoch = generation.get()
        if (metadata?.private != true && now - lastDht >= 60_000 && dhtRequest?.isDone != false) {
            lastDht = now
            dhtRequest = session.dht?.findPeers(hash, session.port, { isCurrent(epoch) && metadata?.private != true }) { if (isCurrent(epoch)) offerPeer(it, "dht") }
        }
        if (session.dht != null && metadata?.private == false && now - lastLsd >= 300_000) { lastLsd = now; LocalDiscovery.announce(hash, session.port) }
        trackerTiers.forEachIndexed { index, tier ->
            if (now >= (trackerNext[index] ?: 0) && trackerBusy.add(index)) session.discovery.execute {
                try {
                    var success = false
                    for (url in tier) {
                        if (!isCurrent(epoch)) break
                        val event = when { url !in trackerStarted -> "started"; pieces?.completedBytes() == metadata?.totalLength && url !in trackerCompleted -> "completed"; else -> "" }
                        try {
                            val result = Trackers.announce(url, announce(event))
                            if (!isCurrent(epoch)) break
                            trackerStarted += url
                            if (event == "completed" || event == "started" && pieces?.completedBytes() == metadata?.totalLength) trackerCompleted += url
                            result.peers.forEach { offerPeer(it, "tracker") }
                            trackerNext[index] = System.currentTimeMillis() + result.intervalSeconds.coerceAtMost(86400) * 1000
                            success = true; break
                        } catch (e: Exception) { discoveryError = e.message }
                    }
                    if (!success) trackerNext[index] = System.currentTimeMillis() + 60_000
                } finally { trackerBusy -= index }
            }
        }
        val limit = if (metadata == null) 4 else 32
        knownPeers.entries.sortedBy { attempts[it.key] ?: 0L }.forEach { (address, source) ->
            if (connecting.size + connections.size >= limit) return@forEach
            if (connections.values.any { it.address == address } || now - (attempts[address] ?: 0L) < 60_000 || !connecting.add(address)) return@forEach
            attempts[address] = now
            session.transfers.execute {
                try {
                    if (isCurrent(epoch)) PeerConnection(this, address, source, epoch).run()
                } finally { connecting -= address }
            }
        }
    }
    internal fun accept(socket: Socket, handshake: PeerConnection.Handshake) {
        val epoch = generation.get()
        session.transfers.execute { PeerConnection(this, socket.remoteSocketAddress as InetSocketAddress, "incoming", epoch, socket, handshake).run() }
    }
    private fun announce(event: String) = Announce(hash, session.peerId, session.port, downloaded.get(), uploaded.get(),
        (metadata?.totalLength ?: 1L) - (pieces?.completedBytes() ?: 0), event)
    internal fun checkpoint() {
        val disk = storage ?: return
        val tracker = pieces ?: return
        Resume.save(session.stateDirectory.resolve("$id.resume"), disk, tracker)
    }
    fun snapshot(): TorrentSnapshot {
        val tracker = pieces
        val total = tracker?.selectedBytes() ?: 0
        return TorrentSnapshot(id, name, if (total > 0) 100.0 * tracker!!.selectedCompletedBytes() / total else if (tracker != null) 100.0 else 0.0,
            downloaded.get(), uploaded.get(), connections.size, status, error, output.toString())
    }
}

class RateLimiter {
    @Volatile var bytesPerSecond: Long = 0
    private var next = System.nanoTime()
    fun consume(bytes: Int, active: () -> Boolean) {
        val rate = bytesPerSecond
        if (rate <= 0) return
        val deadline = synchronized(this) {
            val now = System.nanoTime()
            next = maxOf(now, next) + bytes.toLong() * 1_000_000_000L / rate
            next
        }
        while (active()) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            TimeUnit.NANOSECONDS.sleep(minOf(left, 100_000_000L))
        }
    }
}
