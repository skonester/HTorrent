import { DEFAULTS, TROUBLESHOOT, linkName, torrentLinks } from "./helpers.js";

const $ = (id) => document.getElementById(id);
const ask = (msg) => chrome.runtime.sendMessage(msg);
const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

function showError(text) {
  $("status").textContent = text;
  $("status").className = "bad";
}

// Settings
const s = { ...DEFAULTS, ...(await chrome.storage.sync.get(Object.keys(DEFAULTS))) };
$("enabled").checked = s.enabled;
$("enabled").onchange = () => chrome.storage.sync.set({ enabled: $("enabled").checked });

// Opens the desktop app, or brings it to the front if it is already open.
$("open").onclick = async () => {
  const r = await ask({ type: "open" });
  if (!r || !r.ok) showError((r && r.error) || TROUBLESHOOT);
};

// One link
$("send").onclick = async () => {
  const r = await ask({ type: "add-link", url: $("link").value });
  if (r && r.ok) $("link").value = "";
  else showError((r && r.error) || TROUBLESHOOT);
};

// Torrents on this page
$("find").onclick = async () => {
  if (!tab) return;
  // Browser pages such as the new tab page don't allow this.
  const { links = [] } = (await ask({ type: "page-links", tabId: tab.id })) || {};
  const found = torrentLinks(links);
  $("links").textContent = "";
  for (const url of found) {
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = true;
    box.value = url;
    const name = document.createElement("span");
    name.textContent = linkName(url);
    name.title = url;
    const li = document.createElement("li");
    li.append(box, name);
    $("links").append(li);
  }
  $("links").hidden = $("linkActions").hidden = found.length === 0;
  $("found").textContent = found.length ? `Found ${found.length}.` : "None found on this page.";
};
const boxes = () => [...$("links").querySelectorAll("input")];
$("all").onclick = () => boxes().forEach((b) => (b.checked = true));
$("none").onclick = () => boxes().forEach((b) => (b.checked = false));
$("add").onclick = async () => {
  const urls = boxes().filter((b) => b.checked).map((b) => b.value);
  if (!urls.length) return;
  const r = await ask({ type: "add-links", urls });
  $("add").textContent = r && r.ok ? `Added ${r.sent}` : "Failed";
};

// App status
const r = await ask({ type: "ping" });
$("status").textContent = r && r.ok ? `Connected to HTorrent ${r.version}` : TROUBLESHOOT;
$("status").className = r && r.ok ? "ok" : "bad";
