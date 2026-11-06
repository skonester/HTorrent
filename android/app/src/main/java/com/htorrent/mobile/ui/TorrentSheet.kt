@file:OptIn(ExperimentalMaterial3Api::class)

package com.htorrent.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.htorrent.mobile.core.Format
import com.htorrent.mobile.core.tr
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.torrent.NativeTorrent
import com.htorrent.mobile.torrent.TorrentInfo
import com.htorrent.mobile.torrent.TorrentPreview

/** Add Torrent: reads the torrent's files first (nothing is downloaded), then the files to download are chosen. */
@Composable
fun TorrentSheet(source: String, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enabled by remember { mutableStateOf(Downloads.settings.torrentsEnabled) }
    var info by remember { mutableStateOf<TorrentInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Boolean>() }
    var cancelled by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled || info != null || loading || !NativeTorrent.available) return@LaunchedEffect
        loading = true; error = null
        Thread({
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val result = runCatching { TorrentPreview.load(context, source, { cancelled }) { text -> main.post { lookup = text } } }
            main.post {
                loading = false
                result.onSuccess { found ->
                    info = found; chosen.clear(); chosen.addAll(found.files.map { !it.pad })
                }.onFailure { error = it.message ?: "Couldn't read this torrent." }
            }
        }, "htorrent-torrent-preview").start()
    }
    DisposableEffect(Unit) { onDispose { cancelled = true } }

    fun add(startNow: Boolean) {
        val found = info ?: return
        val priorities = found.files.mapIndexed { i, f -> if (!f.pad && chosen.getOrElse(i) { false }) 4 else 0 }
        if (priorities.none { it > 0 }) { error = "Choose at least one file."; return }
        Downloads.addTorrent(found.copy(priorities = priorities), startNow)
        onDone(if (startNow) "Torrent started" else "Torrent kept for later")
    }

    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Add Torrent"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Torrents on"))
                    Text(tr("Peers and trackers can see your IP address."), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; Downloads.settings.torrentsEnabled = it })
            }
            when {
                !NativeTorrent.available -> Text(tr("This version of the app doesn't include the torrent engine."), color = MaterialTheme.colorScheme.error)
                !enabled -> Text(tr("Turn on torrents to see this torrent's files."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp)); Spacer(Modifier.width(8.dp))
                    Column {
                        Text(tr("Finding the torrent's files…"))
                        if (lookup.isNotEmpty()) Text(lookup, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            info?.let { found ->
                Text(found.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val selected = found.files.filterIndexed { i, f -> !f.pad && chosen.getOrElse(i) { false } }.sumOf { it.size }
                Text("${found.files.count { !it.pad }} files · selected ${Format.size(selected)}", style = MaterialTheme.typography.bodySmall)
                found.files.forEachIndexed { i, f ->
                    if (!f.pad) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chosen.getOrElse(i) { false }, onCheckedChange = { v -> if (i < chosen.size) chosen[i] = v })
                        Text(f.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(Format.size(f.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(tr("Every piece is checked. Finished files go to your Downloads folder. Phones don't keep sharing (seeding) after the download."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                TextButton(onClick = { onDone(null) }) { Text(tr("Cancel")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { add(false) }, enabled = info != null) { Text(tr("Later")) }
                Button(onClick = { add(true) }, enabled = info != null) { Text(tr("Download")) }
            }
        }
    }
}
