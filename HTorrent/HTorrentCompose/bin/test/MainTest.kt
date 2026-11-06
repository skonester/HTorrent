import com.htorrent.engine.Magnet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class MainTest {
    @Test fun `magnet decodes names trackers and selection`() {
        val magnet = Magnet.parse("magnet:?xt=urn:btih:" + "ab".repeat(20) + "&dn=Test+File&tr=http%3A%2F%2Flocalhost%2Fannounce&so=0,2-4")
        assertEquals("Test File", magnet.name)
        assertEquals(listOf("http://localhost/announce"), magnet.trackers)
        assertEquals(setOf(0, 2, 3, 4), magnet.onlyFiles)
    }
    @Test fun `reject invalid magnet before adding a row`() {
        assertFailsWith<IllegalArgumentException> { Magnet.parse("https://example.com") }
    }
    @Test fun `settings survive restart`() {
        val folder = Files.createTempDirectory("htorrent-settings")
        val store = SettingsStore(folder.resolve("settings.properties"))
        val expected = AppSettings(folder.resolve("downloads").toString(), 512, 128, false, false)
        store.save(expected)
        assertEquals(expected, store.load(folder.resolve("unused")))
    }
    @Test fun `Halite toolbar artwork is packaged`() {
        val input = assertNotNull(javaClass.classLoader.getResourceAsStream("halite-toolbar.bmp"))
        val bitmap = input.use(ImageIO::read)
        assertEquals(220, bitmap.width)
        assertEquals(22, bitmap.height)
    }
    @Test fun `second launch forwards arguments to the running instance`() {
        val directory = Files.createTempDirectory("htorrent-instance")
        val first = assertNotNull(InstanceBridge.open(directory, listOf("initial.torrent")))
        try {
            val messages = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
            val delivered = CountDownLatch(1)
            first.attach { messages += it; if (it == listOf("magnet:?xt=example")) delivered.countDown() }
            assertNull(InstanceBridge.open(directory, listOf("magnet:?xt=example")))
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("initial.torrent"), messages.first())
        } finally {
            first.close()
        }
    }
}
