// Pure helpers shared by the background worker and the popup (tested with node --test).

export const HOST = "com.htorrent.host";

export const DEFAULTS = { enabled: true };

/** What to show when the app could not be reached. */
export const TROUBLESHOOT =
  "HTorrent could not be reached. Open HTorrent once so it can connect to this browser, then try again.";

export function isMagnet(link) {
  return /^magnet:\?/i.test(String(link || "").trim());
}

/** True for a downloaded file's path or name ending in .torrent. */
export function isTorrentFile(name) {
  return /\.torrent$/i.test(String(name || "").trim());
}

/** True for a web address of a .torrent file. */
export function isTorrentLink(link) {
  try {
    const url = new URL(String(link || "").trim());
    return (url.protocol === "http:" || url.protocol === "https:") && isTorrentFile(url.pathname);
  } catch (_) {
    return false;
  }
}

/** The magnet links and .torrent files in a list of links, without duplicates. */
export function torrentLinks(links) {
  return [...new Set((links || []).map((link) => String(link || "").trim()).filter((link) => isMagnet(link) || isTorrentLink(link)))];
}

/** A readable name for a magnet link or .torrent address. */
export function linkName(link) {
  try {
    if (isMagnet(link)) return new URLSearchParams(String(link).trim().slice(8)).get("dn") || "Magnet link";
    return decodeURIComponent(new URL(link).pathname.split("/").pop()) || link;
  } catch (_) {
    return String(link);
  }
}
