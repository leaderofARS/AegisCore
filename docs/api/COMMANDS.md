# AegisCore Command API Reference

This document provides the protocol specification for the AegisCore Game Lobby Server. All commands are UTF-8 text sent over a TCP connection, delimited by a newline character (`\n`).

---

## 1. Protocol State Machine

A player connection transitions through several states represented by the `PlayerStatus` enum:

```
           [CONNECTED]
                │
                ▼  NAME <username>
            [IN_LOBBY] ◄────────────────────────┐
             │       │                          │
   CREATE /  │       │ QUEUE                    │ LEAVE /
   JOIN      │       │                          │ QUIT_ROOM
             ▼       ▼                          │
         [IN_ROOM] [QUEUED] ──► Match Found ────┘
             │
             ├─ READY ──► (Waiting for other players)
             ▼
         [IN_GAME] (Lobby countdown finishes)
```

---

## 2. Command Reference

### NAME
Set your display name. This command must be executed successfully before any other command is allowed.
- **Syntax:** `NAME <username>`
- **Arguments:**
  - `username`: A unique alphanumeric string (no spaces).
- **State Requirement:** Player must be in `CONNECTED` state.
- **Server Responses:**
  - Success: `[SERVER] Welcome to AegisCore, <username>!`
  - Error: `[ERROR] NAME command requires a username.`
  - Error: `[ERROR] Username is already taken.`

### CREATE
Create a new lobby room.
- **Syntax:** `CREATE <room-name> [slots]`
- **Arguments:**
  - `room-name`: Name of the room (no spaces).
  - `slots` (Optional): Maximum players allowed in the room (default: 4, range: 2–32).
- **State Requirement:** Player must be in `IN_LOBBY` state.
- **Server Responses:**
  - Success: `[SERVER] Room created -> <room-id> | "<room-name>" | <slots> slots`
  - Error: `[ERROR] Room name required.`
  - Error: `[ERROR] Invalid slot count. Must be between 2 and 32.`

### JOIN
Join an existing lobby room by its unique ID.
- **Syntax:** `JOIN <room-id>`
- **Arguments:**
  - `room-id`: The short identifier of the room (e.g., `r-001`).
- **State Requirement:** Player must be in `IN_LOBBY` state.
- **Server Responses:**
  - Success: `[SERVER] Joined room: <room-id>` (plus a broadcast to the room: `[ROOM] <player-name> joined the room.`)
  - Error: `[ERROR] Room ID required.`
  - Error: `[ERROR] Room <room-id> does not exist.`
  - Error: `[ERROR] Room <room-id> is full.`
  - Error: `[ERROR] Room <room-id> is already in progress.`

### LIST
List all active, joinable rooms in the lobby.
- **Syntax:** `LIST`
- **State Requirement:** Player must be in `IN_LOBBY` state.
- **Server Responses:**
  - Success:
    ```
    [SERVER] ╔══ Open Rooms ══════════════════════════╗
    [SERVER]   ID       Name                 Players  Status
    [SERVER]   r-001    Aincrad-Floor1       1/4      WAITING
    [SERVER] ╚════════════════════════════════════════╝
    ```
  - Success (No rooms): `[SERVER] No active rooms. Use CREATE to start one.`

### QUEUE
Enter the matchmaking queue to be automatically matched into a game room.
- **Syntax:** `QUEUE`
- **State Requirement:** Player must be in `IN_LOBBY` state.
- **Server Responses:**
  - Success: `[MATCH] Entered matchmaking queue.`
  - Error: `[ERROR] You are already in the matchmaking queue.`

### DEQUEUE
Leave the matchmaking queue.
- **Syntax:** `DEQUEUE`
- **State Requirement:** Player must be in `QUEUED` state.
- **Server Responses:**
  - Success: `[MATCH] Left matchmaking queue.`
  - Error: `[ERROR] You are not in the matchmaking queue.`

### READY
Mark yourself as ready to start the game. Once all players in a room are ready, a 5-second countdown begins.
- **Syntax:** `READY`
- **State Requirement:** Player must be in `IN_ROOM` state.
- **Server Responses:**
  - Success: `[READY] <player-name> is ready (<ready-count>/<total-players>)`
  - Countdown Triggered: `[READY] All players ready! Starting in 5...` (followed by `4...`, `3...`, `2...`, `1...` and `[INFO] ⚔ Game session started! Good luck.`)
  - Error: `[ERROR] You are already marked as ready.`

### UNREADY
Cancel your ready status. Doing so during a countdown will abort the countdown.
- **Syntax:** `UNREADY`
- **State Requirement:** Player must be in `IN_ROOM` state.
- **Server Responses:**
  - Success: `[READY] <player-name> is no longer ready.`
  - Countdown Aborted: `[READY] Countdown cancelled: <player-name> is unready.`
  - Error: `[ERROR] You are not marked as ready.`

### CHAT
Send a text chat message to all players in your current room.
- **Syntax:** `CHAT <message>`
- **Arguments:**
  - `message`: The chat text to broadcast.
- **State Requirement:** Player must be in `IN_ROOM` state.
- **Server Responses:**
  - Success: Broadcasts message to room: `[ROOM] <player-name>: <message>`
  - Error: `[ERROR] CHAT command requires a message.`

### STATS
Retrieve live telemetry metrics from the server.
- **Syntax:** `STATS`
- **State Requirement:** Any state after successful naming.
- **Server Responses:**
  - Success:
    ```
    [SERVER] --- AegisCore Telemetry ---
    [SERVER] Active Connections: 3
    [SERVER] Lifetime Accepted: 24
    [SERVER] Messages Relayed: 142
    [SERVER] Bytes Dispatched: 12056
    ```

### QUIT
Cleanly close the TCP socket session and disconnect from the server.
- **Syntax:** `QUIT`
- **State Requirement:** Any state.
- **Server Responses:**
  - Success: `[SERVER] Goodbye!` (followed by closing the socket).
