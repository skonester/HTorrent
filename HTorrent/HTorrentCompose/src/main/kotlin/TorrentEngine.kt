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
import javax.swing.SwingUtilities

internal data class TorrentRow(val id: String, val name: String, val progress: Double = 0.0,
    val downSpeed: Long = 0, val upSpeed: Long = 0, val status: String = "Queued", val peers: Int = 0)

internal class TorrentEngine(initialDownloadPath: Path) : AutoCloseable {
    private val itemsState = mutableStateListOf<TorrentRow>()
    val items: List<TorrentRow> get() = itemsState
    var downloadPath by mutableStateOf(initialDownloadPath.toString()); private set
    var engineStatus by mutableStateOf("Starting engine"); private set
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "htorrent-ui-bridge").apply { isDaemon = true } }
    private var session: Session? = null
    private var api: HttpApi? = null
    private val player = StreamPlayer()
    private var last = emptyMap<String, TorrentSnapshot>()
    private var lastTime = System.nanoTime()
    @Volatile private var closed = false
    init {
        worker.execute {
            try {
                Files.createDirectories(initialDownloadPath)
                val state = Path.of(System.getProperty("user.home"), ".htorrent", "session")
                session = Session(state)
                session!!.restore()
                api = HttpApi(session!!, { Path.of(downloadPath) })
            } catch (e: Exception) { showError(e) }
        }
        worker.scheduleWithFixedDelay({ if (!closed) refresh() }, 0, 1, TimeUnit.SECONDS)
    }
    fun setDownloadPath(path: Path) {
        worker.execute { try { Files.createDirectories(path); SwingUtilities.invokeLater { downloadPath = path.toString() } } catch (e: Exception) { showError(e) } }
    }
    fun addTorrentFile(file: File) { val output = Path.of(downloadPath); perform { require(file.length() <= 32 * 1024 * 1024); it.addTorrent(file.readBytes(), output) } }
    fun addMagnet(link: String) { val output = Path.of(downloadPath); perform { it.addMagnet(link, output) } }
    fun startAll() = perform { it.all().forEach { torrent -> torrent.start() }; it.save() }
    fun pauseAll() = perform { it.all().forEach { torrent -> torrent.pause() }; it.save() }
    fun stopAll() = perform { it.all().forEach { torrent -> torrent.pause(stopped = true) }; it.save() }
    fun showFiles(id: String) = perform { session ->
        val torrent = session.get(id) ?: return@perform
        val metadata = torrent.metadata ?: error("Metadata is still being downloaded")
        val selected = torrent.onlyFiles
        val port = api?.port
        SwingUtilities.invokeLater {
            val panel = javax.swing.JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
            val boxes = metadata.files.mapIndexed { index, file ->
                val row = javax.swing.JPanel(java.awt.BorderLayout())
                val box = javax.swing.JCheckBox(file.path.joinToString("/"), selected == null || index in selected)
                box.isEnabled = !file.padding
                row.add(box, java.awt.BorderLayout.CENTER)
                if (!file.padding && port != null) row.add(javax.swing.JButton("Stream").apply {
                    addActionListener { player.open("http://127.0.0.1:$port/torrents/$id/stream/$index", file.path.last()) }
                }, java.awt.BorderLayout.EAST)
                panel.add(row)
                box
            }
            val scroll = javax.swing.JScrollPane(panel).apply { preferredSize = java.awt.Dimension(620, 350) }
            if (javax.swing.JOptionPane.showConfirmDialog(null, scroll, torrent.name, javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                val files = boxes.indices.filter { boxes[it].isSelected && boxes[it].isEnabled }.toSet()
                perform { it.get(id)?.selectFiles(files) }
            }
        }
    }
    fun showLimits() = perform { session ->
        SwingUtilities.invokeLater {
            val down = javax.swing.JTextField((session.downloadLimiter.bytesPerSecond / 1024).toString())
            val up = javax.swing.JTextField((session.uploadLimiter.bytesPerSecond / 1024).toString())
            val panel = javax.swing.JPanel(java.awt.GridLayout(3, 2, 8, 8)).apply {
                add(javax.swing.JLabel("Download KiB/s")); add(down)
                add(javax.swing.JLabel("Upload KiB/s")); add(up)
                add(javax.swing.JLabel("0 = unlimited"))
            }
            if (javax.swing.JOptionPane.showConfirmDialog(null, panel, "Speed Limits", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                perform {
                    val download = down.text.toLongOrNull() ?: error("Enter a whole number")
                    val upload = up.text.toLongOrNull() ?: error("Enter a whole number")
                    require(download in 0..1_000_000_000 && upload in 0..1_000_000_000)
                    it.downloadLimiter.bytesPerSecond = download * 1024
                    it.uploadLimiter.bytesPerSecond = upload * 1024
                }
            }
        }
    }
    fun removeAll() = perform { it.all().forEach { torrent -> it.remove(torrent.id) } }
    private fun perform(action: (Session) -> Unit) {
        if (closed) return
        worker.execute {
            try { action(session ?: error("Torrent engine failed to start")); refresh() }
            catch (e: Exception) { showError(e) }
        }
    }
    private fun showError(e: Exception) = SwingUtilities.invokeLater {
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
                if (item.error != null) "Error: ${item.error}" else item.status.name.lowercase().replaceFirstChar { it.uppercase() }, item.peers)
        }
        last = snapshots.associateBy { it.id }; lastTime = now
        val text = "DHT: ${active.dht?.nodeCount ?: 0} nodes | Port: ${active.port}"
        SwingUtilities.invokeLater { if (!closed) { itemsState.clear(); itemsState.addAll(rows); engineStatus = text } }
    }
    override fun close() {
        closed = true
        player.close()
        worker.execute { api?.close(); session?.close() }
        worker.shutdown()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }
}
