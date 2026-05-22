# CHANGELOG

All notable changes to this project are documented here.

This file follows the principles of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Format:
```
## [version] - YYYY-MM-DD
### Added / Changed / Fixed / Removed / Security
```

---

## [0.0.6] - 2026-05-22 — Level 2.4.1: Thread Safety & Synchronization Hardening

### Added
- `src/ServerStats.java` — New immutable telemetry snapshot class. All fields are `final`, giving JMM-safe cross-thread publication without any additional synchronization. Produced by `SharedClientRegistry.getStats()`.
- `AtomicLong totalConnectionsAccepted` in `SharedClientRegistry` — monotonic lifetime connection counter. Incremented with a single CAS instruction per connection — zero lock overhead.
- `AtomicLong totalMessagesRelayed` in `SharedClientRegistry` — counts total broadcast events dispatched since server start (one increment per `BroadcastMessage()` call).
- `AtomicLong totalBytesSent` in `SharedClientRegistry` — accumulates approximate bytes written across all client streams. Useful for throughput profiling in Level 2.5.
- `SharedClientRegistry.getStats()` — returns a `ServerStats` snapshot via atomic volatile reads. Safe to call from any thread at any time without locking.
- `Logger.logRegistry()` and `Logger.logRegistryError()` — new log methods routing to `logs/Registry.log`, separating registry lifecycle events from server socket events.

### Changed
- **`ClientHandler.java` — Eliminated dual-writer output stream race condition.**
  - Promoted `PrintWriter output` from a local variable to a class field, initialized once in `run()`.
  - All writes (welcome message, ACKs, broadcast deliveries) now flow exclusively through the single `synchronized sendMessage()` method.
  - Previously, `sendMessage()` created a new `PrintWriter(socket.getOutputStream())` on every call while `run()` held its own separate `PrintWriter` on the same stream — two writers on the same `OutputStream` with no coordinated locking. Under concurrent broadcast, this produced interleaved byte sequences and garbled client output. This is now fully eliminated.
  - Added `output.checkError()` check after every write to surface broken connections that `PrintWriter` swallows silently.
- **`SharedClientRegistry.java` — Replaced all `System.out.println()` with `Logger.logRegistry()`.**
  - Raw `System.out.println()` calls under concurrent load produce interleaved, partially-written console lines. Routing through the synchronized Logger serializes all registry output.
  - `BroadcastMessage()` now documents the weakly-consistent iterator contract, per-client lock acquisition pattern, and failure isolation behaviour.
- **`Logger.java` — Fixed log filename typo and added registry log channel.**
  - `CliendHandler.log` → `ClientHandler.log` (typo fixed).
  - Added `logRegistry()` / `logRegistryError()` writing to `logs/Registry.log`.

### Architecture Notes
- **Fine-grained locking principle demonstrated:** `sendMessage()` holds only the intrinsic lock on a single `ClientHandler` instance. Broadcast writes to different clients proceed in parallel — only writes to the *same* client are serialized. No global lock is ever held during `BroadcastMessage()` iteration.
- **Lock contention analysis:** The synchronized scope is exactly one `output.println()` call per client per message. This is the minimum viable critical section. Over-synchronization (locking the registry during broadcast) is explicitly avoided.
- **`AtomicLong` vs `synchronized int`:** For high-frequency numeric counters, CAS-based atomics eliminate lock acquisition overhead entirely. Suitable for telemetry under thousands of concurrent events per second.
- All changes retain the zero-external-dependency constraint (pure Java 21 standard library).

---

## [0.0.5] - 2026-05-20 — Level 2.3: Logging Infrastructure & Concurrency Resilience

### Added
- `src/Logger.java` — A high-performance, thread-safe logging system writing to `/logs` directory under specific outputs:
  - `Server.log` for overall socket server connections and lifecycles.
  - `CliendHandler.log` for per-client messaging, input events, and thread states.
  - `ClientID.log` for client-side diagnostic outputs.
- `failure_scenarios.md` — Deep systems-level diagnostic documentation covering critical TCP server failure modes: Client Disconnects, Slow Consumers (Network Backpressure), and Message Storms.
- Ignored local systems engineering documentation (`failure_scenarios.md`) by appending it to the root `.gitignore`.

### Changed
- Refactored `Server.java`, `ClientHandler.java`, and `Client.java` to route all server/client console and diagnostic outputs through the centralized thread-safe `Logger` utility.
- Synchronized `ClientHandler.sendMessage()` method to prevent scrambled interleaved outputs and socket descriptor corruptions during high-frequency concurrent broadcasts.

### Architecture Notes
- Achieved thread-safe atomic file writing without external package dependencies (retaining plain Java 21 standard library compilation).
- Solved socket write race conditions using atomic execution hooks inside the client thread broadcast pipeline.

---

## [0.0.4] - 2026-05-20 — Level 3: Shared State & Synchronization

### Added
- `SharedClientRegistry.java` — a thread-safe singleton global client registry using `ConcurrentHashMap` to track all connected clients.
- `BroadcastMessage(String message)` method in `SharedClientRegistry` to iterate over active client handlers and safely distribute messages.
- Asynchronous real-time messaging in `Client.java` using a background `ServerListener` daemon thread.
- Dedicated `cleanup()` and self-unregistration lifecycle hook in `ClientHandler.java` to remove disconnected client IDs from the registry and close sockets cleanly.
- Automated concurrency stress test suite under `tests/LoadTest.java` and `scripts/stress-test.ps1`.

### Changed
- Refactored `ClientHandler.java` to delegate global broadcast distribution through the thread-safe shared registry.
- Simplified `Client.java` main loop to focus exclusively on reading keyboard input and writing to socket.

### Architecture Notes
- Achieved thread-safe shared state using high-performance concurrent collections (`ConcurrentHashMap`).
- Formulated real-time client event loop avoiding synchronization bottlenecks.

---

## [0.0.3] - 2026-05-18 — Level 2: Multithreading Entry

### Added
- `ClientHandler.java` — dedicated `Thread`-based handler for each connected client
- Per-client independent communication loop (`while readLine()`)
- Clean session termination on `exit` command
- Server-side log output for connect and disconnect events

### Changed
- Server now spawns a new `Thread` for each accepted connection instead of handling serially
- Architecture now supports N simultaneous clients (bounded by OS thread limits)

### Known Issues
- Thread explosion risk: one OS thread per client, no pool limit
- No shared state between client threads (addressed in Level 3)
- No authentication (addressed in Level 8)

### Architecture Notes
- Current threading model: `new Thread(clientHandler).start()` — intentional for Level 2 learning
- This will be replaced with `ExecutorService` in Level 6
- Blocking I/O will be replaced with Java NIO `Selector`/`Channel` in Level 11

---

## [0.0.2] - 2026-05-17 — Level 1: Raw Socket Networking

### Added
- `Server.java` — TCP server listening on port 5000
- `Client.java` — TCP client connecting to localhost:5000
- Bidirectional text-based communication over TCP
- `ServerSocket.accept()` loop for incoming connections
- `BufferedReader` / `PrintWriter` for stream-based I/O
- `.gitignore` — excludes compiled `.class` files and IDE metadata

### Architecture
- Single-threaded server (one client at a time)
- Blocking I/O: `accept()`, `readLine()` both block the main thread
- Port: 5000, Encoding: UTF-8, Delimiter: newline (`\n`)

### Decision
- Chose TCP over UDP: reliable delivery required for stateful sessions
- Chose blocking I/O for Level 1: simplest correct model before introducing threads

---

## [0.0.1] - 2026-05-16 — Level 0: Project Initialization

### Added
- Project directory structure created
- Java 21 development environment confirmed
- Initial README.md stub

### Status
- Level 0 (Java Core Foundation) confirmed complete
- Development roadmap established across 17 engineering levels

---

*Engineering record maintained from project Day 1.*
