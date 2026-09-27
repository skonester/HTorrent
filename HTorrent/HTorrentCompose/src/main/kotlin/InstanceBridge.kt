import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Keeps one session writer alive and passes Explorer launches to that instance. */
internal class InstanceBridge private constructor(
    private val directory: Path, private val channel: FileChannel, private val lock: FileLock,
    private val server: ServerSocket, private val token: String
) : AutoCloseable {
    private val pending = ConcurrentLinkedQueue<List<String>>()
    @Volatile private var receiver: ((List<String>) -> Unit)? = null
    @Volatile private var closed = false
    private val listener = Thread({
        while (!closed) runCatching {
            server.accept().use { socket ->
                socket.soTimeout = 2000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val allowed = input.readUTF() == token
                val count = input.readInt()
                if (allowed && count in 0..16) {
                    val args = List(count) { input.readUTF() }
                    deliver(args)
                    output.writeBoolean(true)
                } else output.writeBoolean(false)
                output.flush()
            }
        }.onFailure { if (!closed) Thread.sleep(100) }
    }, "htorrent-instance-bridge").apply { isDaemon = true; start() }

    fun attach(handler: (List<String>) -> Unit) {
        receiver = handler
        while (true) handler(pending.poll() ?: break)
    }

    private fun deliver(args: List<String>) {
        val handler = receiver
        if (handler == null) pending += args else handler(args)
    }

    override fun close() {
        if (closed) return
        closed = true
        receiver = null
        server.close()
        Files.deleteIfExists(directory.resolve("instance.info"))
        lock.release()
        channel.close()
    }

    companion object {
        /** Returns null after a launch was successfully handed to the existing process. */
        fun open(directory: Path, args: List<String>): InstanceBridge? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() }
                catch (_: java.nio.channels.OverlappingFileLockException) { null }
                catch (error: Exception) { channel.close(); throw error }
            if (lock == null) {
                channel.close()
                forward(directory, args)
                return null
            }
            var server: ServerSocket? = null
            try {
                server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
                val token = UUID.randomUUID().toString()
                val temporary = Files.createTempFile(directory, "instance", ".tmp")
                try {
                    Files.writeString(temporary, "${server.localPort}\n$token\n")
                    try { Files.move(temporary, directory.resolve("instance.info"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary, directory.resolve("instance.info"), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { Files.deleteIfExists(temporary) }
                return InstanceBridge(directory, channel, lock, server, token).apply { if (args.isNotEmpty()) deliver(args) }
            } catch (error: Exception) {
                server?.close()
                lock.release(); channel.close()
                throw error
            }
        }

        private fun forward(directory: Path, args: List<String>) {
            require(args.size <= 16) { "Too many launch arguments" }
            repeat(20) {
                val result = runCatching {
                    val lines = Files.readAllLines(directory.resolve("instance.info"))
                    val port = lines[0].toInt()
                    Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                        socket.soTimeout = 1000
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeUTF(lines[1]); output.writeInt(args.size)
                        args.forEach(output::writeUTF)
                        output.flush()
                        check(DataInputStream(socket.getInputStream()).readBoolean()) { "HTorrent rejected the launch request" }
                    }
                }
                if (result.isSuccess) return
                Thread.sleep(100)
            }
            error("The running HTorrent instance did not accept the request")
        }
    }
}
