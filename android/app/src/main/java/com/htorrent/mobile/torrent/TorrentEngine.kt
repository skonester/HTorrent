package com.htorrent.mobile.torrent

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.htorrent.mobile.core.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Reading a torrent's files before anything is downloaded. */
object TorrentPreview {
    private const val MAX_METADATA = 8 * 1024 * 1024

    /** A magnet link, a link to a .torrent file, or a .torrent file from the phone (content:// or file://). */
    fun isTorrent(source: String): Boolean {
        val s = source.trim()
        return s.startsWith("magnet:?", ignoreCase = true) || s.startsWith("content://") ||
            s.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true)
    }

    /** A download the browser started that is a .torrent file, judged by its address, its name or its type. */
    fun isTorrentDownload(url: String, fileName: String?, mime: String?): Boolean =
        isTorrent(url) || fileName?.trim()?.endsWith(".torrent", ignoreCase = true) == true ||
            mime?.substringBefore(';')?.trim().equals("application/x-bittorrent", ignoreCase = true)

    /** The torrent's name and files; never downloads its content. [cancelled] stops a slow magnet lookup. */
    fun load(context: Context, source: String, cancelled: () -> Boolean = { false }): TorrentInfo {
        val s = source.trim()
        val magnet = s.startsWith("magnet:?", ignoreCase = true)
        val metainfo = if (magnet) "" else Base64.encodeToString(read(context, s), Base64.NO_WRAP)
        val dir = File(context.cacheDir, "torrent-preview/${UUID.randomUUID()}")
        try {
            TorrentSession(dir).use { engine ->
                var snap = engine.call(JSONObject().put("action", "add").put("id", "preview").put("preview", true)
                    .put("source", if (magnet) s else "").put("metainfo", metainfo))
                val deadline = System.currentTimeMillis() + 100_000
                while (snap.optString("state") != "preview") {
                    if (cancelled()) throw IOException("Cancelled.")
                    if (snap.optString("state") == "failed") throw IOException(snap.optString("error"))
                    if (System.currentTimeMillis() > deadline) throw IOException("Couldn't find the torrent's files. Check the link or try again later.")
                    Thread.sleep(250)
                    snap = engine.call(JSONObject().put("action", "status").put("id", "preview"))
                }
                return TorrentInfo.fromSnapshot(if (magnet) s else "", snap)
            }
        } finally { dir.deleteRecursively() }
    }

    private fun read(context: Context, source: String): ByteArray {
        val input: InputStream = when {
            source.startsWith("content://") || source.startsWith("file://") ->
                context.contentResolver.openInputStream(Uri.parse(source)) ?: throw IOException("Can't open the .torrent file.")
            source.startsWith("https://", true) || source.startsWith("http://", true) -> openWeb(context, source)
            else -> File(source).inputStream()
        }
        input.use {
            val bytes = it.readNBytesCompat(MAX_METADATA + 1)
            if (bytes.size > MAX_METADATA) throw IOException("This .torrent file is too large.")
            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw IOException("This isn't a .torrent file.")
            return bytes
        }
    }

    /**
     * Fetches a .torrent the way the built-in browser would, so links on sites you're signed in to work. Redirects are
     * followed here because Android won't cross between http and https, and so each site only gets its own cookies.
     */
    private fun openWeb(context: Context, source: String): InputStream {
        var url = URL(source)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(context))
            android.webkit.CookieManager.getInstance().getCookie(url.toString())?.let { c.setRequestProperty("Cookie", it) }
            when (val code = c.responseCode) {
                in 200..299 -> return c.inputStream
                301, 302, 303, 307, 308 -> {
                    url = URL(url, c.getHeaderField("Location") ?: throw IOException("The torrent link answered $code."))
                    if (url.protocol != "http" && url.protocol != "https") throw IOException("The torrent link redirected somewhere unsupported.")
                }
                else -> throw IOException("The torrent link answered $code.")
            }
        }
        throw IOException("The torrent link redirected too many times.")
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** What a running torrent reports to the download list. */
interface TorrentListener {
    fun progress(received: Long, wanted: Long, downloadSpeed: Long, uploadSpeed: Long, peers: Int, seeds: Int, seedsTotal: Int, peersTotal: Int)
    /** Every selected file was downloaded and checked; [folder] holds them under the torrent's own paths. */
    fun finished(folder: File, files: List<TorrentFileInfo>)
    fun failed(message: String)
}

/**
 * Downloads one torrent while the app's download service runs. Pausing saves a checkpoint, so it continues from there
 * (pieces are checked again if the checkpoint is missing). Torrents don't keep sharing (seeding) on phones.
 */
class TorrentJob(
    private val info: TorrentInfo,
    private val stateDir: File,
    private val folder: File,
    private val downloadLimitKB: Long,
    private val listener: TorrentListener,
) : Job {
    @Volatile private var stopped = false
    private var thread: Thread? = null

    override fun start() {
        thread = Thread({ run() }, "htorrent-torrent").also { it.start() }
    }

    override fun pause() {
        stopped = true
        thread?.interrupt()
    }

    private fun run() {
        var engine: TorrentSession? = null
        var done = false
        try {
            folder.mkdirs()
            engine = TorrentSession(stateDir)
            engine.call(JSONObject().put("action", "add").put("id", "payload").put("preview", false).put("source", info.source)
                .put("metainfo", info.metainfo).put("destination", folder.canonicalPath).put("priorities", JSONArray(info.priorities))
                .put("trackers", JSONArray(info.trackers)).put("seed", false).put("upload_limit", 0)
                .put("download_limit", downloadLimitKB * 1024).put("max_connections", 50).put("max_uploads", 4))
            var snap = engine.call(JSONObject().put("action", "resume").put("id", "payload"))
            while (!stopped) {
                when (snap.optString("state")) {
                    "failed" -> throw IOException(snap.optString("error").ifEmpty { "The torrent failed." })
                    "complete", "seeding" -> if (snap.optBoolean("payload_complete")) { done = true; break }
                }
                listener.progress(snap.optLong("received"), snap.optLong("wanted_size"), snap.optLong("download_speed"), snap.optLong("upload_speed"),
                    snap.optInt("peers"), snap.optInt("seeds"), snap.optInt("seeds_total", -1), snap.optInt("peers_total", -1))
                Thread.sleep(1000)
                snap = engine.call(JSONObject().put("action", "status").put("id", "payload"))
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!stopped) listener.failed(e.message ?: "The torrent failed.")
            stopped = true
        } finally {
            engine?.let {
                runCatching { it.call(JSONObject().put("action", "pause").put("id", "payload")) }
                runCatching { it.call(JSONObject().put("action", "flush")) }
                it.close()
            }
        }
        if (done) listener.finished(folder, info.selectedFiles)
    }
}
