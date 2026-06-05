# AegisCore Networking Specification

This document details the networking protocol, connection lifecycle, and message framing specs implemented in the **AegisCore** Game Lobby Server.

---

## 1. Network Protocol Specifications

| Protocol Detail | Value | Description |
|---|---|---|
| **Layer** | Transport Layer | Operates directly over raw TCP sockets. |
| **Default Port** | `5000` | Configurable system listener port. |
| **Character Set** | UTF-8 | All messages are parsed as UTF-8 encoded text. |
| **Framing Delimiter** | Newline (`\n`) | Line-oriented protocol. A message is exactly one line. |
| **I/O Model** | Blocking I/O | The server reads inputs line-by-line using blocking stream operations. |

---

## 2. Connection Handshake Lifecycle

When a client establishes a socket connection to port `5000`, the server initiates a handshake sequence:

```
Client                                                  Server
  │                                                       │
  │────────────────── TCP Handshake ─────────────────────>│ (Socket accepted)
  │                                                       │
  |<─────────────── [SERVER] Welcomes... ─────────────────│ (Welcome header sent)
  │                                                       │
  │                                                       │
  │─────────────────── NAME Kirito ──────────────────────>│ (Name registration)
  │                                                       │
  |<───────────── [SERVER] Welcome... Kirito! ────────────│ (State: IN_LOBBY)
```

1. **TCP Connection:** The client opens a socket connection to port 5000.
2. **Server Welcome Response:** The server immediately sends a welcome header:
   `[SERVER] Welcome to AegisCore Game Lobby Server`
3. **Name Registration:** The client must issue a `NAME <username>` command.
4. **Lobby Entry:** On success, the server responds with a verification message, enabling access to lobby commands (e.g. `CREATE`, `JOIN`, `LIST`, `QUEUE`).

---

## 3. Message Framing & Syntax

### 3.1 Client Commands
All client commands must follow this format:
```
<COMMAND> [arguments...]\n
```
- **Delimiter:** Exactly one trailing `\n`.
- **Command Name:** Always uppercase (e.g., `NAME`, `CREATE`, `JOIN`, `READY`).
- **Whitespace:** Commands and arguments are separated by spaces. Arguments containing spaces are not permitted unless handled by command type parameters (e.g., `CHAT`).

*Note: The protocol does not use slashes (e.g., `/command` is invalid; use `COMMAND`).*

---

## 4. Server Response Framing

All responses sent by the server start with a specific system prefix indicator to help clients parse events:

- **`[SERVER]`**
  System confirmations, configuration updates, and lobby telemetry details.
  *Example:* `[SERVER] Joined room: r-001`
- **`[ROOM]`**
  Room-scoped updates and broadcasts (e.g. chat messages, players joining/leaving).
  *Example:* `[ROOM] Kirito: Hello everyone!`
- **`[READY]`**
  State changes, countdown markers, and ready status confirmations.
  *Example:* `[READY] All players ready! Starting in 5...`
- **`[MATCH]`**
  Matchmaking state updates.
  *Example:* `[MATCH] Entered matchmaking queue.`
- **`[INFO]`**
  System informational logs.
  *Example:* `[INFO] ⚔ Game session started! Good luck.`
- **`[ERROR]`**
  Command syntax violations, state errors, or invalid actions.
  *Example:* `[ERROR] Username is already taken.`

---

## 5. Future Network Architecture

Future releases plan to introduce:
- A **WebSocket adapter** to bridge HTML5 web clients to the TCP lobby.
- A **Java NIO Selector engine** to run the socket lifecycle asynchronously under a single thread, enabling the server to scale past 10,000 concurrent socket connections.
