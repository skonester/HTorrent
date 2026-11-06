package com.htorrent.mobile.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** One byte range of a download. `end` is inclusive; -1 means "to the end of an unknown size". */
data class Segment(val id: Int, val start: Long, var end: Long, var written: Long = 0) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val remaining: Long get() = if (end < 0) Long.MAX_VALUE else length - written
    val isComplete: Boolean get() = end >= 0 && written >= length
}

/** How a file is split into parts; carried over from Nexonic Download Manager. */
object SegmentPlanner {
    const val MIN_SEGMENT: Long = 512 * 1024
    const val MIN_SPLIT: Long = 1L shl 20
    const val MAX_CONNECTIONS = 32

    /** Equal segments of at least 512 KB, between 1 and 32 of them. */
    fun plan(size: Long?, connections: Int): List<Segment> {
        if (size == null || size <= 0) return listOf(Segment(0, 0, -1))
        val byMin = maxOf(1L, size / MIN_SEGMENT).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val n = maxOf(1, minOf(connections, MAX_CONNECTIONS, byMin))
        val base = size / n
        var start = 0L
        return (0 until n).map { i ->
            val end = if (i == n - 1) size - 1 else start + base - 1
            Segment(i, start, end).also { start = end + 1 }
        }
    }

    /** The unfinished segment with the most bytes left, split in half: (index, its new end, the new segment). */
    fun splitLargest(segments: List<Segment>, nextId: Int): Triple<Int, Long, Segment>? {
        var best = -1
        var bestLeft = 0L
        segments.forEachIndexed { i, s ->
            if (s.end >= 0 && !s.isComplete && s.remaining > bestLeft) { best = i; bestLeft = s.remaining }
        }
        if (best < 0 || bestLeft < MIN_SPLIT) return null
        val s = segments[best]
        val mid = s.start + s.written + bestLeft / 2
        return Triple(best, mid - 1, Segment(nextId, mid, s.end))
    }
}

object FileNames {
    /** `filename*=UTF-8''…` wins over `filename="…"`. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(";").map { it.trim() }
        for (p in parts) if (p.lowercase(Locale.ROOT).startsWith("filename*=")) {
            var v = p.substring(10).trim('"')
            val i = v.indexOf("''")
            if (i >= 0) v = v.substring(i + 2)
            val decoded = runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrNull()
            if (!decoded.isNullOrEmpty()) return sanitize(decoded)
        }
        for (p in parts) if (p.lowercase(Locale.ROOT).startsWith("filename=")) {
            val v = p.substring(9).trim('"', ' ')
            if (v.isNotEmpty()) return sanitize(v)
        }
        return null
    }

    fun fromUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.rawPath.orEmpty()
        val last = path.substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return sanitize(if (decoded.isEmpty()) uri?.host ?: "download" else decoded)
    }

    /** No path separators, control characters or leading dots; at most 200 characters. */
    fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (ch in name) sb.append(if (ch == '/' || ch == '\\' || ch == ':' || ch.isISOControl() || ch in "<>|?*\"") '_' else ch)
        var cleaned = sb.toString().trim()
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1)
        if (cleaned.isEmpty()) cleaned = "download"
        if (cleaned.length > 200) {
            val ext = extensionOf(cleaned)
            val stem = cleaned.substring(0, cleaned.length - (if (ext.isEmpty()) 0 else ext.length + 1)).take(190)
            cleaned = if (ext.isEmpty()) stem else "$stem.$ext"
        }
        return cleaned
    }

    fun extensionOf(name: String): String {
        val base = name.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot > 0 && dot < base.length - 1) base.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    /** "file.zip" → "file (1).zip" … until `exists` says the name is free. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val ext = extensionOf(name)
        val stem = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        var n = 1
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }
}

enum class FileCategory(val label: String) {
    COMPRESSED("Compressed"), DOCUMENTS("Documents"), MUSIC("Music"), VIDEO("Video"),
    PROGRAMS("Programs"), IMAGES("Images"), OTHER("General");

    /** Subfolder like IDM; files of no particular type stay in the main folder. */
    val folder: String? get() = if (this == OTHER) null else label

    companion object {
        private val map = mapOf(
            COMPRESSED to setOf("zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "zst"),
            DOCUMENTS to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "epub", "csv", "odt", "pages", "key", "numbers"),
            MUSIC to setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "aiff"),
            VIDEO to setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "flv", "ts", "3gp"),
            PROGRAMS to setOf("dmg", "pkg", "app", "exe", "msi", "deb", "rpm", "apk", "iso", "img", "xapk", "apks"),
            IMAGES to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "tiff", "bmp", "raw", "avif"),
        )

        fun of(fileName: String): FileCategory {
            val ext = FileNames.extensionOf(fileName)
            return map.entries.firstOrNull { ext in it.value }?.key ?: OTHER
        }
    }
}

object Links {
    private val commaBeforeLink = Regex(",\\s*(?=https?://)", RegexOption.IGNORE_CASE)

    /** Links pasted together: one per line, or separated by spaces or commas. Only web links are kept. */
    fun split(text: String): List<String> =
        text.replace(commaBeforeLink, "\n").split(Regex("\\s+"))
            .map { it.trim(',', ';', '"', '\'', '(', ')', '<', '>') }
            .filter { isWebLink(it) }
            .map { unwrap(it) }
            .distinct()

    /**
     * The download links in a htorrent:// link (what the desktop app's QR codes hold):
     * htorrent://add?url=https%3A%2F%2F…&url=… . Only web links are kept; nothing else in it is used.
     */
    fun fromHTorrentLink(link: String): List<String> {
        val u = runCatching { URI(link.trim()) }.getOrNull() ?: return emptyList()
        if (!u.scheme.equals("htorrent", true)) return emptyList()
        val query = u.rawQuery ?: return emptyList()
        return query.split("&").mapNotNull {
            if (it.substringBefore('=') != "url") null
            else runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }.filter { isWebLink(it) }.distinct().take(200)
    }

    fun isWebLink(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val s = u.scheme?.lowercase(Locale.ROOT)
        return (s == "http" || s == "https") && !u.host.isNullOrEmpty()
    }

    /** The real address behind Google Images/results, Facebook and Reddit redirect links. */
    fun unwrap(url: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return url
        val host = u.host?.lowercase(Locale.ROOT) ?: return url
        val query = u.rawQuery ?: return url
        val params = query.split("&").mapNotNull {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        fun param(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            params.firstOrNull { it.first == n }?.second?.takeIf { isWebLink(it) }
        }
        val google = host.startsWith("google.") || host.startsWith("www.google.") || host.startsWith("images.google.")
        return when {
            google && u.path == "/imgres" -> param("imgurl")
            google && u.path == "/url" -> param("q", "url")
            (host == "l.facebook.com" || host == "lm.facebook.com") && u.path == "/l.php" -> param("u")
            host == "out.reddit.com" -> param("url")
            else -> null
        } ?: url
    }
}

object Net {
    private fun port(u: URI): Int = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    /** Same server and no step down from https: cookies and logins may be sent there. */
    fun sameSite(url: String, origin: String): Boolean {
        val a = runCatching { URI(url) }.getOrNull() ?: return false
        val o = runCatching { URI(origin) }.getOrNull() ?: return false
        val downgrade = o.scheme.equals("https", true) && !a.scheme.equals("https", true)
        return a.host != null && a.host.equals(o.host, true) && port(a) == port(o) && !downgrade
    }

    /** A strong ETag, else Last-Modified. */
    fun validator(etag: String?, lastModified: String?): String? = when {
        !etag.isNullOrEmpty() && !etag.startsWith("W/") -> etag
        !lastModified.isNullOrEmpty() -> lastModified
        else -> null
    }

    fun sameVersion(saved: String?, now: String?): Boolean = saved == null || now == null || saved == now

    private val contentRange = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")

    /** A 206 answer must start where we asked and belong to a file of the expected size. */
    fun rangeMatches(header: String?, from: Long, size: Long?): Boolean {
        val m = header?.let { contentRange.find(it) } ?: return false
        if (m.groupValues[1].toLongOrNull() != from) return false
        val total = m.groupValues[3].toLongOrNull()
        return size == null || total == null || total == size
    }

    /** Total size from "bytes 0-0/12345". */
    fun totalFromContentRange(header: String?): Long? = header?.let { contentRange.find(it) }?.groupValues?.get(3)?.toLongOrNull()
}

object Media {
    val MEDIA_TYPES = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    val STREAM_TYPES = setOf("m3u8", "mpd", "ts", "m4s")
    val DOWNLOADABLE = MEDIA_TYPES + setOf(
        "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "apk", "exe", "msi", "dmg", "pkg", "iso", "img", "deb",
        "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv",
    )

    /** A whole audio/video file worth offering (streams and their small pieces are not). */
    fun isMedia(url: String, contentType: String?, length: Long): Boolean {
        if (!Links.isWebLink(url)) return false
        val ext = FileNames.extensionOf(url)
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        if (ext in STREAM_TYPES || Regex("mpegurl|dash\\+xml|mp2t").containsMatchIn(type)) return false
        if (length in 0 until 300 * 1024) return false
        return type.startsWith("video/") || type.startsWith("audio/") || ext in MEDIA_TYPES
    }

    /** A link that most likely is a file download rather than a page. */
    fun looksDownloadable(url: String): Boolean = Links.isWebLink(url) && FileNames.extensionOf(url) in DOWNLOADABLE

    fun isStream(url: String): Boolean = FileNames.extensionOf(url) == "m3u8"
}

object Format {
    fun size(bytes: Long): String {
        if (bytes < 0) return "Unknown size"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "" else size(bytesPerSecond) + "/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds > 99L * 86400 -> "more than 99 days"
        seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

/** Numbered series like `img[001-100].jpg` or `file[a-f].txt` (desktop BatchPattern). At most 10,000 links. */
object BatchPattern {
    private val pattern = Regex("\\[(\\d+)-(\\d+)]|\\[([a-zA-Z])-([a-zA-Z])]")

    fun expand(template: String, limit: Int = 10_000): List<String> {
        val m = pattern.find(template) ?: return listOf(template)
        val before = template.substring(0, m.range.first)
        val after = template.substring(m.range.last + 1)
        val items: List<String> = if (m.groupValues[1].isNotEmpty()) {
            val a = m.groupValues[1]; val b = m.groupValues[2]
            val width = if (a.length > 1 && a.startsWith("0")) a.length else 0
            val from = a.toLong(); val to = b.toLong()
            val range = if (from <= to) from..to else from downTo to
            range.asSequence().take(limit).map { if (width > 0) it.toString().padStart(width, '0') else it.toString() }.toList()
        } else {
            val from = m.groupValues[3][0]; val to = m.groupValues[4][0]
            (if (from <= to) (from..to) else (from downTo to)).take(limit).map { it.toString() }
        }
        return items.flatMap { expand(before + it + after, limit) }.take(limit)
    }
}

/** Search engines for the address bar of the built-in browser (Settings → Browser). */
object SearchEngines {
    data class Engine(val id: String, val name: String, val pattern: String)

    val all = listOf(
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Engine("google", "Google", "https://www.google.com/search?q=%s"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
        Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
    )

    fun of(id: String?): Engine = all.firstOrNull { it.id == id } ?: all[0]

    fun url(id: String?, query: String): String = of(id).pattern.replace("%s", java.net.URLEncoder.encode(query.trim(), "UTF-8"))
}

/** Saved pages of the built-in browser, kept as lines of "address<TAB>title". Newest first, one entry per address. */
object Bookmarks {
    data class Bookmark(val url: String, val title: String)

    fun parse(text: String): List<Bookmark> = text.lines().mapNotNull { line ->
        val url = line.substringBefore('\t').trim()
        if (!Links.isWebLink(url)) null else Bookmark(url, line.substringAfter('\t', "").trim().ifEmpty { url })
    }.distinctBy { it.url }

    fun format(list: List<Bookmark>): String =
        list.joinToString("\n") { it.url + "\t" + it.title.replace('\t', ' ').replace('\n', ' ') } + if (list.isEmpty()) "" else "\n"

    fun add(list: List<Bookmark>, url: String, title: String): List<Bookmark> =
        if (!Links.isWebLink(url)) list else listOf(Bookmark(url, title.trim().ifEmpty { url })) + list.filter { it.url != url }

    fun remove(list: List<Bookmark>, url: String): List<Bookmark> = list.filter { it.url != url }
}
