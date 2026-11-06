package com.htorrent.mobile.data

import com.htorrent.mobile.core.FileCategory
import com.htorrent.mobile.core.Segment
import com.htorrent.mobile.core.TransferState
import org.json.JSONArray
import org.json.JSONObject

enum class Status { QUEUED, DOWNLOADING, PAUSED, LATER, COMPLETE, FAILED }

/** One download in the list. Cookies and logins are not kept here: they are encrypted in [SecretStore]. */
data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val status: Status = Status.QUEUED,
    val size: Long? = null,
    val written: Long = 0,
    val speed: Long = 0,
    val error: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val contentUri: String? = null,
    val savedPath: String? = null,
    val quality: String? = null,
    val resumable: Boolean = false,
    val state: TransferState? = null,
    /** Video pages (YouTube, Facebook…): the yt-dlp format selector and the result's extension. */
    val videoFormat: String? = null,
    val videoExt: String? = null,
    /** Set for torrents: the files chosen and what the engine last reported. */
    val torrent: com.htorrent.mobile.torrent.TorrentInfo? = null,
) {
    val isTorrent: Boolean get() = torrent != null
    val isVideo: Boolean get() = videoFormat != null
    val category: FileCategory get() = FileCategory.of(fileName)
    val progress: Float get() = size?.takeIf { it > 0 }?.let { (written.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val isActive: Boolean get() = status == Status.DOWNLOADING || status == Status.QUEUED
    val isFinished: Boolean get() = status == Status.COMPLETE
    val etaSeconds: Long get() = if (speed > 0 && size != null) (size - written) / speed else -1

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("fileName", fileName); put("status", status.name)
        size?.let { put("size", it) }; put("written", written)
        error?.let { put("error", it) }; referer?.let { put("referer", it) }; userAgent?.let { put("userAgent", it) }
        put("addedAt", addedAt); finishedAt?.let { put("finishedAt", it) }
        contentUri?.let { put("contentUri", it) }; savedPath?.let { put("savedPath", it) }; quality?.let { put("quality", it) }
        put("resumable", resumable)
        videoFormat?.let { put("videoFormat", it) }; videoExt?.let { put("videoExt", it) }
        torrent?.let { put("torrent", it.toJson()) }
        state?.let { s ->
            put("state", JSONObject().apply {
                s.size?.let { put("size", it) }; put("resumable", s.resumable); s.validator?.let { put("validator", it) }
                put("segments", JSONArray().apply {
                    s.segments.forEach { g -> put(JSONObject().put("id", g.id).put("start", g.start).put("end", g.end).put("written", g.written)) }
                })
            })
        }
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val st = o.optJSONObject("state")?.let { s ->
                val segs = s.optJSONArray("segments") ?: JSONArray()
                TransferState(
                    size = if (s.has("size")) s.getLong("size") else null,
                    resumable = s.optBoolean("resumable"),
                    validator = s.optString("validator").ifEmpty { null },
                    segments = (0 until segs.length()).map { i ->
                        val g = segs.getJSONObject(i)
                        Segment(g.getInt("id"), g.getLong("start"), g.getLong("end"), g.getLong("written"))
                    }.toMutableList(),
                )
            }
            return DownloadItem(
                id = o.getLong("id"),
                url = o.getString("url"),
                fileName = o.getString("fileName"),
                status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED),
                size = if (o.has("size")) o.getLong("size") else null,
                written = o.optLong("written"),
                error = o.optString("error").ifEmpty { null },
                referer = o.optString("referer").ifEmpty { null },
                userAgent = o.optString("userAgent").ifEmpty { null },
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                finishedAt = if (o.has("finishedAt")) o.getLong("finishedAt") else null,
                contentUri = o.optString("contentUri").ifEmpty { null },
                savedPath = o.optString("savedPath").ifEmpty { null },
                quality = o.optString("quality").ifEmpty { null },
                resumable = o.optBoolean("resumable"),
                state = st,
                videoFormat = o.optString("videoFormat").ifEmpty { null },
                videoExt = o.optString("videoExt").ifEmpty { null },
                torrent = o.optJSONObject("torrent")?.let { runCatching { com.htorrent.mobile.torrent.TorrentInfo.fromJson(it) }.getOrNull() },
            )
        }
    }
}

data class Secrets(val cookies: String? = null, val user: String? = null, val password: String? = null) {
    val isEmpty: Boolean get() = cookies.isNullOrEmpty() && user.isNullOrEmpty() && password.isNullOrEmpty()
}
