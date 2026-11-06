@file:OptIn(ExperimentalMaterial3Api::class)

package com.htorrent.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.htorrent.mobile.browser.Offer
import com.htorrent.mobile.core.tr
import com.htorrent.mobile.core.BatchPattern
import com.htorrent.mobile.core.DownloadRequest
import com.htorrent.mobile.core.FileCategory
import com.htorrent.mobile.core.FileNames
import com.htorrent.mobile.core.Format
import com.htorrent.mobile.core.Http
import com.htorrent.mobile.core.HttpStatusException
import com.htorrent.mobile.core.Links
import com.htorrent.mobile.core.ProbeResult
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.data.NewDownload
import com.htorrent.mobile.data.Secrets
import com.htorrent.mobile.core.VideoChoice
import com.htorrent.mobile.core.VideoPages
import com.htorrent.mobile.video.VideoEngine
import com.htorrent.mobile.video.VideoPage
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The download window (like the desktop "Download File Info"): file name, size, type and whether it can resume,
 * with Start Download, Download Later or Cancel. Also takes pasted or shared links, several at once, and batch patterns.
 */
@Composable
fun AddDownloadSheet(offer: Offer?, text: String?, onDone: (String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var input by remember { mutableStateOf(offer?.url ?: text.orEmpty()) }
    var batch by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(offer?.fileName.orEmpty()) }
    var nameEdited by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var probing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Video pages (YouTube, Facebook, Instagram, TikTok…): the video engine reads the page instead of saving it.
    var isVideo by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<VideoPage?>(null) }
    var pick by remember { mutableStateOf(0) }

    val links: List<String> = remember(input, batch) {
        val l = if (batch) Links.split(input).flatMap { BatchPattern.expand(it, 2000) }.filter { Links.isWebLink(it) } else Links.split(input)
        l.distinct()
    }
    val single = links.singleOrNull()

    // Ask the server for the name and size of a single link.
    LaunchedEffect(single, user, password) {
        probe = null; problem = null; page = null; isVideo = false; pick = 0
        if (single == null) return@LaunchedEffect
        delay(350)
        if (VideoPages.isVideoSite(single)) {
            isVideo = true
        } else {
            probing = true
            val req = DownloadRequest(single, offer?.cookies?.takeIf { single == offer?.url }, offer?.referer, offer?.userAgent, user.ifEmpty { null }, password.ifEmpty { null })
            val result = withContext(Dispatchers.IO) { runCatching { Http.probe(req) } }
            probing = false
            result.onSuccess { p ->
                // A web page is never saved as a file: look for the video on it instead.
                if (VideoPages.isWebPage(p.contentType)) isVideo = true
                else { probe = p; if (!nameEdited) name = p.fileName }
            }.onFailure { e ->
                problem = when {
                    e is HttpStatusException && (e.code == 401 || e.code == 403) -> "The server asks for a login or refused the link (${e.code})."
                    e is HttpStatusException -> "The server answered ${e.code}."
                    else -> "Could not reach the server. You can still add it and try later."
                }
                if (!nameEdited && name.isEmpty()) name = FileNames.fromUrl(single)
            }
        }
        if (isVideo) {
            reading = true
            val read = withContext(Dispatchers.IO) { runCatching { VideoEngine.read(single, offer?.userAgent, offer?.referer) } }
            reading = false
            read.onSuccess { p ->
                page = p
                if (!nameEdited) name = VideoPages.fileName(p.title, p.choices.first().ext)
            }.onFailure { problem = (it as? com.htorrent.mobile.video.VideoException)?.message ?: VideoPages.engineError(it.javaClass.simpleName, it.message) }
        }
    }

    fun choose(i: Int) {
        val p = page ?: return
        pick = i
        if (!nameEdited) name = VideoPages.fileName(p.title, p.choices[i].ext)
        else name = name.substringBeforeLast('.', name) + "." + p.choices[i].ext
    }

    // A video can be added right away: while the page is still being read it downloads in the best quality and is named
    // after the video; problems then show on the download itself. Only a page that has no video can't be added.
    val canAdd = links.isNotEmpty() && !(single != null && isVideo && page == null && !reading)

    fun add(startNow: Boolean) {
        if (links.isEmpty()) return
        val secrets = Secrets(cookies = offer?.cookies, user = user.ifEmpty { null }, password = password.ifEmpty { null })
        val choice: VideoChoice? = page?.choices?.getOrNull(pick)
        links.forEach { u ->
            val same = single != null
            // Several links: video pages get the best quality, named after the video.
            val video = if (same && isVideo) choice ?: VideoPages.best else if (VideoPages.isVideoSite(u)) VideoPages.best else null
            Downloads.add(
                NewDownload(
                    url = u,
                    fileName = if (same && name.isNotBlank() && (video == null || page != null)) name else if (video != null) VideoPages.AUTO_NAME else FileNames.fromUrl(u),
                    size = if (video != null) video.size else if (same) probe?.size ?: offer?.size?.takeIf { it >= 0 } else null,
                    resumable = if (same) probe?.resumable ?: false else false,
                    referer = offer?.referer, userAgent = offer?.userAgent,
                    secrets = if (u == offer?.url || offer == null) secrets else Secrets(),
                    mime = if (same) probe?.contentType ?: offer?.mime else null,
                    quality = offer?.quality,
                    video = video,
                ),
                startNow,
            )
        }
        val what = if (links.size == 1) "Download" else "${links.size} downloads"
        onDone(if (startNow) "$what started" else "$what saved for later")
    }

    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr(if (isVideo) "Download video" else "Download file"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (offer == null) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text(tr("Links (one or more)")) },
                    placeholder = { Text(tr("https://…")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (clip.isNotBlank()) input = clip
                        }) { Icon(Icons.Outlined.ContentPaste, tr("Paste")) }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Batch pattern"), style = MaterialTheme.typography.bodyMedium)
                        Text(tr("file[01-50].jpg makes 50 links"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = batch, onCheckedChange = { batch = it })
                }
            } else {
                Text(offer.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            when {
                single != null && isVideo -> {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; nameEdited = true },
                        label = { Text(tr("File name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    page?.let { p ->
                        Text(tr("Quality"), fontWeight = FontWeight.SemiBold)
                        p.choices.forEachIndexed { i, c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(i) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = pick == i, onClick = { choose(i) })
                                Text(tr(c.label), modifier = Modifier.weight(1f))
                                c.size?.let { Text(Format.size(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        val audio = p.choices.getOrNull(pick)?.audioOnly == true
                        val folder = if (audio) "Music" else "Video"
                        InfoRow("Saved to", "Downloads/HTorrent" + if (Downloads.settings.categoryFolders) "/$folder" else "")
                        InfoRow("Resume", "Yes")
                    }
                    if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text(tr("Finding the video… You can start now: it downloads in the best quality."), style = MaterialTheme.typography.bodySmall)
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                single != null -> {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; nameEdited = true },
                        label = { Text(tr("File name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    val category = FileCategory.of(name)
                    InfoRow("Size", when {
                        probing -> "Checking…"
                        probe?.size != null -> Format.size(probe!!.size!!)
                        (offer?.size ?: -1) >= 0 -> Format.size(offer!!.size)
                        else -> "Unknown"
                    })
                    InfoRow("Type", category.label)
                    InfoRow("Saved to", "Downloads/HTorrent" + (if (Downloads.settings.categoryFolders) category.folder?.let { "/$it" } ?: "" else ""))
                    InfoRow("Resume", when { probing -> "…"; probe?.resumable == true -> "Yes"; probe != null -> "No (starts over if stopped)"; else -> "Unknown" })
                    offer?.quality?.let { InfoRow("Quality", it) }
                    if (probing) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text(tr("Asking the server…"), style = MaterialTheme.typography.bodySmall)
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                links.size > 1 -> {
                    Text("${links.size} links", fontWeight = FontWeight.SemiBold)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                        links.take(8).forEach { Text(FileNames.fromUrl(it), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (links.size > 8) Text("and ${links.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                input.isNotBlank() -> Text(tr("No web link found. Links start with http:// or https://"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            TextButton(onClick = { showLogin = !showLogin }) { Text(tr(if (showLogin) "Hide login" else "Needs a login?")) }
            if (showLogin) {
                OutlinedTextField(user, { user = it }, label = { Text(tr("User name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(tr("Password")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(tr("Sent only to this site, and kept encrypted on this phone."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onDone(null) }) { Text(tr("Cancel")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { add(false) }, enabled = canAdd) { Text(tr("Later")) }
                Button(onClick = { add(true) }, enabled = canAdd) { Text(tr("Start download")) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(96.dp)) { Text(tr(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        Text(tr(value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
