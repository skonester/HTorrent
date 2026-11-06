import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamPlayerTest {
    private fun locateFrom(appPath: File): File? {
        val previous = System.getProperty("jpackage.app-path")
        System.setProperty("jpackage.app-path", appPath.path)
        try { return StreamPlayer.locate() }
        finally { if (previous == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", previous) }
    }

    private fun touch(file: File) = file.apply { parentFile.mkdirs(); writeText("") }

    @Test fun `player is found in installed and dev build layouts`() {
        val root = Files.createTempDirectory("htorrent-player-layout").toFile()
        val installed = touch(root.resolve("install/player/${StreamPlayer.EXE}"))
        assertEquals(installed, locateFrom(root.resolve("install/runtime/HTorrent.exe")))

        val dev = touch(root.resolve("build/player-out/${StreamPlayer.EXE}"))
        assertEquals(dev, locateFrom(root.resolve("build/compose/binaries/main/app/HTorrent/HTorrent.exe")))
        root.deleteRecursively()
    }
}
