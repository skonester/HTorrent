import com.htorrent.engine.Magnet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
