# Shared native torrent engine

The C ABI in include/nexonic_torrent.h wraps pinned libtorrent-rasterbar 2.0.11 in process. There is no Python,
Java runtime or shell dependency for desktop transfers. Android uses the same C++ engine through its JNI adapter;
Apple platforms can link the combined static archive. Client platform adapters are being integrated separately.
This directory alone does not make an application platform complete.

Build and run the generated-data test with `python3 scripts/build-torrent-native.py <target> --test`.
The toolchain is pinned to Microsoft vcpkg ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0 (2025.06.13).
Generated SDKs and dependency caches stay under .native-cache/. Each SDK includes a component inventory, upstream
copyright files and libtorrent's additional embedded source-header notices. Keep those notices with every binary.

The JSON interface exposes separate metadata preview and payload tasks, validated v1/v2/hybrid paths, per-file
priorities, pause/resume, persisted native state, peer/seed/rate/ETA statistics, upload/download limits, trackers,
DHT settings, seeding ratio/time limits, torrent creation, recheck and final per-file SHA-256 results.
A preview cannot be resumed as a payload task. Completion requires a native piece recheck and real selected-file
hashes, never merely a downloaded .torrent descriptor. Removing a task retains payload files.

Hosts fetch HTTP(S) metadata with their platform networking APIs, enforcing bounded reads, redirects and credential
isolation, then pass its Base64 bytes to the native preview. Only the host UI can approve adding actual payload
work. Existing protocol/authentication boundaries must remain explicit. State and download locations are trusted
host choices, not tracker or torrent-supplied paths. Peers and trackers can see the user's IP address.

The context serializes its calls. Hosts must drain calls before destroying a context and call pause/flush on
backgrounding or graceful exit. iOS may run peer transfers while foregrounded; it cannot claim an unrestricted
background service or indefinite background seeding. Platform-specific integration/tests document distribution
and background-execution limits.

The native test creates legal deterministic data, seeds it over loopback, previews metadata and a magnet, transfers
selected payload, pauses, destroys/recreates the native context, resumes from checkpoints, verifies pieces and final
SHA-256, modifies trackers and checks safe removal. Desktop CI runs it on Windows, macOS and both Linux architectures.
Mobile wrapper/device tests are required separately before claiming Android or iPhone/iPad completion.

### .NET hosts

`windows/Core/Torrents/NativeTorrentSession.cs` owns the C ABI session and serializes calls/disposal. Production loading uses only the application directory. `TorrentSource.ReadMetadataAsync` resolves local/HTTP(S) descriptors with an 8 MiB limit and bounded redirects; the bytes still require native validation and never represent a completed payload.

The managed real-transfer test uses two native sessions, generated legal data, loopback peers, Unicode paths, magnet metadata, file selection, checkpoint/restart, final SHA-256 and safe removal:

```
dotnet run --project native/tests/dotnet -- /absolute/path/to/libnexonic_torrent.so
```

Use `nexonic_torrent.dll` on Windows or `libnexonic_torrent.dylib` for a shared macOS test build. The Apple SDK itself remains static. This adapter does not yet switch the shipped app frontend from its existing provider.

### Apple integration in progress

`python3 scripts/build-torrent-apple.py` prepares the current Mac architecture as a local XCFramework; use `--universal-mac` for distribution, or `--ios` for device/simulator slices. The native libraries and dependencies preserve macOS 13 / iOS 17 deployment floors. Sources and notices stay pinned; generated SDKs are ignored.

The `NexonicTorrent` Swift package product provides the serialized C ABI, bounded metadata loading, typed snapshots and foreground worker. Its real loopback transfer XCTest covers Unicode paths, magnet preview, selection, checkpoint restart and final SHA-256. The Mac queue adapter is groundwork; a complete GUI and iOS application integration are not delivered by this draft.
