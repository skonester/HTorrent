package com.htorrent.search

import com.htorrent.engine.Magnet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private val http = SearchHttp()

    @Test fun `json reads nested values escapes and quoted numbers`() {
        val value = Json.parse("""{"a":[1,-2.5,true,null],"s":"x\"yé\n","n":"42"}""")
        assertEquals(listOf(1L, -2.5, true, null), value["a"])
        assertEquals("x\"yé\n", value["s"].text())
        assertEquals(42L, value["n"].long())
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a":1""") }
        assertFailsWith<IllegalArgumentException> { Json.parse("[1] 2") }
    }

    @Test fun `piratebay parses string numbers and skips the empty result marker`() {
        val body = """[{"id":"59191690","name":"Ubuntu 22.04 LTS","info_hash":"2C6B6858D61DA9543D4231A71DB4B1C9264B0685","leechers":"1","seeders":"31","size":"3654957056","imdb":""}]"""
        val torrent = PirateBayProvider(http).parse(body).single()
        assertEquals("Ubuntu 22.04 LTS", torrent.title)
        assertEquals(3654957056, torrent.size)
        assertEquals(31, torrent.seeds)
        assertNull(torrent.imdbId)
        assertEquals("https://thepiratebay.org/description.php?id=59191690", torrent.infoUrl)
        val empty = """[{"id":"0","name":"No results returned","info_hash":"0000000000000000000000000000000000000000","leechers":"0","seeders":"0","size":"0"}]"""
        assertTrue(PirateBayProvider(http).parse(empty).isEmpty())
    }

    @Test fun `generated magnets are accepted by the engine`() {
        val magnet = Magnet.parse(formatMagnet("2C6B6858D61DA9543D4231A71DB4B1C9264B0685", "Ubuntu 22.04 & friends"))
        assertEquals("Ubuntu 22.04 & friends", magnet.name)
        assertEquals(trackers, magnet.trackers)
    }

    @Test fun `yts expands each movie into its torrents`() {
        val body = """{"status":"ok","data":{"movie_count":8,"limit":1,"page_number":1,"movies":[{"id":59406,"url":"https://yts.gg/movies/x","imdb_code":"tt30849138","title_long":"Matrix: Generation (2024)",
            "torrents":[{"url":"https://yts.gg/torrent/download/A","hash":"ABCDEF0123456789ABCDEF0123456789ABCDEF01","quality":"1080p","type":"web","seeds":12,"peers":3,"size_bytes":1234}]}]}}"""
        val result = YtsProvider(http).parse(body)
        val torrent = result.torrents.single()
        assertEquals("Matrix: Generation (2024) 1080p web", torrent.title)
        assertEquals("tt30849138", torrent.imdbId)
        assertEquals("https://yts.gg/movies/x", torrent.infoUrl)
        assertTrue(result.hasMoreResults)
    }

    @Test fun `eztv keeps provider magnets and derives hashes`() {
        val body = """{"imdb_id":"0944947","torrents_count":146,"limit":2,"page":1,"torrents":[{"id":1469305,"hash":"a017","magnet_url":"magnet:?xt=urn:btih:a017ac9bf02de9e36f1f9177bdb60612186b0b0d&dn=GoT","title":"Game of Thrones S01E10 EZTV","seeds":5,"peers":1,"size_bytes":"999","imdb_id":"0944947"}]}"""
        val torrent = EztvProvider(http).parse(body).torrents.single()
        assertEquals("A017AC9BF02DE9E36F1F9177BDB60612186B0B0D", torrent.hash)
        assertEquals("tt0944947", torrent.imdbId)
        assertEquals(999, torrent.size)
        assertEquals("https://eztvx.to/ep/1469305/game-of-thrones-s01e10/", torrent.infoUrl)
    }

    @Test fun `nyaa reads table rows and pagination info`() {
        val html = """<table class="torrent-list"><tbody><tr class="default">
            <td><a href="/?c=6_1" title="Software - Applications"><img src="x.png"></a></td>
            <td colspan="2"><a href="/view/96659#comments" class="comments">3</a><a href="/view/96659" title="Koha Live CD">Koha Live CD</a></td>
            <td class="text-center"><a href="/download/96659.torrent"></a><a href="magnet:?xt=urn:btih:45008e48c8800b7d7643337b2e70a634e4c69f6a&amp;dn=Koha"></a></td>
            <td class="text-center">624.0 MiB</td><td class="text-center">2009-11-03 07:03</td>
            <td class="text-center">7</td><td class="text-center">2</td><td class="text-center">0</td>
            </tr></tbody></table>
            <div class="pagination-page-info">Displaying results 1-1 out of 1 results.<br>Please refine</div>"""
        val result = NyaaProvider(http).parse(html)
        val torrent = result.torrents.single()
        assertEquals("Koha Live CD", torrent.title)
        assertEquals("magnet:?xt=urn:btih:45008e48c8800b7d7643337b2e70a634e4c69f6a&dn=Koha", torrent.magnetUrl)
        assertEquals((624.0 * 1024 * 1024).toLong(), torrent.size)
        assertEquals(7, torrent.seeds)
        assertEquals(2, torrent.peers)
        assertEquals("https://nyaa.si/view/96659", torrent.infoUrl)
        assertEquals(1, result.totalTorrents)
    }

    @Test fun `1337x rows need resolution and ignore the hidden seed count in size`() {
        val html = """<table class="table-list"><tbody><tr>
            <td class="coll-1 name"><a href="/sub/1/0/" class="icon"></a><a href="/torrent/123/Some-Movie/">Some Movie</a></td>
            <td class="coll-2 seeds">40</td><td class="coll-3 leeches">4</td><td class="coll-date">Jan. 1st</td>
            <td class="coll-4 size mob-uploader">1.5 GB<span class="seeds">40</span></td></tr></tbody></table>"""
        val result = X1337Provider(http).parse(html)
        val torrent = result.torrents.single()
        assertTrue(result.requiresResolution)
        assertEquals(false, torrent.isResolved)
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), torrent.size)
        assertEquals("https://1337x.to/torrent/123/Some-Movie/", torrent.infoUrl)
    }

    @Test fun `provider selection honours toggles categories and query kind`() {
        TorrentSearch().use { search ->
            val all = search.providers.map { it.name }.toSet()
            fun names(query: TorrentQuery) = search.providersFor(query, all).map { it.name }
            assertEquals(listOf("PirateBay", "YTS", "Nyaa", "1337x"), names(TorrentQuery(content = "ubuntu")))
            assertEquals(listOf("YTS", "EZTV"), names(TorrentQuery(imdbId = "tt0944947")))
            assertEquals(listOf("EZTV"), names(TorrentQuery(imdbId = "tt0944947", category = Category.TV)))
            assertEquals(listOf("Nyaa"), names(TorrentQuery(content = "x", category = Category.ANIME)))
            assertTrue(search.providersFor(TorrentQuery(content = "!!!"), setOf("PirateBay")).isEmpty())
        }
    }

    @Test fun `file sizes parse binary and decimal suffixes`() {
        assertEquals(1024L, parseFileSize("1 KiB"))
        assertEquals(1536L * 1024, parseFileSize("1.5 MB"))
        assertNull(parseFileSize("big"))
    }
}

/** Hits the real provider sites; run manually, since results depend on the network and the sites' availability. */
object LiveSearchSmoke {
    @JvmStatic fun main(args: Array<String>) {
        TorrentSearch().use { search ->
            for (query in listOf(TorrentQuery(content = args.firstOrNull() ?: "ubuntu"), TorrentQuery(imdbId = "tt0944947"))) {
                val providers = search.providersFor(query, search.providers.map { it.name }.toSet())
                val done = java.util.concurrent.CountDownLatch(providers.size)
                search.search(query, providers) { result ->
                    when (result) {
                        is ProviderResult.Success -> println("${result.providerName}: ${result.torrents.size} results, first: ${result.torrents.firstOrNull()?.title}")
                        is ProviderResult.Error -> println("${result.providerName}: FAILED ${result.message}")
                    }
                    done.countDown()
                }
                done.await()
            }
        }
    }
}
