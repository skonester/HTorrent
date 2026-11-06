# HTorrent for Android

A rebranded copy of the Android app from [Nexonic Download Manager](https://github.com/Shahrukhalid/nexonic-download-manager) by Shahrukh Khalid. See [NEXONIC-NOTICE.md](../NEXONIC-NOTICE.md).

Native Android app (Kotlin, Jetpack Compose), Android 10 or later. Same download rules as the Mac and Windows apps.

## What it does

- **Fast downloads:** up to 32 connections per file. A connection that finishes takes over half of the largest part still downloading.
- **Pause and resume**, also after the app or the phone restarts. A file that changed on the server is downloaded again instead of mixed up. Expired links can be refreshed.
- **Built-in browser** with an address bar, back/forward and search. Downloads started on a page open the download window (name, size, type, whether it can resume). Long-press a link or image to download it. **Found on this page** lists the videos, music and files on the page, with their quality labels when the page names them.
- **Share from any browser:** in Chrome or another app, *Share → Download with HTorrent*. Web links can also be opened with HTorrent.
- **Queues:** several downloads at a time (1–6), others wait their turn. *Later* keeps a download for the scheduler or *Start all*.
- **Scheduler:** start downloads kept for later at a set time every day.
- **Background downloads** with a progress notification and *Pause all*; a notification when each file is done.
- **Wi-Fi only**, speed limit, retries, logins (user name and password), batch patterns like `file[01-50].jpg`, several links pasted at once.
- **Files:** finished files go to *Download/HTorrent* (by type: Video, Music, Documents…), visible in the Files app. Open, share or delete them from the app.
- **List:** All / Unfinished / Finished, filter by type, search, and per-download details with each connection's progress.
- **Private:** no account, ads or tracking. Cookies and passwords are encrypted with a key held in the Android Keystore, and sent only to their own site (never after a redirect to another site or from https to http). Third-party cookies are blocked in the browser by default. The app is excluded from cloud backup.

## Limits on Android

- Chrome for Android does not run desktop extensions. Use the built-in browser, or *Share → Download with HTorrent* from Chrome.
- Video pages (YouTube, Facebook, Instagram, TikTok and 1,000+ sites) go through the video engine: yt-dlp with Python and ffmpeg, from [youtubedl-android](https://github.com/yausername/youtubedl-android). It updates yt-dlp once a day (Settings → Video engine). It adds about 50 MB, so releases ship one APK per processor type: `HTorrent-Android.apk` (64-bit ARM) and `-32bit.apk`. Copy-protected (DRM) video is not supported.
- Copy-protected video is never supported.
- Android limits background work. Downloads run as a data-sync foreground service. Android 15 may stop it after several hours; downloads then wait and continue when you open the app. Exact scheduled starts need *Alarms & reminders* permission; without it, Android may delay the start or show a notification to tap.

## License

The original Android code is by Shahrukh Khalid and is used here under the MIT License (upstream offers MIT OR
Apache-2.0). The APK also contains [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) with Python and
an FFmpeg built with GPL parts, and the rest of this repository is GPL-3.0, so the APK is distributed under **GPL-3.0**.
The app lists every component, its license and where to get the source under *Settings -> About -> Open-source licenses*
([app/src/main/assets/licenses](app/src/main/assets/licenses)).

## Build

The *Android and extension* GitHub Actions workflow builds a debug APK you can install on a phone. It builds the torrent
engine from source first, so the first run takes much longer than later ones.

To build locally without the torrent engine (JDK 17, Android SDK, Gradle 8.11):

```bash
gradle -p android testDebugUnitTest assembleDebug
```

The version comes from `VERSION` at the repository root. Release builds are signed only when the `HTORRENT_ANDROID_KEYSTORE`,
`HTORRENT_ANDROID_KEYSTORE_PASSWORD`, `HTORRENT_ANDROID_KEY_ALIAS` and `HTORRENT_ANDROID_KEY_PASSWORD` environment variables
are set. Keys are never stored in the repository.
