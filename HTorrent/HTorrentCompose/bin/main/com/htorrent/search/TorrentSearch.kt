// Kotlin/JVM adaptation of TorrentSearch-Kotlin's search API (TorrentSearch, TorrentProvider, models).
// Copyright (c) 2020 Andrew Carlson. Licensed under MIT.
package com.htorrent.search

import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class Category { ALL, AUDIO, VIDEO, OTHER, MOVIES, XXX, GAMES, TV, MUSIC, APPS, BOOKS, ANIME }

data class TorrentQuery(
    val content: String? = null,
    val category: Category = Category.ALL,
    val imdbId: String? = null,
    val page: Int = 1,
    val limit: Int = -1,
) {
    init { require(page > 0) { "Search query page must be greater than zero." } }
}

data class TorrentDescription(
    val provider: String,
    val magnetUrl: String?,
    val title: String,
    val size: Long,
    val seeds: Int,
    val peers: Int,
    val imdbId: String? = null,
    val infoUrl: String? = null,
    val hash: String?,
) {
    val isResolved: Boolean get() = !magnetUrl.isNullOrBlank()
}

sealed class ProviderResult {
    abstract val providerName: String
    data class Success(
        override val providerName: String,
        val torrents: List<TorrentDescription>,
        val page: Int = 1,
        val pageSize: Int = torrents.size,
        val totalTorrents: Int = torrents.size,
        val requiresResolution: Boolean = false,
    ) : ProviderResult() {
        val hasMoreResults: Boolean get() = totalTorrents > 0 && page * pageSize < totalTorrents
    }
    data class Error(override val providerName: String, val message: String) : ProviderResult()
}

interface TorrentProvider {
    val name: String
    /** Provider category ids; a query in a category missing here skips this provider. */
    val categories: Map<Category, String>
    val enabledByDefault: Boolean get() = true
    /** Short hint shown next to the provider toggle. */
    val note: String get() = ""
    /** False when the query lacks what this provider needs (text or an IMDb id). */
    fun supports(query: TorrentQuery): Boolean
    fun search(query: TorrentQuery): ProviderResult
    /** Fetch the magnet link for results from providers that only list info pages. */
    fun resolve(torrent: TorrentDescription): TorrentDescription = torrent
}

class HttpStatusException(val status: Int, url: String) : IOException("HTTP $status from ${URI(url).host}")

class SearchHttp(private val userAgent: String = USER_AGENT) {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .cookieHandler(CookieManager())
        .build()

    fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", userAgent).header("Accept", "application/json, text/html;q=0.9, */*;q=0.8").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() !in 200..299) throw HttpStatusException(response.statusCode(), url)
            val bytes = input.readNBytes(MAX_BODY + 1)
            if (bytes.size > MAX_BODY) throw IOException("Response too large from ${URI(url).host}")
            return bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:98.0) Gecko/20100101 Firefox/98.0"
        private const val MAX_BODY = 8 * 1024 * 1024
    }
}

class TorrentSearch(
    val providers: List<TorrentProvider> = defaultProviders(SearchHttp()),
) : AutoCloseable {
    private val pool: ExecutorService = Executors.newFixedThreadPool(providers.size.coerceIn(1, 8)) { task ->
        Thread(task, "htorrent-search").apply { isDaemon = true }
    }

    /** Providers from [enabled] that can answer [query] in its category. */
    fun providersFor(query: TorrentQuery, enabled: Set<String>): List<TorrentProvider> = providers.filter { provider ->
        provider.name in enabled && provider.supports(query) &&
            (query.category == Category.ALL || provider.categories.containsKey(query.category))
    }

    /** Queries each provider in parallel; [onResult] is called once per provider from a worker thread. */
    fun search(query: TorrentQuery, providers: List<TorrentProvider>, onResult: (ProviderResult) -> Unit): List<Future<*>> =
        providers.map { provider ->
            pool.submit {
                val result = try { provider.search(query) }
                catch (e: InterruptedException) { return@submit }
                catch (e: Throwable) { ProviderResult.Error(provider.name, e.message ?: e.javaClass.simpleName) }
                onResult(result)
            }
        }

    /** Blocking; returns [torrent] with its magnet link filled in when the provider can find one. */
    fun resolve(torrent: TorrentDescription): TorrentDescription =
        if (torrent.isResolved) torrent else providers.firstOrNull { it.name == torrent.provider }?.resolve(torrent) ?: torrent

    override fun close() { pool.shutdownNow() }
}

fun defaultProviders(http: SearchHttp): List<TorrentProvider> = listOf(
    PirateBayProvider(http),
    YtsProvider(http),
    EztvProvider(http),
    NyaaProvider(http),
    X1337Provider(http),
)

// Upstream's tracker list is mostly offline now; these are the commonly working public trackers.
internal val trackers = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
)

internal fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
internal fun pathEncode(value: String): String = urlEncode(value).replace("+", "%20")

internal fun formatMagnet(infoHash: String, name: String): String =
    "magnet:?xt=urn:btih:$infoHash&dn=${urlEncode(name)}" + trackers.joinToString("") { "&tr=${urlEncode(it)}" }

internal fun hashFromMagnetUrl(magnetUrl: String): String = magnetUrl.substringAfter("xt=urn:btih:").substringBefore('&').uppercase()

/** Parses sizes like "1.4 GiB" or "700 MB" (both treated as binary units, as upstream does). */
internal fun parseFileSize(text: String): Long? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    val value = parts[0].replace(",", "").toDoubleOrNull() ?: return null
    val power = when (parts[1].uppercase()) { "B", "BYTES" -> 0; "KB", "KIB" -> 1; "MB", "MIB" -> 2; "GB", "GIB" -> 3; "TB", "TIB" -> 4; else -> return null }
    return (value * Math.pow(1024.0, power.toDouble())).toLong()
}
