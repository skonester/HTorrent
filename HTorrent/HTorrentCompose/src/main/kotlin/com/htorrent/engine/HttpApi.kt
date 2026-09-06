// Kotlin adaptation of rqbit's torrent HTTP handlers and prioritized range streaming.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HttpApi(private val session: Session, private val defaultOutput: () -> Path, port: Int = 0) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    private val workers = daemonPool("htorrent-http", 8)
    val port get() = server.address.port
    init {
        server.executor = workers
        server.createContext("/") { exchange ->
            try { handle(exchange) }
            catch (e: Exception) {
                if (exchange.responseCode == -1) runCatching { reply(exchange, if (e is IllegalArgumentException) 400 else 500, mapOf("error" to (e.message ?: "Request failed"))) }
            } finally { exchange.close() }
        }
        server.start()
    }
    private fun handle(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        val host = exchange.requestHeaders.getFirst("Host")
        if (host !in listOf("127.0.0.1:$port", "localhost:$port") || origin != null && origin !in listOf("http://127.0.0.1:$port", "http://localhost:$port")) {
            reply(exchange, 403, mapOf("error" to "Local requests only")); return
        }
        val path = exchange.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }
        val method = exchange.requestMethod
        if (path.isEmpty() && method == "GET") { reply(exchange, 200, mapOf("server" to "HTorrent", "torrents" to "/torrents", "dht" to "/dht/stats")); return }
        if (path == listOf("dht", "stats") && method == "GET") {
            reply(exchange, 200, mapOf("routing_table_size" to (session.dht?.nodeCount ?: 0), "port" to session.dht?.port)); return
        }
        if (path == listOf("stats") && method == "GET") {
            val all = session.snapshots()
            reply(exchange, 200, mapOf("downloaded_bytes" to all.sumOf { it.downloaded }, "uploaded_bytes" to all.sumOf { it.uploaded }, "live_peers" to all.sumOf { it.peers })); return
        }
        if (path == listOf("torrents")) {
            when (method) {
                "GET" -> reply(exchange, 200, mapOf("torrents" to session.all().map(::details)))
                "POST" -> {
                    val body = body(exchange)
                    val text = body.toString(Charsets.UTF_8).trim()
                    val params = exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    val output = params["output_folder"]?.let(Path::of) ?: defaultOutput()
                    val start = params["paused"] != "true"
                    val torrent = if (text.startsWith("magnet:", true)) session.addMagnet(text, output, start) else session.addTorrent(body, output, start)
                    reply(exchange, 200, details(torrent))
                }
                else -> reply(exchange, 405, mapOf("error" to "Method not allowed"))
            }
            return
        }
        if (path.firstOrNull() == "torrents" && path.size >= 2) {
            val torrent = session.get(path[1]) ?: run { reply(exchange, 404, mapOf("error" to "Torrent not found")); return }
            val action = path.getOrNull(2)
            if (method == "GET" || method == "HEAD") {
                when (action) {
                    null, "stats" -> reply(exchange, 200, details(torrent))
                    "metadata" -> { val meta = torrent.metadata ?: error("Metadata not available"); sendBytes(exchange, "application/x-bittorrent", meta.torrentBytes()) }
                    "haves" -> sendBytes(exchange, "application/octet-stream", torrent.pieces?.bitfield() ?: byteArrayOf())
                    "stream" -> stream(exchange, torrent, path.getOrNull(3)?.toIntOrNull() ?: error("Missing file index"))
                    else -> reply(exchange, 404, mapOf("error" to "Unknown endpoint"))
                }
                return
            }
            if (method == "POST") {
                when (action) {
                    "start" -> torrent.start()
                    "pause" -> torrent.pause()
                    "forget" -> session.remove(torrent.id)
                    "update_only_files" -> {
                        val text = body(exchange).toString(Charsets.UTF_8)
                        val match = Regex("\\s*\\{\\s*\"only_files\"\\s*:\\s*\\[([0-9,\\s]*)]\\s*}\\s*").matchEntire(text) ?: error("Expected {\"only_files\":[0,1]}")
                        torrent.selectFiles(match.groupValues[1].split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toSet())
                    }
                    "add_peers" -> body(exchange).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
                        val uri = URI("tcp://${it.trim()}"); require(uri.port in 1..65535); torrent.addPeer(InetSocketAddress(uri.host, uri.port))
                    }
                    else -> { reply(exchange, 404, mapOf("error" to "Unknown endpoint")); return }
                }
                session.save(); reply(exchange, 200, mapOf("ok" to true)); return
            }
        }
        reply(exchange, 404, mapOf("error" to "Unknown endpoint"))
    }
    private fun details(t: ManagedTorrent): Map<String, Any?> {
        val s = t.snapshot()
        return mapOf("id" to s.id, "info_hash" to s.id, "name" to s.name, "state" to s.status.name.lowercase(),
            "progress" to s.progress, "downloaded_bytes" to s.downloaded, "uploaded_bytes" to s.uploaded, "live_peers" to s.peers,
            "error" to s.error, "files" to t.metadata?.files?.mapIndexed { i, file ->
                mapOf("id" to i, "name" to file.path.joinToString("/"), "length" to file.length,
                    "included" to (t.onlyFiles == null || i in t.onlyFiles!!), "stream_url" to "http://127.0.0.1:$port/torrents/${t.id}/stream/$i")
            }.orEmpty())
    }
    private fun stream(exchange: HttpExchange, torrent: ManagedTorrent, index: Int) {
        val meta = torrent.metadata ?: error("Metadata not available")
        require(index in meta.files.indices)
        val file = meta.files[index]
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val range = try { parseRange(rangeHeader, file.length) } catch (_: IllegalArgumentException) {
            exchange.responseHeaders.set("Content-Range", "bytes */${file.length}")
            exchange.sendResponseHeaders(416, -1); return
        }
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("Content-Type", Files.probeContentType(Path.of(file.path.last())) ?: "application/octet-stream")
        exchange.responseHeaders.set("Content-Length", range.length.toString())
        if (rangeHeader != null) exchange.responseHeaders.set("Content-Range", "bytes ${range.start}-${range.end}/${file.length}")
        val status = if (rangeHeader == null) 200 else 206
        if (exchange.requestMethod == "HEAD" || range.length == 0L) { exchange.sendResponseHeaders(status, -1); return }
        val streamId = java.util.UUID.randomUUID().toString()
        var position = range.start
        fun waitForPiece(piece: Int) {
            val tracker = torrent.pieces ?: error("Torrent is initializing")
            val lastPiece = minOf(meta.pieceCount - 1, ((file.offset + position + 32 * 1024 * 1024L) / meta.pieceLength).toInt(),
                ((file.offset + file.length - 1) / meta.pieceLength).toInt())
            tracker.updateStream(streamId, piece..lastPiece)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!tracker.has(piece)) {
                check(torrent.running) { "Torrent is paused" }
                check(System.nanoTime() < deadline) { "Timed out waiting for stream data" }
                Thread.sleep(25)
            }
        }
        try {
        waitForPiece(((file.offset + position) / meta.pieceLength).toInt())
        exchange.sendResponseHeaders(status, range.length)
        exchange.responseBody.use { output ->
            while (position <= range.end) {
                val absolute = file.offset + position
                val piece = (absolute / meta.pieceLength).toInt()
                waitForPiece(piece)
                val length = minOf(65536L, range.end - position + 1, (piece + 1L) * meta.pieceLength - absolute).toInt()
                output.write(torrent.storage!!.read(absolute, length)); position += length
            }
        }
        } finally { torrent.pieces?.removeStream(streamId) }
    }
    private fun body(exchange: HttpExchange): ByteArray = exchange.requestBody.use {
        val bytes = it.readNBytes(32 * 1024 * 1024 + 1); require(bytes.size <= 32 * 1024 * 1024); bytes
    }
    private fun sendBytes(exchange: HttpExchange, type: String, bytes: ByteArray) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Content-Length", bytes.size.toString())
        exchange.sendResponseHeaders(200, if (bytes.isEmpty() || exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
        if (exchange.requestMethod != "HEAD" && bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }
    private fun reply(exchange: HttpExchange, status: Int, value: Any?) {
        val bytes = json(value).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
        if (exchange.requestMethod != "HEAD") exchange.responseBody.write(bytes)
    }
    override fun close() { server.stop(0); workers.shutdownNow() }
    data class ByteRange(val start: Long, val end: Long) { val length get() = maxOf(0, end - start + 1) }
    companion object {
        internal fun parseRange(header: String?, size: Long): ByteRange {
            if (header == null) return ByteRange(0, size - 1)
            require(size > 0)
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: throw IllegalArgumentException("Invalid byte range")
            val (startText, endText) = match.destructured
            if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: throw IllegalArgumentException("Invalid suffix")
                require(suffix > 0); return ByteRange(maxOf(0, size - suffix), size - 1)
            }
            val start = startText.toLongOrNull() ?: throw IllegalArgumentException("Invalid range start")
            val end = if (endText.isEmpty()) size - 1 else endText.toLongOrNull() ?: throw IllegalArgumentException("Invalid range end")
            require(start < size && end >= start)
            return ByteRange(start, minOf(size - 1, end))
        }
        internal fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"" + buildString { value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } } } + "\""
            is Boolean, is Number -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
            is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
            else -> json(value.toString())
        }
    }
}
