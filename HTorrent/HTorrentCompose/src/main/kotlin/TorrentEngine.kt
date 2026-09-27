import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.htorrent.engine.HttpApi
import com.htorrent.engine.Session
import com.htorrent.engine.TorrentSnapshot
import com.htorrent.engine.TorrentStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

internal data class TorrentRow(val id: String, val name: String, val progress: Double = 0.0,
    val downSpeed: Long = 0, val upSpeed: Long = 0, val status: String = "Queued", val peers: Int = 0,
    val downloaded: Long = 0, val uploaded: Long = 0, val output: String = "")
internal data class TorrentFileDetail(val index: Int, val path: String, val size: Long, val selected: Boolean)
internal data class TorrentDetail(val id: String, val files: List<TorrentFileDetail>,
    val trackers: List<String>, val peers: List<String>, val metadataReady: Boolean)

internal class TorrentEngine(initialDownloadPath: Path) : AutoCloseable {
    private val itemsState = mutableStateListOf<TorrentRow>()
    val items: List<TorrentRow> get() = itemsState
    private val settingsStore = SettingsStore(Path.of(System.getProperty("user.home"), ".htorrent", "settings.properties"))
    var settings by mutableStateOf(settingsStore.load(initialDownloadPath)); private set
    var downloadPath by mutableStateOf(settings.downloadPath); private set
    var engineStatus by mutableStateOf("Starting engine"); private set
    var detail by mutableStateOf<TorrentDetail?>(null); private set
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "htorrent-ui-bridge").apply { isDaemon = true } }
    private var session: Session? = null
    private var api: HttpApi? = null
    private val player = StreamPlayer()
    private var last = emptyMap<String, TorrentSnapshot>()
    private var lastTime = System.nanoTime()
    private val started = AtomicBoolean(false)
    @Volatile private var inspectedId: String? = null
    @Volatile private var closed = false
    /** Called after Compose applies the snapshot that created this engine's observable state. */
    fun start() {
        if (closed || !started.compareAndSet(false, true)) return
        worker.execute {
            try {
                Files.createDirectories(Path.of(downloadPath))
                val state = Path.of(System.getProperty("user.home"), ".htorrent", "session")
                session = Session(state)
                session!!.downloadLimiter.bytesPerSecond = settings.downloadLimitKiB * 1024
                session!!.uploadLimiter.bytesPerSecond = settings.uploadLimitKiB * 1024
                session!!.restore(startRunning = settings.resumeOnLaunch)
                api = HttpApi(session!!, { Path.of(downloadPath) })
            } catch (e: Exception) { showError(e) }
        }
        worker.scheduleWithFixedDelay({
            if (!closed) runCatching { refresh() }.onFailure {
                CrashLog.record("torrent refresh", it)
                SwingUtilities.invokeLater { engineStatus = "Error refreshing torrents: ${it.message ?: it.javaClass.simpleName}" }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }
    fun setDownloadPath(path: Path) {
        updateSettings(settings.copy(downloadPath = path.toAbsolutePath().normalize().toString()))
    }
    fun updateSettings(value: AppSettings) = perform { active ->
        val path = Path.of(value.downloadPath).toAbsolutePath().normalize()
        require(value.downloadLimitKiB in 0..1_000_000_000 && value.uploadLimitKiB in 0..1_000_000_000)
        Files.createDirectories(path)
        val saved = value.copy(downloadPath = path.toString())
        settingsStore.save(saved)
        active.downloadLimiter.bytesPerSecond = saved.downloadLimitKiB * 1024
        active.uploadLimiter.bytesPerSecond = saved.uploadLimitKiB * 1024
        SwingUtilities.invokeLater { settings = saved; downloadPath = saved.downloadPath }
    }
    fun addTorrentFile(file: File) { val output = Path.of(downloadPath); val start = settings.startNewTorrents
        perform { require(file.length() <= 32 * 1024 * 1024); it.addTorrent(file.readBytes(), output, start) } }
    fun addMagnet(link: String) { val output = Path.of(downloadPath); val start = settings.startNewTorrents
        perform { it.addMagnet(link, output, start) } }
    fun startAll() = perform { it.all().forEach { torrent -> torrent.start() }; it.save() }
    fun pauseAll() = perform { it.all().forEach { torrent -> torrent.pause() }; it.save() }
    fun stopAll() = perform { it.all().forEach { torrent -> torrent.pause(stopped = true) }; it.save() }
    fun start(id: String) = perform { it.get(id)?.start(); it.save() }
    fun pause(id: String) = perform { it.get(id)?.pause(); it.save() }
    fun stop(id: String) = perform { it.get(id)?.pause(stopped = true); it.save() }
    fun remove(id: String) = perform { it.remove(id) }
    fun openDownloadFolder(id: String) = perform { session ->
        val output = session.get(id)?.output ?: return@perform
        Files.createDirectories(output)
        java.awt.Desktop.getDesktop().open(output.toFile())
    }
    fun inspect(id: String?) {
        inspectedId = id
        if (!closed) worker.execute { session?.let(::refreshDetail) }
    }
    fun selectFile(id: String, index: Int, selected: Boolean) = perform { session ->
        val torrent = session.get(id) ?: return@perform
        val metadata = torrent.metadata ?: return@perform
        require(index in metadata.files.indices && !metadata.files[index].padding)
        val files = torrent.onlyFiles?.toMutableSet() ?: metadata.files.indices.filter { !metadata.files[it].padding }.toMutableSet()
        if (selected) files += index else files -= index
        torrent.selectFiles(files)
    }
    fun streamFile(id: String, index: Int) = perform { session ->
        val torrent = session.get(id) ?: return@perform
        val file = torrent.metadata?.files?.getOrNull(index) ?: return@perform
        require(!file.padding)
        val port = api?.port ?: error("Stream server is not ready")
        player.open("http://127.0.0.1:$port/torrents/$id/stream/$index", file.path.last())
    }
    fun recheck(id: String) = perform { it.get(id)?.recheck() }
    fun removeAll() = perform { it.all().forEach { torrent -> it.remove(torrent.id) } }
    private fun perform(action: (Session) -> Unit) {
        if (closed) return
        worker.execute {
            try { action(session ?: error("Torrent engine failed to start")); refresh() }
            catch (e: Exception) { showError(e) }
        }
    }
    private fun showError(e: Exception) = SwingUtilities.invokeLater {
        CrashLog.record("torrent engine", e)
        engineStatus = "Error: ${e.message ?: e.javaClass.simpleName}"
        javax.swing.JOptionPane.showMessageDialog(null, engineStatus, "HTorrent", javax.swing.JOptionPane.ERROR_MESSAGE)
    }
    private fun refresh() {
        val active = session ?: return
        val snapshots = active.snapshots()
        val now = System.nanoTime()
        val seconds = ((now - lastTime) / 1_000_000_000.0).coerceAtLeast(0.001)
        val rows = snapshots.map { item ->
            val previous = last[item.id]
            val running = item.status in listOf(TorrentStatus.DOWNLOADING, TorrentStatus.SEEDING, TorrentStatus.FINISHED)
            TorrentRow(item.id, item.name, item.progress,
                if (running && previous != null) ((item.downloaded - previous.downloaded).coerceAtLeast(0) / seconds).toLong() else 0,
                if (running && previous != null) ((item.uploaded - previous.uploaded).coerceAtLeast(0) / seconds).toLong() else 0,
                if (item.error != null) "Error: ${item.error}" else item.status.name.lowercase().replaceFirstChar { it.uppercase() }, item.peers,
                item.downloaded, item.uploaded, item.output)
        }
        last = snapshots.associateBy { it.id }; lastTime = now
        val text = "DHT: ${active.dht?.nodeCount ?: 0} nodes | Port: ${active.port}"
        SwingUtilities.invokeLater { if (!closed) { itemsState.clear(); itemsState.addAll(rows); engineStatus = text } }
        refreshDetail(active)
    }
    private fun refreshDetail(active: Session) {
        val torrent = inspectedId?.let(active::get)
        val metadata = torrent?.metadata
        val selected = torrent?.onlyFiles
        val value = torrent?.let { managed ->
            TorrentDetail(managed.id,
                metadata?.files?.mapIndexedNotNull { index, file ->
                    if (file.padding) null else TorrentFileDetail(index, file.path.joinToString("/"), file.length, selected == null || index in selected)
                }.orEmpty(),
                managed.trackerTiers.flatten().distinct(),
                managed.connections.values.map { "${it.address.hostString}:${it.address.port}  (${it.source})" }.sorted(),
                metadata != null)
        }
        SwingUtilities.invokeLater { if (!closed) detail = value }
    }
    override fun close() {
        closed = true
        player.close()
        worker.execute { api?.close(); session?.close() }
        worker.shutdown()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }
}
