// Kotlin/JVM adaptation of TorrentSearch-Kotlin's providers (PirateBay, YTS, EZTV, Nyaa, 1337x).
// Copyright (c) 2020 Andrew Carlson. Licensed under MIT.
package com.htorrent.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal class PirateBayProvider(private val http: SearchHttp) : TorrentProvider {
    override val name = "PirateBay"
    private val baseUrl = "https://apibay.org"
    override val categories = mapOf(
        Category.ALL to "", Category.AUDIO to "100", Category.MUSIC to "101", Category.VIDEO to "200",
        Category.MOVIES to "201", Category.TV to "205", Category.APPS to "300", Category.GAMES to "400",
        Category.XXX to "500", Category.OTHER to "600",
    )

    // apibay rejects most punctuation; upstream also dropped digits, which broke searches like "ubuntu 24.04".
    private fun queryText(query: TorrentQuery) = query.content?.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else ' ' }
        ?.joinToString("")?.trim()?.replace(Regex("\\s+"), " ")

    override fun supports(query: TorrentQuery) = !queryText(query).isNullOrBlank()

    override fun search(query: TorrentQuery): ProviderResult {
        val category = categories[query.category].orEmpty()
        val url = "$baseUrl/q.php?q=${urlEncode(queryText(query).orEmpty())}" + if (category.isNotEmpty()) "&cat=$category" else ""
        return ProviderResult.Success(name, parse(http.get(url)))
    }

    fun parse(body: String): List<TorrentDescription> = Json.parse(body).items().mapNotNull { item ->
        val hash = item["info_hash"].text()?.takeIf { hash -> hash.isNotBlank() && !hash.all { it == '0' } } ?: return@mapNotNull null
        val title = item["name"].text() ?: "<unknown>"
        TorrentDescription(
            provider = name,
            magnetUrl = formatMagnet(hash, title),
            title = title,
            size = item["size"].long() ?: -1,
            seeds = item["seeders"].int() ?: -1,
            peers = item["leechers"].int() ?: -1,
            imdbId = item["imdb"].text()?.takeIf { it.isNotBlank() },
            infoUrl = item["id"].text()?.let { "https://thepiratebay.org/description.php?id=$it" },
            hash = hash,
        )
    }
}

internal class YtsProvider(private val http: SearchHttp) : TorrentProvider {
    override val name = "YTS"
    // yts.mx no longer resolves; the API announced movies-api.accel.li as its new base, yts.lt redirects to the site mirror.
    private val baseUrls = listOf("https://movies-api.accel.li/api/v2/", "https://yts.lt/api/v2/")
    override val categories = mapOf(Category.MOVIES to "")
    override val note = "movies"

    override fun supports(query: TorrentQuery) = !query.content.isNullOrBlank() || !query.imdbId.isNullOrBlank()

    override fun search(query: TorrentQuery): ProviderResult {
        val path = if (!query.imdbId.isNullOrBlank()) "movie_details.json?imdb_id=${urlEncode(query.imdbId)}"
        else "list_movies.json?sort_by=date_added&query_term=${urlEncode(query.content.orEmpty())}&page=${query.page}" +
            if (query.limit > -1) "&limit=${query.limit}" else ""
        var failure: Exception? = null
        for (base in baseUrls) {
            val body = try { http.get(base + path) } catch (e: java.io.IOException) { failure = e; continue }
            return parse(body)
        }
        throw failure ?: IllegalStateException("No YTS mirror available")
    }

    fun parse(body: String): ProviderResult.Success {
        val data = Json.parse(body)["data"]
        val movies = (data["movie"]?.let { listOf(it) } ?: data["movies"].items()).filter { (it["id"].long() ?: 0) > 0 }
        val torrents = movies.flatMap { movie ->
            val title = movie["title_long"].text() ?: movie["title"].text() ?: "<unknown>"
            movie["torrents"].items().mapNotNull { torrent ->
                val hash = torrent["hash"].text() ?: return@mapNotNull null
                TorrentDescription(
                    provider = name,
                    magnetUrl = formatMagnet(hash, title),
                    title = listOfNotNull(title, torrent["quality"].text(), torrent["type"].text()).joinToString(" "),
                    size = torrent["size_bytes"].long() ?: -1,
                    seeds = torrent["seeds"].int() ?: -1,
                    peers = torrent["peers"].int() ?: -1,
                    imdbId = movie["imdb_code"].text(),
                    infoUrl = movie["url"].text() ?: torrent["url"].text(),
                    hash = hash,
                )
            }
        }
        return ProviderResult.Success(
            name, torrents,
            page = data["page_number"].int() ?: 1,
            pageSize = data["limit"].int() ?: torrents.size,
            totalTorrents = if (movies.isEmpty()) 0 else data["movie_count"].int() ?: movies.size,
        )
    }
}

internal class EztvProvider(private val http: SearchHttp) : TorrentProvider {
    override val name = "EZTV"
    private val baseUrl = "https://eztvx.to"
    override val categories = mapOf(Category.TV to "")
    override val note = "TV, IMDb id only"

    override fun supports(query: TorrentQuery) = !query.imdbId?.dropWhile { it == 't' }.isNullOrBlank()

    override fun search(query: TorrentQuery): ProviderResult {
        val imdbId = query.imdbId.orEmpty().dropWhile { it == 't' }
        val url = "$baseUrl/api/get-torrents?imdb_id=${urlEncode(imdbId)}&page=${query.page}" + if (query.limit > -1) "&limit=${query.limit}" else ""
        return parse(http.get(url))
    }

    fun parse(body: String): ProviderResult.Success {
        val json = Json.parse(body)
        val torrents = json["torrents"].items().mapNotNull { torrent ->
            val magnet = torrent["magnet_url"].text() ?: return@mapNotNull null
            val title = torrent["title"].text() ?: "<unknown>"
            val slug = title.lowercase().replace(' ', '-').replace('_', '-').removeSuffix("-eztv")
            TorrentDescription(
                provider = name,
                magnetUrl = magnet,
                title = title,
                size = torrent["size_bytes"].long() ?: -1,
                seeds = torrent["seeds"].int() ?: -1,
                peers = torrent["peers"].int() ?: -1,
                imdbId = torrent["imdb_id"].text()?.let { "tt$it" },
                infoUrl = torrent["id"].text()?.let { "$baseUrl/ep/$it/$slug/" },
                hash = hashFromMagnetUrl(magnet),
            )
        }
        return ProviderResult.Success(
            name, torrents,
            page = json["page"].int() ?: 1,
            pageSize = json["limit"].int()?.takeIf { it > 0 } ?: torrents.size,
            totalTorrents = json["torrents_count"].int() ?: torrents.size,
        )
    }
}

internal class NyaaProvider(private val http: SearchHttp) : TorrentProvider {
    override val name = "Nyaa"
    private val baseUrl = "https://nyaa.si"
    override val enabledByDefault = false
    override val note = "anime"
    override val categories = mapOf(
        Category.ALL to "0_0", Category.AUDIO to "2_0", Category.MOVIES to "1_0", Category.TV to "1_0",
        Category.ANIME to "1_0", Category.GAMES to "6_2", Category.MUSIC to "2_0", Category.APPS to "6_1", Category.BOOKS to "3_0",
    )

    override fun supports(query: TorrentQuery) = !query.content.isNullOrBlank()

    override fun search(query: TorrentQuery): ProviderResult {
        val category = categories[query.category] ?: categories.getValue(Category.ALL)
        return parse(http.get("$baseUrl/?q=${urlEncode(query.content.orEmpty())}&p=${query.page}&c=$category"))
    }

    fun parse(html: String): ProviderResult.Success {
        val document = Jsoup.parse(html, baseUrl)
        val torrents = document.select("table.torrent-list tbody tr").mapNotNull(::row)
        val page = document.selectFirst("ul.pagination li.active")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
        val total = document.selectFirst(".pagination-page-info")?.text()?.split(' ')?.getOrNull(5)?.toIntOrNull() ?: torrents.size
        return ProviderResult.Success(name, torrents, page = page, pageSize = 75, totalTorrents = total)
    }

    private fun row(row: Element): TorrentDescription? {
        val link = row.selectFirst("td:nth-child(2) a:last-child") ?: return null
        val magnet = row.selectFirst("td:nth-child(3) a[href^=magnet:]")?.attr("href") ?: return null
        val seeds = row.selectFirst("td:nth-child(6)")?.text()?.toIntOrNull() ?: return null
        return TorrentDescription(
            provider = name,
            magnetUrl = magnet,
            title = link.attr("title").ifBlank { link.text() },
            size = row.selectFirst("td:nth-child(4)")?.text()?.let(::parseFileSize) ?: 0,
            seeds = seeds,
            peers = row.selectFirst("td:nth-child(7)")?.text()?.toIntOrNull() ?: 0,
            infoUrl = link.absUrl("href").ifBlank { null },
            hash = hashFromMagnetUrl(magnet),
        )
    }
}

internal class X1337Provider(private val http: SearchHttp) : TorrentProvider {
    override val name = "1337x"
    private val baseUrl = "https://1337x.to"
    // 1337x currently answers scripted clients with a Cloudflare challenge (HTTP 403), so it starts disabled.
    override val enabledByDefault = false
    override val note = "often blocked"
    override val categories = mapOf(
        Category.ALL to "", Category.TV to "TV", Category.MOVIES to "Movies", Category.GAMES to "Games",
        Category.MUSIC to "Music", Category.APPS to "Apps", Category.XXX to "XXX",
    )

    override fun supports(query: TorrentQuery) = !query.content.isNullOrBlank()

    override fun search(query: TorrentQuery): ProviderResult {
        val content = pathEncode(query.content.orEmpty().trim())
        val url = if (query.category == Category.ALL) "$baseUrl/search/$content/${query.page}/"
        else "$baseUrl/category-search/$content/${categories.getValue(query.category)}/${query.page}/"
        return parse(http.get(url))
    }

    fun parse(html: String): ProviderResult.Success {
        val document = Jsoup.parse(html, baseUrl)
        val torrents = document.select("table.table-list tbody tr").mapNotNull(::row)
        val pagination = document.selectFirst(".pagination")
        val page = pagination?.selectFirst("li.active")?.text()?.toIntOrNull() ?: 1
        val pageCount = pagination?.selectFirst("li.last a")?.attr("href")?.trim('/')?.split('/')?.lastOrNull()?.toIntOrNull() ?: 1
        return ProviderResult.Success(name, torrents, page = page, totalTorrents = torrents.size * pageCount, requiresResolution = true)
    }

    private fun row(row: Element): TorrentDescription? {
        val link = row.selectFirst("td.name a[href*=/torrent/]") ?: return null
        val seeds = row.selectFirst("td.seeds")?.text()?.toIntOrNull() ?: return null
        // The size cell also contains a hidden span repeating the seed count.
        val size = row.selectFirst("td.size")?.ownText()?.let(::parseFileSize) ?: 0
        return TorrentDescription(
            provider = name, magnetUrl = null, title = link.text(), size = size, seeds = seeds,
            peers = row.selectFirst("td.leeches")?.text()?.toIntOrNull() ?: 0,
            infoUrl = link.absUrl("href").ifBlank { null }, hash = null,
        )
    }

    override fun resolve(torrent: TorrentDescription): TorrentDescription {
        val infoUrl = torrent.infoUrl ?: return torrent
        val document = Jsoup.parse(http.get(infoUrl), infoUrl)
        val magnet = document.selectFirst("a[href^=magnet:]")?.attr("href") ?: return torrent
        return torrent.copy(magnetUrl = magnet, hash = document.selectFirst(".infohash-box p span")?.text() ?: hashFromMagnetUrl(magnet))
    }
}
