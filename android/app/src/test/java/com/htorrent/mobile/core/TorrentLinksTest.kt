package com.htorrent.mobile.core

import com.htorrent.mobile.torrent.TorrentPreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentLinksTest {
    @Test fun recognisesMagnetsAndTorrentFiles() {
        assertTrue(TorrentPreview.isTorrent("magnet:?xt=urn:btih:abc"))
        assertTrue(TorrentPreview.isTorrent("https://example.org/linux.torrent?x=1"))
        assertTrue(TorrentPreview.isTorrent("content://downloads/123"))
        assertFalse(TorrentPreview.isTorrent("https://example.org/file.zip"))
    }
}
