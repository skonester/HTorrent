import kotlin.test.Test
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `findAvailablePort picks a valid free local port`() {
        val port = findAvailablePort()
        assertTrue(port > 0)
        assertTrue(port <= 65535)
    }

    @Test
    fun `dht config enables public router bootstrap for peer discovery`() {
        val config = TorrentEngine(Paths.get(System.getProperty("user.home"), "Downloads", "HTorrentTest")).let {
            it.buildDhtConfig()
        }
        assertTrue(config.getListeningPort() > 0)
        assertTrue(config.shouldUseRouterBootstrap())
    }
}
