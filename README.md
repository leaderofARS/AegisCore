# AegisCore

> **The open-source game lobby server for Java backends.**
> Real-time player sessions, room management, and matchmaking — the infrastructure layer every multiplayer game needs, built from raw TCP sockets up.

---

```
Every immersive multiplayer world needs a server that handles players, parties,
instances, and matchmaking before the game session even begins.
AegisCore is that server.
```

---

## Why AegisCore

Every multiplayer game — from competitive arena shooters to full-dive VR worlds — shares the same pre-game problem: you need players to find each other, form groups, signal readiness, and transition into a live session together. That infrastructure is **not** part of your game engine. It lives on a Java backend, and until now, your only options were:

| Option | Problem |
|---|---|
| Build it from scratch | Takes weeks, race conditions everywhere |
| Nakama / PlayFab | Cloud-locked, opaque, expensive at scale |
| Netty | Networking framework, not a lobby system |
| Roll your own on top of raw sockets | That's what this is — done right |

AegisCore gives you the complete lobby layer: **zero external dependencies, zero cloud lock-in, embeddable in any Java backend, open to the metal.**

---

## What It Does

```
Player connects → sets a name → browses rooms → creates or joins
        ↓
Room fills up → ready-check fires → countdown begins
        ↓
All players READY → 5-second countdown → IN_PROGRESS
        ↓
Game client takes over — AegisCore holds the session state
```

### Feature Set

| Feature | Status |
|---|---|
| Player session management | ✅ |
| Room creation with custom slot count | ✅ |
| Room join by ID | ✅ |
| Live room listing | ✅ |
| Ready-check with 5-second countdown | ✅ |
| Countdown cancellation via UNREADY | ✅ |
| Automatic matchmaking queue | ✅ |
| Room-scoped broadcast (CHAT) | ✅ |
| Player disconnect room cleanup | ✅ |
| Live server statistics | ✅ |
| Graceful 3-phase server shutdown | ✅ |
| Per-file concurrent logging | ✅ |
| WebSocket support | 🔜 |
| Persistent player profiles | 🔜 |
| Guild / party system | 🔜 |
| VR session state sync | 🔜 |

---

## Quick Start

**Requirements:** Java 21+, no other dependencies.

```bash
# 1. Clone
git clone https://github.com/leaderofARS/AegisCore.git
cd AegisCore

# 2. Compile
javac -d bin -sourcepath src src/server/Server.java src/server/Client.java

# 3. Run the server
java -cp bin server.Server

# 4. Connect (open a new terminal for each player)
java -cp bin server.Client
```

Or connect with any TCP client:
```bash
telnet localhost 5000
```

---

## Protocol

Line-oriented text protocol over TCP. One command per line. Works with telnet, netcat, or any socket library.

### Commands

```
NAME   <username>              Set your display name (required first)
CREATE <room-name> [slots]     Create a lobby room (default: 4 slots, max: 32)
JOIN   <room-id>               Join an existing open room
LEAVE                          Return to lobby from a room
LIST                           List all open rooms
READY                          Mark yourself ready in your room
UNREADY                        Cancel your ready status (aborts countdown)
QUEUE                          Enter automatic matchmaking
DEQUEUE                        Leave the matchmaking queue
CHAT   <message>               Send a message to everyone in your room
STATS                          View live server statistics
QUIT                           Disconnect cleanly
```

### Server Response Prefixes

```
[SERVER]   System confirmations and info
[ROOM]     Room-scoped broadcasts
[MATCH]    Matchmaking events
[READY]    Ready-check state changes
[INFO]     Player join/leave and game start events
[ERROR]    Invalid commands or state violations
```

### Example Session

```
→ NAME Kirito
← [SERVER] Welcome to AegisCore, Kirito!
← [SERVER] Commands: CREATE <name> [slots]  |  JOIN <id>  |  QUEUE  |  LIST  |  STATS

→ CREATE Aincrad-Floor1 4
← [SERVER] Room created → r-001 | "Aincrad-Floor1" | 4 slots
← [SERVER] Share this ID to invite others: r-001

→ LIST
← [SERVER] ╔══ Open Rooms ══════════════════════════╗
← [SERVER]   ID       Name                 Players  Status
← [SERVER]   r-001    Aincrad-Floor1       1/4      WAITING
← [SERVER] ╚════════════════════════════════════════╝

→ READY
← [READY] Kirito is ready  (1/1)

# Second player joins and readies up...
← [READY] Asuna is ready  (2/2)
← [READY] All players ready! Starting in 5...
← [READY] 4...
← [READY] 3...
← [READY] 2...
← [READY] 1...
← [INFO]  ⚔  Game session started! Good luck.
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT SIDE                              │
│  server.Client  ──TCP──  NAME / CREATE / JOIN / READY / CHAT   │
└────────────────────────────┬────────────────────────────────────┘
                             │ TCP  (port 5000)
┌────────────────────────────▼────────────────────────────────────┐
│                    server.Server (accept loop)                  │
│                    server.ClientHandler  (1 thread / player)    │
│                             │                                   │
│              protocol.CommandRouter  (shared, stateless)        │
│               /         |          \          \                 │
│    player.*   room.*    matchmaking.*   core.*                  │
│                                                                 │
│  PlayerRegistry   RoomRegistry   MatchmakingQueue   Logger      │
│  ConcurrentHashMap ConcurrentHashMap LinkedBlockingQueue        │
└─────────────────────────────────────────────────────────────────┘
```

### Package Structure

```
src/
├── server/
│   ├── Server.java          Entry point, accept loop, 3-phase shutdown
│   ├── ClientHandler.java   TCP socket lifecycle, I/O serialisation
│   └── Client.java          Interactive test client
├── player/
│   ├── Player.java          Game identity: name, status, room assignment
│   ├── PlayerStatus.java    State machine enum (CONNECTED → IN_LOBBY → IN_ROOM → IN_GAME)
│   └── PlayerRegistry.java  Global ConcurrentHashMap of live players
├── room/
│   ├── Room.java            Lobby state machine, ready-check, 5-sec countdown
│   ├── RoomState.java       WAITING → READY_CHECK → IN_PROGRESS → CLOSED
│   └── RoomRegistry.java    Global room map, open-room listing, cleanup
├── matchmaking/
│   ├── MatchmakingQueue.java  Daemon thread, LinkedBlockingQueue, auto-room creation
│   └── MatchConfig.java       Match size configuration
├── protocol/
│   ├── CommandRouter.java   Central dispatcher for all 12 commands
│   ├── CommandType.java     Enum of valid commands with min-arg validation
│   └── CommandContext.java  Immutable per-invocation value object
└── core/
    ├── Logger.java          Per-file concurrent logging (4 independent locks)
    └── ServerStats.java     Immutable telemetry snapshot
```

### Concurrency Model

| Concern | Mechanism |
|---|---|
| Player map | `ConcurrentHashMap` — lock-free reads and writes |
| Room player list | `CopyOnWriteArrayList` — safe iteration under broadcast |
| Ready set | `ConcurrentHashMap.newKeySet()` — lock-free add/remove |
| Ready-check atomicity | `synchronized` on `Room.this` |
| Output stream safety | `synchronized` on `ClientHandler.this` |
| Duplicate eviction logs | `AtomicBoolean.compareAndSet` CAS |
| Countdown timer | Shared `ScheduledExecutorService` (daemon) |
| Matchmaking | `LinkedBlockingQueue` + dedicated daemon thread |
| Logging | 4 independent `Object` monitors (one per log file) |

---

## For VR Game Developers

AegisCore is purpose-built for the server infrastructure layer that immersive multiplayer experiences require — the layer between the player's hardware and the game world itself.

In any full-dive or VR-class multiplayer game, the server must handle:

- **Session establishment** before the player enters the world
- **Party formation** — players grouping before entering an instance
- **Instance readiness** — all party members confirming they are ready
- **Matchmaking** — finding opponents or teammates automatically
- **Graceful disconnects** — the world continues when one player drops

AegisCore handles all of this. Your game engine handles what happens inside the instance.

```
VR Client (Unity / Unreal / Godot)
       │
       │  TCP  →  NAME, CREATE, JOIN, READY
       ▼
  AegisCore (Java backend)
       │
       │  IN_PROGRESS event fired
       ▼
  Your Game World Server (physics, state sync, combat)
```

The protocol is intentionally minimal and engine-agnostic. Any client that can open a TCP socket and send text lines can integrate with AegisCore — Unity, Godot, Unreal, custom C++ clients, or even browser-based clients via a WebSocket bridge.

---

## Contributing

AegisCore is under active development. Contributions, issues, and protocol proposals are welcome.

Planned next milestones:
- WebSocket bridge for browser/JS clients
- Persistent player profiles (SQLite / PostgreSQL)
- Guild system (persistent cross-session groups)
- VR session state synchronisation protocol
- Docker image + one-command deployment

---

## License

MIT — free to use in commercial and open-source projects.

---

*Built with Java 21. Zero dependencies. Open to the metal.*