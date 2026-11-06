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

    @Test fun recognisesTorrentDownloadsFromTheBrowser() {
        assertTrue(TorrentPreview.isTorrentDownload("https://example.org/linux.torrent", "linux.torrent", null))
        // Sites often serve them from an address that doesn't end in .torrent.
        assertTrue(TorrentPreview.isTorrentDownload("https://example.org/get?id=5", "linux.TORRENT", null))
        assertTrue(TorrentPreview.isTorrentDownload("https://example.org/get?id=5", "get.bin", "application/x-bittorrent"))
        assertTrue(TorrentPreview.isTorrentDownload("https://example.org/get?id=5", "get.bin", "application/x-bittorrent; charset=binary"))
        assertFalse(TorrentPreview.isTorrentDownload("https://example.org/file.zip", "file.zip", "application/zip"))
        assertFalse(TorrentPreview.isTorrentDownload("https://example.org/get?id=5", "get.bin", null))
    }
}
