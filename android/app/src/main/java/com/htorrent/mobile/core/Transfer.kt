package com.htorrent.mobile.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What the app knows about a download request. Cookies and logins are only ever sent to the origin's own server. */
data class DownloadRequest(
    val url: String,
    val cookies: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val user: String? = null,
    val password: String? = null,
)

data class ProbeResult(
    val finalUrl: String,
    val size: Long?,
    val resumable: Boolean,
    val validator: String?,
    val fileName: String,
    val contentType: String?,
)

/** Saved progress of a download, enough to continue after the app or the phone restarts. */
data class TransferState(
    var size: Long? = null,
    var resumable: Boolean = false,
    var validator: String? = null,
    var segments: MutableList<Segment> = mutableListOf(),
) {
    val written: Long get() = segments.sumOf { it.written }
    fun copy(): TransferState = TransferState(size, resumable, validator, segments.map { it.copy() }.toMutableList())
}

class HttpStatusException(val code: Int) : IOException("The server answered $code")

object Http {
    const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 10

    /**
     * Opens [request]'s URL (or [url]), following redirects by hand: a redirect to another server, or from https to http,
     * loses the cookies, the login and the referrer. Only http and https are followed.
     */
    fun open(request: DownloadRequest, url: String = request.url, range: String? = null, ifRange: String? = null,
             register: (HttpURLConnection) -> Unit = {}): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!Links.isWebLink(current)) throw IOException("Only web links can be downloaded")
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept-Encoding", "identity")
            request.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            if (Net.sameSite(current, request.url)) {
                request.cookies?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
                request.referer?.takeIf { Links.isWebLink(it) }?.let { conn.setRequestProperty("Referer", it) }
                if (!request.user.isNullOrEmpty()) {
                    val token = Base64.getEncoder().encodeToString("${request.user}:${request.password.orEmpty()}".toByteArray())
                    conn.setRequestProperty("Authorization", "Basic $token")
                }
            }
            if (range != null) {
                conn.setRequestProperty("Range", range)
                if (!ifRange.isNullOrEmpty()) conn.setRequestProperty("If-Range", ifRange)
            }
            register(conn)
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty()) throw IOException("Redirect without a location")
                current = URI(current).resolve(location.trim().replace(" ", "%20")).toString()
                return@repeat
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    /** Asks for the first byte to learn the size, whether ranges work, the file's version and its name. */
    fun probe(request: DownloadRequest): ProbeResult {
        val conn = open(request, range = "bytes=0-0")
        try {
            val code = conn.responseCode
            if (code >= 400) throw HttpStatusException(code)
            val finalUrl = conn.url.toString()
            val cr = conn.getHeaderField("Content-Range")
            val ranged = code == 206 && cr != null
            val size = if (ranged) Net.totalFromContentRange(cr) else conn.getHeaderField("Content-Length")?.toLongOrNull()
            val type = conn.contentType
            var name = FileNames.fromContentDisposition(conn.getHeaderField("Content-Disposition")) ?: FileNames.fromUrl(finalUrl)
            if (FileNames.extensionOf(name).isEmpty()) MimeExt.forType(type)?.let { name = "$name.$it" }
            return ProbeResult(finalUrl, size?.takeIf { it >= 0 }, ranged && size != null,
                Net.validator(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified")), name, type)
        } finally {
            conn.disconnect()
        }
    }
}

object MimeExt {
    private val map = mapOf(
        "application/pdf" to "pdf", "application/zip" to "zip", "video/mp4" to "mp4", "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a", "video/webm" to "webm", "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif",
        "image/webp" to "webp", "application/vnd.android.package-archive" to "apk", "text/plain" to "txt",
        "application/x-7z-compressed" to "7z", "application/x-rar-compressed" to "rar", "application/epub+zip" to "epub",
    )
    fun forType(type: String?): String? = type?.substringBefore(';')?.trim()?.lowercase()?.let { map[it] }
}

/** Shared speed limit in bytes per second (0 = no limit). */
class SpeedLimiter(@Volatile var bytesPerSecond: Long = 0) {
    private var windowStart = System.nanoTime()
    private var used = 0L

    fun take(bytes: Int) {
        val limit = bytesPerSecond
        if (limit <= 0) return
        val sleep: Long
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) { windowStart = now; used = 0 }
            used += bytes
            sleep = if (used > limit) (1_000_000_000L - (now - windowStart)) / 1_000_000L else 0
        }
        if (sleep > 0) Thread.sleep(sleep)
    }
}

interface TransferListener {
    /** About twice a second while running. */
    fun progress(state: TransferState, bytesPerSecond: Long)
    /** Saved progress (every few seconds and when stopping). */
    fun checkpoint(state: TransferState) {}
    fun finished(state: TransferState)
    fun failed(message: String, state: TransferState)
    fun paused(state: TransferState) {}
}

/** A running download that can be started and paused (a file [Transfer] or a video download). */
interface Job {
    fun start()
    fun pause()
}

/**
 * Downloads [request] into [file] with up to [connections] ranges at the same time, like the desktop apps:
 * a connection that finishes takes over half of the largest remaining range; a range that fails is retried and
 * progress that is kept earns fresh retries; a file that changed on the server is downloaded again from the start.
 */
class Transfer(
    private val request: DownloadRequest,
    private val file: File,
    private val connections: Int,
    initial: TransferState?,
    private val listener: TransferListener,
    private val limiter: SpeedLimiter = SpeedLimiter(),
    private val maxRetries: Int = 6,
) : Job {
    private val lock = Any()
    private var state = initial?.copy() ?: TransferState()
    private val stopped = AtomicBoolean(false)
    private val done = AtomicBoolean(false)
    private val open = mutableSetOf<HttpURLConnection>()
    private val running = mutableSetOf<Int>()
    private var pool: ExecutorService? = null
    private var raf: RandomAccessFile? = null
    private var nextId = 0
    private val total = AtomicLong(0)
    private var ticker: Thread? = null
    @Volatile private var gen = 0

    private fun alive(g: Int) = !stopped.get() && g == gen

    override fun start() {
        Thread({ runCatching { begin() }.onFailure { fail(it.message ?: "Download failed") } }, "htorrent-probe").start()
    }

    /** Stops every connection and keeps the progress. */
    override fun pause() {
        if (!stopped.compareAndSet(false, true)) return
        closeAll()
        listener.paused(snapshot())
    }

    fun snapshot(): TransferState = synchronized(lock) { state.copy() }

    private fun begin() {
        val probe = Http.probe(request)
        if (stopped.get()) return
        synchronized(lock) {
            val same = state.segments.isNotEmpty() && state.resumable && probe.resumable &&
                state.size == probe.size && Net.sameVersion(state.validator, probe.validator) && file.exists()
            if (!same) {
                state = TransferState(probe.size, probe.resumable, probe.validator,
                    SegmentPlanner.plan(if (probe.resumable) probe.size else null, if (probe.resumable) connections else 1).toMutableList())
                file.parentFile?.mkdirs()
                file.delete()
            } else {
                state.validator = probe.validator ?: state.validator
                // Never trust more progress than the file holds.
                val len = file.length()
                state.segments.forEach { s -> if (s.start + s.written > len) s.written = maxOf(0L, len - s.start) }
            }
            nextId = (state.segments.maxOfOrNull { it.id } ?: -1) + 1
            raf = RandomAccessFile(file, "rw").also { r -> state.size?.let { if (r.length() < it) r.setLength(it) } }
            total.set(state.written)
        }
        startTicker()
        val workers = state.segments.filter { !it.isComplete }
        if (workers.isEmpty()) { finishIfDone(); return }
        pool = Executors.newFixedThreadPool(SegmentPlanner.MAX_CONNECTIONS)
        workers.forEach { launch(it) }
    }

    private fun launch(seg: Segment) {
        val g = gen
        synchronized(lock) { running.add(seg.id) }
        pool?.execute { runSegment(seg, g) }
    }

    private fun runSegment(seg: Segment, g: Int) {
        var attempts = 0
        val buf = ByteArray(64 * 1024)
        while (alive(g)) {
            var conn: HttpURLConnection? = null
            var progressed = false
            try {
                val (from, end, resumable, validator) = synchronized(lock) {
                    if (seg.isComplete) Quad(-1L, 0L, false, null) else Quad(seg.start + seg.written, seg.end, state.resumable, state.validator)
                }
                if (from < 0) break
                if (!resumable && from > 0) {
                    // Without ranges every try starts over.
                    synchronized(lock) { total.addAndGet(-seg.written); seg.written = 0 }
                }
                val range = if (resumable) "bytes=$from-${if (end >= 0) end.toString() else ""}" else null
                conn = Http.open(request, range = range, ifRange = validator) { c -> synchronized(lock) { open.add(c) } }
                val code = conn.responseCode
                if (resumable) {
                    if (code == 200 && !validator.isNullOrEmpty()) { fileChanged(); return }
                    if (code != 206 || !Net.rangeMatches(conn.getHeaderField("Content-Range"), from, state.size)) {
                        throw if (code >= 400) HttpStatusException(code) else IOException("The server sent the wrong part of the file")
                    }
                } else if (code !in 200..299) throw HttpStatusException(code)
                val input: InputStream = conn.inputStream
                var finished = false
                while (alive(g)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val w = synchronized(lock) {
                        val pos = seg.start + seg.written
                        val allowed = if (seg.end < 0) Long.MAX_VALUE else seg.end - pos + 1
                        val w = minOf(n.toLong(), allowed).toInt()
                        if (w > 0) {
                            raf!!.seek(pos)
                            raf!!.write(buf, 0, w)
                            seg.written += w
                        }
                        if (allowed <= n) finished = true
                        w
                    }
                    if (w > 0) { total.addAndGet(w.toLong()); progressed = true; attempts = 0; limiter.take(w) }
                    if (finished) break
                }
                if (!alive(g)) return
                synchronized(lock) {
                    if (seg.end < 0) {
                        val known = state.size
                        if (known != null && seg.written < known) throw IOException("The connection closed early")
                        seg.end = seg.start + seg.written - 1
                        if (known == null) state.size = seg.written
                    }
                }
                if (synchronized(lock) { seg.isComplete }) break
                throw IOException("The connection closed early")
            } catch (e: Exception) {
                if (!alive(g)) return
                if (e is HttpStatusException && e.code in listOf(401, 403, 404, 410) && !progressed) {
                    fail(if (e.code == 403 || e.code == 401) "The server refused the download (${e.code}). Try again from the page, or refresh the link."
                         else "The file is no longer there (${e.code}). Refresh the link to continue.")
                    return
                }
                if (!progressed) attempts++
                if (attempts > maxRetries) { fail(e.message ?: "Network error"); return }
                Thread.sleep(minOf(30_000L, 1000L shl minOf(attempts, 5)))
            } finally {
                conn?.let { c -> synchronized(lock) { open.remove(c) }; c.disconnect() }
            }
        }
        if (!alive(g)) return
        synchronized(lock) { running.remove(seg.id) }
        splitAndLaunch()
        finishIfDone()
    }

    private data class Quad(val from: Long, val end: Long, val resumable: Boolean, val validator: String?)

    private fun splitAndLaunch() {
        if (stopped.get()) return
        val added = synchronized(lock) {
            if (!state.resumable || running.size >= connections) return
            val split = SegmentPlanner.splitLargest(state.segments, nextId) ?: return
            nextId++
            state.segments[split.first].end = split.second
            state.segments.add(split.third)
            split.third
        }
        launch(added)
    }

    private fun finishIfDone() {
        val complete = synchronized(lock) { running.isEmpty() && state.segments.all { it.isComplete } }
        if (!complete || stopped.get() || !done.compareAndSet(false, true)) return
        closeAll()
        Thread.interrupted()
        val s = snapshot()
        val size = s.size
        if (size != null && (file.length() != size || s.written != size)) {
            if (file.length() > size && s.written == size) RandomAccessFile(file, "rw").use { it.setLength(size) }
            else { listener.failed("The downloaded size does not match", s); return }
        }
        listener.finished(s)
    }

    private fun fileChanged() {
        if (!stopped.compareAndSet(false, true)) return
        gen++
        closeAll()
        Thread.interrupted()
        synchronized(lock) { state = TransferState() }
        file.delete()
        stopped.set(false)
        done.set(false)
        start()
    }

    private fun fail(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        closeAll()
        Thread.interrupted()
        listener.failed(message, snapshot())
    }

    private fun closeAll() {
        val conns = synchronized(lock) { open.toList().also { open.clear() } }
        conns.forEach { runCatching { it.disconnect() } }
        pool?.shutdownNow()
        pool = null
        ticker?.interrupt()
        synchronized(lock) {
            runCatching { raf?.fd?.sync() }
            runCatching { raf?.close() }
            raf = null
            running.clear()
        }
        listener.checkpoint(snapshot())
    }

    private fun startTicker() {
        ticker = Thread({
            var last = total.get()
            var lastTime = System.nanoTime()
            var speed = 0.0
            var ticks = 0
            try {
                while (!stopped.get() && !done.get()) {
                    Thread.sleep(500)
                    val now = System.nanoTime()
                    val t = total.get()
                    val inst = (t - last) * 1e9 / (now - lastTime).coerceAtLeast(1)
                    speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                    last = t; lastTime = now
                    if (!stopped.get() && !done.get()) {
                        listener.progress(snapshot(), speed.toLong())
                        if (++ticks % 6 == 0) {
                            synchronized(lock) { runCatching { raf?.fd?.sync() } }
                            listener.checkpoint(snapshot())
                        }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "htorrent-ticker").apply { isDaemon = true; start() }
    }
}
