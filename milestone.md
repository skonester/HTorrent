# Milestone: Subtitles in HTorrentPlayer

HTorrentPlayer cannot show subtitles yet. This is the plan for adding them, in the order we should build it. Each stage is useful on its own, so we can stop after any of them.

## Where we are today

The player page (`HTorrent/HTorrentCompose/player/web/index.html`) came from Sasami4k(Skonester's test program) with half of a subtitle feature:

- A hidden file picker for `.vtt` and `.srt` files (`<input id="sub-input">`, line 586).
- `handleSubSelect()` (line 978) turns the chosen file into a `<track>` on the video and shows "SUBTITLES LOADED".

What's missing:

1. **Nothing opens the picker.** No menu item, button or shortcut calls `sub-input`, so users can't reach it.
2. **`.srt` files don't work.** The picker accepts them, but a `<track>` only understands WebVTT (`.vtt`). An `.srt` would load and show nothing.
3. **Subtitles that come with the torrent are ignored.** Many torrents ship `Movie.srt` next to `Movie.mp4`, but HTorrent only sends the video to the player.
4. **Dropping a subtitle file on the player loads it as a video.** The drop handler (line 1014) treats every dropped file as media, so this fails with "FORMAT NOT SUPPORTED".

## Stage 1: Load a subtitle file by hand

Goal: a user can pick a `.srt` or `.vtt` from their PC and see it on the video.

- **Add a way in.** Add a **Load Subtitles...** item to the right-click **File** menu (next to **Open URL...**, line 613) that clicks `sub-input`. Add a keyboard shortcut too (for example `U`), and list it in the `?` help overlay.
- **Convert SRT to WebVTT in the page before making the `<track>`:**
  - add a `WEBVTT` header line
  - change the comma in timestamps to a dot (`00:01:02,500` becomes `00:01:02.500`)
  - drop the numeric cue counters (optional; WebVTT ignores them)
  - normalise line endings

  Then create the blob URL from the converted text, not the original file. Keep reusing `currentSubUrl` so the old blob URL is still revoked.
- **Handle text encodings.** Many SRT files are not UTF-8; older ones are often Windows-1252. Read the file as bytes, try `TextDecoder('utf-8', { fatal: true })`, and fall back to `TextDecoder('windows-1252')` if that fails. Strip a leading byte-order mark.
- **Handle dropped subtitle files.** In the drop handler, send files ending in `.srt` or `.vtt` to the subtitle loader and everything else to `loadMedia()`. If no video is loaded yet, show a status message instead.
- **Clear subtitles when media changes.** `loadMedia()`, `loadUrl()` and `closeMedia()` should remove the old `<track>` and revoke its blob URL, so one video's subtitles don't carry over to the next.

## Stage 2: Subtitle controls

Goal: users can control subtitles once they are loaded.

- **On/off toggle:** a **Subtitles** submenu with **Off** and one entry per loaded track, switching `textTrack.mode` between `showing` and `disabled`. A shortcut such as `C` should toggle them.
- **Timing offset:** **Delay +0.5s / −0.5s** items that shift every cue's `startTime` and `endTime`. Out-of-sync subtitles are common.
- **Size and style:** style cues with the `::cue` CSS selector (font size, background, outline). A small / medium / large choice is enough to start.
- **Remember choices:** keep size and on/off in `localStorage` so they stick between sessions.

## Stage 3: Subtitles that come with the torrent

Goal: when a torrent includes subtitles for a video, the player loads them automatically.

- **Find matching files in HTorrent.** When the user clicks **Stream** (`TorrentEngine.kt`, line 63), look through the same torrent's file list for `.srt` or `.vtt` files whose name matches the video (`Movie.srt`, `Movie.en.srt`, files in a `Subs` folder, and so on). Each one already has a stream URL (`http://127.0.0.1:<port>/torrents/<id>/stream/<index>`).
- **Pass them to the player.** Add a `--subtitle <url>` argument (repeatable, with an optional language label) in:
  - `StreamPlayer.open()` (`StreamPlayer.kt`, line 13)
  - the argument parser in `player/Program.fs` (line 13)
  - the page's start URL in `startUrl` (`player/PlayerWindow.fs`, line 18), for example `&sub=<url>&sub=<url>`

  The page then reads `params.getAll('sub')` next to `params.get('src')` (line 1290).
- **Watch out for how the page fetches them.** The page is a local `file://` page and the subtitles come from HTorrent's server at `http://127.0.0.1`. That makes the request cross-origin, so it has to be fetched and converted, not linked straight into a `<track>`. HTorrent's server also rejects requests whose `Origin` header isn't its own (`HttpApi.kt`, line 29), and a `fetch()` from a `file://` page probably sends `Origin: null`. We need to test this. There are two ways to fix it; option A is recommended:
  - **A:** the F# player host downloads the subtitle itself (no `Origin` header, so the server accepts it) and passes the text to the page with `SendWebMessage`. The page then runs the same Stage 1 conversion.
  - **B:** let the server accept `Origin: null` on the `stream` endpoint only. This is simpler, but any local HTML file could then read torrent files through HTorrent's server, so it needs a security review first.
- **Subtitles that haven't downloaded yet.** Subtitle files are tiny, but in a torrent that is still downloading they may not have arrived. The stream endpoint already waits for the data. The player should load them in the background and show "SUBTITLES LOADED" when they arrive, without holding up the video.

## Later: subtitles inside the video file

Many MKV files (and some MP4s) carry subtitles inside the video file. WebView2 does not expose these to the page, so they would need extra tooling: reading the MKV/MP4 data ourselves, or bundling a tool such as ffmpeg to pull them out. `.ass`/`.ssa` styled subtitles (common for anime) would also need a renderer such as a libass WebAssembly build, or a simplified conversion to WebVTT that loses the styling. Both are much bigger jobs; leave them until Stages 1–3 are done.

## Testing checklist

- [ ] `.vtt` file loads and displays.
- [ ] `.srt` file (UTF-8) loads and displays with correct timing.
- [ ] `.srt` file in Windows-1252 shows accented characters correctly.
- [ ] Dropping a subtitle file on the player loads it as subtitles, not as a video.
- [ ] Loading a new video removes the previous video's subtitles.
- [ ] Toggle, delay and size controls work, and size/on-off are remembered after reopening the player.
- [ ] Streaming a torrent that includes `Movie.srt` next to `Movie.mp4` shows the subtitles automatically.
- [ ] Same test while the torrent is still downloading: the video starts right away and the subtitles appear once they arrive.
- [ ] A torrent with no subtitles behaves exactly as it does today.
- [ ] README updated: add subtitles to the HTorrentPlayer feature list.
