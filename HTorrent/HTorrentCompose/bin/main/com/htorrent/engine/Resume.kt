// Kotlin adaptation of rqbit's persisted have bitmap and filesystem fast resume.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0.
package com.htorrent.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object Resume {
    private fun fingerprint(storage: TorrentStorage): List<Map<String, Any>> = storage.metadata.files.mapIndexed { index, file ->
        val stat = if (file.padding) null else runCatching { Files.readAttributes(storage.filePath(index), BasicFileAttributes::class.java) }.getOrNull()
        mapOf("size" to (stat?.size() ?: -1L), "modified" to (stat?.lastModifiedTime()?.toString() ?: ""),
            "created" to (stat?.creationTime()?.toString() ?: ""), "key" to (stat?.fileKey()?.toString() ?: ""))
    }
    fun save(path: Path, storage: TorrentStorage, tracker: PieceTracker) = synchronized(tracker) {
        synchronized(storage) {
            atomicWrite(path, Bencode.encode(mapOf("hash" to storage.metadata.hash, "root" to storage.root.toAbsolutePath().normalize().toString(),
                "files" to fingerprint(storage), "have" to tracker.bitfield())))
        }
    }
    fun load(path: Path, storage: TorrentStorage, tracker: PieceTracker): Boolean = runCatching {
        val record = Bencode.decode(Files.readAllBytes(path)) as BValue.Dict
        require(record.bytes("hash")!!.contentEquals(storage.metadata.hash))
        require(record.text("root") == storage.root.toAbsolutePath().normalize().toString())
        require(Bencode.encode(record["files"]!!).contentEquals(Bencode.encode(fingerprint(storage))))
        val bits = record.bytes("have")!!
        val count = storage.metadata.pieceCount
        require(bits.size == (count + 7) / 8)
        if (count % 8 != 0) require(bits.last().toInt() and ((1 shl (8 - count % 8)) - 1) == 0)
        for (index in 0 until count) if (bits[index / 8].toInt() and (128 ushr (index % 8)) != 0) tracker.verified(index)
        true
    }.getOrDefault(false)
}
