package com.htorrent.mobile.torrent

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The built-in torrent engine (libtorrent), the same one the Mac, Windows and Linux apps use. */
object NativeTorrent {
    /** False when this build doesn't include the engine (for example a local debug build). */
    val available: Boolean = runCatching { System.loadLibrary("nexonic_torrent"); true }.getOrDefault(false)

    external fun create(config: ByteArray): Long
    external fun call(handle: Long, request: ByteArray): ByteArray?
    external fun destroy(handle: Long)
}

/** One engine session. Calls are serialized; [close] saves nothing by itself, so pause and flush first. */
class TorrentSession(stateDir: File, dht: Boolean = true) : AutoCloseable {
    private var handle: Long = 0

    init {
        if (!NativeTorrent.available) throw IOException("This build doesn't include the torrent engine.")
        stateDir.mkdirs()
        // The engine refuses paths containing symbolic links, and Android's own app folder (/data/user/0) is one.
        val config = JSONObject().put("state_dir", stateDir.canonicalPath).put("dht", dht)
        handle = NativeTorrent.create(config.toString().toByteArray())
        if (handle == 0L) {
            val why = NativeTorrent.call(0, "{}".toByteArray())?.let { runCatching { JSONObject(String(it)).optString("error") }.getOrNull() }
            throw IOException(why?.ifEmpty { null } ?: "The torrent engine could not start.")
        }
    }

    @Synchronized fun call(request: JSONObject): JSONObject {
        if (handle == 0L) throw IOException("The torrent session is closed.")
        val bytes = NativeTorrent.call(handle, request.toString().toByteArray()) ?: throw IOException("No answer from the torrent engine.")
        val result = JSONObject(String(bytes))
        if (!result.optBoolean("ok")) throw IOException(result.optString("error").ifEmpty { "Torrent engine error." })
        return result
    }

    @Synchronized override fun close() {
        if (handle != 0L) { NativeTorrent.destroy(handle); handle = 0 }
    }
}
