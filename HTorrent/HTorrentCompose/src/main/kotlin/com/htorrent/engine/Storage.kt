// Kotlin adaptation of rqbit's filesystem storage, chunk_tracker and piece_tracker.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.BitSet

class TorrentStorage(val root: Path, val metadata: Metainfo) {
    init { Files.createDirectories(root) }
    fun filePath(index: Int): Path {
        val base = root.toAbsolutePath().normalize().toRealPath()
        var path = base
        metadata.files[index].path.forEach { component ->
            path = path.resolve(component)
            require(!Files.isSymbolicLink(path)) { "Torrent path contains a symbolic link" }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) require(path.toRealPath().startsWith(base)) { "Torrent path escapes output folder" }
        }
        require(path.normalize().startsWith(base))
        return path
    }
    @Synchronized fun read(offset: Long, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && size.toLong() <= metadata.totalLength - offset)
        val bytes = ByteArray(size)
        segments(offset, size) { index, fileOffset, arrayOffset, length ->
            if (!metadata.files[index].padding) RandomAccessFile(filePath(index).toFile(), "r").use {
                it.seek(fileOffset); it.readFully(bytes, arrayOffset, length)
            }
        }
        return bytes
    }
    @Synchronized fun writePiece(index: Int, bytes: ByteArray) {
        require(bytes.size == metadata.pieceSize(index) && sha1(bytes).contentEquals(metadata.hashes[index])) { "Piece hash mismatch" }
        segments(index.toLong() * metadata.pieceLength, bytes.size) { file, fileOffset, arrayOffset, length ->
            if (!metadata.files[file].padding) {
                val path = filePath(file)
                Files.createDirectories(path.parent)
                RandomAccessFile(filePath(file).toFile(), "rw").use { it.seek(fileOffset); it.write(bytes, arrayOffset, length) }
            }
        }
    }
    fun createEmptyFiles() { metadata.files.indices.filter { metadata.files[it].length == 0L && !metadata.files[it].padding }.forEach {
        val path = filePath(it); Files.createDirectories(path.parent); if (!Files.exists(path)) Files.createFile(path)
    } }
    private fun segments(offset: Long, size: Int, block: (Int, Long, Int, Int) -> Unit) {
        metadata.files.forEachIndexed { index, file ->
            val start = maxOf(offset, file.offset)
            val end = minOf(offset + size, file.offset + file.length)
            if (end > start) block(index, start - file.offset, (start - offset).toInt(), (end - start).toInt())
        }
    }
}

class PieceTracker(val metadata: Metainfo, selection: Set<Int>? = null) {
    private val have = BitSet(metadata.pieceCount)
    private val needed = BitSet(metadata.pieceCount)
    private data class Owned(val peer: String, val started: Long)
    private val inflight = mutableMapOf<Int, Owned>()
    private val priorities = linkedSetOf<Int>()
    private val streams = linkedMapOf<String, List<Int>>()
    init { select(selection) }
    @Synchronized fun select(files: Set<Int>?) {
        require(files == null || files.all { it in metadata.files.indices }) { "File selection is out of range" }
        needed.clear()
        metadata.files.forEachIndexed { index, file ->
            if ((files == null || index in files) && !file.padding && file.length > 0) {
                val start = (file.offset / metadata.pieceLength).toInt()
                val end = ((file.offset + file.length - 1) / metadata.pieceLength).toInt()
                needed.set(start, end + 1)
            }
        }
    }
    @Synchronized fun acquire(peer: String, available: BitSet, averageMillis: Long? = null): Int? {
        fun steal(multiplier: Int): Int? {
            if (averageMillis == null) return null
            val candidate = inflight.entries.filter { it.value.peer != peer && available[it.key] &&
                System.currentTimeMillis() - it.value.started > maxOf(1000, averageMillis) * multiplier }.minByOrNull { it.value.started } ?: return null
            inflight[candidate.key] = Owned(peer, System.currentTimeMillis())
            return candidate.key
        }
        steal(10)?.let { return it }
        val priorityOrder = streams.values.flatten().distinct() + priorities
        val queued = (needed.clone() as BitSet).apply { priorityOrder.forEach { set(it) }; and(available); andNot(have); inflight.keys.forEach { clear(it) } }
        val piece = priorityOrder.firstOrNull { queued[it] } ?: queued.nextSetBit(0).takeIf { it >= 0 }
        if (piece != null) { inflight[piece] = Owned(peer, System.currentTimeMillis()); return piece }
        return steal(3)
    }
    @Synchronized fun owns(peer: String, index: Int) = inflight[index]?.peer == peer
    @Synchronized fun release(peer: String) { inflight.entries.removeIf { it.value.peer == peer } }
    @Synchronized fun verified(index: Int) { have.set(index); inflight.remove(index); priorities.remove(index) }
    @Synchronized fun commit(peer: String, index: Int, bytes: ByteArray, storage: TorrentStorage): Boolean {
        if (!owns(peer, index)) return false
        if (!sha1(bytes).contentEquals(metadata.hashes[index])) { inflight.remove(index); return false }
        storage.writePiece(index, bytes)
        verified(index)
        return true
    }
    @Synchronized fun updateStream(id: String, range: IntRange) { streams[id] = range.filter { it in metadata.hashes.indices } }
    @Synchronized fun removeStream(id: String) { streams.remove(id) }
    @Synchronized fun prioritize(index: Int) { require(index in metadata.hashes.indices); needed.set(index); priorities += index }
    @Synchronized fun has(index: Int) = index in metadata.hashes.indices && have[index]
    @Synchronized fun bitfield(): ByteArray = ByteArray((metadata.pieceCount + 7) / 8).also { bytes ->
        var i = have.nextSetBit(0)
        while (i >= 0) { bytes[i / 8] = (bytes[i / 8].toInt() or (128 ushr (i % 8))).toByte(); i = have.nextSetBit(i + 1) }
    }
    @Synchronized fun completedBytes(): Long = metadata.hashes.indices.filter { have[it] }.sumOf { metadata.pieceSize(it).toLong() }
    @Synchronized fun selectedBytes(): Long = metadata.hashes.indices.filter { needed[it] }.sumOf { metadata.pieceSize(it).toLong() }
    @Synchronized fun selectedCompletedBytes(): Long = metadata.hashes.indices.filter { needed[it] && have[it] }.sumOf { metadata.pieceSize(it).toLong() }
    @Synchronized fun finished(): Boolean = (needed.clone() as BitSet).apply { andNot(have) }.isEmpty
}
