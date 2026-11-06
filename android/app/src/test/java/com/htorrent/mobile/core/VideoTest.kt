package com.htorrent.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTest {
    @Test fun videoSites() {
        assertTrue(VideoPages.isVideoSite("https://www.youtube.com/watch?v=abc"))
        assertTrue(VideoPages.isVideoSite("https://youtu.be/abc"))
        assertTrue(VideoPages.isVideoSite("https://m.facebook.com/watch/?v=1"))
        assertTrue(VideoPages.isVideoSite("https://www.instagram.com/reel/xyz/"))
        assertTrue(VideoPages.isVideoSite("https://vm.tiktok.com/ZMabc/"))
        assertFalse(VideoPages.isVideoSite("https://example.com/file.zip"))
        assertFalse(VideoPages.isVideoSite("https://notyoutube.com/x"))
        assertFalse(VideoPages.isVideoSite("not a link"))
        assertTrue(VideoPages.isVideoPage("https://www.youtube.com/watch?v=abc"))
        assertFalse(VideoPages.isVideoPage("https://www.youtube.com/"))
    }

    @Test fun webPages() {
        assertTrue(VideoPages.isWebPage("text/html; charset=utf-8"))
        assertFalse(VideoPages.isWebPage("video/mp4"))
        assertFalse(VideoPages.isWebPage(null))
    }

    @Test fun choicesBestFirstWithSound() {
        val f = listOf(
            StreamFormat(1080, true, false, "mp4", 50_000_000),
            StreamFormat(1080, true, false, "webm", 40_000_000),
            StreamFormat(720, true, false, "mp4", 20_000_000),
            StreamFormat(360, true, true, "mp4", 8_000_000),
            StreamFormat(0, false, true, "m4a", 3_000_000),
            StreamFormat(0, false, true, "webm", 2_500_000),
        )
        val c = VideoPages.choices(f)
        assertEquals(listOf("Full HD (1080p)", "HD (720p)", "360p", "Sound only (M4A)"), c.map { it.label })
        assertEquals(53_000_000L, c[0].size)
        assertEquals(8_000_000L, c[2].size)
        assertTrue(c.last().audioOnly)
        assertEquals("m4a", c.last().ext)
    }

    @Test fun choicesWhenNothingIsKnown() {
        val c = VideoPages.choices(emptyList())
        assertEquals("Best quality", c.single().label)
    }

    @Test fun namesAndErrors() {
        assertEquals("My clip.mp4", VideoPages.fileName("My clip", "mp4"))
        assertEquals("video.m4a", VideoPages.fileName(null, "m4a"))
        assertEquals("No video found on this page.", VideoPages.friendlyError("ERROR: Unsupported URL: https://x"))
        assertTrue(VideoPages.friendlyError("ERROR: [Instagram] abc: login required").contains("signed in"))
    }

    @Test fun shareLinksAndLogin() {
        assertTrue(VideoPages.isShareLink("https://www.facebook.com/share/r/19oeprsmak/"))
        assertTrue(VideoPages.isShareLink("https://fb.watch/abc/"))
        assertFalse(VideoPages.isShareLink("https://www.facebook.com/reel/123"))
        assertTrue(VideoPages.isLoginPage("https://www.facebook.com/login/?next=x"))
        assertFalse(VideoPages.isLoginPage("https://www.facebook.com/reel/123"))
    }

    @Test fun cookiesFileLines() {
        val l = VideoPages.netscapeCookies(".instagram.com", "sessionid=abc; csrftoken=x=y; bad")
        assertEquals(2, l.size)
        assertEquals(".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc", l[0])
        assertEquals("x=y", l[1].split('\t').last())
    }

    @Test fun engineErrorsAreReadable() {
        val m = VideoPages.engineError("NoClassDefFoundError", "s2.e")
        assertTrue(m.startsWith("The video engine could not start"))
        assertTrue(m.contains("NoClassDefFoundError: s2.e"))
    }

    @Test fun progressLines() {
        val (t, sp) = VideoPages.parseProgress("[download]  45.3% of ~  12.50MiB at    1.25MiB/s ETA 00:10 (frag 3/20)")
        assertEquals((12.5 * 1048576).toLong(), t)
        assertEquals((1.25 * 1048576).toLong(), sp)
        val (t2, sp2) = VideoPages.parseProgress("[download] 100% of 3.00KiB in 00:00:01")
        assertEquals(3072L, t2)
        assertEquals(null, sp2)
        assertEquals(null to null, VideoPages.parseProgress("[info] Downloading webpage"))
    }
}
