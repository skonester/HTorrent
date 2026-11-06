import assert from "node:assert/strict";
import test from "node:test";
import { isMagnet, isTorrentFile, isTorrentLink, linkName, torrentLinks } from "./helpers.js";

const MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Some%20Linux";

test("magnet links", () => {
  assert.equal(isMagnet(MAGNET), true);
  assert.equal(isMagnet("MAGNET:?xt=urn:btih:abc"), true);
  for (const bad of ["https://example.com/a.torrent", "magnet:", "javascript:alert(1)", "", null]) assert.equal(isMagnet(bad), false, String(bad));
});

test("downloaded .torrent files", () => {
  assert.equal(isTorrentFile("C:\\Users\\x\\Downloads\\Some Linux.TORRENT"), true);
  assert.equal(isTorrentFile("C:\\Users\\x\\Downloads\\a.torrent.zip"), false);
  assert.equal(isTorrentFile("C:\\Users\\x\\Downloads\\a.zip"), false);
});

test(".torrent web links", () => {
  assert.equal(isTorrentLink("https://example.com/files/a.torrent"), true);
  assert.equal(isTorrentLink("http://example.com/a.torrent?key=1"), true);
  assert.equal(isTorrentLink("https://example.com/a.zip"), false);
  assert.equal(isTorrentLink("file:///C:/a.torrent"), false);
  assert.equal(isTorrentLink("not a url"), false);
});

test("finds only torrents on a page, once each", () => {
  const links = [MAGNET, MAGNET, "https://example.com/a.torrent", "https://example.com/page.html", "mailto:x@example.com"];
  assert.deepEqual(torrentLinks(links), [MAGNET, "https://example.com/a.torrent"]);
});

test("readable names", () => {
  assert.equal(linkName(MAGNET), "Some Linux");
  assert.equal(linkName("magnet:?xt=urn:btih:abc"), "Magnet link");
  assert.equal(linkName("https://example.com/files/My%20File.torrent"), "My File.torrent");
});
