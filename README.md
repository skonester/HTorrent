<p align="center">
  <img src="images/HTorrent.png" alt="HTorrent logo" width="200">
</p>

# HTorrent

HTorrent is a modern, lightweight BitTorrent client for Windows by Skonester, inspired by the discontinued Halite torrent client. It downloads torrents and magnet links, searches public torrent sites, and plays video or audio in its own player, **HTorrentPlayer**, while the torrent is still downloading.

## Download

Get the latest `HTorrent-Setup-<version>.exe` from the [Releases page](https://github.com/skonester/HTorrent/releases/latest) and run it. It installs HTorrent with Start menu and optional desktop shortcuts, and it can be removed from **Settings → Apps**.

The installer can also register HTorrent as an available handler for `.torrent` files and magnet links. Windows keeps your current defaults; choose HTorrent in **Default apps** if you want those links to open here. Launches from Explorer go to the running HTorrent window.

**Requirements:** 64-bit Windows 10 or 11. HTorrentPlayer uses the Microsoft Edge WebView2 Runtime, which comes with Windows 11 and most up-to-date Windows 10 PCs. If the player reports that it is missing, install it from [Microsoft](https://developer.microsoft.com/microsoft-edge/webview2/).

## Features

- **Torrents and magnet links:** add `.torrent` files or paste magnet links, then start, pause, stop and remove them.
- **Torrent controls:** select a torrent to use the toolbar, double-click for its files, or right-click for actions including **Recheck Files**. The **All Torrents** menu makes bulk controls explicit. Removing a torrent keeps its downloaded files.
- **Torrent details:** the lower pane shows Overview, Files, Trackers, and Peers. Choose files and start streams directly from the Files tab.
- **Peer discovery:** finds peers through trackers, DHT, and local network discovery.
- **Choose files:** download only the files you want from a torrent.
- **Settings:** save the download folder and global speed limits, and choose whether new or restored torrents start automatically.
- **Resume:** your torrents come back when you reopen HTorrent, without re-checking files that haven't changed.
- **Search:** searches PirateBay, YTS, EZTV, Nyaa and 1337x at once, and downloads a result with one click.
- **Streaming:** watch a video before the download finishes (see below).

## Streaming with HTorrentPlayer

1. Double-click a torrent in the list, or select it and click **Files**. Its Files tab opens.
2. Click **Stream** next to a video or audio file.
3. HTorrentPlayer opens and starts playing. HTorrent downloads the part you are watching first; the player shows **BUFFERING** while it waits for data and **PLAYING** once it has enough.

You can seek anywhere in the video; HTorrent fetches that part next. The seek bar shows how much has arrived, and pressing the arrow keys several times adds up into one jump (for example +15s), so HTorrent re-prioritizes pieces once instead of on every press. HTorrentPlayer remembers where you stopped each video and resumes from there.

Right-click the video (or click **⋮** on the control bar) for playback speed, loop, aspect ratio, brightness and other video adjustments, fullscreen, picture-in-picture, playback stats and the ambilight glow. Scroll over the volume or speed control to change it. The settings button changes the accent color, the control bar's look and when it hides. Press `?` for keyboard shortcuts. The player closes when HTorrent closes. If it can't start, the stream opens in your web browser instead.

What plays depends on the formats WebView2 supports. MP4 (H.264/AAC) and WebM work best; for other formats the player shows **FORMAT NOT SUPPORTED**.

## Where things are stored

- **Downloads:** `Downloads\HTorrent` in your user folder. Change it from **Settings → Change Download Folder**.
- **Settings:** `.htorrent\settings.properties` in your user folder.
- **Error log:** `.htorrent\logs\htorrent-errors.log` in your user folder, created if a startup or background error occurs.
- **Torrent list and resume data:** `.htorrent\session` in your user folder.
- **Player data:** `%LOCALAPPDATA%\HTorrent\HTorrentPlayer`.

Uninstalling HTorrent leaves these alone.

## Building from source

You need [JDK 21](https://adoptium.net/), the [.NET 10 SDK](https://dotnet.microsoft.com/download) and [NSIS 3](https://nsis.sourceforge.io/) (`makensis` on your `PATH`). From `HTorrent/HTorrentCompose`:

```powershell
./gradlew.bat test              # run the tests
./gradlew.bat packageApp        # build the app into build\dist\HTorrent (run HTorrent.exe there)
./gradlew.bat packageInstaller  # build build\installer\HTorrent-Setup-<version>.exe
```

### Project layout

| Path | Contents |
| --- | --- |
| `HTorrent/HTorrentCompose/src/main/kotlin` | The app: Compose Desktop UI (`Main.kt`, `SearchWindow.kt`) and the bridge to the engine (`TorrentEngine.kt`, `StreamPlayer.kt`) |
| `.../com/htorrent/engine` | The torrent engine: peers, DHT, trackers, storage, resume, and the local HTTP API that serves streams |
| `.../com/htorrent/search` | Torrent site search |
| `HTorrent/HTorrentCompose/player` | HTorrentPlayer, an F# app using [InfiniFrame](https://github.com/InfiniLore/InfiniFrame) (WebView2) |
| `HTorrent/HTorrentCompose/launcher` | The small native `HTorrent.exe` that starts the Java app |
| `HTorrent/HTorrentCompose/installer` | The NSIS installer script |

### Releases

Every push to `main` that changes `HTorrent/` builds the installer on GitHub Actions and publishes it to the release for the current version, `v<version>`. The version comes from `version` in `HTorrent/HTorrentCompose/build.gradle.kts`. If a release for that version already exists, its installer is replaced with the new build. To publish a new version as its own release, bump the version in `build.gradle.kts`, `launcher/HTorrent.Launcher.csproj`, `player/HTorrentPlayer.fsproj` and the window title in `Main.kt`.

## License

HTorrent, including HTorrentPlayer, is free software released under the [GNU General Public License v3.0](LICENSE). You can use, study, share and change it; if you distribute a modified version, it must also be under GPL-3.0 with its source code available.

Copyright © 2026 Skonester.

## Credits

HTorrent includes code adapted from these projects, used under their own licenses (all compatible with GPL-3.0):

- The torrent engine is a Kotlin adaptation of [rqbit](https://github.com/ikatson/rqbit) by Igor Katson (Apache-2.0). See `RQBIT-NOTICE.md`.
- Search is a Kotlin/JVM adaptation of [TorrentSearch-Kotlin](https://github.com/DrewCarlson/TorrentSearch-Kotlin) by Andrew Carlson (MIT). See `TORRENTSEARCH-NOTICE.md`.
- The toolbar uses the original bitmap strip from [Halite](https://github.com/Eoinocal/Halite). See `HALITE-NOTICE.md` and `licenses/Halite-LICENSE.txt`.
- HTML parsing uses [jsoup](https://jsoup.org/) (MIT).
- HTorrentPlayer is built on [InfiniFrame](https://github.com/InfiniLore/InfiniFrame) (Apache-2.0).
- HTorrentPlayer's interface is adapted from [Perdanga VSP](https://gitlab.com/perdanga/perdanga-vsp) by PerdangaSoftware (MIT).

The license texts are in `HTorrent/HTorrentCompose/licenses` and are installed with the app.
