package com.htorrent.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.htorrent.mobile.core.DownloadRequest
import com.htorrent.mobile.core.FileNames
import com.htorrent.mobile.core.Job
import com.htorrent.mobile.core.VideoChoice
import com.htorrent.mobile.core.VideoPages
import com.htorrent.mobile.video.VideoJob
import com.htorrent.mobile.video.VideoEngine
import com.htorrent.mobile.core.Links
import com.htorrent.mobile.core.SpeedLimiter
import com.htorrent.mobile.core.Transfer
import com.htorrent.mobile.core.TransferListener
import com.htorrent.mobile.core.TransferState
import com.htorrent.mobile.service.DownloadService
import com.htorrent.mobile.service.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/** What the app needs to add a download (from the Add window, the browser or a shared link). */
data class NewDownload(
    val url: String,
    val fileName: String,
    val size: Long? = null,
    val resumable: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    val secrets: Secrets = Secrets(),
    val mime: String? = null,
    val quality: String? = null,
    /** Set for video pages: the quality picked in the download window. */
    val video: VideoChoice? = null,
)

/**
 * The download list and the queue: at most [Settings.maxActive] downloads run at once, the rest wait their turn.
 * Downloads are written to the app's own folder and moved to the public Downloads folder when they finish.
 */
object Downloads {
    private lateinit var app: Context
    private lateinit var store: ListStore
    lateinit var secrets: SecretStore
        private set
    lateinit var settings: Settings
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val transfers = mutableMapOf<Long, Job>()
    private val mimes = mutableMapOf<Long, String?>()
    val limiter = SpeedLimiter()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = ListStore(app)
        secrets = SecretStore(app)
        settings = Settings(app)
        limiter.bytesPerSecond = settings.speedLimitKB * 1024
        // Downloads that were running when the app stopped continue.
        _items.value = store.load().map { if (it.status == Status.DOWNLOADING) it.copy(status = Status.QUEUED, speed = 0) else it.copy(speed = 0) }
        if (_items.value.any { it.status == Status.QUEUED }) main.post { pump() }
        // Waiting for Wi-Fi: start as soon as an unmetered network is back.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    if (_waitingForWifi.value) main.post { pump() }
                }
            })
        }
    }

    private fun partFile(id: Long): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/$id.part")
    private fun videoDir(id: Long): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/video-$id")
    private fun torrentDir(id: Long): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "partial/torrent-$id")
    private fun torrentState(id: Long): File = File(app.filesDir, "torrents/$id")

    /** Adds a torrent whose files were chosen in the Add Torrent window. */
    @Synchronized fun addTorrent(info: com.htorrent.mobile.torrent.TorrentInfo, startNow: Boolean): Long {
        val key = info.source.ifEmpty { "torrent:" + info.infoHash }
        _items.value.firstOrNull { it.torrent?.infoHash == info.infoHash && it.status != Status.COMPLETE }?.let { existing ->
            if (startNow && existing.status !in listOf(Status.DOWNLOADING, Status.QUEUED)) resume(existing.id)
            return existing.id
        }
        val id = maxOf(System.currentTimeMillis(), (_items.value.maxOfOrNull { it.id } ?: 0) + 1)
        val item = DownloadItem(id = id, url = key, fileName = FileNames.sanitize(info.name), size = info.selectedSize, resumable = true,
            status = if (startNow) Status.QUEUED else Status.LATER, torrent = info)
        _items.value = listOf(item) + _items.value
        persist()
        if (startNow) pump()
        return id
    }

    private fun startTorrent(item: DownloadItem) {
        val id = item.id
        val info = item.torrent!!
        val job = com.htorrent.mobile.torrent.TorrentJob(info, torrentState(id), torrentDir(id), settings.speedLimitKB,
            object : com.htorrent.mobile.torrent.TorrentListener {
                override fun progress(received: Long, wanted: Long, downloadSpeed: Long, uploadSpeed: Long, peers: Int, seeds: Int, seedsTotal: Int, peersTotal: Int) {
                    update(id, save = false) {
                        if (it.status != Status.DOWNLOADING) it
                        else it.copy(written = received, size = wanted.takeIf { w -> w > 0 } ?: it.size, speed = downloadSpeed,
                            torrent = it.torrent?.copy(peers = peers, seeds = seeds, seedsTotal = seedsTotal, peersTotal = peersTotal, uploadSpeed = uploadSpeed))
                    }
                }

                override fun finished(folder: File, files: List<com.htorrent.mobile.torrent.TorrentFileInfo>) {
                    synchronized(this@Downloads) { transfers.remove(id) }
                    try {
                        // Each file goes to Downloads (or the chosen folder), named after the torrent when it has several.
                        var first: Publisher.Saved? = null
                        for (f in files) {
                            val source = File(folder, f.path)
                            val base = f.path.substringAfterLast('/')
                            val name = if (files.size == 1) base else FileNames.sanitize(info.name + " - " + base)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(base.substringAfterLast('.', "").lowercase())
                            val saved = Publisher.publish(app, source, name, mime, settings.categoryFolders, settings.saveTree)
                            if (first == null) first = saved
                        }
                        update(id) {
                            it.copy(status = Status.COMPLETE, written = info.selectedSize, size = info.selectedSize, speed = 0,
                                fileName = if (files.size == 1) first!!.name else it.fileName,
                                contentUri = first?.uri?.toString(), savedPath = first?.path, finishedAt = System.currentTimeMillis(), error = null)
                        }
                        folder.deleteRecursively(); torrentState(id).deleteRecursively()
                        get(id)?.let { Notifier.finished(app, it) }
                    } catch (e: Exception) {
                        update(id) { it.copy(status = Status.FAILED, speed = 0, error = "Could not save to Downloads: ${e.message}") }
                    }
                    main.post { pump() }
                }

                override fun failed(message: String) {
                    synchronized(this@Downloads) { transfers.remove(id) }
                    update(id) { it.copy(status = Status.FAILED, error = message, speed = 0) }
                    get(id)?.let { Notifier.failed(app, it) }
                    main.post { pump() }
                }
            })
        transfers[id] = job
        update(id) { it.copy(status = Status.DOWNLOADING, error = null) }
        job.start()
    }

    @Synchronized private fun update(id: Long, save: Boolean = true, change: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
        if (save) persist()
    }

    private fun persist() {
        val snapshot = _items.value
        io.execute { runCatching { store.save(snapshot) } }
    }

    fun get(id: Long): DownloadItem? = _items.value.firstOrNull { it.id == id }

    /** Adds a download; `startNow = false` keeps it for later (Start queue or the scheduler starts it). */
    @Synchronized fun add(d: NewDownload, startNow: Boolean): Long {
        val existing = _items.value.firstOrNull { it.url == d.url && it.status != Status.COMPLETE && it.status != Status.FAILED }
        if (existing != null) {
            // Never add a link that is already being downloaded: show the running one instead.
            if (startNow && existing.status !in listOf(Status.DOWNLOADING, Status.QUEUED)) resume(existing.id)
            return existing.id
        }
        val id = maxOf(System.currentTimeMillis(), (_items.value.maxOfOrNull { it.id } ?: 0) + 1)
        val name = FileNames.sanitize(d.fileName)
        if (!d.secrets.isEmpty) secrets.put(id, d.secrets)
        mimes[id] = d.mime
        val item = DownloadItem(
            id = id, url = d.url, fileName = name, size = d.size, resumable = d.resumable,
            referer = d.referer, userAgent = d.userAgent, quality = d.video?.label ?: d.quality,
            status = if (startNow) Status.QUEUED else Status.LATER,
            videoFormat = d.video?.selector, videoExt = d.video?.ext,
        )
        _items.value = listOf(item) + _items.value
        persist()
        if (startNow) pump()
        // Added before its page was read: find the video's title in the background and show it in the list.
        if (d.video != null && name == VideoPages.AUTO_NAME) Thread({
            runCatching { VideoEngine.read(d.url, d.userAgent, d.referer) }.getOrNull()?.title?.let { title ->
                update(id) { if (it.fileName == VideoPages.AUTO_NAME && it.status != Status.COMPLETE) it.copy(fileName = VideoPages.fileName(title, it.videoExt ?: "mp4")) else it }
            }
        }, "htorrent-video-title").start()
        return id
    }

    fun resume(id: Long) {
        update(id) { if (it.status == Status.COMPLETE || it.status == Status.DOWNLOADING) it else it.copy(status = Status.QUEUED, error = null) }
        pump()
    }

    fun pause(id: Long) {
        val t = synchronized(this) { transfers.remove(id) }
        update(id) { if (it.status == Status.COMPLETE) it else it.copy(status = Status.PAUSED, speed = 0) }
        t?.pause()
        pump()
    }

    fun retry(id: Long) {
        update(id) { it.copy(status = Status.QUEUED, error = null) }
        pump()
    }

    /** A fresh link for a download whose link expired: it continues where it stopped. */
    fun refreshLink(id: Long, url: String, cookies: String?) {
        if (!Links.isWebLink(url)) return
        pause(id)
        if (!cookies.isNullOrEmpty()) secrets.put(id, secrets.get(id).copy(cookies = cookies))
        update(id) { it.copy(url = url) }
        resume(id)
    }

    fun setLogin(id: Long, user: String, password: String) {
        secrets.put(id, secrets.get(id).copy(user = user.ifEmpty { null }, password = password.ifEmpty { null }))
    }

    fun pauseAll() { _items.value.filter { it.isActive }.forEach { pause(it.id) } }

    fun startAll() {
        _items.value.filter { it.status in listOf(Status.PAUSED, Status.LATER, Status.FAILED) }.forEach { item ->
            update(item.id, save = false) { it.copy(status = Status.QUEUED, error = null) }
        }
        persist()
        pump()
    }

    /** The scheduler: everything kept for later starts now. */
    fun startLater() {
        _items.value.filter { it.status == Status.LATER }.forEach { item -> update(item.id, save = false) { it.copy(status = Status.QUEUED) } }
        persist()
        pump()
    }

    fun remove(id: Long, deleteFile: Boolean) {
        val t = synchronized(this) { transfers.remove(id) }
        t?.pause()
        val item = get(id) ?: return
        synchronized(this) { _items.value = _items.value.filterNot { it.id == id } }
        persist()
        secrets.remove(id)
        io.execute {
            Thread.sleep(300)
            partFile(id).delete()
            videoDir(id).deleteRecursively()
            torrentDir(id).deleteRecursively(); torrentState(id).deleteRecursively()
            if (deleteFile) item.contentUri?.let { Publisher.delete(app, it) }
        }
        pump()
    }

    fun clearFinished() {
        synchronized(this) { _items.value = _items.value.filterNot { it.status == Status.COMPLETE } }
        persist()
    }

    fun setSpeedLimit(kb: Long) {
        settings.speedLimitKB = kb
        limiter.bytesPerSecond = kb * 1024
    }

    private fun onWifiOrNotNeeded(): Boolean {
        if (!settings.wifiOnly) return true
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts queued downloads while there is room. */
    @Synchronized fun pump() {
        if (!::app.isInitialized) return
        val queued = _items.value.filter { it.status == Status.QUEUED && it.id !in transfers }
        if (queued.isEmpty()) { if (transfers.isEmpty()) DownloadService.stop(app); return }
        if (!onWifiOrNotNeeded()) {
            _waitingForWifi.value = true
            if (transfers.isEmpty()) DownloadService.stop(app)
            return
        }
        _waitingForWifi.value = false
        var room = settings.maxActive - transfers.size
        for (item in queued.sortedBy { it.addedAt }) {
            if (room <= 0) break
            startTransfer(item)
            room--
        }
        if (transfers.isNotEmpty()) DownloadService.start(app)
    }

    private fun startVideo(item: DownloadItem) {
        val id = item.id
        val choice = VideoChoice(item.quality ?: "", item.videoFormat!!, item.videoExt ?: "mp4", item.size, audioOnly = item.videoExt != "mp4")
        var lastBytes = item.written
        var lastTime = System.currentTimeMillis()
        val job = VideoJob(
            id, item.url, choice, videoDir(id), settings.speedLimitKB, item.userAgent, item.referer,
            onProgress = { percent, _, total, speed ->
                update(id, save = false) {
                    if (it.status != Status.DOWNLOADING) return@update it
                    // yt-dlp reports the size of the part it is downloading (video, then sound) and its speed.
                    val size = total?.takeIf { t -> t > 0 } ?: it.size
                    val written = if (size != null && size > 0) (size * percent / 100).toLong() else it.written
                    val now = System.currentTimeMillis()
                    val measured = if (now - lastTime >= 1000) ((written - lastBytes) * 1000 / (now - lastTime)).coerceAtLeast(0).also {
                        lastBytes = written; lastTime = now
                    } else it.speed
                    it.copy(written = written, size = size, speed = speed ?: measured)
                }
            },
            onDone = { file ->
                synchronized(this@Downloads) { transfers.remove(id) }
                val current = get(id)
                if (current != null) try {
                    val ext = file.extension.ifEmpty { current.videoExt ?: "mp4" }
                    val name = if (current.fileName == VideoPages.AUTO_NAME) FileNames.sanitize(file.name)
                        else current.fileName.substringBeforeLast('.', current.fileName) + "." + ext
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    val length = file.length()
                    val saved = Publisher.publish(app, file, name, mime, settings.categoryFolders, settings.saveTree)
                    videoDir(id).deleteRecursively()
                    update(id) {
                        it.copy(status = Status.COMPLETE, written = length, size = length, speed = 0,
                            fileName = saved.name, contentUri = saved.uri.toString(), savedPath = saved.path,
                            finishedAt = System.currentTimeMillis(), error = null)
                    }
                    get(id)?.let { Notifier.finished(app, it) }
                } catch (e: Exception) {
                    update(id) { it.copy(status = Status.FAILED, speed = 0, error = "Could not save to Downloads: ${e.message}") }
                }
                main.post { pump() }
            },
            onFail = { message ->
                synchronized(this@Downloads) { transfers.remove(id) }
                update(id) { it.copy(status = Status.FAILED, error = message, speed = 0) }
                get(id)?.let { Notifier.failed(app, it) }
                main.post { pump() }
            },
        )
        transfers[id] = job
        update(id) { it.copy(status = Status.DOWNLOADING, error = null) }
        job.start()
    }

    private fun startTransfer(item: DownloadItem) {
        if (item.isVideo) return startVideo(item)
        if (item.isTorrent) return startTorrent(item)
        val id = item.id
        val s = secrets.get(id)
        val request = DownloadRequest(item.url, s.cookies, item.referer, item.userAgent, s.user, s.password)
        val listener = object : TransferListener {
            override fun progress(state: TransferState, bytesPerSecond: Long) {
                update(id, save = false) {
                    if (it.status != Status.DOWNLOADING) it
                    else it.copy(written = state.written, size = state.size ?: it.size, speed = bytesPerSecond, resumable = state.resumable)
                }
            }

            override fun checkpoint(state: TransferState) {
                update(id) { if (it.status == Status.COMPLETE) it else it.copy(state = state, written = state.written, size = state.size ?: it.size) }
            }

            override fun finished(state: TransferState) {
                synchronized(this@Downloads) { transfers.remove(id) }
                val current = get(id) ?: return
                try {
                    val saved = Publisher.publish(app, partFile(id), current.fileName, mimes[id], settings.categoryFolders, settings.saveTree)
                    update(id) {
                        it.copy(status = Status.COMPLETE, written = state.written, size = state.size ?: state.written, speed = 0,
                            fileName = saved.name, contentUri = saved.uri.toString(), savedPath = saved.path,
                            finishedAt = System.currentTimeMillis(), state = null, error = null)
                    }
                    secrets.remove(id)
                    get(id)?.let { Notifier.finished(app, it) }
                } catch (e: Exception) {
                    update(id) { it.copy(status = Status.FAILED, speed = 0, error = "Could not save to Downloads: ${e.message}") }
                }
                main.post { pump() }
            }

            override fun failed(message: String, state: TransferState) {
                synchronized(this@Downloads) { transfers.remove(id) }
                update(id) { it.copy(status = Status.FAILED, error = message, speed = 0, state = state) }
                get(id)?.let { Notifier.failed(app, it) }
                main.post { pump() }
            }
        }
        val t = Transfer(request, partFile(id), settings.connections, item.state, listener, limiter)
        transfers[id] = t
        update(id) { it.copy(status = Status.DOWNLOADING, error = null) }
        t.start()
    }

    /** Called when the system ends background time (Android 15 limits data-sync services): keep progress, retry later. */
    fun pauseForSystem() {
        _items.value.filter { it.status == Status.DOWNLOADING }.forEach { item ->
            val t = synchronized(this) { transfers.remove(item.id) }
            t?.pause()
            update(item.id) { it.copy(status = Status.QUEUED, speed = 0) }
        }
    }

    /** Screenshots only (debug builds): replaces the list with sample downloads. */
    @Synchronized fun loadDemo(sample: List<DownloadItem>) {
        _items.value = sample
        persist()
    }

    val activeCount: Int get() = _items.value.count { it.status == Status.DOWNLOADING }
    val totalSpeed: Long get() = _items.value.filter { it.status == Status.DOWNLOADING }.sumOf { it.speed }
}
