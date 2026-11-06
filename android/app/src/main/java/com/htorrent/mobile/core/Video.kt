package com.htorrent.mobile.core

import java.net.URI

/** One stream the video engine found on a page (a simplified yt-dlp format). */
data class StreamFormat(
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val ext: String?,
    val size: Long?,
)

/** A quality the user can pick in the download window. */
data class VideoChoice(
    val label: String,
    /** yt-dlp format selector. */
    val selector: String,
    /** File extension of the result. */
    val ext: String,
    val size: Long?,
    val audioOnly: Boolean = false,
)

/** Rules for links that are video pages (YouTube, Facebook, Instagram…), the same idea as the desktop apps. */
object VideoPages {
    /** Name of a video added without reading its page first; the finished file is named after the video. */
    const val AUTO_NAME = "video.mp4"

    /** Best quality, for video links added several at a time. */
    val best = VideoChoice("Best quality", "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b", "mp4", null)
    private val hosts = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.watch", "fb.com", "instagram.com", "tiktok.com",
        "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "dai.ly", "reddit.com", "redd.it", "twitch.tv",
        "threads.net", "threads.com", "pinterest.com", "pin.it", "linkedin.com", "snapchat.com", "bilibili.com",
        "soundcloud.com", "bandcamp.com", "streamable.com", "rumble.com", "ok.ru", "vk.com", "likee.video",
    )

    private fun host(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }.getOrNull()

    /** True for links to sites whose pages hold a video (not a file), so the video engine reads them. */
    fun isVideoSite(url: String): Boolean {
        val h = host(url) ?: return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    /** A page on a video site that can hold a video (not the site's home page). */
    fun isVideoPage(url: String): Boolean {
        if (!isVideoSite(url)) return false
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return (u.path?.trim('/')?.isNotEmpty() == true) || !u.query.isNullOrEmpty()
    }

    /** A link that answered with a web page instead of a file. */
    fun isWebPage(contentType: String?): Boolean {
        val t = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return t == "text/html" || t == "application/xhtml+xml"
    }

    /** Quality choices for the download window: each resolution once, best first, then sound only. */
    fun choices(formats: List<StreamFormat>, maxChoices: Int = 6): List<VideoChoice> {
        val bestAudio = formats.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { (if (it.ext == "m4a") 1L shl 40 else 0L) + (it.size ?: 0) }
        val heights = formats.filter { it.hasVideo && it.height > 0 }.map { it.height }.distinct().sortedDescending()
        val out = mutableListOf<VideoChoice>()
        for (h in heights.take(maxChoices)) {
            val video = formats.filter { it.hasVideo && it.height == h }
                .maxByOrNull { (if (it.ext == "mp4") 1L shl 40 else 0L) + (it.size ?: 0) }
            val size = when {
                video?.size == null -> null
                video.hasAudio -> video.size
                else -> video.size + (bestAudio?.size ?: 0)
            }
            out += VideoChoice(
                label = label(h),
                selector = "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b",
                ext = "mp4",
                size = size,
            )
        }
        if (out.isEmpty()) out += VideoChoice("Best quality", "bv*+ba/b", "mp4", formats.firstOrNull { it.hasVideo && it.hasAudio }?.size)
        if (bestAudio != null || formats.any { it.hasAudio }) {
            val audioExt = if (bestAudio?.ext == "m4a" || bestAudio == null) "m4a" else bestAudio.ext ?: "m4a"
            out += VideoChoice("Sound only (${audioExt.uppercase()})", "ba[ext=m4a]/ba/b", audioExt, bestAudio?.size, audioOnly = true)
        }
        return out
    }

    fun label(height: Int): String = when {
        height >= 4320 -> "8K (${height}p)"
        height >= 2160 -> "4K (${height}p)"
        height >= 1440 -> "2K (${height}p)"
        height >= 1080 -> "Full HD (${height}p)"
        height >= 720 -> "HD (${height}p)"
        else -> "${height}p"
    }

    /** A file name from the video title, with the right extension. */
    fun fileName(title: String?, ext: String): String {
        val base = FileNames.sanitize((title ?: "video").take(120)).ifBlank { "video" }.removeSuffix(".$ext")
        return "$base.$ext"
    }

    /** Links that only redirect to the real video page (Facebook share links, fb.watch). */
    fun isShareLink(url: String): Boolean {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val h = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        return h == "fb.watch" || (h == "facebook.com" && (u.path ?: "").startsWith("/share/"))
    }

    fun isLoginPage(url: String): Boolean {
        val p = runCatching { URI(url).path }.getOrNull()?.lowercase() ?: return false
        return p.startsWith("/login") || p.startsWith("/accounts/login") || p.contains("checkpoint")
    }

    /** Browser cookies ("a=1; b=2") as lines of a Netscape cookies file for [domain] (".instagram.com"). */
    fun netscapeCookies(domain: String, header: String): List<String> =
        header.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val name = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            if (name.isEmpty() || name.any { it == '\t' || it == '\n' } || value.any { it == '\t' || it == '\n' }) null
            else "$domain\tTRUE\t/\tTRUE\t0\t$name\t$value"
        }

    /** The video engine itself failed (not the site): a readable message with the technical detail for a report. */
    fun engineError(kind: String, detail: String?): String {
        val d = detail?.trim()?.takeIf { it.isNotEmpty() && it.length < 160 }
        return "The video engine could not start on this phone. Restart the app and try again; if it keeps happening, " +
            "please report it (" + kind + (d?.let { ": $it" } ?: "") + ")."
    }

    private val sizeRe = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
    private val speedRe = Regex("""at\s+~?\s*([\d.]+)\s*([KMGT]?i?B)/s""")

    private fun bytes(n: String, unit: String): Long? {
        val v = n.toDoubleOrNull() ?: return null
        val m = when (unit.uppercase().replace("I", "")) { "B" -> 1.0; "KB" -> 1024.0; "MB" -> 1048576.0; "GB" -> 1073741824.0; "TB" -> 1099511627776.0; else -> return null }
        return (v * m).toLong()
    }

    /** Total size and speed from a yt-dlp progress line ("[download] 45.3% of ~ 12.34MiB at 1.23MiB/s ETA 00:10"). */
    fun parseProgress(line: String): Pair<Long?, Long?> {
        val total = sizeRe.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) }
        val speed = speedRe.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) }
        return total to speed
    }

    /** yt-dlp's error text, shortened to something useful for the user. */
    fun friendlyError(raw: String?): String {
        val line = raw.orEmpty().lines().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: raw.orEmpty().lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val l = line.lowercase()
        return when {
            "unsupported url" in l -> "No video found on this page."
            "sign in" in l || "login" in l || "log in" in l || "cookies" in l ->
                "This video needs you to be signed in. Open it in the app's Browser tab, sign in, then download it there."
            "private" in l -> "This video is private."
            "not available" in l || "unavailable" in l -> "This video is not available."
            "unable to download webpage" in l || "network" in l || "timed out" in l -> "Could not reach the site. Check the connection and try again."
            line.isEmpty() -> "The video could not be read."
            else -> line.take(200)
        }
    }
}
