package com.htorrent.mobile.torrent

import org.json.JSONArray
import org.json.JSONObject

data class TorrentFileInfo(val index: Int, val path: String, val size: Long, val pad: Boolean)

/** A torrent's files and the choices made for it, plus what the engine last reported while it ran. */
data class TorrentInfo(
    val source: String,
    val name: String,
    val infoHash: String,
    /** The .torrent file's bytes, base64 (from the preview), so a download never needs the original file again. */
    val metainfo: String,
    val files: List<TorrentFileInfo>,
    val priorities: List<Int>,
    val trackers: List<String> = emptyList(),
    val peers: Int = 0,
    val seeds: Int = 0,
    val seedsTotal: Int = -1,
    val peersTotal: Int = -1,
    val uploadSpeed: Long = 0,
    val uploaded: Long = 0,
) {
    val selectedSize: Long get() = files.filterIndexed { i, f -> !f.pad && priorities.getOrElse(i) { 4 } > 0 }.sumOf { it.size }
    val selectedFiles: List<TorrentFileInfo> get() = files.filterIndexed { i, f -> !f.pad && priorities.getOrElse(i) { 4 } > 0 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source); put("name", name); put("infoHash", infoHash); put("metainfo", metainfo)
        put("files", JSONArray().apply { files.forEach { put(JSONObject().put("index", it.index).put("path", it.path).put("size", it.size).put("pad", it.pad)) } })
        put("priorities", JSONArray(priorities)); put("trackers", JSONArray(trackers))
    }

    companion object {
        fun fromJson(o: JSONObject): TorrentInfo {
            val f = o.optJSONArray("files") ?: JSONArray()
            val p = o.optJSONArray("priorities") ?: JSONArray()
            val t = o.optJSONArray("trackers") ?: JSONArray()
            return TorrentInfo(
                source = o.optString("source"), name = o.optString("name"), infoHash = o.optString("infoHash"), metainfo = o.optString("metainfo"),
                files = (0 until f.length()).map { i -> f.getJSONObject(i).let { TorrentFileInfo(it.getInt("index"), it.getString("path"), it.getLong("size"), it.optBoolean("pad")) } },
                priorities = (0 until p.length()).map { p.getInt(it) },
                trackers = (0 until t.length()).map { t.getString(it) },
            )
        }

        /** The engine's snapshot of a torrent whose metadata is known. */
        fun fromSnapshot(source: String, s: JSONObject): TorrentInfo {
            val f = s.optJSONArray("files") ?: JSONArray()
            val files = (0 until f.length()).map { i -> f.getJSONObject(i).let { TorrentFileInfo(it.getInt("index"), it.getString("path"), it.getLong("size"), it.optBoolean("pad")) } }
            val t = s.optJSONArray("trackers") ?: JSONArray()
            val v1 = s.optString("info_hash_v1")
            return TorrentInfo(
                source = source, name = s.optString("name").ifEmpty { "Torrent" }, infoHash = v1.ifEmpty { s.optString("info_hash_v2") },
                metainfo = s.optString("metainfo"), files = files, priorities = files.map { if (it.pad) 0 else 4 },
                trackers = (0 until t.length()).map { t.getString(it) },
            )
        }
    }
}
