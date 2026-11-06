@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.htorrent.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.htorrent.mobile.UiState
import com.htorrent.mobile.core.tr
import com.htorrent.mobile.core.FileCategory
import com.htorrent.mobile.core.Format
import com.htorrent.mobile.core.Links
import com.htorrent.mobile.data.DownloadItem
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.data.Status
import java.text.DateFormat
import java.util.Date

private enum class View(val label: String) { ALL("All"), UNFINISHED("Unfinished"), FINISHED("Finished") }

fun categoryIcon(c: FileCategory): ImageVector = when (c) {
    FileCategory.VIDEO -> Icons.Outlined.Movie
    FileCategory.MUSIC -> Icons.Outlined.MusicNote
    FileCategory.COMPRESSED -> Icons.Outlined.Archive
    FileCategory.DOCUMENTS -> Icons.Outlined.Description
    FileCategory.PROGRAMS -> Icons.Outlined.Apps
    FileCategory.IMAGES -> Icons.Outlined.Image
    FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
fun DownloadsScreen(items: List<DownloadItem>, ui: UiState, onOpenBrowser: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(View.ALL) }
    var category by rememberSaveable { mutableStateOf<FileCategory?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val waitingForWifi by Downloads.waitingForWifi.collectAsState()
    // Long-press a download to select it; then tap others to add them. Actions apply to every selected download.
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }

    val inView = items.filter {
        when (view) {
            View.ALL -> true
            View.UNFINISHED -> it.status != Status.COMPLETE
            View.FINISHED -> it.status == Status.COMPLETE
        }
    }
    val shown = inView.filter { (category == null || it.category == category) && (query.isBlank() || it.fileName.contains(query, true) || it.url.contains(query, true)) }
    val running = items.count { it.isActive }
    val later = items.count { it.status == Status.LATER || it.status == Status.PAUSED }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val chosen = items.filter { it.id in selected }
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, tr("Cancel selection")) } },
                    title = { Text("${selected.size} selected", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, tr(if (selected.size == shown.size) "Deselect all" else "Select all"))
                        }
                        if (chosen.any { it.isActive }) IconButton(onClick = { chosen.filter { it.isActive }.forEach { Downloads.pause(it.id) } }) { Icon(Icons.Outlined.Pause, tr("Pause")) }
                        if (chosen.any { it.status in listOf(Status.PAUSED, Status.LATER, Status.FAILED) }) IconButton(onClick = {
                            chosen.filter { it.status in listOf(Status.PAUSED, Status.LATER, Status.FAILED) }.forEach { Downloads.retry(it.id) }
                        }) { Icon(Icons.Outlined.PlayArrow, tr("Start")) }
                        if (chosen.size == 1) IconButton(onClick = { ui.detailId = chosen.first().id; selected = emptySet() }) { Icon(Icons.Outlined.Info, tr("Details")) }
                        IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Outlined.Delete, tr("Delete")) }
                    },
                )
            } else if (searching) {
                TopAppBar(title = {
                    OutlinedTextField(query, { query = it }, placeholder = { Text(tr("Search downloads")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }, actions = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Outlined.Close, tr("Close search")) } })
            } else {
                TopAppBar(
                    title = { Text(tr("Downloads"), fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, tr("Search")) }
                        if (running > 0) IconButton(onClick = { Downloads.pauseAll() }) { Icon(Icons.Outlined.Pause, tr("Pause all")) }
                        else if (later > 0) IconButton(onClick = { Downloads.startAll() }) { Icon(Icons.Outlined.PlayArrow, tr("Start all")) }
                    },
                )
            }
            SecondaryTabRow(selectedTabIndex = view.ordinal) {
                View.entries.forEach { v ->
                    val count = items.count { when (v) { View.ALL -> true; View.UNFINISHED -> it.status != Status.COMPLETE; View.FINISHED -> it.status == Status.COMPLETE } }
                    Tab(selected = view == v, onClick = { view = v }, text = { Text("${tr(v.label)} ($count)") })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text(tr("All types")) })
                FileCategory.entries.forEach { c ->
                    val n = inView.count { it.category == c }
                    if (n > 0 || category == c) FilterChip(
                        selected = category == c, onClick = { category = if (category == c) null else c },
                        label = { Text("${tr(c.label)} $n") },
                        leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (waitingForWifi) Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("Waiting for Wi-Fi. Turn off \"Wi-Fi only\" in Settings to use mobile data.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (shown.isEmpty()) {
                Empty(items.isEmpty(), onAdd = { ui.addText = "" }, onOpenBrowser = onOpenBrowser)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { item ->
                        DownloadRow(
                            item, selected = item.id in selected, selecting = selecting,
                            onClick = { if (selecting) selected = selected.toggle(item.id) else ui.detailId = item.id },
                            onLongClick = { selected = selected.toggle(item.id) },
                        )
                    }
                }
            }
        }
        if (!selecting) FloatingActionButton(onClick = { ui.addText = "" }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Outlined.Add, tr("Add download"))
        }
    }

    if (confirmRemove) {
        val chosen = items.filter { it.id in selected }
        var alsoFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (chosen.size == 1) "Remove this download?" else "Remove ${chosen.size} downloads?") },
            text = {
                Column {
                    Text(tr("They are removed from the list."), style = MaterialTheme.typography.bodyMedium)
                    if (chosen.any { it.status == Status.COMPLETE && it.contentUri != null }) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(alsoFiles, { alsoFiles = it }); Text(tr("Also delete the downloaded files"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chosen.forEach { Downloads.remove(it.id, alsoFiles) }; selected = emptySet(); confirmRemove = false }) { Text(tr("Remove")) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

@Composable
private fun Empty(nothing: Boolean, onAdd: () -> Unit, onOpenBrowser: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(tr(if (nothing) "No downloads yet" else "Nothing here"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (nothing) tr("Paste a link, share one from Chrome with \"Download with HTorrent\", or browse with the built-in browser.")
            else tr("No downloads match."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (nothing) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("Add a link")) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBrowser) { Icon(Icons.Outlined.Language, null); Spacer(Modifier.width(6.dp)); Text(tr("Open the browser")) }
        }
    }
}

fun statusText(item: DownloadItem): String = when (item.status) {
    Status.DOWNLOADING -> listOf(
        if (item.size != null) "${Format.size(item.written)} of ${Format.size(item.size)}" else Format.size(item.written),
        Format.speed(item.speed),
        Format.eta(item.etaSeconds).let { if (it.isEmpty()) "" else "$it left" },
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    Status.QUEUED -> tr("Waiting")
    Status.PAUSED -> tr("Paused") + (item.size?.let { " · ${Format.size(item.written)} of ${Format.size(it)}" } ?: "")
    Status.LATER -> tr("Download later") + (item.size?.let { " · ${Format.size(it)}" } ?: "")
    Status.COMPLETE -> Format.size(item.size ?: item.written) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.finishedAt ?: item.addedAt))
    Status.FAILED -> item.error ?: tr("Failed")
}

@Composable
fun DownloadRow(item: DownloadItem, selected: Boolean = false, selecting: Boolean = false, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val color = categoryColor(item.category)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, tr("Selected"), tint = MaterialTheme.colorScheme.onPrimary)
            else Icon(categoryIcon(item.category), null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (item.status == Status.DOWNLOADING || item.status == Status.PAUSED) {
                Spacer(Modifier.height(5.dp))
                if (item.size != null) LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                statusText(item), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (item.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (!selecting) when (item.status) {
            Status.DOWNLOADING, Status.QUEUED -> IconButton(onClick = { Downloads.pause(item.id) }) { Icon(Icons.Outlined.Pause, tr("Pause")) }
            Status.PAUSED, Status.LATER -> IconButton(onClick = { Downloads.resume(item.id) }) { Icon(Icons.Outlined.PlayArrow, tr("Resume")) }
            Status.FAILED -> IconButton(onClick = { Downloads.retry(item.id) }) { Icon(Icons.Outlined.Refresh, tr("Try again")) }
            Status.COMPLETE -> {}
        }
    }
}

/** Details of one download, like the desktop progress window: every connection's progress and all actions. */
@Composable
fun DetailSheet(item: DownloadItem, onDismiss: () -> Unit, message: (String) -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(false) }
    var newLink by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = categoryColor(item.category)
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(categoryIcon(item.category), null, tint = color)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.status != Status.COMPLETE && item.size != null) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            }
            InfoRow("Status", when (item.status) {
                Status.DOWNLOADING -> "Downloading"; Status.QUEUED -> "Waiting"; Status.PAUSED -> "Paused"
                Status.LATER -> "Download later"; Status.COMPLETE -> "Complete"; Status.FAILED -> "Failed"
            })
            InfoRow("Size", item.size?.let { Format.size(it) } ?: "Unknown")
            if (item.status != Status.COMPLETE) InfoRow("Downloaded", Format.size(item.written) + (item.size?.let { " (${(item.progress * 100).toInt()} %)" } ?: ""))
            if (item.status == Status.DOWNLOADING) {
                InfoRow("Speed", Format.speed(item.speed).ifEmpty { "…" })
                InfoRow("Time left", Format.eta(item.etaSeconds).ifEmpty { "…" })
            }
            InfoRow("Resume", if (item.resumable) "Yes" else "No")
            item.torrent?.let { t ->
                fun count(n: Int, total: Int) = if (total >= 0) "$n ($total)" else "$n"
                InfoRow("Files", "${t.selectedFiles.size} of ${t.files.count { !it.pad }}")
                if (item.status == Status.DOWNLOADING) {
                    InfoRow("Seeds", count(t.seeds, t.seedsTotal))
                    InfoRow("Peers", count((t.peers - t.seeds).coerceAtLeast(0), t.peersTotal))
                    InfoRow("Upload speed", Format.speed(t.uploadSpeed).ifEmpty { "0" })
                }
                if (t.infoHash.isNotEmpty()) InfoRow("Hash", t.infoHash)
            }
            item.quality?.let { InfoRow("Quality", it) }
            InfoRow("Type", item.category.label)
            item.savedPath?.let { InfoRow("Saved to", it) }
            InfoRow("Added", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.addedAt)))
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            item.state?.takeIf { it.segments.size > 1 && it.size != null && item.status != Status.COMPLETE }?.let { st ->
                Text(tr("Progress by connection"), style = MaterialTheme.typography.labelLarge)
                val track = MaterialTheme.colorScheme.surfaceVariant
                val fill = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
                    drawRect(track)
                    val total = st.size!!.toFloat()
                    st.segments.forEach { s ->
                        val x = s.start / total * size.width
                        val w = s.written / total * size.width
                        drawRect(fill, Offset(x, 0f), Size(w.coerceAtLeast(1f), size.height))
                        drawRect(Color.Black.copy(alpha = 0.25f), Offset(x, 0f), Size(1.5f, size.height))
                    }
                }
                Text("${st.segments.count { !it.isComplete }} of ${st.segments.size} parts still downloading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.status) {
                    Status.DOWNLOADING, Status.QUEUED -> FilledTonalButton(onClick = { Downloads.pause(item.id) }) { Text(tr("Pause")) }
                    Status.PAUSED, Status.LATER -> Button(onClick = { Downloads.resume(item.id) }) { Text(tr("Resume")) }
                    Status.FAILED -> Button(onClick = { Downloads.retry(item.id) }) { Text(tr("Try again")) }
                    Status.COMPLETE -> {
                        Button(onClick = { open(context, item, message) }) { Text(tr("Open")) }
                        FilledTonalButton(onClick = { share(context, item, message) }) { Text(tr("Share")) }
                    }
                }
                if (item.status != Status.COMPLETE && !item.isTorrent) OutlinedButton(onClick = { refresh = true }) { Text(tr("Refresh link")) }
                OutlinedButton(onClick = { copy(context, item.url); message("Link copied") }) { Text(tr("Copy link")) }
                OutlinedButton(onClick = { confirmDelete = true }) { Text(tr("Delete")) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete) {
        var alsoFile by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Remove from the list?")) },
            text = {
                Column {
                    Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.status == Status.COMPLETE && item.contentUri != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(alsoFile, { alsoFile = it }); Text(tr("Also delete the downloaded file"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); Downloads.remove(item.id, alsoFile) }) { Text(tr("Remove")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
    if (refresh) {
        AlertDialog(
            onDismissRequest = { refresh = false },
            title = { Text(tr("Refresh link")) },
            text = {
                Column {
                    Text(tr("Paste a fresh link to the same file. The download continues where it stopped."), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(newLink, { newLink = it }, singleLine = true, placeholder = { Text(tr("https://…")) })
                }
            },
            confirmButton = {
                TextButton(enabled = Links.isWebLink(newLink.trim()), onClick = {
                    Downloads.refreshLink(item.id, newLink.trim(), null); refresh = false; message("Link updated")
                }) { Text(tr("Continue")) }
            },
            dismissButton = { TextButton(onClick = { refresh = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun open(context: android.content.Context, item: DownloadItem, message: (String) -> Unit) {
    val uri = item.contentUri?.let { Uri.parse(it) } ?: return message("The file is not available")
    val type = context.contentResolver.getType(uri) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, tr("Open with"))) }.onFailure { message("No app can open this file") }
}

private fun share(context: android.content.Context, item: DownloadItem, message: (String) -> Unit) {
    val uri = item.contentUri?.let { Uri.parse(it) } ?: return message("The file is not available")
    val intent = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(uri) ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, tr("Share"))) }
}
