# Architecture Decision Records (ADRs)

This document records the architectural decisions made during the design and development of the AegisCore Game Lobby Server.

---

## ADR-001: TCP over UDP
- **Status:** Accepted
- **Context:** The server needs to handle player connections, room states, ready checks, and matchmaking transactions.
- **Decision:** Use TCP as the transport layer protocol.
- **Reason:** TCP provides reliable, ordered, and error-checked delivery of stream data. This is critical for session state consistency. If a connection drops, TCP detects it quickly, allowing the lobby to cleanly evict the player.
- **Consequences:** Slightly higher overhead compared to UDP, which is acceptable since lobby actions are transaction-oriented rather than high-frequency real-time physics state syncs.

---

## ADR-002: One Thread Per Client
- **Status:** Accepted (Temporary)
- **Context:** The server needs to handle multiple client sessions concurrently.
- **Decision:** Assign a dedicated OS thread (`ClientHandler`) to handle the blocking I/O loop of each connected client.
- **Reason:** Simplifies connection management. Each client's thread reads commands in an isolated loop, making code execution easy to debug.
- **Consequences:** Thread stack overhead (approx. 1MB per thread). This model does not scale well past a few hundred concurrent clients. It will be superseded in future phases by thread pools and non-blocking I/O (NIO).

---

## ADR-003: Blocking I/O Model
- **Status:** Accepted
- **Context:** Reading data from sockets.
- **Decision:** Use standard Java Blocking I/O (`java.io.BufferedReader` and `java.io.PrintWriter`).
- **Reason:** It is the simplest and most readable model for streaming text inputs.
- **Consequences:** The client handler thread blocks on `readLine()` calls until data is sent.

---

## ADR-004: Default Port 5000
- **Status:** Accepted
- **Context:** Server network binding.
- **Decision:** Bind the TCP master socket to port `5000` by default.
- **Reason:** Standard port commonly free on local development environments, preventing port conflicts.
- **Consequences:** Standard network permissions apply.

---

## ADR-005: Newline Delimiter for Protocols
- **Status:** Accepted
- **Context:** Message framing.
- **Decision:** Use the newline character (`\n`) as the delimiter for all protocol messages.
- **Reason:** Simplifies message reading. Text can be parsed directly with `BufferedReader.readLine()`, removing complex byte boundary parsing.
- **Consequences:** Messages cannot contain literal newlines.

---

## ADR-006: Java 21 Target Version
- **Status:** Accepted
- **Context:** Development environment.
- **Decision:** Target Java 21 LTS as the baseline version.
- **Reason:** Provides modern concurrency features, enums, records, pattern matching, and a foundation for Virtual Threads (Project Loom).
- **Consequences:** Requires clients/servers to run JRE 21+.

---

## ADR-007: Singleton Pattern for Registries
- **Status:** Accepted
- **Context:** Global state accessibility.
- **Decision:** Implement `PlayerRegistry` and `RoomRegistry` as singletons.
- **Reason:** Ensures there is exactly one global source of truth for active players and rooms, accessible by any client handler thread.
- **Consequences:** Requires internal collections (`ConcurrentHashMap`) to be thread-safe to handle concurrent lookups and modifications.

---

## ADR-008: CopyOnWriteArrayList for Room Player Lists
- **Status:** Accepted
- **Context:** Room broadcasts and active occupant tracking.
- **Decision:** Use a `CopyOnWriteArrayList` to store the players list inside `Room`.
- **Reason:** Eliminates `ConcurrentModificationException` during broadcast loops. Disconnect cleanup threads can remove players from a room while another thread is iterating to broadcast messages.
- **Consequences:** Write operations (join/leave) are expensive as they clone the underlying array, which is acceptable since join/leave events are infrequent compared to chat and state broadcasts.

---

## ADR-009: ScheduledExecutorService for Countdown Timers
- **Status:** Accepted
- **Context:** Ready check countdowns.
- **Decision:** Use a shared daemon `ScheduledExecutorService` for handling lobby room countdown timers.
- **Reason:** Running timers on the client handler thread would block that client from sending commands (e.g. `UNREADY`). A scheduled thread pool allows timer tasks to execute asynchronously without blocking connection threads.
- **Consequences:** Requires synchronization on the `Room` object to safely cancel scheduled countdown tasks when players toggle unready status.

---

## ADR-010: Command Router Pattern over Command Registry
- **Status:** Accepted
- **Context:** Protocol command dispatching.
- **Decision:** Use a centralized command router (`CommandRouter`) containing a command dispatcher rather than a complex registry pattern.
- **Reason:** Keeps the protocol implementation simple, lightweight, and fast. The dispatcher validates states, formats arguments, and maps commands to code paths directly.
- **Consequences:** Less modular than a decoupled command registration map, but highly readable and efficient for 11 core commands.
