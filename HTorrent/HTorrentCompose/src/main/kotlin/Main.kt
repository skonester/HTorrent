import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.image.BufferedImage
import java.io.File
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

fun main() {
    // Swing dialogs opened with a null parent (file pickers, prompts, errors) are owned by this shared frame.
    appIconImage?.let { image -> SwingUtilities.invokeLater { JOptionPane.getRootFrame().iconImage = image } }
    application {
        val engine = remember { TorrentEngine(Paths.get(System.getProperty("user.home"), "Downloads", "HTorrent")) }
        androidx.compose.runtime.DisposableEffect(engine) { onDispose { engine.close() } }
        val search = remember { SearchController(onDownload = engine::addMagnet) }
        androidx.compose.runtime.DisposableEffect(search) { onDispose { search.close() } }
        val appIcon = remember { appIconImage?.let { BitmapPainter(it.toComposeImageBitmap()) } }
        var searchOpen by remember { mutableStateOf(false) }
        var searchRaise by remember { mutableStateOf(0) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "HTorrent v1.0.0",
            icon = appIcon,
            state = rememberWindowState(width = 984.dp, height = 521.dp)
        ) {
            HTorrentTheme {
                val torrents = engine.items
                val statusText = buildStatusText(torrents) + " | " + engine.engineStatus

                AttractorWindow(
                    downloadPath = engine.downloadPath,
                    torrents = torrents,
                    statusText = statusText,
                    onSearch = { searchOpen = true; searchRaise++ },
                    onAddTorrent = {
                        val file = pickFile("Select Torrent Files")
                        if (file != null) engine.addTorrentFile(file)
                    },
                    onAddMagnet = {
                        val link = JOptionPanePrompt("Enter Magnet Link")
                        if (!link.isNullOrBlank()) engine.addMagnet(link)
                    },
                    onStart = { engine.startAll() },
                    onFiles = { engine.showFiles(it) },
                    onLimits = { engine.showLimits() },
                    onPause = { engine.pauseAll() },
                    onStop = { engine.stopAll() },
                    onRemove = { engine.removeAll() },
                    onChangePath = {
                        val chosen = pickDirectory("Select Download Folder")
                        if (chosen != null) engine.setDownloadPath(chosen.toPath())
                    }
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
    }
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
    statusText: String,
    onAddTorrent: () -> Unit,
    onAddMagnet: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    onChangePath: () -> Unit,
    onFiles: (String) -> Unit = {},
    onLimits: () -> Unit = {},
    onSearch: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGray)
    ) {
        ToolStrip(
            buttons = listOf(
                "Add Torrent" to onAddTorrent,
                "Add Magnet" to onAddMagnet,
                "Start" to onStart,
                "Pause" to onPause,
                "Stop" to onStop,
                "Remove" to onRemove,
                "Change Path" to onChangePath,
                "Speed Limits" to onLimits,
                "Search Torrents" to onSearch
            )
        )

        Text(
            text = "Download Path: $downloadPath  |  Click a torrent for files and streaming",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = TextDark
            ),
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp)
        )

        TablePanel(
            torrents = torrents,
            onFiles = onFiles,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

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

@Composable
private fun ToolStrip(
    buttons: List<Pair<String, () -> Unit>>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(25.dp)
            .background(PanelGray)
            .border(BorderStroke(0.dp, BorderGray)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEachIndexed { index, (text, action) ->
            if (index == 2 || index == 5 || index == 8) {
                Spacer(modifier = Modifier.width(8.dp))
            }

            ToolButton(
                text = text,
                onClick = action,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
internal fun ToolButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(22.dp),
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

@Composable
private fun TablePanel(
    torrents: List<TorrentRow>,
    onFiles: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val columnWidths = listOf(350.dp, 100.dp, 120.dp, 120.dp, 120.dp, 80.dp)

    Surface(
        modifier = modifier
            .width(960.dp)
            .height(420.dp)
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clickable { onFiles(row.id) }
                        .background(if (rowIndex % 2 == 0) Color(0xFFFFFFFF) else Color(0xFFF8F8F8)),
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
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

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

private fun pickFile(title: String): File? {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = true
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.firstOrNull()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun pickDirectory(title: String): File? {
    return try {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        val result = chooser.showOpenDialog(null)
        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    } catch (_: Exception) {
        null
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
            statusText = "Active: 0/0 | ? 0 B/s | ? 0 B/s",
            onAddTorrent = {},
            onAddMagnet = {},
            onStart = {},
            onPause = {},
            onStop = {},
            onRemove = {},
            onChangePath = {}
        )
    }
}
