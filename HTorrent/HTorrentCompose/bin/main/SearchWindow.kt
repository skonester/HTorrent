import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.htorrent.search.Category
import com.htorrent.search.ProviderResult
import com.htorrent.search.TorrentDescription
import com.htorrent.search.TorrentQuery
import com.htorrent.search.TorrentSearch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.SwingUtilities

private val SelectedBlue = Color(0xFFCCE4F7)
private val HintGray = Color(0xFF8A8A8A)
private val ImdbId = Regex("tt\\d{5,}", RegexOption.IGNORE_CASE)
private const val SearchHint = "Type a title and press Enter. An IMDb id (e.g. tt0133093) also searches YTS and EZTV by id."

/** Search state lives here, outside the window, so results survive closing and reopening it. Mutated on the Swing EDT only. */
internal class SearchController(private val onDownload: (String) -> Unit) : AutoCloseable {
    private val search = TorrentSearch()
    private val resolver = Executors.newSingleThreadExecutor { task -> Thread(task, "htorrent-search-resolve").apply { isDaemon = true } }
    val providers = search.providers
    val enabled = mutableStateMapOf<String, Boolean>().apply { providers.forEach { put(it.name, it.enabledByDefault) } }
    var query by mutableStateOf("")
    var category by mutableStateOf(Category.ALL)
    var selected by mutableStateOf<TorrentDescription?>(null)
    var searching by mutableStateOf(false); private set
    var status by mutableStateOf(SearchHint); private set
    private val resultsState = mutableStateListOf<TorrentDescription>()
    val results: List<TorrentDescription> get() = resultsState
    private var generation = 0
    private var pending = emptyList<Future<*>>()

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) { status = SearchHint; return }
        val imdbId = ImdbId.matchEntire(text)?.value?.lowercase()
        val torrentQuery = if (imdbId != null) TorrentQuery(imdbId = imdbId, category = category) else TorrentQuery(content = text, category = category)
        val targets = search.providersFor(torrentQuery, enabled.filterValues { it }.keys)
        pending.forEach { it.cancel(true) }
        val current = ++generation
        resultsState.clear(); selected = null
        if (targets.isEmpty()) {
            searching = false
            status = "No ticked source handles this search. Tick more sources, change the category, or search by IMDb id for EZTV."
            return
        }
        searching = true
        status = "Searching ${targets.joinToString { it.name }}..."
        val finished = mutableListOf<String>()
        val errors = mutableListOf<String>()
        pending = search.search(torrentQuery, targets) { result ->
            SwingUtilities.invokeLater {
                if (current != generation) return@invokeLater
                when (result) {
                    is ProviderResult.Success -> resultsState.addAll(result.torrents)
                    is ProviderResult.Error -> errors += "${result.providerName} (${result.message})"
                }
                finished += result.providerName
                searching = finished.size < targets.size
                status = buildString {
                    append("${resultsState.size} results")
                    if (searching) append(" | waiting for ${targets.map { it.name }.filterNot { it in finished }.joinToString()}")
                    if (errors.isNotEmpty()) append(" | failed: ${errors.joinToString(", ")}")
                }
            }
        }
    }

    fun download(torrent: TorrentDescription) {
        torrent.magnetUrl?.takeIf { torrent.isResolved }?.let { add(torrent, it); return }
        status = "Fetching magnet link for ${torrent.title}..."
        resolver.execute {
            val result = runCatching { search.resolve(torrent) }
            SwingUtilities.invokeLater {
                val magnet = result.getOrNull()?.magnetUrl
                if (magnet.isNullOrBlank()) status = "Could not get a magnet link for ${torrent.title}: ${result.exceptionOrNull()?.message ?: "none listed"}"
                else add(torrent, magnet)
            }
        }
    }

    fun openInfoPage(torrent: TorrentDescription) {
        val url = torrent.infoUrl ?: return
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.onFailure { status = "Could not open browser: ${it.message}" }
    }

    private fun add(torrent: TorrentDescription, magnet: String) {
        onDownload(magnet)
        status = "Added to downloads: ${torrent.title}"
    }

    override fun close() {
        generation++
        search.close()
        resolver.shutdownNow()
    }
}

private enum class SortColumn(val title: String, val width: Dp?, val comparator: Comparator<TorrentDescription>) {
    NAME("Name", null, compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
    SIZE("Size", 90.dp, compareBy { it.size }),
    SEEDS("Seeds", 70.dp, compareBy { it.seeds }),
    PEERS("Peers", 70.dp, compareBy { it.peers }),
    SOURCE("Source", 90.dp, compareBy(String.CASE_INSENSITIVE_ORDER) { it.provider }),
}

private val searchTextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, color = TextDark)

@Composable
internal fun SearchScreen(controller: SearchController) {
    var sortColumn by remember { mutableStateOf(SortColumn.SEEDS) }
    var descending by remember { mutableStateOf(true) }
    val rows = controller.results.sortedWith(if (descending) sortColumn.comparator.reversed() else sortColumn.comparator)
    val selected = controller.selected

    Column(Modifier.fillMaxSize().background(BackgroundGray)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(controller.query, { controller.query = it }, controller::search, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            CategoryPicker(controller.category) { controller.category = it }
            ToolButton(if (controller.searching) "Searching..." else "Search", onClick = controller::search)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sources:", style = searchTextStyle)
            controller.providers.forEach { provider ->
                SourceToggle(
                    label = provider.name + if (provider.note.isNotEmpty()) " (${provider.note})" else "",
                    checked = controller.enabled[provider.name] == true,
                    onToggle = { controller.enabled[provider.name] = controller.enabled[provider.name] != true }
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).border(BorderStroke(1.dp, BorderGray)).background(Color.White)) {
            Row(Modifier.fillMaxWidth().height(24.dp).background(Color(0xFFEAEAEA)), verticalAlignment = Alignment.CenterVertically) {
                SortColumn.values().forEach { column ->
                    val arrow = if (column == sortColumn) (if (descending) " ▼" else " ▲") else ""
                    Cell(column, Modifier.fillMaxHeight().clickable {
                        if (column == sortColumn) descending = !descending
                        else { sortColumn = column; descending = column != SortColumn.NAME && column != SortColumn.SOURCE }
                    }) {
                        Text(column.title + arrow, style = searchTextStyle.copy(color = HeaderText, fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
                    itemsIndexed(rows) { index, torrent -> ResultRow(torrent, index, torrent == selected, controller) }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                if (rows.isEmpty()) {
                    Text(
                        if (controller.searching) "Searching..." else "No results",
                        style = searchTextStyle.copy(color = HintGray),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("Download Selected", onClick = { selected?.let(controller::download) }, enabled = selected != null)
            ToolButton("Open Info Page", onClick = { selected?.let(controller::openInfoPage) }, enabled = selected?.infoUrl != null)
            Spacer(Modifier.width(8.dp))
            Text("Double-click a result to download it", style = searchTextStyle.copy(color = HintGray))
        }
        StatusBar(controller.status)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(torrent: TorrentDescription, index: Int, isSelected: Boolean, controller: SearchController) {
    val background = when {
        isSelected -> SelectedBlue
        index % 2 == 0 -> Color.White
        else -> Color(0xFFF8F8F8)
    }
    Row(
        Modifier.fillMaxWidth().height(22.dp).background(background)
            .combinedClickable(onClick = { controller.selected = torrent }, onDoubleClick = { controller.selected = torrent; controller.download(torrent) }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortColumn.values().forEach { column ->
            val (text, color) = when (column) {
                SortColumn.NAME -> torrent.title to TextDark
                SortColumn.SIZE -> (if (torrent.size >= 0) formatBytes(torrent.size) else "?") to TextDark
                SortColumn.SEEDS -> (if (torrent.seeds >= 0) torrent.seeds.toString() else "?") to (if (torrent.seeds > 0) ActiveGreen else StoppedGray)
                SortColumn.PEERS -> (if (torrent.peers >= 0) torrent.peers.toString() else "?") to TextDark
                SortColumn.SOURCE -> torrent.provider to HeaderText
            }
            Cell(column) { Text(text, style = searchTextStyle.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun RowScope.Cell(column: SortColumn, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val sized = column.width?.let { modifier.width(it) } ?: modifier.weight(1f)
    Box(sized.padding(start = 8.dp, end = 4.dp), contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = searchTextStyle,
        modifier = modifier.height(24.dp).focusRequester(focus)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { onSubmit(); true } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(Color.White).border(BorderStroke(1.dp, BorderGray)).padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text("Search torrents...", style = searchTextStyle.copy(color = HintGray))
                inner()
            }
        }
    )
}

@Composable
private fun CategoryPicker(category: Category, onChange: (Category) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolButton("Category: ${categoryLabel(category)} ▾", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Category.values().forEach { option ->
                DropdownMenuItem(onClick = { onChange(option); expanded = false }, modifier = Modifier.height(26.dp)) {
                    Text(categoryLabel(option), style = searchTextStyle)
                }
            }
        }
    }
}

private fun categoryLabel(category: Category) = when (category) {
    Category.TV, Category.XXX -> category.name
    else -> category.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun SourceToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Color.White).border(BorderStroke(1.dp, HeaderText)), contentAlignment = Alignment.Center) {
            if (checked) Box(Modifier.size(6.dp).background(TextDark))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = searchTextStyle)
    }
}
