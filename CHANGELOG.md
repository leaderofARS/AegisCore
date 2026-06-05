# Changelog

All notable changes to the AegisCore project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-06-05

### Added
- **TCP Socket Server:** Multithreaded TCP engine listening on port 5000 with connection handler threads (`ClientHandler`) spawned per client connection.
- **Protocol Command Engine:** Text-based protocol supporting 11 core commands: `NAME`, `CREATE`, `JOIN`, `LIST`, `QUEUE`, `DEQUEUE`, `READY`, `UNREADY`, `CHAT`, `STATS`, and `QUIT`.
- **Player Registry System:** A centralized, thread-safe player registration manager (`PlayerRegistry`) mapping player sessions using a thread-safe `ConcurrentHashMap`.
- **Room Registry & Lifecycle:** Dynamic room creation (`RoomRegistry`) supporting slots management, player list propagation, ready checks, and room state machine transitions (`RoomState` from `WAITING` to `READY_CHECK`, `IN_PROGRESS`, and `CLOSED`).
- **5-Second Countdown Timer:** Automatic game session startup sequence using a shared `ScheduledExecutorService`. Supports countdown cancellation if a player issues `UNREADY` during the window.
- **Matchmaking System:** An automated matchmaking engine (`MatchmakingQueue`) running on a dedicated background daemon thread, utilising a `LinkedBlockingQueue` to auto-pair players into rooms.
- **Telemetry System:** Real-time statistics telemetry (`ServerStats`) tracking connection lifetime metrics, relayed messages, and socket byte throughput.
- **Concurrently-Safe Logging:** Centralized Logger (`Logger`) utilizing 4 isolated file-write lock monitors to log server, registry, client-handler, and client-side events safely without global bottlenecking.
- **Graceful Shutdown Hook:** A 3-phase server shutdown routine triggered by termination signals (SIGINT) to clean up client threads, close sockets, and shut down executor pools.

### Changed
- **Socket Writer Synchronization:** Thread-safe output stream wrapping (`ClientHandler.sendMessage`) to serialize socket writes and prevent byte interleaving during simultaneous broadcasts.
- **Broadcasting Collection Safety:** Weakly-consistent iterators for client broadcasting loops to prevent `ConcurrentModificationException` when players disconnect mid-broadcast.
