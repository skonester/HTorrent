import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

internal val BackgroundGray = Color(0xFFF1F1F1)
internal val PanelGray = Color(0xFFE6E6E6)
internal val BorderGray = Color(0xFFC5C5C5)
internal val TextDark = Color(0xFF1F1F1F)
internal val HeaderText = Color(0xFF3A3A3A)
internal val ActiveGreen = Color(0xFF008000)
internal val ActiveBlue = Color(0xFF0000B0)
internal val StoppedGray = Color(0xFF808080)
internal val ErrorRed = Color(0xFFB22222)

// Same artwork as icon/favicon.ico (used for the .exe); the JVM cannot decode .ico itself.
private val appIconImage: BufferedImage? = runCatching {
    Thread.currentThread().contextClassLoader.getResourceAsStream("htorrent-icon.png")?.use(ImageIO::read)
}.getOrNull()
private val haliteToolbarImage: ImageBitmap? = runCatching {
    Thread.currentThread().contextClassLoader.getResourceAsStream("halite-toolbar.bmp")?.use(ImageIO::read)?.toComposeImageBitmap()
}.getOrNull()

fun main(args: Array<String>) {
    CrashLog.install()
    val bridge = try {
        InstanceBridge.open(Path.of(System.getProperty("user.home"), ".htorrent"), args.toList())
    } catch (error: Exception) {
        CrashLog.record("instance startup", error)
        JOptionPane.showMessageDialog(null, error.message, "HTorrent", JOptionPane.ERROR_MESSAGE)
        return
    } ?: return
    // Swing dialogs opened with a null parent (file pickers, prompts, errors) are owned by this shared frame.
    appIconImage?.let { image -> SwingUtilities.invokeLater { JOptionPane.getRootFrame().iconImage = image } }
    try { application {
        val engine = remember { TorrentEngine(Paths.get(System.getProperty("user.home"), "Downloads", "HTorrent")) }
        androidx.compose.runtime.DisposableEffect(engine) {
            engine.start()
            onDispose { engine.close() }
        }
        var raiseMain by remember { mutableStateOf(0) }
        androidx.compose.runtime.DisposableEffect(bridge, engine) {
            bridge.attach { launchArgs -> SwingUtilities.invokeLater {
                launchArgs.forEach { arg ->
                    if (arg.startsWith("magnet:", ignoreCase = true)) engine.addMagnet(arg)
                    else File(arg).takeIf { it.isFile && it.extension.equals("torrent", ignoreCase = true) }?.let(engine::addTorrentFile)
                }
                raiseMain++
            } }
            onDispose { }
        }
        val search = remember { SearchController(onDownload = engine::addMagnet) }
        androidx.compose.runtime.DisposableEffect(search) { onDispose { search.close() } }
        val appIcon = remember { appIconImage?.let { BitmapPainter(it.toComposeImageBitmap()) } }
        var searchOpen by remember { mutableStateOf(false) }
        var searchRaise by remember { mutableStateOf(0) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "HTorrent v1.0.2",
            icon = appIcon,
            state = rememberWindowState(width = 1180.dp, height = 700.dp)
        ) {
            LaunchedEffect(raiseMain) { if (raiseMain > 0) window.toFront() }
            HTorrentTheme {
                val torrents = engine.items
                val statusText = buildStatusText(torrents) + " | " + engine.engineStatus

                AttractorWindow(
                    downloadPath = engine.downloadPath,
                    torrents = torrents,
                    detail = engine.detail,
                    statusText = statusText,
                    onSearch = { searchOpen = true; searchRaise++ },
                    onAddTorrent = {
                        pickFiles("Select Torrent Files").forEach(engine::addTorrentFile)
                    },
                    onAddMagnet = {
                        val link = JOptionPanePrompt("Enter Magnet Link")
                        if (!link.isNullOrBlank()) engine.addMagnet(link)
                    },
                    onStart = engine::start,
                    onInspect = engine::inspect,
                    onSelectFile = engine::selectFile,
                    onStream = engine::streamFile,
                    onRecheck = engine::recheck,
                    onSettings = { showSettingsDialog(engine.settings, engine::updateSettings) },
                    onPause = engine::pause,
                    onStop = engine::stop,
                    onRemove = { id ->
                        if (confirmRemoval("Remove the selected torrent from HTorrent? Downloaded files will stay on disk.")) engine.remove(id)
                    },
                    onStartAll = engine::startAll,
                    onPauseAll = engine::pauseAll,
                    onStopAll = engine::stopAll,
                    onOpenFolder = engine::openDownloadFolder
                )
            }
        }
        if (searchOpen) {
            Window(
                onCloseRequest = { searchOpen = false },
                title = "HTorrent - Search",
                icon = appIcon,
                state = rememberWindowState(width = 900.dp, height = 600.dp)
            ) {
                LaunchedEffect(searchRaise) { window.toFront() }
                HTorrentTheme { SearchScreen(search) }
            }
        }
    } } catch (error: Throwable) {
        CrashLog.record("application", error)
        throw error
    } finally { bridge.close() }
}

@Composable
private fun HTorrentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = Color(0xFF2D2D2D),
            background = BackgroundGray,
            surface = BackgroundGray,
            onPrimary = Color.White,
            onSurface = TextDark
        ),
        typography = MaterialTheme.typography,
        content = content
    )
}

private fun buildStatusText(torrents: List<TorrentRow>): String {
    val activeCount = torrents.count { it.status.lowercase(Locale.getDefault()) in listOf("downloading", "seeding", "metadata", "initializing") }
    val totalDown = torrents.sumOf { it.downSpeed }
    val totalUp = torrents.sumOf { it.upSpeed }
    return "Active: $activeCount/${torrents.size} | Down ${formatBytes(totalDown)}/s | Up ${formatBytes(totalUp)}/s"
}

@Composable
private fun AttractorWindow(
    downloadPath: String,
    torrents: List<TorrentRow>,
    detail: TorrentDetail?,
    statusText: String,
    onAddTorrent: () -> Unit,
    onAddMagnet: () -> Unit,
    onStart: (String) -> Unit,
    onPause: (String) -> Unit,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onInspect: (String?) -> Unit,
    onSelectFile: (String, Int, Boolean) -> Unit,
    onStream: (String, Int) -> Unit,
    onRecheck: (String) -> Unit,
    onSettings: () -> Unit,
    onStartAll: () -> Unit = {},
    onPauseAll: () -> Unit = {},
    onStopAll: () -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onSearch: () -> Unit = {}
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf("Overview") }
    val selected = torrents.firstOrNull { it.id == selectedId }
    fun select(id: String) { selectedId = id; onInspect(id) }
    fun openFiles(id: String) { select(id); activeTab = "Files" }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
    ) {
        ToolStrip(
            buttons = listOf(
                ToolbarCommand("Add Torrent", 1, true, onAddTorrent),
                ToolbarCommand("Add Magnet", 2, true, onAddMagnet),
                ToolbarCommand("Start", 5, selected != null) { selected?.id?.let(onStart) },
                ToolbarCommand("Pause", 6, selected != null) { selected?.id?.let(onPause) },
                ToolbarCommand("Stop", 7, selected != null) { selected?.id?.let(onStop) },
                ToolbarCommand("Remove", 8, selected != null) { selected?.id?.let(onRemove) },
                ToolbarCommand("Files", 0, selected != null) { selected?.id?.let(::openFiles) },
                ToolbarCommand("Search", null, true, onSearch)
            ),
            hasTorrents = torrents.isNotEmpty(),
            onStartAll = onStartAll,
            onPauseAll = onPauseAll,
            onStopAll = onStopAll,
            onSettings = onSettings
        )

        Text(
            text = "Download Path: $downloadPath  |  Select a torrent for details; double-click for files",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = TextDark
            ),
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp)
        )

        TablePanel(
            torrents = torrents,
            selectedId = selectedId,
            onSelect = ::select,
            onFiles = ::openFiles,
            onStart = onStart,
            onPause = onPause,
            onStop = onStop,
            onRemove = onRemove,
            onRecheck = onRecheck,
            onOpenFolder = onOpenFolder,
            modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
        )

        TorrentDetails(selected, detail?.takeIf { it.id == selectedId }, activeTab, { activeTab = it },
            onSelectFile, onStream, onRecheck, onOpenFolder)

        StatusBar(statusText)
    }
}

@Composable
internal fun StatusBar(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Color(0xFFF6F6F6))
            .border(BorderStroke(1.dp, BorderGray)),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = TextDark
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private data class ToolbarCommand(val text: String, val iconIndex: Int?, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun ToolStrip(
    buttons: List<ToolbarCommand>,
    hasTorrents: Boolean,
    onStartAll: () -> Unit,
    onPauseAll: () -> Unit,
    onStopAll: () -> Unit,
    onSettings: () -> Unit
) {
    var allMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(PanelGray)
            .border(BorderStroke(0.dp, BorderGray)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEachIndexed { index, button ->
            if (index == 2 || index == 6 || index == 7) {
                Spacer(modifier = Modifier.width(8.dp))
            }

            ToolButton(
                text = button.text,
                onClick = button.onClick,
                enabled = button.enabled,
                iconIndex = button.iconIndex,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        Box {
            ToolButton("All Torrents ▾", onClick = { allMenu = true }, enabled = hasTorrents)
            DropdownMenu(expanded = allMenu, onDismissRequest = { allMenu = false }) {
                listOf("Start All" to onStartAll, "Pause All" to onPauseAll, "Stop All" to onStopAll).forEach { (label, action) ->
                    DropdownMenuItem(onClick = { allMenu = false; action() }) { Text(label) }
                }
            }
        }
        ToolButton("Settings...", onClick = onSettings, iconIndex = 4)
    }
}

@Composable
internal fun ToolButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconIndex: Int? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (iconIndex == null) 22.dp else 30.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.Transparent,
            contentColor = Color.Black,
            disabledBackgroundColor = Color.Transparent
        ),
        elevation = null,
        border = null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        if (iconIndex != null && haliteToolbarImage != null) {
            Canvas(Modifier.size(22.dp)) {
                drawImage(
                    image = haliteToolbarImage,
                    srcOffset = IntOffset(iconIndex * 22, 0),
                    srcSize = IntSize(22, 22),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = if (enabled) TextDark else StoppedGray
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TablePanel(
    torrents: List<TorrentRow>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onFiles: (String) -> Unit = {},
    onStart: (String) -> Unit = {},
    onPause: (String) -> Unit = {},
    onStop: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onRecheck: (String) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val columnWidths = listOf(350.dp, 100.dp, 120.dp, 120.dp, 120.dp, 80.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderGray)),
        color = Color.White
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFFEAEAEA)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                columnWidths.forEachIndexed { index, width ->
                    Box(
                        modifier = Modifier.width(width).padding(start = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        val header = listOf("Name", "Progress", "Down Speed", "Up Speed", "Status", "Peers")[index]
                        Text(
                            text = header,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 12.sp,
                                color = HeaderText,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            torrents.forEachIndexed { rowIndex, row ->
                val rowColor = when (row.status.lowercase(Locale.getDefault())) {
                    "downloading", "hashing", "metadata" -> ActiveGreen
                    "seeding" -> ActiveBlue
                    "stopped", "paused" -> StoppedGray
                    "error" -> ErrorRed
                    else -> Color.Black
                }

                ContextMenuArea(items = {
                    listOf(
                        ContextMenuItem("Start") { onSelect(row.id); onStart(row.id) },
                        ContextMenuItem("Pause") { onSelect(row.id); onPause(row.id) },
                        ContextMenuItem("Stop") { onSelect(row.id); onStop(row.id) },
                        ContextMenuItem("Recheck Files") { onSelect(row.id); onRecheck(row.id) },
                        ContextMenuItem("Files") { onSelect(row.id); onFiles(row.id) },
                        ContextMenuItem("Open Download Folder") { onSelect(row.id); onOpenFolder(row.id) },
                        ContextMenuItem("Remove Torrent") { onSelect(row.id); onRemove(row.id) }
                    )
                }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .background(when {
                                row.id == selectedId -> Color(0xFFDCEBFA)
                                rowIndex % 2 == 0 -> Color.White
                                else -> Color(0xFFF8F8F8)
                            })
                            .combinedClickable(
                                onClick = { onSelect(row.id) },
                                onDoubleClick = { onSelect(row.id); onFiles(row.id) }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        columnWidths.forEachIndexed { index, width ->
                            val value = when (index) {
                                0 -> row.name
                                1 -> "${String.format(Locale.US, "%.2f", row.progress)}%"
                                2 -> "${formatBytes(row.downSpeed)}/s"
                                3 -> "${formatBytes(row.upSpeed)}/s"
                                4 -> row.status
                                else -> row.peers.toString()
                            }

                            Box(
                                modifier = Modifier.width(width).padding(start = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = value,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 12.sp,
                                        color = rowColor
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TorrentDetails(
    row: TorrentRow?, detail: TorrentDetail?, activeTab: String, onTab: (String) -> Unit,
    onSelectFile: (String, Int, Boolean) -> Unit, onStream: (String, Int) -> Unit,
    onRecheck: (String) -> Unit, onOpenFolder: (String) -> Unit
) {
    val tabs = listOf("Overview", "Files", "Trackers", "Peers")
    Column(
        Modifier.fillMaxWidth().height(235.dp).padding(horizontal = 12.dp, vertical = 6.dp)
            .border(BorderStroke(1.dp, BorderGray)).background(Color.White)
    ) {
        if (row == null) {
            Text("Select a torrent to see its files, trackers, and peers", modifier = Modifier.padding(10.dp), fontSize = 12.sp, color = StoppedGray)
        } else {
            Text(row.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TabRow(selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0), backgroundColor = PanelGray,
                modifier = Modifier.height(48.dp)) {
                tabs.forEach { tab -> Tab(selected = activeTab == tab, onClick = { onTab(tab) },
                    text = { Text(tab, fontSize = 12.sp) }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                when (activeTab) {
                    "Files" -> if (detail?.metadataReady != true) Text("Waiting for torrent metadata", fontSize = 12.sp)
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            detail.files.forEach { file ->
                                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(file.selected, onCheckedChange = { onSelectFile(row.id, file.index, it) }, modifier = Modifier.size(24.dp))
                                    Text(file.path, modifier = Modifier.weight(1f).padding(start = 6.dp), fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatBytes(file.size), fontSize = 12.sp, color = HeaderText)
                                    ToolButton("Stream", onClick = { onStream(row.id, file.index) }, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    "Trackers" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (detail?.trackers.isNullOrEmpty()) Text("No trackers listed for this torrent", fontSize = 12.sp)
                        else detail?.trackers.orEmpty().forEach { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    "Peers" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (detail?.peers.isNullOrEmpty()) Text("No peers connected", fontSize = 12.sp)
                        else detail?.peers.orEmpty().forEach { Text(it, fontSize = 12.sp) }
                    }
                    else -> Column {
                        Text("${row.status}  •  ${String.format(Locale.US, "%.2f", row.progress)}%  •  ${row.peers} peers", fontSize = 12.sp)
                        Text("Down ${formatBytes(row.downSpeed)}/s  •  Up ${formatBytes(row.upSpeed)}/s", fontSize = 12.sp)
                        Text("Transferred: ${formatBytes(row.downloaded)} down, ${formatBytes(row.uploaded)} up", fontSize = 12.sp)
                        Text("Folder: ${row.output}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            ToolButton("Open Folder", onClick = { onOpenFolder(row.id) })
                            ToolButton("Recheck Files", onClick = { onRecheck(row.id) }, enabled = detail?.metadataReady == true)
                        }
                    }
                }
            }
        }
    }
}

private fun confirmRemoval(message: String): Boolean = javax.swing.JOptionPane.showConfirmDialog(
    null, message, "Remove Torrent", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE
) == javax.swing.JOptionPane.YES_OPTION

internal fun formatBytes(bytes: Long): String {
    val sizes = listOf("B", "KB", "MB", "GB", "TB")
    var len = bytes.toDouble()
    var order = 0
    while (len >= 1024 && order < sizes.size - 1) {
        order++
        len /= 1024
    }
    return String.format(Locale.US, "%.2f %s", len, sizes[order])
}

private fun pickFiles(title: String): List<File> {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = true
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.toList()
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun JOptionPanePrompt(message: String): String? {
    return javax.swing.JOptionPane.showInputDialog(null, message, "Add Magnet", javax.swing.JOptionPane.PLAIN_MESSAGE)
}

@Preview
@Composable
private fun PreviewAttractorWindow() {
    MaterialTheme {
        AttractorWindow(
            downloadPath = "C:\\Users\\admin\\Documents\\HTorrentDownloads",
            torrents = emptyList(),
            detail = null,
            statusText = "Active: 0/0 | ? 0 B/s | ? 0 B/s",
            onAddTorrent = {},
            onAddMagnet = {},
            onStart = {},
            onPause = {},
            onStop = {},
            onRemove = {},
            onInspect = {},
            onSelectFile = { _, _, _ -> },
            onStream = { _, _ -> },
            onRecheck = {},
            onSettings = {}
        )
    }
}
