# AegisCore Security Posture

This document provides a realistic overview of the security measures, design decisions, and future plans implemented in the **AegisCore** Game Lobby Server.

---

## 1. Authentication Status (By Design)

AegisCore currently implements **no password authentication**. 
- Because AegisCore is a pre-game lobby server, players register transiently during their connection lifetime using display names via the `NAME` command.
- Display names must be unique within the active `PlayerRegistry` instance.
- No user accounts are stored persistently, meaning credential harvesting risks are mitigated.

---

## 2. Thread Safety as a Security Boundary

Concurrency bugs (race conditions, data corruption) represent a severe security threat to server availability. AegisCore implements key measures to prevent thread-safety vulnerabilities:
- **Lock-Free Registries:** Uses `ConcurrentHashMap` for `PlayerRegistry` and `RoomRegistry` to prevent race conditions during player registrations and room allocations.
- **Interleaving Protection:** Synchronizes client socket output streams within `ClientHandler.sendMessage()` to prevent concurrent broadcasts from corrupting TCP frame boundaries.
- **Copy-On-Write Collections:** Uses `CopyOnWriteArrayList` for player tracking inside `Room` objects, ensuring broadcast loops are thread-safe and immune to concurrent modifications.

---

## 3. Input Validation & Protocol Hardening

The [CommandRouter.java](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/src/protocol/CommandRouter.java) functions as the primary security filter for incoming messages:
- **Command Syntax Enforcement:** Ensures incoming commands match valid `CommandType` definitions.
- **Argument Counting:** Rejects commands that do not satisfy minimum argument counts (e.g. `NAME` without a username).
- **State Requirements Validation:** Prevents players from executing commands out of sequence (e.g., executing `READY` while in the lobby, or `CREATE` while in a room).
- **Whitespace Sanitization:** Cleans inputs to prevent injection attacks or command parsing anomalies.

---

## 4. Connection Resilience & Resource Protection

To prevent resource exhaustion (denial of service via socket descriptor exhaustion or CPU bottlenecking):
- **Dead Client Eviction:** Monitors socket write flags using `PrintWriter.checkError()`. Sockets that fail are evicted immediately, releasing thread resources.
- **Clean Registry Release:** Wraps `ClientHandler` run loops in `try-finally` blocks to guarantee player objects are deleted from the global registry even if connection streams abort unexpectedly.
- **Graceful Process Shutdown:** Intercepts SIGINT signals to disconnect active sockets cleanly, allowing clients to terminate state machines safely and avoiding half-open socket leaks.

---

## 5. Planned Security Enhancements

Planned security improvements on the roadmap include:
- **TLS/SSL Encryption:** Adding transport layer security for game data packets.
- **BCrypt Password Authentication:** Persistent user accounts with BCrypt-hashed credentials.
- **JWT Stateful Authentication:** Exchanging validated JWTs during the `NAME` registration handshake.
- **Lobby Rate Limiting:** Token-bucket algorithms inside `ClientHandler` to throttle command spam.
