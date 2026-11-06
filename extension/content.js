// Sends magnet links the user clicks to HTorrent. Runs in every page, and does nothing until a magnet link is clicked.
(() => {
  if (window.__htorrentMagnets) return;
  window.__htorrentMagnets = true;

  document.addEventListener(
    "click",
    (e) => {
      // isTrusted: a page's own script can't add a torrent by clicking a link for itself.
      if (!e.isTrusted || e.button !== 0 || !chrome.runtime?.id) return;
      const link = e.composedPath().find((node) => node.tagName === "A" && /^magnet:\?/i.test(node.href || ""));
      if (!link) return;
      e.preventDefault();
      const url = link.href;
      chrome.runtime.sendMessage({ type: "click-magnet", url }, (reply) => {
        if (chrome.runtime.lastError || !reply || !reply.ok) location.href = url;
      });
    },
    true,
  );
})();
