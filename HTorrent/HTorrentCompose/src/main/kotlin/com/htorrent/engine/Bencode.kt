// Kotlin adaptation of rqbit's bencode and librqbit_core crates.
// Copyright 2021 Igor Katson. Licensed under Apache-2.0. See RQBIT-NOTICE.md.
package com.htorrent.engine

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

sealed class BValue {
    data class Number(val value: Long) : BValue()
    class Bytes(val value: ByteArray) : BValue()
    data class ListValue(val value: List<BValue>) : BValue()
    data class Dict(val value: Map<String, BValue>) : BValue() {
        operator fun get(key: String): BValue? = value[key]
        fun bytes(key: String) = (value[key] as? Bytes)?.value
        fun text(key: String) = bytes(key)?.toString(Charsets.UTF_8)
        fun long(key: String) = (value[key] as? Number)?.value
        fun dict(key: String) = value[key] as? Dict
        fun list(key: String) = (value[key] as? ListValue)?.value.orEmpty()
    }
}

object Bencode {
    class Reader(val data: ByteArray) {
        var position = 0
            private set
        var infoBytes: ByteArray? = null
            private set
        private var values = 0
        fun read(depth: Int = 0): BValue {
            require(depth <= 64 && ++values <= 1_000_000 && position < data.size) { "Invalid bencode nesting or length" }
            return when (data[position].toInt().toChar()) {
                'i' -> {
                    position++
                    val start = position
                    while (position < data.size && data[position] != 'e'.code.toByte()) position++
                    require(position < data.size && position - start in 1..20) { "Invalid integer" }
                    val number = data.copyOfRange(start, position++).toString(Charsets.US_ASCII)
                    require(number.matches(Regex("0|-?[1-9][0-9]*"))) { "Noncanonical integer" }
                    BValue.Number(number.toLong())
                }
                'l' -> {
                    position++
                    val list = mutableListOf<BValue>()
                    while (position < data.size && data[position] != 'e'.code.toByte()) list += read(depth + 1)
                    require(position < data.size) { "Unterminated list" }
                    position++
                    BValue.ListValue(list)
                }
                'd' -> {
                    position++
                    val map = linkedMapOf<String, BValue>()
                    while (position < data.size && data[position] != 'e'.code.toByte()) {
                        val key = (read(depth + 1) as? BValue.Bytes)?.value?.toString(Charsets.ISO_8859_1)
                            ?: error("Dictionary key is not bytes")
                        require(!map.containsKey(key)) { "Duplicate dictionary key" }
                        val start = position
                        map[key] = read(depth + 1)
                        if (depth == 0 && key == "info") infoBytes = data.copyOfRange(start, position)
                    }
                    require(position < data.size) { "Unterminated dictionary" }
                    position++
                    BValue.Dict(map)
                }
                in '0'..'9' -> {
                    val start = position
                    while (position < data.size && data[position] in '0'.code.toByte()..'9'.code.toByte()) position++
                    require(position < data.size && data[position] == ':'.code.toByte() && position - start <= 9)
                    val lengthText = data.copyOfRange(start, position++).toString(Charsets.US_ASCII)
                    require(lengthText == "0" || !lengthText.startsWith('0'))
                    val size = lengthText.toInt()
                    require(size <= data.size - position) { "Truncated byte string" }
                    BValue.Bytes(data.copyOfRange(position, position + size)).also { position += size }
                }
                else -> error("Invalid bencode at $position")
            }
        }
    }

    fun decode(data: ByteArray): BValue = Reader(data).run {
        val result = read()
        require(position == data.size) { "Trailing bencode data" }
        result
    }

    fun encode(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(v: Any) {
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            when (v) {
                is BValue.Number -> write(v.value)
                is BValue.Bytes -> write(v.value)
                is BValue.Dict -> write(v.value)
                is BValue.ListValue -> write(v.value)
                is ByteArray -> { ascii("${v.size}:"); out.write(v) }
                is String -> write(v.toByteArray(Charsets.UTF_8))
                is Number -> ascii("i${v.toLong()}e")
                is List<*> -> { ascii("l"); v.forEach { write(requireNotNull(it)) }; ascii("e") }
                is Map<*, *> -> {
                    ascii("d")
                    v.entries.sortedBy { it.key as String }.forEach {
                        write((it.key as String).toByteArray(Charsets.ISO_8859_1)); write(requireNotNull(it.value))
                    }
                    ascii("e")
                }
                else -> error("Unsupported bencode value")
            }
        }
        write(value)
        return out.toByteArray()
    }
}

internal fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun unhex(text: String): ByteArray {
    require(text.length % 2 == 0 && text.all { it.digitToIntOrNull(16) != null }) { "Invalid hex" }
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
internal fun compactAddress(address: InetSocketAddress): ByteArray =
    address.address.address + byteArrayOf((address.port ushr 8).toByte(), address.port.toByte())
internal fun compactPeers(bytes: ByteArray, ipv6: Boolean = false): List<InetSocketAddress> {
    val size = if (ipv6) 18 else 6
    require(bytes.size % size == 0) { "Invalid compact peer list" }
    return bytes.asList().chunked(size).mapNotNull { chunk ->
        val b = chunk.toByteArray()
        val port = ByteBuffer.wrap(b, size - 2, 2).short.toInt() and 65535
        if (port == 0) null else InetSocketAddress(InetAddress.getByAddress(b.copyOfRange(0, size - 2)), port)
    }
}
