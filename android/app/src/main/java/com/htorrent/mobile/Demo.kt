package com.htorrent.mobile

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.htorrent.mobile.browser.Browser
import com.htorrent.mobile.browser.Offer
import com.htorrent.mobile.core.Segment
import com.htorrent.mobile.core.TransferState
import com.htorrent.mobile.data.DownloadItem
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.data.NewDownload
import com.htorrent.mobile.data.Status

/**
 * Screenshots (debug builds only, like the desktop apps' NDM_DEMO): a sample list, one real running download from the
 * local demo server (scripts/demo-server.py, reached from the emulator as 10.0.2.2) and the screen to show.
 * Started by android/demo/take-screenshots.sh with `--es ndm_demo 1 --es ndm_screen <name>`.
 */
object Demo {
    private const val HOST = "http://10.0.2.2"

    fun requested(intent: Intent?): Boolean = BuildConfig.DEBUG && intent?.getStringExtra("ndm_demo") == "1"

    fun run(intent: Intent, ui: UiState, browser: Browser) {
        val screen = intent.getStringExtra("ndm_screen") ?: "downloads"
        seed()
        val main = Handler(Looper.getMainLooper())
        when (screen) {
            "detail" -> main.postDelayed({ ui.detailId = Downloads.items.value.firstOrNull { it.status == Status.DOWNLOADING }?.id }, 2500)
            "add" -> main.postDelayed({
                ui.addOffer = Offer(
                    url = "$HOST:8099/studio-suite-2026-installer.zip",
                    fileName = "studio-suite-2026-installer.zip",
                    referer = "https://example.com/download",
                )
            }, 1500)
            "browser", "found" -> {
                ui.tab = Tab.BROWSER
                browser.go("$HOST:8098/page.html")
                if (screen == "found") main.postDelayed({ ui.showFound = true }, 6000)
            }
            "settings" -> ui.tab = Tab.SETTINGS
        }
    }

    private fun seed() {
        val s = Downloads.settings
        s.connections = 8
        s.maxActive = 3
        Downloads.setSpeedLimit(6000)
        val now = System.currentTimeMillis()
        fun item(id: Long, name: String, size: Long, status: Status, done: Double, hoursAgo: Double, parts: Int = 8): DownloadItem {
            val each = size / parts
            val segments = (0 until parts).map { i ->
                val start = i * each
                val end = if (i == parts - 1) size - 1 else start + each - 1
                val len = end - start + 1
                val share = if (done >= 1) 1.0 else (done * parts - i * 0.6).coerceIn(0.0, 1.0)
                Segment(i, start, end, (len * share).toLong())
            }.toMutableList()
            val written = segments.sumOf { it.written }
            val added = now - (hoursAgo * 3_600_000).toLong()
            return DownloadItem(
                id = id, url = "https://example.com/files/$name", fileName = name, status = status, size = size,
                written = if (status == Status.COMPLETE) size else written, addedAt = added, resumable = true,
                finishedAt = if (status == Status.COMPLETE) added + 600_000 else null,
                savedPath = if (status == Status.COMPLETE) "Download/HTorrent/$name" else null,
                state = if (status == Status.COMPLETE) null else TransferState(size, true, "\"demo\"", segments),
            )
        }
        Downloads.loadDemo(
            listOf(
                item(6, "nature-documentary-4k.mkv", 6_200_000_000, Status.PAUSED, 0.37, 2.0),
                item(5, "dataset-2026.tar.gz", 3_900_000_000, Status.LATER, 0.0, 3.0),
                item(4, "course-lecture-08-1080p.mp4", 1_420_000_000, Status.COMPLETE, 1.0, 6.0),
                item(3, "project-assets.zip", 824_000_000, Status.COMPLETE, 1.0, 20.0),
                item(2, "podcast-episode-42.mp3", 96_300_000, Status.COMPLETE, 1.0, 26.0),
                item(1, "design-handbook.pdf", 18_400_000, Status.COMPLETE, 1.0, 30.0, parts = 1),
            )
        )
        // One real download, so speed, time left and connections are real.
        Downloads.add(
            NewDownload(url = "$HOST:8099/conference-keynote-1080p.mp4", fileName = "conference-keynote-1080p.mp4", resumable = true),
            startNow = true,
        )
    }
}
