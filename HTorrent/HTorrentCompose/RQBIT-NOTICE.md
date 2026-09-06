# rqbit attribution and Kotlin port

This distribution includes Kotlin adaptations of code and algorithms from the
rqbit source tree used during the port, originally written by Igor Katson and rqbit
contributors. Upstream: https://github.com/ikatson/rqbit

Copyright 2021 Igor Katson. Licensed under the Apache License, Version 2.0.
The upstream copyright notice is reproduced in `licenses/rqbit-LICENSE.txt`;
the complete license is in `licenses/Apache-2.0.txt`.

The adaptations are in `src/main/kotlin/com/htorrent/engine`. They replace the
previous `bt-core`/`bt-dht` backend. They run in the JVM and do not invoke Rust,
an external rqbit process, or the old Java torrent library.

| Kotlin file | Upstream rqbit source |
| --- | --- |
| Bencode.kt | crates/bencode; librqbit_core/compact_ip |
| Metainfo.kt | librqbit_core/magnet, torrent_metainfo, lengths |
| Dht.kt | dht/dht, routing_table, peer_store, persistence |
| Trackers.kt | tracker_comms/tracker_comms_http, tracker_comms_udp |
| Storage.kt | librqbit/storage/filesystem, chunk_tracker, piece_tracker |
| Session.kt | librqbit/session, torrent_state, session_persistence |
| PeerConnection.kt | peer_binary_protocol, librqbit/peer_info_reader, live/peer |
| LocalDiscovery.kt | librqbit_lsd; upnp |
| Resume.kt | session_persistence; persisted have bitmap |
| HttpApi.kt | http_api/handlers; torrent_state/streaming |

The Kotlin adaptation retains a shared session/DHT, sequential piece selection,
exclusive piece ownership, the 10x/reserve/3x slow-peer steal order, hash checking
before disk writes, failed/disconnected piece requeueing, metadata hash checking,
tracker tiers, and private-torrent discovery restrictions. JVM sockets, locks,
executors and atomic file replacement replace Rust/Tokio primitives.

Implemented: v1 and hybrid metainfo, hex/base32 magnets, BEP-53 file selection,
IPv4/IPv6 compact peers and DHT nodes, DHT queries/responses/announces, routing
bucket splitting, node persistence/refresh, HTTP(S)/UDP trackers, TCP peers,
metadata exchange, PEX, LAN discovery, seeding, padding files, multifile storage,
pause/resume, session persistence, file-fingerprint-validated fast resume,
bandwidth limiting, UPnP port leases, and a local management/streaming HTTP API.

This is not complete rqbit feature parity. The upstream uTP transport, SOCKS
proxy, UPnP media server, mDNS advertising, directory watcher, Prometheus
endpoints, torrent creation, and full HTTP API surface are not ported here.
Pure v2 torrents are rejected explicitly. The Kotlin HTTP API uses info hashes
as identifiers and binds only to loopback. The Compose interface retains the
existing bulk controls and exposes file selection, streaming and rate limits. Network interoperability and performance beyond the
recorded tests still require broader verification.
