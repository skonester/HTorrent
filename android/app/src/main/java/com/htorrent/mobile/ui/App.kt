@file:OptIn(ExperimentalMaterial3Api::class)

package com.htorrent.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.htorrent.mobile.Tab
import com.htorrent.mobile.core.tr
import com.htorrent.mobile.UiState
import com.htorrent.mobile.browser.Browser
import com.htorrent.mobile.browser.Offer
import com.htorrent.mobile.core.DownloadRequest
import com.htorrent.mobile.core.Http
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.data.NewDownload
import com.htorrent.mobile.data.Secrets
import com.htorrent.mobile.data.Status

@Composable
fun App(ui: UiState, browser: Browser) {
    val items by Downloads.items.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val active = items.count { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(tr(it)); ui.message = null }
    }
    BackHandler(enabled = ui.tab != Tab.DOWNLOADS) {
        if (ui.tab == Tab.BROWSER && browser.view.canGoBack()) browser.back() else ui.tab = Tab.DOWNLOADS
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = ui.tab == Tab.DOWNLOADS, onClick = { ui.tab = Tab.DOWNLOADS },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Outlined.Download, null) }
                    },
                    label = { Text(tr("Downloads")) },
                )
                NavigationBarItem(
                    selected = ui.tab == Tab.BROWSER, onClick = { ui.tab = Tab.BROWSER; browser.startIfNeeded() },
                    icon = { Icon(Icons.Outlined.Language, null) }, label = { Text(tr("Browser")) },
                )
                NavigationBarItem(
                    selected = ui.tab == Tab.SETTINGS, onClick = { ui.tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text(tr("Settings")) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (ui.tab) {
                Tab.DOWNLOADS -> DownloadsScreen(items, ui, onOpenBrowser = { ui.tab = Tab.BROWSER; browser.startIfNeeded() })
                Tab.BROWSER -> BrowserScreen(browser, ui)
                Tab.SETTINGS -> SettingsScreen(browser) { ui.message = it }
            }
        }
    }

    // A magnet link pasted or shared as text opens the Add Torrent window instead.
    LaunchedEffect(ui.addText) {
        val t = ui.addText?.trim()
        if (t != null && t.startsWith("magnet:?", true) && !t.contains('\n')) { ui.addText = null; ui.addTorrent = t }
    }
    ui.addTorrent?.let { source ->
        TorrentSheet(source) { msg -> ui.addTorrent = null; if (msg != null) ui.message = msg }
    }

    if (ui.addOffer != null || ui.addText != null) {
        AddDownloadSheet(
            offer = ui.addOffer,
            text = ui.addText,
            onDone = { msg ->
                ui.addOffer = null; ui.addText = null
                if (msg != null) ui.message = msg
            },
        )
    }

    ui.longPress?.let { (offer, image) ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { ui.longPress = null },
            title = { Text(tr(if (image) "Image" else "Link"), maxLines = 1) },
            text = { Text(offer.url, maxLines = 4) },
            confirmButton = {
                TextButton(onClick = { ui.longPress = null; ui.addOffer = offer }) { Text(tr(if (image) "Download image" else "Download link")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ui.longPress = null
                    copy(context, offer.url)
                    ui.message = "Link copied"
                }) { Text(tr("Copy link")) }
            },
        )
    }

    ui.detailId?.let { id ->
        items.firstOrNull { it.id == id }?.let { item -> DetailSheet(item, onDismiss = { ui.detailId = null }) { ui.message = it } }
            ?: run { ui.detailId = null }
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(tr("link"), text))
}

/** Adds a browser download without asking (when "Ask before downloading" is off). */
fun quickAdd(offer: Offer, message: (String) -> Unit) {
    Thread {
        val req = DownloadRequest(offer.url, offer.cookies, offer.referer, offer.userAgent)
        val probe = runCatching { Http.probe(req) }.getOrNull()
        Downloads.add(
            NewDownload(
                url = offer.url, fileName = probe?.fileName ?: offer.fileName ?: "download", size = probe?.size ?: offer.size.takeIf { it >= 0 },
                resumable = probe?.resumable ?: false, referer = offer.referer, userAgent = offer.userAgent,
                secrets = Secrets(cookies = offer.cookies), mime = probe?.contentType ?: offer.mime, quality = offer.quality,
            ),
            startNow = true,
        )
    }.start()
    message("Download started")
}
