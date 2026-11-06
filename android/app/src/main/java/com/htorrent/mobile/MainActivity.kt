package com.htorrent.mobile

import com.htorrent.mobile.video.VideoEngine
import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.htorrent.mobile.browser.Browser
import com.htorrent.mobile.browser.Offer
import com.htorrent.mobile.core.Links
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.core.Translations
import com.htorrent.mobile.service.Notifier
import com.htorrent.mobile.service.Scheduler
import com.htorrent.mobile.service.canNotify
import com.htorrent.mobile.torrent.TorrentPreview
import com.htorrent.mobile.ui.App
import com.htorrent.mobile.ui.HTorrentTheme

class HTorrentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Translations.use(resources.configuration.locales[0].language) { runCatching { assets.open(it).bufferedReader().use { r -> r.readText() } }.getOrNull() }
        Downloads.init(this)
        Notifier.channels(this)
        Scheduler.apply(this)
        VideoEngine.init(this)
    }
}

enum class Tab { DOWNLOADS, BROWSER, SETTINGS }

/** Shared UI state: which tab is open and which add window or menu is showing. */
class UiState {
    var tab by mutableStateOf(Tab.DOWNLOADS)
    /** The add window: a browser offer, or pasted/shared text. */
    var addOffer by mutableStateOf<Offer?>(null)
    var addText by mutableStateOf<String?>(null)
    /** A magnet link or .torrent file to show in the Add Torrent window. */
    var addTorrent by mutableStateOf<String?>(null)
    var longPress by mutableStateOf<Pair<Offer, Boolean>?>(null)
    var detailId by mutableStateOf<Long?>(null)
    var message by mutableStateOf<String?>(null)
    var showFound by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private val ui = UiState()
    lateinit var browser: Browser
        private set

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Translations.use(resources.configuration.locales[0].language) { runCatching { assets.open(it).bufferedReader().use { r -> r.readText() } }.getOrNull() }
        Downloads.init(this)
        browser = Browser(this,
            onOffer = { offer ->
                if (TorrentPreview.isTorrentDownload(offer.url, offer.fileName, offer.mime)) ui.addTorrent = offer.url
                else if (Downloads.settings.askBeforeDownload) ui.addOffer = offer
                else com.htorrent.mobile.ui.quickAdd(offer) { ui.message = it }
            },
            onLongPress = { offer, image -> ui.longPress = offer to image },
            onMagnet = { link -> ui.addTorrent = link },
        )
        val demo = Demo.requested(intent)
        if (!demo && Build.VERSION.SDK_INT >= 33 && !canNotify(this)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        if (demo) Demo.run(intent, ui, browser)
        setContent { HTorrentTheme { App(ui, browser) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        Downloads.pump()
    }

    /** Links shared from other apps, "Open with" web links, and taps on notifications. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
                // A shared .torrent file, or a magnet link in shared text.
                val stream = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                val magnet = Regex("magnet:\\?[^\\s]+").find(text)?.value
                if (stream != null || magnet != null) { ui.tab = Tab.DOWNLOADS; ui.addTorrent = stream?.toString() ?: magnet; intent.action = null; return }
                val links = Links.split(text)
                if (links.isEmpty()) ui.message = "No web link was shared."
                else { ui.tab = Tab.DOWNLOADS; ui.addText = links.joinToString("\n") }
            }
            Intent.ACTION_VIEW -> {
                val u = intent.dataString
                if (u != null && (u.startsWith("magnet:", true) || intent.type == "application/x-bittorrent" || u.startsWith("content://") || u.startsWith("file://"))) {
                    ui.tab = Tab.DOWNLOADS; ui.addTorrent = u; intent.action = null; return
                }
                val shared = u?.let { Links.fromHTorrentLink(it) }.orEmpty()
                if (shared.isNotEmpty()) { ui.tab = Tab.DOWNLOADS; ui.addText = shared.joinToString("\n") }  // a QR code from the desktop app
                else if (u != null && Links.isWebLink(u)) { ui.tab = Tab.BROWSER; browser.go(u) }
            }
        }
        if (intent.getBooleanExtra("startLater", false)) Downloads.startLater()
        intent.getLongExtra("open", 0L).takeIf { it != 0L }?.let { ui.tab = Tab.DOWNLOADS; ui.detailId = it }
        intent.action = null
    }
}
