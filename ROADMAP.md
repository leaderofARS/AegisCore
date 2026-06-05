# AegisCore Project Roadmap

AegisCore is a high-performance, multithreaded TCP game lobby server designed to provide a lightweight, robust, and zero-dependency infrastructure layer for multiplayer game backends.

This roadmap details the progression phases from the core TCP lobby engine to virtual reality state synchronization and distributed clustering.

---

## Phase 1: Core Lobby Engine (Complete)
- **Networking Base:** Stable multithreaded TCP socket server listening on port 5000 with connection handler threads.
- **Protocol Dispatching:** Command router parsing client inputs into Command Contexts and executing commands (`NAME`, `CREATE`, `JOIN`, `LIST`, `QUEUE`, `DEQUEUE`, `READY`, `UNREADY`, `CHAT`, `STATS`, `QUIT`).
- **Lobby Management:** Room registries with customizable slot sizes, ready-state checking, and a 5-second countdown timer.
- **Matchmaking Engine:** Background daemon thread paired with blocking queues to auto-match players.
- **Resilient Multithreading:** Thread-safe registries, serialized socket writer streams, and isolated exception scopes to prevent cascading network failures.
- **Telemetry & Logs:** Telemetry metrics snapshotting and concurrent per-channel file logging.

## Phase 2: Performance & Scalability (Planned)
- **Thread Pool Migration:** Replace naive thread-per-client allocation with a custom or JVM-backed `ExecutorService` thread pool.
- **Connection Timetouts:** Heartbeat PING/PONG keep-alive frames and timeout policies for evicting idle/dead connections.
- **Non-Blocking I/O (NIO):** Migrate from standard Java Blocking I/O to a Java NIO event loop utilizing selectors and channels to handle 10,000+ concurrent clients.

## Phase 3: Identity & Security (Planned)
- **Secure Authentication:** Player account registration and credentials verification (secure password hashing using BCrypt).
- **Session Tokens:** JWT-based or custom session tokens for secure, stateless state validation.
- **Transport Encryption:** Support TLS/SSL for secure data transmission over the wire.
- **Persistent Storage:** Integrate databases (PostgreSQL/SQLite) using connection pools (HikariCP) for persistent user data.

## Phase 4: Integration & Clustering (Planned)
- **WebSocket Gateway:** Add a WebSocket protocol adapter to bridge web-based (JS/HTML5) clients directly to the game lobby.
- **gRPC Inter-Service Communication:** Use gRPC to communicate with secondary game world servers or services.
- **Distributed Clustering:** Horizontal scaling support using Redis pub/sub and Apache Kafka event streams for synchronization across lobby nodes.

## Phase 5: VR & Spatial Infrastructure (Planned)
- **VR Client SDKs:** Dedicated integration packages for Unity, Unreal Engine, and Godot.
- **Spatial Audio Routing:** Metadata-driven spatial routing protocols to match voice and environment channels within lobbies.
- **Motion-State Sync:** High-frequency, low-overhead position and motion-state synchronization protocol for pre-match VR spatial lobbies.
