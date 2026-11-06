import { DEFAULTS, HOST, TROUBLESHOOT, isMagnet, isTorrentFile, isTorrentLink } from "./helpers.js";

async function settings() {
  return { ...DEFAULTS, ...(await chrome.storage.sync.get(Object.keys(DEFAULTS))) };
}

/** Sends one request to HTorrent. A helper that doesn't answer within a few seconds counts as unreachable. */
async function callApp(message) {
  let reply = null;
  try {
    reply = await Promise.race([
      chrome.runtime.sendNativeMessage(HOST, message),
      new Promise((resolve) => setTimeout(() => resolve(null), 8000)),
    ]);
  } catch (_) {
    reply = null;
  }
  await showStatus(!!reply);
  return reply || { ok: false, error: TROUBLESHOOT };
}

/** The toolbar button shows "!" and says what to do while the app can't be reached. */
async function showStatus(reachable) {
  await chrome.action.setBadgeText({ text: reachable ? "" : "!" });
  if (!reachable) await chrome.action.setBadgeBackgroundColor({ color: "#cf222e" }).catch(() => {});
  await chrome.action.setTitle({ title: reachable ? "HTorrent" : TROUBLESHOOT }).catch(() => {});
}

/** A magnet goes straight to the app. A .torrent link is downloaded by the browser (so site logins work), then handed over. */
async function addLink(link) {
  if (isMagnet(link)) return callApp({ type: "add-magnet", url: link.trim() });
  if (isTorrentLink(link)) {
    await chrome.downloads.download({ url: link.trim() });
    return { ok: true };
  }
  return { ok: false, error: "Only magnet links and .torrent files can be added" };
}

// ---- Finished .torrent downloads go to the app ----

chrome.downloads.onChanged.addListener(async (delta) => {
  if (!delta.state || delta.state.current !== "complete") return;
  if (!(await settings()).enabled) return;
  const [item] = await chrome.downloads.search({ id: delta.id });
  if (item && isTorrentFile(item.filename)) await callApp({ type: "add-file", path: item.filename });
});

// ---- Right-click menu ----

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "htorrent-add",
    title: "Add to HTorrent",
    contexts: ["link"],
    targetUrlPatterns: ["magnet:*", "*://*/*.torrent", "*://*/*.torrent?*"],
  });
});

chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === "htorrent-add" && info.linkUrl) addLink(info.linkUrl).catch(() => {});
});

/** Links on a page. */
async function pageLinks(tabId) {
  const [result] = await chrome.scripting.executeScript({
    target: { tabId },
    func: () => [...document.querySelectorAll("a[href]")].map((a) => a.href),
  });
  return (result && result.result) || [];
}

// ---- Messages from the popup and the page ----

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  const run = async () => {
    switch (msg && msg.type) {
      case "click-magnet":
        // A magnet clicked on a page. Turned off, it opens however the computer normally opens magnets.
        return (await settings()).enabled ? callApp({ type: "add-magnet", url: msg.url }) : { ok: false };
      case "add-link":
        return addLink(msg.url);
      case "add-links": {
        let sent = 0;
        for (const link of msg.urls || []) if ((await addLink(link)).ok) sent++;
        return { ok: sent > 0, sent };
      }
      case "page-links":
        return { links: await pageLinks(msg.tabId) };
      case "ping":
        return callApp({ type: "ping" });
      case "open":
        return callApp({ type: "open" });
      default:
        return { ok: false };
    }
  };
  run().then(respond, (e) => respond({ ok: false, error: e && e.message }));
  return true;
});
