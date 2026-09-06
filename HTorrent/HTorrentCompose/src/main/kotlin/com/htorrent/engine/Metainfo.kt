// Kotlin adaptation of rqbit/crates/librqbit_core/{magnet,torrent_metainfo,lengths}.rs.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path

data class Magnet(val hash: ByteArray, val name: String?, val trackers: List<String>, val onlyFiles: Set<Int>?) {
    companion object {
        fun parse(link: String): Magnet {
            val uri = URI(link.trim())
            require(uri.scheme.equals("magnet", true)) { "Expected a magnet link" }
            val fields = (uri.rawQuery ?: uri.rawSchemeSpecificPart.substringAfter('?', "")).split('&').map {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            val hashText = fields.firstOrNull { it.first == "xt" && it.second.startsWith("urn:btih:", true) }
                ?.second?.substring(9) ?: error("This engine requires a BitTorrent v1 or hybrid magnet (btih)")
            val hash = when (hashText.length) {
                40 -> unhex(hashText)
                32 -> {
                    var accumulator = 0
                    var bits = 0
                    val bytes = mutableListOf<Byte>()
                    hashText.uppercase().forEach {
                        val value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(it)
                        require(value >= 0) { "Invalid base32 info hash" }
                        accumulator = (accumulator shl 5) or value
                        bits += 5
                        if (bits >= 8) { bits -= 8; bytes += (accumulator ushr bits).toByte() }
                    }
                    bytes.toByteArray()
                }
                else -> error("Invalid info hash length")
            }
            val selection = fields.firstOrNull { it.first == "so" }?.second?.split(',')?.flatMap { range ->
                val ends = range.split('-', limit = 2).map { it.toInt() }
                val last = ends.last()
                require(ends[0] >= 0 && last >= ends[0] && last <= 100_000) { "Invalid file selection" }
                (ends[0]..last).toList()
            }?.toSet()
            return Magnet(hash, fields.firstOrNull { it.first == "dn" }?.second,
                fields.filter { it.first == "tr" }.map { it.second }.distinct(), selection)
        }
    }
}

data class TorrentFile(val path: List<String>, val length: Long, val offset: Long, val padding: Boolean)
data class Metainfo(
    val hash: ByteArray, val infoBytes: ByteArray, val name: String, val pieceLength: Int,
    val hashes: List<ByteArray>, val files: List<TorrentFile>, val trackerTiers: List<List<String>>,
    val private: Boolean, val nodes: List<Pair<String, Int>> = emptyList()
) {
    val totalLength = files.sumOf { it.length }
    val pieceCount get() = hashes.size
    fun pieceSize(index: Int): Int {
        require(index in hashes.indices)
        return minOf(pieceLength.toLong(), totalLength - index.toLong() * pieceLength).toInt()
    }
    fun torrentBytes(): ByteArray {
        // Preserve the exact info dictionary bytes: re-encoding would change noncanonical info hashes.
        val prefix = Bencode.encode(mapOf("announce-list" to trackerTiers))
        return prefix.copyOf(prefix.size - 1) + "4:info".toByteArray() + infoBytes + "e".toByteArray()
    }
    companion object {
        fun parse(bytes: ByteArray): Metainfo {
            require(bytes.size <= 32 * 1024 * 1024) { "Torrent metadata is too large" }
            val reader = Bencode.Reader(bytes)
            val root = reader.read() as? BValue.Dict ?: error("Expected torrent dictionary")
            require(reader.position == bytes.size)
            val tiers = root.list("announce-list").mapNotNull { (it as? BValue.ListValue)?.value?.mapNotNull { v ->
                (v as? BValue.Bytes)?.value?.toString(Charsets.UTF_8)
            }?.takeIf { it.isNotEmpty() } }.ifEmpty { root.text("announce")?.let { listOf(listOf(it)) }.orEmpty() }
            val nodes = root.list("nodes").mapNotNull { entry ->
                val list = (entry as? BValue.ListValue)?.value ?: return@mapNotNull null
                val host = (list.getOrNull(0) as? BValue.Bytes)?.value?.toString(Charsets.UTF_8) ?: return@mapNotNull null
                val port = (list.getOrNull(1) as? BValue.Number)?.value ?: return@mapNotNull null
                if (port in 1..65535) host to port.toInt() else null
            }
            return fromInfo(reader.infoBytes ?: error("Missing info dictionary"), tiers, nodes)
        }
        fun fromInfo(bytes: ByteArray, tiers: List<List<String>> = emptyList(), nodes: List<Pair<String, Int>> = emptyList()): Metainfo {
            val info = Bencode.decode(bytes) as? BValue.Dict ?: error("Invalid info dictionary")
            val name = safeComponent(info.text("name.utf-8") ?: info.text("name") ?: error("Missing torrent name"))
            val pieceLength = info.long("piece length") ?: error("Missing piece length")
            require(pieceLength in 1..(32 * 1024 * 1024)) { "Invalid piece length" }
            val pieceHashes = info.bytes("pieces") ?: error("This engine requires v1 or hybrid torrent metadata")
            require(pieceHashes.size % 20 == 0)
            var offset = 0L
            val files = if (info["files"] != null) {
                info.list("files").map { value ->
                    val file = value as? BValue.Dict ?: error("Invalid file entry")
                    val length = file.long("length") ?: error("Missing file length")
                    require(length >= 0)
                    val components = file.list(if (file["path.utf-8"] != null) "path.utf-8" else "path").map {
                        safeComponent((it as? BValue.Bytes)?.value?.toString(Charsets.UTF_8) ?: error("Invalid path"))
                    }
                    require(components.isNotEmpty())
                    TorrentFile(listOf(name) + components, length, offset, file.text("attr")?.contains('p') == true)
                        .also { offset = Math.addExact(offset, length) }
                }
            } else {
                val length = info.long("length") ?: error("Missing torrent length")
                require(length >= 0)
                offset = length
                listOf(TorrentFile(listOf(name), length, 0, false))
            }
            require(files.isNotEmpty() && files.map { it.path.joinToString("/").lowercase() }.distinct().size == files.size) { "Duplicate or missing files" }
            require(pieceHashes.size / 20L == (offset / pieceLength + if (offset % pieceLength == 0L) 0 else 1)) { "Piece hashes do not match torrent size" }
            return Metainfo(sha1(bytes), bytes, name, pieceLength.toInt(),
                pieceHashes.asList().chunked(20).map { it.toByteArray() }, files, tiers, info.long("private") == 1L, nodes)
        }
        internal fun safeComponent(value: String): String {
            require(value.isNotBlank() && value !in listOf(".", "..") && value.none { it < ' ' || it in "/\\:*?\"<>|" } &&
                !value.endsWith('.') && !value.endsWith(' ') &&
                !value.substringBefore('.').uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) { "Unsafe torrent path component" }
            return value
        }
    }
}
