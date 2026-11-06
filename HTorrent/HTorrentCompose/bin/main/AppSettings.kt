import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class AppSettings(
    val downloadPath: String,
    val downloadLimitKiB: Long = 0,
    val uploadLimitKiB: Long = 0,
    val startNewTorrents: Boolean = true,
    val resumeOnLaunch: Boolean = true
)

internal class SettingsStore(private val file: Path) {
    fun load(defaultDownloadPath: Path): AppSettings {
        val properties = Properties()
        if (Files.exists(file)) runCatching { Files.newInputStream(file).use(properties::load) }
        val path = properties.getProperty("downloadPath")?.takeIf { it.isNotBlank() }
            ?.takeIf { runCatching { Path.of(it) }.isSuccess } ?: defaultDownloadPath.toString()
        fun limit(key: String) = properties.getProperty(key)?.toLongOrNull()?.takeIf { it in 0..1_000_000_000 } ?: 0
        fun flag(key: String) = properties.getProperty(key)?.toBooleanStrictOrNull() ?: true
        return AppSettings(path, limit("downloadLimitKiB"), limit("uploadLimitKiB"),
            flag("startNewTorrents"), flag("resumeOnLaunch"))
    }

    fun save(settings: AppSettings) {
        val properties = Properties().apply {
            setProperty("downloadPath", settings.downloadPath)
            setProperty("downloadLimitKiB", settings.downloadLimitKiB.toString())
            setProperty("uploadLimitKiB", settings.uploadLimitKiB.toString())
            setProperty("startNewTorrents", settings.startNewTorrents.toString())
            setProperty("resumeOnLaunch", settings.resumeOnLaunch.toString())
        }
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "settings", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "HTorrent settings") }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
