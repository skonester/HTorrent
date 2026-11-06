package com.htorrent.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun planSplitsIntoEqualSegments() {
        val s = SegmentPlanner.plan(10L * 1024 * 1024, 8)
        assertEquals(8, s.size)
        assertEquals(0L, s.first().start)
        assertEquals(10L * 1024 * 1024 - 1, s.last().end)
        for (i in 1 until s.size) assertEquals(s[i - 1].end + 1, s[i].start)
    }

    @Test fun planKeepsSegmentsAtLeastHalfAMegabyte() {
        assertEquals(2, SegmentPlanner.plan(1024L * 1024, 32).size)
        assertEquals(1, SegmentPlanner.plan(1000, 8).size)
        assertEquals(-1L, SegmentPlanner.plan(null, 8).single().end)
    }

    @Test fun splitLargestHalvesTheRemainingBytes() {
        val segs = SegmentPlanner.plan(8L * 1024 * 1024, 2)
        segs[0].written = segs[0].length
        val (index, newEnd, added) = SegmentPlanner.splitLargest(segs, 2)!!
        assertEquals(1, index)
        assertEquals(newEnd + 1, added.start)
        assertEquals(segs[1].end, added.end)
        segs[1].written = segs[1].length - 1000
        assertNull(SegmentPlanner.splitLargest(segs, 3))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("a_b_c.txt", FileNames.sanitize("a/b\\c.txt"))
        FileNames.sanitize("../../passwd").let { assertFalse(it.contains("/") || it.startsWith(".")) }
        assertFalse(FileNames.sanitize("..hidden").startsWith("."))
        assertEquals("download", FileNames.sanitize("   "))
        assertTrue(FileNames.sanitize("x".repeat(300) + ".zip").let { it.length <= 200 && it.endsWith(".zip") })
    }

    @Test fun contentDispositionPrefersTheEncodedName() {
        assertEquals("résumé.pdf", FileNames.fromContentDisposition("attachment; filename=\"x.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"))
        assertEquals("plain.zip", FileNames.fromContentDisposition("attachment; filename=\"plain.zip\""))
        assertEquals("evil", FileNames.fromContentDisposition("attachment; filename=\"../evil\"")?.trimStart('_'))
        assertNull(FileNames.fromContentDisposition(null))
    }

    @Test fun namesFromLinksAndUniqueNames() {
        assertEquals("file name.mp4", FileNames.fromUrl("https://x.com/a/file%20name.mp4?sig=1"))
        assertEquals("x.com", FileNames.fromUrl("https://x.com/"))
        val taken = setOf("a.zip", "a (1).zip")
        assertEquals("a (2).zip", FileNames.unique("a.zip") { it in taken })
    }

    @Test fun categories() {
        assertEquals(FileCategory.VIDEO, FileCategory.of("clip.MP4"))
        assertEquals(FileCategory.PROGRAMS, FileCategory.of("app.apk"))
        assertEquals(FileCategory.OTHER, FileCategory.of("noext"))
        assertNull(FileCategory.OTHER.folder)
    }

    @Test fun linksAreSplitAndUnwrapped() {
        val l = Links.split("https://a.com/1.zip, https://b.com/2.zip\nftp://no javascript:alert(1) https://a.com/1.zip")
        assertEquals(listOf("https://a.com/1.zip", "https://b.com/2.zip"), l)
        assertEquals("https://img.com/p.jpg", Links.unwrap("https://www.google.com/imgres?imgurl=https%3A%2F%2Fimg.com%2Fp.jpg&tbnid=1"))
        assertEquals("https://x.org/", Links.unwrap("https://l.facebook.com/l.php?u=https%3A%2F%2Fx.org%2F&h=1"))
        assertEquals("https://www.google.com/imgres?imgurl=javascript:x", Links.unwrap("https://www.google.com/imgres?imgurl=javascript:x"))
    }

    @Test fun credentialsOnlyGoToTheSameSite() {
        assertTrue(Net.sameSite("https://a.com/x", "https://A.com:443/y"))
        assertFalse(Net.sameSite("https://cdn.a.com/x", "https://a.com/y"))
        assertFalse(Net.sameSite("http://a.com/x", "https://a.com/y"))
        assertFalse(Net.sameSite("https://a.com:8443/x", "https://a.com/y"))
        assertFalse(Net.sameSite("https://a.com/x", "http://a.com/y")) // other port
        assertTrue(Net.sameSite("http://a.com:80/x", "http://a.com/y"))
    }

    @Test fun rangesAndVersions() {
        assertTrue(Net.rangeMatches("bytes 5-9/10", 5, 10))
        assertFalse(Net.rangeMatches("bytes 0-9/10", 5, 10))
        assertFalse(Net.rangeMatches("bytes 5-9/11", 5, 10))
        assertFalse(Net.rangeMatches(null, 0, 10))
        assertEquals(12345L, Net.totalFromContentRange("bytes 0-0/12345"))
        assertEquals("\"abc\"", Net.validator("\"abc\"", "Mon"))
        assertEquals("Mon", Net.validator("W/\"weak\"", "Mon"))
        assertTrue(Net.sameVersion(null, "x"))
        assertFalse(Net.sameVersion("x", "y"))
    }

    @Test fun mediaDetection() {
        assertTrue(Media.isMedia("https://v.com/a.mp4", null, -1))
        assertTrue(Media.isMedia("https://v.com/play?id=1", "video/mp4", 5_000_000))
        assertFalse(Media.isMedia("https://v.com/a.m3u8", "application/vnd.apple.mpegurl", -1))
        assertFalse(Media.isMedia("https://v.com/a.mp4", "video/mp4", 1000))
        assertFalse(Media.isMedia("blob:https://v.com/1", "video/mp4", -1))
        assertTrue(Media.looksDownloadable("https://x.com/setup.apk?x=1"))
        assertFalse(Media.looksDownloadable("https://x.com/page.html"))
    }

    @Test fun batchPatterns() {
        assertEquals(listOf("f01.jpg", "f02.jpg", "f03.jpg"), BatchPattern.expand("f[01-03].jpg"))
        assertEquals(listOf("a", "b", "c").map { "x$it" }, BatchPattern.expand("x[a-c]"))
        assertEquals(4, BatchPattern.expand("[1-2]-[1-2]").size)
        assertEquals(10_000, BatchPattern.expand("[1-99999]").size)
    }

    @Test fun formatting() {
        assertEquals("1.5 MB", Format.size(1536L * 1024))
        assertEquals("2m 5s", Format.eta(125))
        assertEquals("more than 99 days", Format.eta(100L * 86400))
    }

    @Test fun linksFromDesktopQrCodes() {
        val l = Links.fromHTorrentLink("htorrent://add?url=https%3A%2F%2Fexample.com%2Fa.zip&url=https%3A%2F%2Fexample.com%2Fb.iso&url=file%3A%2F%2F%2Fetc%2Fpasswd&x=1")
        assertEquals(listOf("https://example.com/a.zip", "https://example.com/b.iso"), l)
        assertTrue(Links.fromHTorrentLink("https://example.com/?url=https%3A%2F%2Fx.com%2Fa").isEmpty())
        assertTrue(Links.fromHTorrentLink("htorrent://add").isEmpty())
    }

    @Test fun searchEngines() {
        assertEquals("https://duckduckgo.com/?q=htorrent+download", SearchEngines.url(null, " htorrent download "))
        assertEquals("https://www.google.com/search?q=a%26b", SearchEngines.url("google", "a&b"))
        assertEquals("DuckDuckGo", SearchEngines.of("unknown").name)
        assertEquals(6, SearchEngines.all.map { it.id }.distinct().size)
    }

    @Test fun bookmarksKeepOnePerAddressNewestFirst() {
        var list = Bookmarks.add(emptyList(), "https://a.com/", "A")
        list = Bookmarks.add(list, "https://b.com/", "")
        list = Bookmarks.add(list, "https://a.com/", "A again")
        list = Bookmarks.add(list, "javascript:alert(1)", "bad")
        assertEquals(listOf("https://a.com/", "https://b.com/"), list.map { it.url })
        assertEquals("https://b.com/", list[1].title)
        val back = Bookmarks.parse(Bookmarks.format(list) + "not a link\tx\n")
        assertEquals(list, back)
        assertTrue(Bookmarks.remove(list, "https://a.com/").none { it.url == "https://a.com/" })
        assertEquals("", Bookmarks.format(emptyList()))
    }
}
