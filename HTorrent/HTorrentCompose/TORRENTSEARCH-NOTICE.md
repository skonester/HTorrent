# TorrentSearch attribution and Kotlin/JVM port

The torrent search window includes a Kotlin/JVM adaptation of TorrentSearch-Kotlin,
originally written by Andrew Carlson. Upstream: https://github.com/DrewCarlson/TorrentSearch-Kotlin

Copyright (c) 2020 Andrew Carlson. Licensed under the MIT License; the license is
reproduced in `licenses/TorrentSearch-Kotlin-LICENSE.txt`.

The adaptation is in `src/main/kotlin/com/htorrent/search`.

| Kotlin file | Upstream TorrentSearch source |
| --- | --- |
| TorrentSearch.kt | TorrentSearch.kt, TorrentProvider.kt, models/*, providers/BaseTorrentProvider.kt |
| Providers.kt | providers/PirateBayProvider, YtsProvider, EztvProvider, NyaaProvider, X1337Provider |
| Json.kt | (new) replaces kotlinx.serialization |

Upstream is a Kotlin Multiplatform library built on Ktor, kotlinx.serialization and
ktsoup. This port uses `java.net.http.HttpClient`, a small JSON reader, and jsoup
(MIT, `licenses/jsoup-LICENSE.txt`), so HTorrent keeps its current Kotlin and Compose versions.
Providers run in parallel on a thread pool and report results one at a time instead of through coroutine flows.

Changes from upstream:
- YTS uses `movies-api.accel.li`, because `yts.mx` no longer resolves. It falls back to `yts.lt`.
- PirateBay queries keep digits (upstream removed them, so a search for "ubuntu 24.04" lost the version).
- Nyaa's Books category maps to Literature (`3_0`).
- Generated magnets use a current list of public trackers.
- Nyaa and 1337x are off by default. 1337x usually answers with a Cloudflare challenge (HTTP 403).

Not ported: the NZBGeek (NZB, API key) and Libre (fixed test data) providers, the
provider result cache, and multi-page `nextResult` loading.
