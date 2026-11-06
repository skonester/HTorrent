@file:OptIn(ExperimentalMaterial3Api::class)

package com.htorrent.mobile.ui

import com.htorrent.mobile.video.VideoEngine
import com.htorrent.mobile.core.tr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.htorrent.mobile.BuildConfig
import com.htorrent.mobile.browser.Browser
import com.htorrent.mobile.data.Downloads
import com.htorrent.mobile.service.Scheduler

@Composable
fun SettingsScreen(browser: Browser, message: (String) -> Unit) {
    val s = Downloads.settings
    val context = LocalContext.current
    var connections by remember { mutableFloatStateOf(s.connections.toFloat()) }
    var maxActive by remember { mutableFloatStateOf(s.maxActive.toFloat()) }
    var limit by remember { mutableStateOf(if (s.speedLimitKB > 0) s.speedLimitKB.toString() else "") }
    var wifiOnly by remember { mutableStateOf(s.wifiOnly) }
    var ask by remember { mutableStateOf(s.askBeforeDownload) }
    var folders by remember { mutableStateOf(s.categoryFolders) }
    var schedule by remember { mutableStateOf(s.scheduleEnabled) }
    var minutes by remember { mutableStateOf(s.scheduleMinutes) }
    var pickTime by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var block3p by remember { mutableStateOf(s.blockThirdPartyCookies) }
    var home by remember { mutableStateOf(s.homePage) }
    var engine by remember { mutableStateOf(s.searchEngine) }
    var pickEngine by remember { mutableStateOf(false) }
    var notifyDone by remember { mutableStateOf(s.notifyFinished) }
    var notifyFail by remember { mutableStateOf(s.notifyFailed) }
    var treeName by remember { mutableStateOf(if (s.saveTree != null) s.saveTreeName else "") }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val kept = runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (kept) {
                s.saveTree?.let { old -> if (old != uri.toString()) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) } }
                val name = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').ifEmpty { "Chosen folder" }
                val volume = Uri.decode(uri.lastPathSegment.orEmpty()).substringBefore(':')
                s.saveTree = uri.toString()
                s.saveTreeName = if (volume == "primary") name else "$name (SD card or USB)"
                treeName = s.saveTreeName
            } else message("This folder can't be used. Choose another one.")
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { engineVersion = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoEngine.version() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(tr("Settings"), fontWeight = FontWeight.SemiBold) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section("Downloads")
            Text("Connections per download: ${connections.toInt()}", style = MaterialTheme.typography.bodyLarge)
            Slider(connections, { connections = it }, valueRange = 1f..32f, steps = 30, onValueChangeFinished = { s.connections = connections.toInt() })
            Hint("More connections are faster on most servers. Each part is at least 512 KB.")
            Spacer(Modifier.height(8.dp))
            Text("Downloads at the same time: ${maxActive.toInt()}", style = MaterialTheme.typography.bodyLarge)
            Slider(maxActive, { maxActive = it }, valueRange = 1f..6f, steps = 4, onValueChangeFinished = { s.maxActive = maxActive.toInt(); Downloads.pump() })
            OutlinedTextField(
                value = limit,
                onValueChange = { v -> limit = v.filter { it.isDigit() }.take(7); Downloads.setSpeedLimit(limit.toLongOrNull() ?: 0) },
                label = { Text(tr("Speed limit (KB/s, empty = no limit)")) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Toggle("Wi-Fi only", "Wait for Wi-Fi or another unmetered network before downloading.", wifiOnly) {
                wifiOnly = it; s.wifiOnly = it; Downloads.pump()
            }
            Toggle("Ask before downloading", "Show the download window when the browser starts a download.", ask) { ask = it; s.askBeforeDownload = it }
            Toggle("Folders by type", "Save into HTorrent/Video, /Music, /Documents…", folders) { folders = it; s.categoryFolders = it }
            Text(tr("Save to"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Hint(if (treeName.isEmpty()) "Downloads/HTorrent on this phone" else treeName)
            Row {
                TextButton(onClick = { pickFolder.launch(null) }) { Text(tr("Choose folder (SD card, USB…)")) }
                if (treeName.isNotEmpty()) TextButton(onClick = {
                    s.saveTree?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
                    s.saveTree = null; s.saveTreeName = ""; treeName = ""
                }) { Text(tr("Use Downloads")) }
            }

            Section("Notifications")
            Toggle("When a download finishes", "A notification you can tap to open the file.", notifyDone) { notifyDone = it; s.notifyFinished = it }
            Toggle("When a download fails", "Says what went wrong.", notifyFail) { notifyFail = it; s.notifyFailed = it }
            Hint("While downloads run, Android shows one quiet notification with their progress and Pause all; it can't be turned off while the app downloads in the background.")
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)) }
            }) { Text(tr("Sounds and other notification settings")) }

            Section("Scheduler")
            Toggle("Start downloads kept for later", "Every day at ${"%02d:%02d".format(minutes / 60, minutes % 60)}", schedule) {
                schedule = it; s.scheduleEnabled = it; Scheduler.apply(context)
            }
            if (schedule) {
                TextButton(onClick = { pickTime = true }) { Text(tr("Change time")) }
                if (!Scheduler.canExact(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Hint("Android may delay scheduled downloads or ask you to tap a notification. Allow alarms for exact timing.")
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(SystemSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                    }) { Text(tr("Allow alarms")) }
                }
            }

            Section("Browser and privacy")
            Toggle("Block third-party cookies", "Sites can't track you across other sites.", block3p) {
                block3p = it; s.blockThirdPartyCookies = it; browser.applyPrivacy()
            }
            Text(tr("Search engine: ") + com.htorrent.mobile.core.SearchEngines.of(engine).name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { pickEngine = true }) { Text(tr("Change search engine")) }
            if (pickEngine) AlertDialog(
                onDismissRequest = { pickEngine = false },
                title = { Text(tr("Search engine")) },
                text = {
                    Column {
                        com.htorrent.mobile.core.SearchEngines.all.forEach { e ->
                            Row(Modifier.fillMaxWidth().clickable { engine = e.id; s.searchEngine = e.id; pickEngine = false }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = engine == e.id, onClick = { engine = e.id; s.searchEngine = e.id; pickEngine = false })
                                Text(e.name)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickEngine = false }) { Text(tr("Close")) } },
            )
            OutlinedTextField(home, { home = it; if (com.htorrent.mobile.core.Links.isWebLink(it)) s.homePage = it },
                label = { Text(tr("Home page")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            TextButton(onClick = { browser.clearData(); message("Browser data cleared") }) { Text(tr("Clear cookies, cache and history")) }
            TextButton(onClick = { confirmClear = true }) { Text(tr("Clear finished downloads from the list")) }
            Hint("No account, no ads, no tracking. Cookies and passwords for downloads are encrypted on this phone and sent only to their own site.")

            Section("Video engine")
            Hint("Saves videos from YouTube, Facebook, Instagram, TikTok, X and 1,000+ other sites (yt-dlp with ffmpeg, like the desktop apps). It updates itself once a day.")
            Text(tr("Version: ") + (engineVersion ?: "starting…"), style = MaterialTheme.typography.bodyMedium)
            TextButton(enabled = !updating, onClick = {
                updating = true
                Thread {
                    val result = runCatching { VideoEngine.updateNow() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        updating = false
                        engineVersion = VideoEngine.version()
                        message(if (result.isSuccess) (if (result.getOrNull() == "ALREADY_UP_TO_DATE") "The video engine is up to date" else "Video engine updated") else "Could not update now. Try again later.")
                    }
                }.start()
            }) { Text(tr(if (updating) "Checking…" else "Update video engine")) }

            Section("About")
            Text("HTorrent ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Hint("HTorrent for Android by Skonester, based on Nexonic Download Manager.")
            Hint("Free and open source by Shahrukh Khalid. Its own code is MIT or Apache-2.0; the app as a whole is GPL-3.0 because of the video engine.")
            TextButton(onClick = { openLink(context, "https://github.com/skonester/HTorrent") }) { Text(tr("Website")) }
            TextButton(onClick = { openLink(context, "https://github.com/skonester/HTorrent") }) { Text(tr("Source code and help")) }
            TextButton(onClick = { openLink(context, "https://github.com/skonester/HTorrent/issues/new") }) { Text(tr("Report a problem")) }
            TextButton(onClick = { showLicenses = true }) { Text(tr("Open-source licenses")) }
            Hint("Only download what you have the right to save. Copy-protected services are not supported.")
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLicenses) {
        val text = remember { licenseTexts(context) }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(tr("Open-source licenses")) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(tr("Close")) } },
        )
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(tr("Start at")) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    minutes = state.hour * 60 + state.minute; s.scheduleMinutes = minutes; Scheduler.apply(context); pickTime = false
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(tr("Cancel")) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear finished downloads?")) },
            text = { Text(tr("They are removed from the list. The files stay in your Downloads folder.")) },
            confirmButton = { TextButton(onClick = { Downloads.clearFinished(); confirmClear = false; message("List cleared") }) { Text(tr("Clear")) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(tr(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 10.dp))
}

@Composable
private fun Hint(text: String) {
    Text(tr(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Switch(value, onChange)
    }
}

/** The notices and license texts shipped in assets/licenses, NOTICES.txt first. */
private fun licenseTexts(context: android.content.Context): String {
    val names = runCatching { context.assets.list("licenses")?.toList() }.getOrNull().orEmpty()
        .sortedWith(compareBy({ it != "NOTICES.txt" }, { it }))
    return names.joinToString("\n\n" + "=".repeat(40) + "\n\n") { name ->
        runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
}
