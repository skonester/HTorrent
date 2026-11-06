import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opens torrent streams in HTorrentPlayer, the InfiniFrame (WebView2) player shipped in player/.
 * Falls back to the default browser when the player is missing or exits with an error.
 * Players are closed with HTorrent, since their streams come from HTorrent's local HTTP API.
 */
internal class StreamPlayer(private val executable: File? = locate()) : AutoCloseable {
    private val running = CopyOnWriteArrayList<Process>()
    @Volatile private var closed = false

    fun open(url: String, title: String) {
        if (closed) return
        val player = executable ?: return browse(url)
        val process = try {
            // Windows argument quoting cannot carry quotes or trailing backslashes safely.
            ProcessBuilder(player.path, "--title", title.filterNot { it == '"' || it == '\\' }, url)
                .directory(player.parentFile)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) { return browse(url) }
        running += process
        process.onExit().thenAccept { running -= it; if (it.exitValue() != 0 && !closed) browse(url) }
    }

    override fun close() {
        closed = true
        running.forEach { it.destroy() }
        running.clear()
    }

    companion object {
        const val EXE = "HTorrentPlayer.exe"

        /**
         * Searches upward from this app's jpackage launcher (jpackage.app-path) for player\ (installed/dist layout:
         * <install>\runtime\HTorrent.exe next to <install>\player\) or player-out\ (dev builds such as
         * build\compose\binaries\main\app\HTorrent\HTorrent.exe, and `gradlew run`, find build\player-out).
         * -Dhtorrent.player=<path> overrides the search.
         */
        fun locate(): File? {
            System.getProperty("htorrent.player")?.let { return File(it).takeIf(File::isFile) }
            val start = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile } ?: File("build").absoluteFile
            return generateSequence(start) { it.parentFile }.take(8)
                .flatMap { dir -> sequenceOf(dir.resolve("player/$EXE"), dir.resolve("player-out/$EXE")) }
                .firstOrNull(File::isFile)
        }

        private fun browse(url: String) {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                .onFailure { javax.swing.JOptionPane.showMessageDialog(null, it.message, "HTorrent", javax.swing.JOptionPane.ERROR_MESSAGE) }
        }
    }
}
