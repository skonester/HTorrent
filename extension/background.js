import {
  DEFAULTS, HOST, TROUBLESHOOT, addMedia, cookieHeader, downloadKey, header, isMedia, isStreamPlaylist, isVideoSite, isWebLink, nameFromUrl, shouldTakeOver, sortMedia,
  summarizePlaylist, titleToName,
} from "./helpers.js";

async function settings() {
  return { ...DEFAULTS, ...(await chrome.storage.sync.get(Object.keys(DEFAULTS))) };
}

async function cookiesFor(url) {
  try {
    return cookieHeader(await chrome.cookies.getAll({ url }));
  } catch (_) {
    return ""; // no cookie access for this address
  }
}

async function send(url, extra = {}) {
  if (!isWebLink(url)) return false;  // nothing else is ever sent to the app (or gets cookies looked up)
  const cookies = await cookiesFor(url);
  const message = { type: "download", url, cookies, userAgent: navigator.userAgent, ...extra };
  try {
    // A helper that doesn't answer within a few seconds counts as unreachable, so the browser keeps the download.
    const reply = await Promise.race([
      chrome.runtime.sendNativeMessage(HOST, message),
      new Promise((resolve) => setTimeout(() => resolve(null), 8000)),
    ]);
    const ok = !!(reply && reply.ok);
    await showStatus(ok);
    return ok;
  } catch (_) {
    await showStatus(false);
    return false;
  }
}

/** The toolbar button shows "!" and says what to do while the app can't be reached. */
async function showStatus(ok) {
  await chrome.action.setBadgeText({ text: ok ? "" : "!" });
  if (!ok) await chrome.action.setBadgeBackgroundColor({ color: "#cf222e" }).catch(() => {});
  await chrome.action.setTitle({ title: ok ? "HTorrent" : TROUBLESHOOT }).catch(() => {});
}

// ---- Browser downloads go to the app ----

const handedOver = new Map(); // link -> time, so one click is never sent to the app twice

chrome.downloads.onCreated.addListener(async (item) => {
  if (!shouldTakeOver(item, await settings())) return;
  try {
    await chrome.downloads.pause(item.id);
  } catch (_) {
    return; // already finished or not pausable
  }
  const url = item.finalUrl || item.url;
  const key = downloadKey(url);
  const now = Date.now();
  for (const [u, t] of handedOver) if (now - t > 10000) handedOver.delete(u);
  if (handedOver.has(key)) {
    // The same link a moment ago (a redirect or a double click): the app already has it.
    await chrome.downloads.cancel(item.id).catch(() => {});
    await chrome.downloads.erase({ id: item.id }).catch(() => {});
    return;
  }
  handedOver.set(key, now);
  const name = item.filename ? item.filename.split(/[\\/]/).pop() : undefined;
  const size = item.totalBytes > 0 ? item.totalBytes : item.fileSize > 0 ? item.fileSize : undefined;
  const ok = await send(url, { referrer: item.referrer || "", fileName: name, size, browser: true });
  if (!ok) handedOver.delete(key);
  if (ok) {
    await chrome.downloads.cancel(item.id).catch(() => {});
    await chrome.downloads.erase({ id: item.id }).catch(() => {});
  } else {
    await chrome.downloads.resume(item.id).catch(() => {}); // the app is not reachable: let the browser finish
  }
});

// ---- Right-click menu ----

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: "htorrent-download", title: "Download with HTorrent", contexts: ["link", "video", "audio"] });
  chrome.contextMenus.create({ id: "htorrent-page-video", title: "Download this page's video with HTorrent", contexts: ["page", "video"] });
  chrome.contextMenus.create({ id: "htorrent-all", title: "Download all links with HTorrent", contexts: ["page", "selection"] });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const referrer = info.pageUrl || (tab && tab.url) || "";
  if (info.menuItemId === "htorrent-all" && tab) {
    const links = await pageLinks(tab.id, !!info.selectionText);
    for (const url of links) await send(url, { referrer });
    return;
  }
  if (info.menuItemId === "htorrent-page-video") {
    send(info.pageUrl || (tab && tab.url), { site: true, browser: true });
    return;
  }
  const url = info.linkUrl || info.srcUrl;
  if (url && /^https?:\/\//i.test(url)) send(url, { referrer, browser: true });
});

/** Links on a page (only inside the selection when there is one). */
async function pageLinks(tabId, selectionOnly) {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId },
    args: [selectionOnly],
    func: (onlySelected) => {
      const anchors = [...document.querySelectorAll("a[href]")];
      const sel = window.getSelection();
      const keep = onlySelected && sel && sel.rangeCount ? anchors.filter((a) => sel.containsNode(a, true)) : anchors;
      return keep.map((a) => a.href);
    },
  });
  return (result && result.result) || [];
}

// ---- Audio and video seen on each tab ----

async function mediaOf(tabId) {
  const key = `media-${tabId}`;
  return (await chrome.storage.session.get(key))[key] || [];
}

async function setMedia(tabId, list) {
  await chrome.storage.session.set({ [`media-${tabId}`]: list });
}

// List changes run one after another, so two answers arriving together don't overwrite each other.
let queue = Promise.resolve();
function serial(fn) {
  queue = queue.then(fn, fn);
  return queue;
}

const checked = new Map(); // tab -> playlists already read on the current page

async function tabTitle(tabId) {
  try {
    return (await chrome.tabs.get(tabId)).title || "";
  } catch (_) {
    return "";
  }
}

/** Reads a streamed-video playlist once and lists it (best quality first), hiding the single-quality parts of it. */
async function addStream(tabId, url) {
  if (!checked.has(tabId)) checked.set(tabId, new Set());
  const seen = checked.get(tabId);
  if (seen.has(url)) return;
  seen.add(url);
  let text = "";
  try {
    text = await (await fetch(url, { credentials: "include" })).text();
  } catch (_) {
    return;
  }
  const s = summarizePlaylist(text, url);
  if (!s.valid) return;
  const name = titleToName(await tabTitle(tabId));
  await serial(async () => {
    let list = await mediaOf(tabId);
    const hidden = new Set(list.flatMap((m) => m.variants || []));
    if (hidden.has(url)) return; // one quality of a stream already listed
    list = list.filter((m) => !s.variants.includes(m.url));
    const item = {
      url, kind: "stream", name: `${name}.mp4`, size: -1, height: s.height, variants: s.variants, protected: s.protected, live: s.live,
    };
    await setMedia(tabId, sortMedia(addMedia(list, item)));
  });
}

async function onMediaResponse(d) {
    if (d.tabId < 0 || d.statusCode >= 400) return;
    // On video sites the app's video engine saves the whole video, so the small pieces the player loads are skipped.
    if (isVideoSite(d.initiator || d.documentUrl || d.originUrl || "")) return;
    const type = header(d.responseHeaders, "content-type");
    if (isStreamPlaylist(d.url, type)) return addStream(d.tabId, d.url);
    const range = header(d.responseHeaders, "content-range");
    const total = range.includes("/") ? Number(range.split("/").pop()) : Number(header(d.responseHeaders, "content-length") || -1);
    if (!isMedia(d.url, type, Number.isFinite(total) ? total : -1)) return;
    const item = { url: d.url, name: nameFromUrl(d.url), size: total > 0 ? total : -1, type: type.split(";")[0] };
    await serial(async () => setMedia(d.tabId, sortMedia(addMedia(await mediaOf(d.tabId), item))));
}

// Only responses that can be audio, video or a stream playlist reach the extension: players' own requests (media, object)
// and script requests whose address looks like a media file. Watching every request of every page cost a lot of
// processing on busy sites.
const MEDIA_URLS = ["m3u8", "mpd", "mp4", "m4v", "m4a", "webm", "mkv", "mov", "mp3", "aac", "flac", "ogg", "opus", "wav"]
  .map((ext) => `*://*/*.${ext}*`);
chrome.webRequest.onResponseStarted.addListener(onMediaResponse, { urls: ["<all_urls>"], types: ["media", "object"] }, ["responseHeaders"]);
chrome.webRequest.onResponseStarted.addListener(onMediaResponse, { urls: MEDIA_URLS, types: ["xmlhttprequest", "other"] }, ["responseHeaders"]);

chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (change.status === "loading" && change.url) {
    checked.delete(tabId);
    serial(() => setMedia(tabId, []));
  }
});
chrome.tabs.onRemoved.addListener((tabId) => {
  checked.delete(tabId);
  chrome.storage.session.remove(`media-${tabId}`);
});

// ---- Messages from the popup and the page button ----

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  // A page's own script only ever speaks for its own tab; only the popup (no sender.tab) may name a tab.
  const tabId = sender.tab ? sender.tab.id : msg.tabId;
  const referrer = msg.referrer || (sender.tab && sender.tab.url) || "";
  const run = async () => {
    switch (msg && msg.type) {
      case "send-link": {
        // One link the user picked (video button, popup, page menu): the app shows its download window.
        const extra = { referrer, later: !!msg.later, browser: true };
        if (msg.stream) Object.assign(extra, { stream: true, fileName: msg.fileName });
        else if (msg.site) {
          Object.assign(extra, { site: true, audioOnly: !!msg.audioOnly });
          if (Number.isInteger(msg.maxHeight) && msg.maxHeight >= 100 && !msg.audioOnly) extra.maxHeight = msg.maxHeight;
        }
        else if (msg.fileName) extra.fileName = msg.fileName;
        return { ok: await send(msg.url, extra) };
      }
      case "send-links": {
        let sent = 0;
        for (const url of msg.urls || []) if (await send(url, { referrer, later: !!msg.later })) sent++;
        return { ok: sent > 0, sent };
      }
      case "media-list":
        return { media: await mediaOf(tabId) };
      case "page-links":
        return { links: await pageLinks(tabId, false) };
      case "video-button": {
        // The page's own address when it is a plain file, otherwise what the tab has loaded.
        const seen = (await mediaOf(tabId)).filter((m) => !m.protected);
        if (/^https?:\/\//i.test(msg.src || "") && !seen.some((m) => m.url === msg.src)) {
          seen.push({ url: msg.src, name: nameFromUrl(msg.src), size: -1 });
        }
        return { media: sortMedia(seen), locked: (await mediaOf(tabId)).some((m) => m.protected) };
      }
      case "ping": {
        const r = await chrome.runtime.sendNativeMessage(HOST, { type: "ping" }).catch(() => null);
        await showStatus(!!(r && r.ok));
        return { ok: !!(r && r.ok), version: r && r.version };
      }
      default:
        return { ok: false };
    }
  };
  run().then(respond, () => respond({ ok: false }));
  return true;
});
