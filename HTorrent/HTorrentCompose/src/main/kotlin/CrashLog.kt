import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Keeps first-run failures available when the Windows GUI launcher has no console. */
internal object CrashLog {
    val file: Path = Path.of(System.getProperty("user.home"), ".htorrent", "logs", "htorrent-errors.log")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(source: String, error: Throwable) {
        runCatching {
            synchronized(this) {
                Files.createDirectories(file.parent)
                Files.writeString(file, "${Instant.now()} [$source]\n${error.stackTraceToString()}\n\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }
}
