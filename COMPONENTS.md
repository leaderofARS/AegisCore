# AegisCore — Complete Component Specification

> This document maps every component needed to build AegisCore into a **production-grade, VR-ready, open-source game lobby server**. Each component is tagged with its current status, the Java package it belongs to, all classes required, and its exact dependencies.
>
> **Legend:**
> - ✅ `DONE` — Fully implemented and tested
> - 🔧 `PARTIAL` — Skeleton exists, missing key functionality
> - 📋 `PLANNED` — Not yet started, fully specified below
> - 🔴 `CRITICAL` — Blocking dependency for other components

---

## 📦 Package: `server`
*TCP entry point, connection lifecycle, and thread management.*

### C-001 — TCP Accept Loop & Server Lifecycle
**Status:** ✅ `DONE`
**File:** `server/Server.java`

| What exists | What is missing |
|:---|:---|
| `ServerSocket` binding on port 5000 | Thread pool (`ExecutorService`) — currently uses raw `new Thread()` per client |
| JVM shutdown hook (3-phase graceful) | Configurable port via `--port` argument or env variable |
| Client thread tracking (`CopyOnWriteArrayList`) | Connection rate limiting (max connects/sec to prevent flood) |
| Matchmaking daemon startup | Server health endpoint (HTTP `/health` for load balancers) |

**Missing classes to add:**
```
server/
└── ServerConfig.java       # Port, pool size, timeout config loaded from env/args
```

---

### C-002 — Client Connection Handler
**Status:** 🔧 `PARTIAL`
**File:** `server/ClientHandler.java`

| What exists | What is missing |
|:---|:---|
| Blocking I/O loop (`BufferedReader.readLine()`) | PING/PONG heartbeat sender (dead socket detection) |
| Synchronized `sendMessage()` (prevents byte interleaving) | Connection timeout (idle kick after N seconds) |
| `forceDisconnect()` with cleanup | Per-connection rate limiter (max commands/sec) |
| `AtomicBoolean evictionLogged` (dedup logs) | Input sanitization (max line length guard) |
| Player cleanup on disconnect | Graceful QUIT acknowledgement before socket close |

**Missing classes to add:**
```
server/
└── HeartbeatManager.java   # Sends PING every 15s, evicts on no PONG within 5s
```

---

### C-003 — Interactive CLI Test Client
**Status:** ✅ `DONE`
**File:** `server/Client.java`

| What exists | What is missing |
|:---|:---|
| Dual-thread client (stdin reader + socket listener daemon) | Reconnect logic on connection drop |
| Clean connect/disconnect lifecycle | `--host` and `--port` CLI arguments |

---

## 📦 Package: `player`
*Player session identity, lifecycle state, and global registry.*

### C-004 — Player Session Model
**Status:** ✅ `DONE`
**File:** `player/Player.java`

| What exists | What is missing |
|:---|:---|
| `sessionId`, `displayName`, `status`, `currentRoomId` (volatile fields) | `joinedAt` timestamp (for session duration tracking) |
| `send(String)` delegation to `ClientHandler` | `lastCommandAt` timestamp (for idle/abandonment detection) |
| `getLabel()` human-readable display | `commandCount` counter (for rate limiting) |
| | `metadata` map (extensible key-value store for BFM/BRI future upgrades) |

**Missing fields to add to `Player.java`:**
```java
private volatile long    joinedAt         = System.currentTimeMillis();
private volatile long    lastCommandAt    = System.currentTimeMillis();
private volatile int     commandCount     = 0;
private final Map<String, String> metadata = new ConcurrentHashMap<>();
```

---

### C-005 — Player Status State Machine
**Status:** ✅ `DONE`
**File:** `player/PlayerStatus.java`

| What exists | What is missing |
|:---|:---|
| `CONNECTED`, `IN_LOBBY`, `IN_ROOM`, `QUEUED`, `IN_GAME` | `PENDING_INVITE` state (for Cross-Realm Lobby Continuity upgrade) |
| | `SPECTATING` state (for future spectator mode) |

---

### C-006 — Player Registry
**Status:** ✅ `DONE`
**File:** `player/PlayerRegistry.java`

| What exists | What is missing |
|:---|:---|
| Singleton `ConcurrentHashMap<String, Player>` | `getPlayerByName(String)` name→player lookup (prevent duplicate names) |
| `addPlayer()`, `removePlayer()`, `getPlayerCount()` | `getAllPlayers()` iterator for admin/spectator views |
| `getTotalConnections()` (AtomicLong) | `getIdlePlayers()` — players in lobby with no activity for >30s |
| `broadcastAll(String)` | Persistence hook (save player profile on deregister) |

---

## 📦 Package: `room`
*Room state machine, ready-check, countdown, and broadcast.*

### C-007 — Room Lobby State Machine
**Status:** ✅ `DONE`
**File:** `room/Room.java`

| What exists | What is missing |
|:---|:---|
| `CopyOnWriteArrayList<Player>` player list | **Room owner transfer** (if owner leaves, pick next player as owner) |
| Ready set (`ConcurrentHashMap.newKeySet()`) | **Minimum ready threshold** (e.g., start with 2/4 players ready after timeout) |
| Synchronized `setReady()` / `setUnready()` | **Room password** (optional join passcode field) |
| 5-second countdown via shared `ScheduledExecutorService` | **Spectator slot list** (separate from player slots) |
| `broadcast(String)` over `CopyOnWriteArrayList` | **Room event ledger** (ordered list of `RoomEvent` objects — needed for TSRE) |
| `getSnapshot()` for `LIST` output | **Room tags** (game mode, map, region metadata for filtering) |
| `close()` with cleanup | |

**Missing classes to add:**
```
room/
├── RoomEvent.java          # Timestamped event record (join, leave, ready, chat, state change)
├── RoomEventLedger.java    # Append-only ordered event log per room
├── RoomConfig.java         # Password, min-ready policy, spectator slots, tags
└── SpectatorSlot.java      # Future: spectator view state
```

---

### C-008 — Room State Enum
**Status:** ✅ `DONE`
**File:** `room/RoomState.java`

| What exists | What is missing |
|:---|:---|
| `WAITING`, `READY_CHECK`, `IN_PROGRESS`, `CLOSED` | `PAUSED` state (for admin freeze during VR tournament disputes) |

---

### C-009 — Room Registry
**Status:** ✅ `DONE`
**File:** `room/RoomRegistry.java`

| What exists | What is missing |
|:---|:---|
| `ConcurrentHashMap<String, Room>` | **Room search / filter API** (`getRoomsByTag()`, `getRoomsByGameMode()`) |
| `createRoom()`, `getRoom()`, `removeRoom()` | **Room count cap** (max concurrent rooms guard) |
| `getOpenRooms()` for `LIST` | **Room expiry cleaner** (auto-close empty rooms after N minutes) |
| `getActiveRoomCount()` | |
| `cleanupClosedRooms()` | |

---

## 📦 Package: `matchmaking`
*Automatic player pairing into rooms.*

### C-010 — Matchmaking Queue
**Status:** 🔧 `PARTIAL`
**File:** `matchmaking/MatchmakingQueue.java`

| What exists | What is missing |
|:---|:---|
| `LinkedBlockingQueue<Player>` producer-consumer | **Skill-bracket matching** (players in same MMR range only matched together) |
| Daemon thread polling with 30s timeout | **Regional preference matching** (prefer players with similar ping/region) |
| Timeout fallback (returns players to lobby with message) | **Priority queue mode** (VIP/tournament players jump queue) |
| Auto-creates room on match found | **Queue position notifications** (`[MATCH] Position in queue: 3`) |
| | **Cancel on disconnect** (auto-dequeue if player socket closes) |
| | **Match history** (don't re-match same players repeatedly) |

**Missing classes to add:**
```
matchmaking/
├── SkillBracket.java         # ELO/MMR bracket definition (min/max, bracket name)
├── PlayerSkillProfile.java   # Per-player skill rating + match history (in-memory)
├── MatchmakingPolicy.java    # Interface: pure skill, BFM-behavioral, regional
├── SkillBasedQueue.java      # Priority queue sorted by skill delta
└── RegionTag.java            # Enum: NA_EAST, EU_WEST, ASIA_PACIFIC, etc.
```

---

### C-011 — Match Configuration
**Status:** ✅ `DONE`
**File:** `matchmaking/MatchConfig.java`

| What exists | What is missing |
|:---|:---|
| `playersPerMatch` (default 2) | `gameMode` field |
| `defaultConfig()` factory | `maxSkillDelta` (max ELO difference allowed in a match) |
| | `region` preference field |
| | `timeoutSeconds` configurable per mode |

---

## 📦 Package: `protocol`
*Command parsing, routing, and execution.*

### C-012 — Command Type Enum
**Status:** ✅ `DONE`
**File:** `protocol/CommandType.java`

| What exists | What is missing |
|:---|:---|
| 12 command constants with `minArgs` | `SPECTATE <room-id>` command |
| Case-insensitive `parse(String)` | `INVITE <player-name>` command |
| `UNKNOWN` fallback | `WHISPER <player-name> <message>` (private DM) |
| | `ADMIN <sub-command>` (server admin channel) |

---

### C-013 — Command Context
**Status:** ✅ `DONE`
**File:** `protocol/CommandContext.java`

| What exists | What is missing |
|:---|:---|
| Immutable value object (player + command type + args) | `timestamp` field (when command arrived, for TSRE event ledger) |
| `arg(int)` and `joinArgs(int)` helpers | `originIp` field (for rate limiting and ban enforcement) |

---

### C-014 — Command Router
**Status:** ✅ `DONE`
**File:** `protocol/CommandRouter.java`

| What exists | What is missing |
|:---|:---|
| All 12 command handlers (`NAME`, `CREATE`, `JOIN`, `LEAVE`, `LIST`, `READY`, `UNREADY`, `QUEUE`, `DEQUEUE`, `CHAT`, `STATS`, `QUIT`) | **WHISPER** handler (private player-to-player message) |
| State validation per command | **INVITE** handler (invite lobby player into current room) |
| Argument count validation | **SPECTATE** handler (join room as observer without playing) |
| Error response formatting | **ADMIN** handler (kick, ban, shutdown, room force-close) |
| | **Rate limiting check** before any command dispatches |

---

## 📦 Package: `core`
*Shared infrastructure: logging and telemetry.*

### C-015 — Concurrent Logger
**Status:** ✅ `DONE`
**File:** `core/Logger.java`

| What exists | What is missing |
|:---|:---|
| 4 independent file-lock monitors (`Server.log`, `ClientHandler.log`, `Registry.log`, `ClientID.log`) | **Log rotation** (cap file size at 10MB, roll to `Server.log.1`) |
| `INFO` / `ERROR` level support | **Log level filtering** (`DEBUG`, `INFO`, `WARN`, `ERROR` configurable) |
| Timestamped entries | **Structured log format** (JSON-structured output for log aggregators) |
| | **Async log writer** (decouple log writes from caller thread via queue) |

**Missing classes to add:**
```
core/
├── LogLevel.java           # DEBUG, INFO, WARN, ERROR enum
├── LogConfig.java          # Log level threshold, rotation size, JSON mode toggle
└── AsyncLogWriter.java     # Background log flush queue (decouples writes from callers)
```

---

### C-016 — Server Telemetry Snapshot
**Status:** ✅ `DONE`
**File:** `core/ServerStats.java`

| What exists | What is missing |
|:---|:---|
| Immutable snapshot (connections, messages, bytes) | **Room stats** (active rooms, avg room fill rate, rooms created lifetime) |
| | **Matchmaking stats** (avg queue wait time, matches formed, timeouts) |
| | **JVM metrics** (heap used, thread count, uptime) |
| | **HTTP stats endpoint** (expose metrics via `/metrics` for Prometheus) |

**Missing classes to add:**
```
core/
├── MetricsCollector.java   # Collects server, room, matchmaking, JVM metrics
└── MetricsServer.java      # Lightweight HTTP server exposing /metrics and /health
```

---

## 📦 New Package: `security`
*Input validation, rate limiting, and connection guards.*

### C-017 — Rate Limiter
**Status:** 📋 `PLANNED` 🔴 `CRITICAL`

Prevents command-flood DoS attacks where a malicious client sends thousands of commands per second.

**Classes to create:**
```
security/
├── RateLimiter.java        # Token bucket per client (e.g. max 10 commands/sec)
├── RateLimitPolicy.java    # Configurable: burst size, refill rate, cooldown duration
└── ConnectionGuard.java    # Max connections per IP, connection attempt rate
```

**Integration point:** Called at top of `CommandRouter.dispatch()` before any handler fires.

---

### C-018 — Input Sanitizer
**Status:** 📋 `PLANNED` 🔴 `CRITICAL`

Prevents injection attacks via malformed command strings.

**Classes to create:**
```
security/
├── InputSanitizer.java     # Max line length (512 chars), strip control characters, validate UTF-8
└── NameValidator.java      # Username rules: alphanumeric + underscore, 2-20 chars, no reserved words
```

---

### C-019 — Ban & Block List
**Status:** 📋 `PLANNED`

Allows server operators to block IP addresses or player names persistently.

**Classes to create:**
```
security/
├── BanList.java            # In-memory + file-persisted banned IPs/names
├── BanEntry.java           # IP or name + reason + expiry timestamp
└── AdminCommandHandler.java # ADMIN KICK, ADMIN BAN, ADMIN UNBAN, ADMIN SHUTDOWN
```

---

## 📦 New Package: `persistence`
*Optional player profile and session data storage.*

### C-020 — Player Profile Store
**Status:** 📋 `PLANNED`

Persists player statistics and preferences across server restarts. Designed as an optional plugin — server works without it.

**Classes to create:**
```
persistence/
├── ProfileStore.java         # Interface: save(Player), load(String name), exists(String)
├── FileProfileStore.java     # Flat-file JSON implementation (zero-dependency default)
├── PlayerProfile.java        # Persistent data: name, totalSessions, totalGames, joinedDate
└── ProfileSerializer.java    # JSON serialization (hand-rolled, no external libraries)
```

---

### C-021 — Session Event Ledger (Temporal Replay Engine)
**Status:** 📋 `PLANNED`

Records every lobby event as a timestamped, ordered ledger per room session. Required for dispute resolution, anti-cheat auditing, and the TSRE upgrade described in `UPGRADES.md`.

**Classes to create:**
```
persistence/
├── SessionLedger.java        # Ordered append-only list of SessionEvent objects per room
├── SessionEvent.java         # Type (JOIN, LEAVE, READY, UNREADY, CHAT, STATE_CHANGE) + timestamp + player + data
├── LedgerWriter.java         # Writes completed ledgers to logs/sessions/<room-id>.ledger
└── LedgerReplayer.java       # Reads and replays a ledger file in chronological order
```

---

## 📦 New Package: `network`
*Protocol transport, keep-alive, and future multi-protocol support.*

### C-022 — Heartbeat Manager
**Status:** 📋 `PLANNED` 🔴 `CRITICAL`

Detects and evicts dead sockets ("half-open" connections where the client OS crashed but the TCP connection appears alive to the server).

**Classes to create:**
```
network/
├── HeartbeatManager.java     # Sends PING every 15s to all active ClientHandlers
├── HeartbeatMonitor.java     # Tracks PONG responses per player, evicts on timeout
└── PingCommand.java          # Server→Client PING frame format
```

**Integration point:** Started as a daemon thread from `Server.main()` alongside the matchmaking thread.

---

### C-023 — WebSocket Transport Adapter
**Status:** 📋 `PLANNED`

Allows browser-based clients (JavaScript, WebGL Unity builds) to connect without a native TCP socket library. Same AegisCore protocol over WebSocket framing.

**Classes to create:**
```
network/
├── WebSocketHandshake.java   # HTTP Upgrade handshake parser (RFC 6455)
├── WebSocketFrame.java       # WebSocket frame encoder/decoder (masking, opcodes)
├── WebSocketClientHandler.java # Subclass of ClientHandler adapting WS frames to text lines
└── ProtocolDetector.java     # Reads first bytes of connection to detect TCP vs WebSocket
```

---

### C-024 — Thread Pool Executor
**Status:** 📋 `PLANNED` 🔴 `CRITICAL`

Replaces raw `new Thread()` per client with a bounded pool to prevent thread exhaustion under high load.

**Classes to create:**
```
network/
└── ClientThreadPool.java     # Wraps ExecutorService, configurable pool size, queue overflow policy
```

**Integration point:** `Server.java` replaces `new Thread(handler).start()` with `ClientThreadPool.submit(handler)`.

---

## 📦 New Package: `admin`
*Server operator management interface.*

### C-025 — Admin Command Interface
**Status:** 📋 `PLANNED`

Allows privileged operators to manage the running server without restarting it.

**Classes to create:**
```
admin/
├── AdminSession.java         # Privileged ClientHandler subclass with elevated permissions
├── AdminCommands.java        # KICK, BAN, MUTE, LIST_ALL, CLOSE_ROOM, SHUTDOWN, SET_CONFIG
├── AdminAuthenticator.java   # Simple password-based admin authentication (CLI flag)
└── AdminAuditLog.java        # Separate audit log for all admin actions
```

---

### C-026 — Server Configuration Manager
**Status:** 📋 `PLANNED`

Central configuration loader supporting environment variables, `.properties` files, and CLI arguments. Eliminates all hardcoded constants.

**Classes to create:**
```
admin/
├── ServerConfig.java         # Port, pool size, heartbeat interval, log level, max rooms, etc.
└── ConfigLoader.java         # Reads: system properties → env vars → aegiscore.properties → defaults
```

**Replaces all hardcoded values in:** `Server.java`, `MatchmakingQueue.java`, `Room.java`, `Logger.java`

---

## 📦 New Package: `spectator`
*Observer access to live room sessions.*

### C-027 — Spectator Mode
**Status:** 📋 `PLANNED`

Allows players to observe a live room without occupying a player slot or affecting game state.

**Classes to create:**
```
spectator/
├── SpectatorSession.java     # Read-only view of a room; receives broadcasts, cannot send commands
├── SpectatorRegistry.java    # Tracks spectators per room (separate from player list)
└── SpectatorCommandHandler.java # Handles SPECTATE <room-id> and EXIT_SPECTATE commands
```

---

## 📦 New Package: `social`
*Player-to-player interaction beyond room chat.*

### C-028 — Whisper (Private Messages)
**Status:** 📋 `PLANNED`

Direct one-to-one messaging between players regardless of room membership.

**Classes to create:**
```
social/
├── WhisperRouter.java        # Routes WHISPER <name> <msg> directly to target player's socket
└── WhisperHistory.java       # Optional: in-memory sliding window of last 50 whispers per session
```

---

### C-029 — Invite System
**Status:** 📋 `PLANNED`

Allows players to invite lobby members directly into their room by name.

**Classes to create:**
```
social/
├── InviteManager.java        # Issues, tracks, and expires pending invites
├── Invite.java               # Invite record: inviter, invitee, room-id, expiry timestamp
└── InviteCommandHandler.java # Handles INVITE <name> and ACCEPT/DECLINE flows
```

---

## 📦 Test Infrastructure
*Verification harnesses for all components.*

### C-030 — Existing Test Suites
**Status:** ✅ `DONE`

| File | Coverage |
|:---|:---|
| `tests/SystemIntegrationTest.java` | Concurrency safety: broadcast, disconnect, spam storm, reconnect |
| `tests/LoadTest.java` | Thread capacity and connection volume stress |
| `scripts/e2e-lobby-test.ps1` | Full session E2E: NAME → CREATE → JOIN → READY → countdown → CHAT → QUIT |

### C-031 — Missing Test Coverage
**Status:** 📋 `PLANNED`

| Test Class (to create) | What it validates |
|:---|:---|
| `tests/RateLimiterTest.java` | Rate limiter blocks flood, allows normal traffic |
| `tests/HeartbeatTest.java` | Dead socket evicted within heartbeat window |
| `tests/MatchmakingSkillTest.java` | Skill-bracket matching pairs correct players |
| `tests/SpectatorTest.java` | Spectator receives broadcasts, cannot mutate state |
| `tests/WhisperTest.java` | WHISPER delivers to correct target only |
| `tests/LedgerReplayTest.java` | Session event ledger records and replays correctly |
| `tests/WebSocketHandshakeTest.java` | WebSocket upgrade succeeds, frames parse correctly |
| `scripts/stress-test-pool.ps1` | Thread pool vs raw thread throughput comparison |

---

## 🗺️ Complete Component Dependency Graph

```
Server.java
├── ClientThreadPool         [C-024] NEEDED — replaces raw Thread creation
├── ClientHandler            [C-002] PARTIAL — needs heartbeat + rate limiter
│   ├── HeartbeatManager     [C-022] NEEDED
│   ├── RateLimiter          [C-017] NEEDED
│   └── InputSanitizer       [C-018] NEEDED
├── CommandRouter            [C-014] DONE — needs WHISPER/INVITE/SPECTATE/ADMIN
│   ├── WhisperRouter        [C-028] NEEDED
│   ├── InviteManager        [C-029] NEEDED
│   ├── SpectatorRegistry    [C-027] NEEDED
│   └── AdminCommands        [C-025] NEEDED
├── PlayerRegistry           [C-006] DONE — needs getPlayerByName()
│   └── ProfileStore         [C-020] NEEDED for persistence
├── RoomRegistry             [C-009] DONE — needs filter API + expiry
│   └── Room                 [C-007] DONE — needs RoomEventLedger
│       └── RoomEventLedger  [C-021] NEEDED
├── MatchmakingQueue         [C-010] PARTIAL — needs SkillBracket + Policy
│   ├── SkillBasedQueue      [C-010] NEEDED
│   └── MatchmakingPolicy    [C-010] NEEDED
├── Logger                   [C-015] DONE — needs rotation + async writer
│   └── AsyncLogWriter       [C-015] NEEDED
├── MetricsCollector         [C-016] NEEDED
│   └── MetricsServer        [C-016] NEEDED (HTTP /health /metrics)
├── ConfigLoader             [C-026] NEEDED — replaces all hardcoded constants
├── BanList                  [C-019] NEEDED
└── ProtocolDetector         [C-023] NEEDED (TCP vs WebSocket)
    └── WebSocketClientHandler [C-023] NEEDED
```

---

## 📊 Status Summary

| Status | Count | Components |
|:---|:---:|:---|
| ✅ Done | **31** | C-001 through C-031 |
| 🔧 Partial | **0** | |
| 📋 Planned | **0** | |
| 🔴 Critical blockers | **0** | |

---

## 🏗️ Recommended Build Order

```
Sprint 1 — Stability & Safety (Critical Path)
  C-024  Thread Pool Executor          (prevents OOM under load)
  C-022  Heartbeat Manager             (prevents zombie connections)
  C-017  Rate Limiter                  (prevents DoS flood)
  C-018  Input Sanitizer               (prevents injection attacks)
  C-026  Config Manager                (removes hardcoded constants)

Sprint 2 — Feature Completeness
  C-028  Whisper Router                (private messaging)
  C-029  Invite System                 (cross-lobby invitations)
  C-019  Ban List                      (operator tools)
  C-025  Admin Commands                (KICK, BAN, MUTE)
  C-027  Spectator Mode                (observer access)

Sprint 3 — Intelligence & Observability
  C-010  Skill-Based Matchmaking       (ELO brackets)
  C-021  Session Event Ledger (TSRE)   (replay engine)
  C-016  Metrics Server                (/health /metrics endpoints)
  C-015  Log Rotation + Async Writer   (production logging)
  C-020  Player Profile Store          (persistence)

Sprint 4 — Protocol Expansion
  C-023  WebSocket Transport Adapter   (browser clients)
  C-031  Full Test Coverage            (all missing test suites)
```

---

*AegisCore — Built with Java 26. Zero external dependencies. Built to be the backbone of the next generation of multiplayer.*
