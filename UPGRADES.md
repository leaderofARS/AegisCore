# AegisCore — Upgrades & Game Integration Guide

> **Everything needed to integrate AegisCore into any game, VR/AR experience, or competitive battle title.**
> This document covers top features, engine-specific integration paths, protocol extension points,
> and the full upgrade roadmap from the current stable core to a fully distributed, VR-ready lobby platform.

---

## Table of Contents

1. [Top Features — What AegisCore Already Gives You](#-top-features)
2. [How AegisCore Fits Into a Game Stack](#-how-aegiscore-fits-into-a-game-stack)
3. [Integration Guides by Platform](#-integration-guides-by-platform)
   - [Unity (PC / VR / Mobile)](#unity-pc--vr--mobile)
   - [Unreal Engine (PC / VR)](#unreal-engine-pc--vr)
   - [Godot (PC / Mobile / Web)](#godot-pc--mobile--web)
   - [Browser / WebGL Clients](#browser--webgl-clients)
   - [Android & iOS Native Clients](#android--ios-native-clients)
4. [Upgrade Tracks](#-upgrade-tracks)
   - [UPG-001 · WebSocket Transport Adapter](#upg-001--websocket-transport-adapter)
   - [UPG-002 · TLS/SSL Encrypted Transport](#upg-002--tlsssl-encrypted-transport)
   - [UPG-003 · JWT / Token-Based Authentication](#upg-003--jwttoken-based-authentication)
   - [UPG-004 · NIO Event Loop (10 K+ Clients)](#upg-004--nio-event-loop-10k-clients)
   - [UPG-005 · Skill-Based Matchmaking (ELO/MMR)](#upg-005--skill-based-matchmaking-elommr)
   - [UPG-006 · Persistent Player Profiles (SQLite / PostgreSQL)](#upg-006--persistent-player-profiles-sqlite--postgresql)
   - [UPG-007 · Guild / Party System](#upg-007--guild--party-system)
   - [UPG-008 · Temporal Session Replay Engine (TSRE)](#upg-008--temporal-session-replay-engine-tsre)
   - [UPG-009 · VR Spatial Lobby Sync](#upg-009--vr-spatial-lobby-sync)
   - [UPG-010 · AR Proximity Matchmaking](#upg-010--ar-proximity-matchmaking)
   - [UPG-011 · gRPC Game World Bridge](#upg-011--grpc-game-world-bridge)
   - [UPG-012 · Redis Pub/Sub Distributed Clustering](#upg-012--redis-pubsub-distributed-clustering)
   - [UPG-013 · Spectator & Broadcast Mode](#upg-013--spectator--broadcast-mode)
   - [UPG-014 · Tournament / Bracket Engine](#upg-014--tournament--bracket-engine)
   - [UPG-015 · Anti-Cheat Session Auditing](#upg-015--anti-cheat-session-auditing)
   - [UPG-016 · Prometheus / Grafana Metrics Pipeline](#upg-016--prometheus--grafana-metrics-pipeline)
   - [UPG-017 · Docker & Kubernetes Deployment](#upg-017--docker--kubernetes-deployment)
   - [UPG-018 · Spatial Audio Routing Metadata](#upg-018--spatial-audio-routing-metadata)
   - [UPG-019 · Cross-Realm Lobby Continuity](#upg-019--cross-realm-lobby-continuity)
   - [UPG-020 · Behavioral Friction Mitigation (BFM)](#upg-020--behavioral-friction-mitigation-bfm)
5. [Protocol Extension Reference](#-protocol-extension-reference)
6. [Component Readiness Matrix](#-component-readiness-matrix)

---

## 🏆 Top Features

These are the capabilities AegisCore delivers **today** — production-stable, zero-dependency, open to the metal.

### Feature Matrix

| # | Feature | Description | Concurrency Model |
|:--|:--------|:------------|:-----------------|
| **F-01** | **Multithreaded TCP Server** | One thread per connected client over raw TCP sockets. Handles hundreds of simultaneous connections with isolated exception scopes — one bad client cannot crash others. | `Thread` per `ClientHandler`, isolated try/catch |
| **F-02** | **Player Session State Machine** | Every player moves through `CONNECTED → IN_LOBBY → IN_ROOM → QUEUED → IN_GAME` lifecycle states. State is validated before every command — no command runs out of sequence. | `volatile` fields on `Player` |
| **F-03** | **Named Lobby Rooms** | Players create named rooms with a custom slot count (2–32). Rooms have a unique ID, live status, and broadcast to all occupants. | `CopyOnWriteArrayList` for safe iteration |
| **F-04** | **Ready-Check with Countdown** | When all players in a room mark `READY`, a synchronized 5-second countdown fires. Any `UNREADY` during countdown cancels it cleanly and atomically. | `synchronized(Room.this)` + `ScheduledExecutorService` |
| **F-05** | **Automatic Matchmaking Queue** | Players type `QUEUE` and the daemon automatically pairs them into a new room as soon as enough players are in queue. 30-second timeout returns players to lobby gracefully. | `LinkedBlockingQueue` + daemon thread |
| **F-06** | **Skill-Based Matchmaking** | ELO/MMR bracket system: `SkillBasedQueue` pairs players within configurable skill-delta windows using `MatchmakingPolicy` strategies. | Priority queue sorted by skill delta |
| **F-07** | **Room-Scoped Broadcast Chat** | `CHAT <message>` broadcasts to every player in the same room simultaneously. Thread-safe under concurrent join/leave. | `CopyOnWriteArrayList` iteration |
| **F-08** | **Private Whisper Messaging** | `WHISPER <name> <msg>` delivers a direct message to any online player regardless of their room. | Direct socket write via `PlayerRegistry` lookup |
| **F-09** | **Invite System** | `INVITE <name>` sends a lobby invite to a player by name. Invites expire after a configurable TTL. `ACCEPT` / `DECLINE` flow handled atomically. | `InviteManager` with timestamp expiry |
| **F-10** | **Rate Limiter & Connection Guard** | Token-bucket rate limiter per client (configurable burst + refill). `ConnectionGuard` caps max connections per IP to prevent flood/DoS. | Per-client `RateLimiter` token bucket |
| **F-11** | **Input Sanitizer & Name Validator** | Max 512-char lines, control-character stripping, UTF-8 validation. Usernames enforce alphanumeric + underscore, 2–20 chars, no reserved words. | `InputSanitizer` + `NameValidator` |
| **F-12** | **Heartbeat PING/PONG** | Server sends `PING` every 15 seconds. Clients that do not respond within 5 seconds are evicted. Eliminates zombie/half-open TCP connections. | `HeartbeatManager` daemon thread |
| **F-13** | **Thread Pool Execution** | `ClientThreadPool` wraps a bounded `ExecutorService` — prevents thread exhaustion under high load. Configurable pool size and queue overflow policy. | `ExecutorService` bounded pool |
| **F-14** | **WebSocket Protocol Adapter** | `ProtocolDetector` inspects first bytes of each connection. WebSocket connections complete RFC 6455 handshake and frame decode/encode transparently, then consume the same `CommandRouter`. | `ProtocolDetector` → `WebSocketClientHandler` |
| **F-15** | **Persistent Player Profiles** | `FileProfileStore` (zero-dependency JSON) saves player name, total sessions, total games, and join date. Loads on reconnect. Swappable to PostgreSQL via `ProfileStore` interface. | File I/O via `ProfileStore` interface |
| **F-16** | **Session Event Ledger** | Every lobby event (join, leave, ready, chat, state change) is appended as a timestamped `SessionEvent` to a per-room `SessionLedger`. Written to `logs/sessions/` on room close. | Append-only `ArrayList` per room |
| **F-17** | **Ledger Replay Engine** | `LedgerReplayer` reads a `.ledger` file and replays all events in chronological order — dispute resolution, anti-cheat auditing, or session reconstruction. | Sequential file read + event dispatch |
| **F-18** | **Spectator Mode** | `SPECTATE <room-id>` joins a room as an observer. Spectators receive all broadcasts but cannot send game commands and do not occupy player slots. | `SpectatorRegistry` separate from player list |
| **F-19** | **Admin Command Channel** | `ADMIN KICK/BAN/MUTE/CLOSE_ROOM/SHUTDOWN` commands available to privileged `AdminSession` connections. All actions written to a separate audit log. | `AdminAuthenticator` + `AdminAuditLog` |
| **F-20** | **Region Tagging** | Players and rooms carry a `RegionTag` (NA_EAST, EU_WEST, ASIA_PACIFIC, etc.) for latency-aware matchmaking and room filtering. | `RegionTag` enum on `Player` + `MatchConfig` |
| **F-21** | **Graceful 3-Phase Shutdown** | On SIGTERM: (1) stop accepting connections, (2) drain in-flight commands, (3) close all sockets cleanly. No data loss on restart. | JVM shutdown hook |
| **F-22** | **Live Server Telemetry** | `STATS` command returns a real-time snapshot: total connections, active rooms, queued players, messages sent, bytes transferred, JVM uptime. | Immutable `ServerStats` snapshot |
| **F-23** | **Per-Channel Concurrent Logging** | 4 independent log-file monitors (`Server.log`, `ClientHandler.log`, `Registry.log`, `ClientID.log`). Writes never block each other. | 4 independent `Object` monitors |
| **F-24** | **Ban & Block List** | Persistent IP and username bans with reason and expiry timestamp. Enforced at connection and at command dispatch. | `BanList` + `BanEntry` with file persistence |
| **F-25** | **Zero External Dependencies** | Entire server compiles and runs with `javac` and `java`. No Maven, no Gradle, no third-party JARs required — embed anywhere. | Pure JDK |

---

## 🎮 How AegisCore Fits Into a Game Stack

AegisCore occupies the **lobby layer** — the infrastructure between the player's client and the live game world. It is not a game engine; it is the pre-game and session-management server that every multiplayer title needs.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLIENT SIDE                                  │
│  Unity / Unreal / Godot / Browser / Mobile / VR Headset            │
│  ↕  TCP text protocol  OR  WebSocket (with UPG-001)                │
└───────────────────────────┬─────────────────────────────────────────┘
                            │  Port 5000 (TCP) / 5001 (WebSocket)
┌───────────────────────────▼─────────────────────────────────────────┐
│                        AEGISCORE                                    │
│  Pre-game lobby: sessions, rooms, matchmaking, ready-check          │
│  Admin tools, spectator, social (whisper, invite), security         │
│  Session ledger, player profiles, telemetry                         │
└───────────────────────────┬─────────────────────────────────────────┘
                            │  IN_PROGRESS event fires
                            │  → gRPC handoff (UPG-011)
┌───────────────────────────▼─────────────────────────────────────────┐
│                     YOUR GAME WORLD SERVER                          │
│  Physics, combat, state sync, movement, scoring, anti-cheat         │
│  (Unity Netcode, Mirror, Photon Realtime, custom, etc.)             │
└─────────────────────────────────────────────────────────────────────┘
```

### The Handoff Contract

When AegisCore fires the `IN_PROGRESS` event, it guarantees:

- ✅ All players in the room are confirmed connected and ready
- ✅ Room ID is stable and unique for the session lifetime
- ✅ Player display names, session IDs, and optionally ELO/MMR are available
- ✅ Room slot count matches the expected player count for the game mode
- ✅ Session ledger is open and recording

Your game world server receives these facts and takes ownership of the live session. AegisCore holds the lobby session state — your server holds the game state.

---

## 🔌 Integration Guides by Platform

### Unity (PC / VR / Mobile)

**Recommended transport:** TCP text socket (current) or WebSocket (after UPG-001)

#### Minimal TCP Integration (Works Now)

```csharp
// AegisCoreClient.cs — drop into any Unity project
using System.Net.Sockets;
using System.IO;
using UnityEngine;

public class AegisCoreClient : MonoBehaviour
{
    TcpClient       _tcp;
    StreamWriter    _writer;
    StreamReader    _reader;
    Thread          _receiveThread;

    public void Connect(string host = "127.0.0.1", int port = 5000)
    {
        _tcp    = new TcpClient(host, port);
        _writer = new StreamWriter(_tcp.GetStream()) { AutoFlush = true };
        _reader = new StreamReader(_tcp.GetStream());

        _receiveThread = new Thread(ReceiveLoop) { IsBackground = true };
        _receiveThread.Start();
    }

    public void Send(string command) => _writer.WriteLine(command);

    public void SetName(string name)   => Send($"NAME {name}");
    public void CreateRoom(string n, int slots) => Send($"CREATE {n} {slots}");
    public void JoinRoom(string id)    => Send($"JOIN {id}");
    public void MarkReady()            => Send("READY");
    public void EnterQueue()           => Send("QUEUE");

    void ReceiveLoop()
    {
        while (_tcp.Connected)
        {
            string line = _reader.ReadLine();
            if (line == null) break;
            // Dispatch on Unity main thread:
            UnityMainThreadDispatcher.Enqueue(() => OnServerMessage(line));
        }
    }

    void OnServerMessage(string msg)
    {
        if (msg.StartsWith("[INFO]") && msg.Contains("Game session started"))
            OnGameStart(); // → load your game scene
        // handle [READY], [MATCH], [ROOM], [ERROR] ...
    }

    void OnGameStart()
    {
        // Hand off to your game world server here
        Debug.Log("AegisCore: all players ready → loading game scene");
        SceneManager.LoadScene("GameWorld");
    }
}
```

#### VR-Specific Integration (Quest / SteamVR)

```
Connection flow for VR:
  XR Rig initialises → AegisCoreClient.Connect() on Awake()
  Player sets name via voice/virtual keyboard → Send("NAME <name>")
  VR lobby scene: show room list via LIST command
  Player points + triggers to "Join" a room hologram
  When [INFO] Game session started → fade to black → XR rig teleports to game world
```

**Key VR considerations:**
- Run `ReceiveLoop` on a background thread — never block the render thread
- Spatial lobby UI should refresh on every `[ROOM]` broadcast
- Implement `PING` response handling to stay alive during XR load screens

---

### Unreal Engine (PC / VR)

**Recommended transport:** TCP text via Unreal's `FSocket` API

```cpp
// AegisCoreSubsystem.h
UCLASS()
class UAegisCoreSubsystem : public UGameInstanceSubsystem
{
    GENERATED_BODY()
public:
    void Connect(const FString& Host, int32 Port);
    void SendCommand(const FString& Command);

    UFUNCTION(BlueprintCallable, Category="AegisCore")
    void SetName(const FString& Name) { SendCommand(FString::Printf(TEXT("NAME %s"), *Name)); }

    UFUNCTION(BlueprintCallable, Category="AegisCore")
    void JoinRoom(const FString& RoomId) { SendCommand(FString::Printf(TEXT("JOIN %s"), *RoomId)); }

    UFUNCTION(BlueprintCallable, Category="AegisCore")
    void MarkReady() { SendCommand(TEXT("READY")); }

    // Delegates for Blueprint
    DECLARE_DYNAMIC_MULTICAST_DELEGATE_OneParam(FOnGameStart, FString, RoomId);
    UPROPERTY(BlueprintAssignable) FOnGameStart OnGameStart;

private:
    FSocket* Socket;
    void ReceiveLoop(); // Runs on async task
};
```

**Blueprint integration:** Bind `OnGameStart` delegate in Level Blueprint → `Open Level` to game map.

---

### Godot (PC / Mobile / Web)

**Recommended transport:** TCP (GDScript `StreamPeerTCP`) or WebSocket after UPG-001

```gdscript
# aegis_core_client.gd
extends Node

var stream := StreamPeerTCP.new()
var _connected := false

signal game_started(room_id: String)
signal room_updated(rooms: Array)

func connect_to_server(host := "127.0.0.1", port := 5000):
    stream.connect_to_host(host, port)

func _process(_delta):
    stream.poll()
    if stream.get_status() == StreamPeerTCP.STATUS_CONNECTED:
        _connected = true
        while stream.get_available_bytes() > 0:
            var line = stream.get_utf8_string(stream.get_available_bytes()).strip_edges()
            _handle_message(line)

func send_cmd(cmd: String):
    stream.put_data((cmd + "\n").to_utf8_buffer())

func set_name(name: String):  send_cmd("NAME " + name)
func create_room(name: String, slots: int): send_cmd("CREATE " + name + " " + str(slots))
func join_room(id: String):   send_cmd("JOIN " + id)
func mark_ready():            send_cmd("READY")
func queue():                 send_cmd("QUEUE")

func _handle_message(msg: String):
    if "[INFO]" in msg and "Game session started" in msg:
        emit_signal("game_started", _current_room_id)
        get_tree().change_scene_to_file("res://scenes/GameWorld.tscn")
```

---

### Browser / WebGL Clients

**Requires:** [UPG-001 WebSocket Transport Adapter](#upg-001--websocket-transport-adapter)

Once UPG-001 is deployed, browser clients connect with standard WebSocket:

```javascript
// aegis-core-client.js — works in any browser or Node.js
class AegisCoreClient {
  constructor(host = 'localhost', port = 5001) {
    this.ws = new WebSocket(`ws://${host}:${port}`);
    this.ws.onmessage = ({ data }) => this._handleMessage(data);
    this.ws.onopen    = () => console.log('[AegisCore] Connected');
  }

  send(cmd)                    { this.ws.send(cmd + '\n'); }
  setName(name)                { this.send(`NAME ${name}`); }
  createRoom(name, slots = 4)  { this.send(`CREATE ${name} ${slots}`); }
  joinRoom(id)                 { this.send(`JOIN ${id}`); }
  ready()                      { this.send('READY'); }
  queue()                      { this.send('QUEUE'); }

  _handleMessage(msg) {
    if (msg.startsWith('[INFO]') && msg.includes('Game session started')) {
      this.onGameStart?.();  // → navigate to game, connect to game world WS
    }
  }
}
```

For **Unity WebGL builds** — use this JavaScript bridge via `Application.ExternalCall` or a JSLib plugin.

---

### Android & iOS Native Clients

**Transport:** TCP via Java `Socket` (Android) or Swift `NWConnection` (iOS)

```java
// AegisCoreClient.java — Android
public class AegisCoreClient {
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        new Thread(this::receiveLoop).start();
    }

    public void sendCommand(String cmd) { writer.println(cmd); }
    public void setName(String name)    { sendCommand("NAME " + name); }
    public void joinRoom(String id)     { sendCommand("JOIN " + id); }
    public void markReady()             { sendCommand("READY"); }

    private void receiveLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Game session started"))
                    onGameStart();
                // post to main thread via Handler
            }
        } catch (IOException ignored) {}
    }
}
```

---

## 🚀 Upgrade Tracks

Each upgrade is independently deployable. Apply only the upgrades your game type needs.

---

### UPG-001 · WebSocket Transport Adapter

> **Status:** 🔧 Skeleton implemented (`network/WebSocketHandshake.java`, `WebSocketFrame.java`, `WebSocketClientHandler.java`, `ProtocolDetector.java`)
> **Enables:** Browser clients, Unity WebGL, any JS/HTML5 game frontend

**What to complete:**

| Class | Remaining Work |
|:------|:---------------|
| `ProtocolDetector.java` | Wire into `Server.java` accept loop — inspect first byte, route to `WebSocketClientHandler` or `ClientHandler` |
| `WebSocketClientHandler.java` | Complete frame fragmentation handling for messages > 125 bytes |
| `WebSocketHandshake.java` | Add subprotocol negotiation (`Sec-WebSocket-Protocol: aegiscore`) |

**Integration point:**
```java
// In Server.java accept loop:
Socket client = serverSocket.accept();
ClientHandler handler = ProtocolDetector.detect(client); // NEW
pool.submit(handler);
```

**Game types:** All browser-based games, Unity WebGL, Godot HTML5 exports, React/Vue game dashboards.

---

### UPG-002 · TLS/SSL Encrypted Transport

> **Status:** 📋 Planned
> **Enables:** Secure production deployments, app store compliance (iOS/Android requirements)

**Implementation plan:**

```
security/
└── TlsConfig.java     # Keystore path, password, protocol version (TLSv1.3)

server/
└── Server.java        # Replace ServerSocket with SSLServerSocket:
                       #   SSLContext ctx = SSLContext.getInstance("TLS");
                       #   ctx.init(kmf.getKeyManagers(), null, null);
                       #   serverSocket = ctx.getServerSocketFactory()
                       #                    .createServerSocket(port);
```

**Certificate setup:**
```bash
# Self-signed for dev:
keytool -genkeypair -alias aegiscore -keyalg RSA -keysize 2048 \
        -keystore aegiscore.jks -validity 365

# Production: use Let's Encrypt cert via certbot, convert to JKS
```

**Game types:** Any game shipping to app stores. Required for iOS and increasingly enforced on Android.

---

### UPG-003 · JWT / Token-Based Authentication

> **Status:** 📋 Planned
> **Enables:** Stateless player identity, reconnect across sessions, integration with your game's auth backend

**Design:**

```
security/
├── JwtProvider.java         # HMAC-SHA256 token sign/verify (hand-rolled, zero deps)
├── AuthToken.java           # Claims: playerId, displayName, elo, expiresAt
└── AuthCommandHandler.java  # AUTH <token> command — validates JWT, sets player identity
```

**Protocol extension:**
```
AUTH <jwt-token>
→ [SERVER] Authenticated as Kirito (ELO: 1847)
```

**Integration with your auth backend:**
```
Game login server → issues JWT with player claims
Player client     → connects to AegisCore → sends AUTH <token>
AegisCore         → verifies JWT signature → loads player identity
```

**Game types:** Any game with user accounts. Essential for competitive titles tracking stats.

---

### UPG-004 · NIO Event Loop (10K+ Clients)

> **Status:** 📋 Planned (Phase 2 of ROADMAP.md)
> **Enables:** Massive concurrent player counts — MMO lobbies, battle royale pre-game, esports platforms

**Architecture shift:**

```
Current:  1 thread per client (good to ~500 concurrent)
Upgraded: NIO Selector event loop (10,000+ concurrent, same JVM)
```

**Implementation:**
```
network/
├── NioEventLoop.java        # Selector-based I/O demultiplexer
├── NioClientSession.java    # Non-blocking read/write buffers per client
└── NioServer.java           # Drop-in replacement for Server.java
```

**No protocol changes** — the command layer stays identical. Only the transport layer changes.

**Game types:** Battle royale (100+ players in pre-game lobby), MMO world entry, large esports events.

---

### UPG-005 · Skill-Based Matchmaking (ELO/MMR)

> **Status:** 🔧 Skeleton implemented (`matchmaking/SkillBracket.java`, `SkillBasedQueue.java`, `PlayerSkillProfile.java`, `MatchmakingPolicy.java`, `RegionTag.java`)
> **Enables:** Competitive integrity — players match opponents at their skill level

**What to complete:**

| Class | Remaining Work |
|:------|:---------------|
| `SkillBasedQueue.java` | Wire into `MatchmakingQueue` — replace `LinkedBlockingQueue` with `PriorityQueue` sorted by ELO |
| `PlayerSkillProfile.java` | Persist ELO to `FileProfileStore` after each match result |
| `MatchmakingPolicy.java` | Implement `REGIONAL` policy — prefer players within 50ms ping window |

**ELO update flow:**
```
Game session ends → game world server POSTs result to AegisCore REST endpoint
→ ELO calculated (standard K=32 formula)
→ PlayerProfile.elo updated and persisted
→ Player re-queues into correct bracket
```

**Game types:** All competitive PvP titles — FPS, MOBA, fighting games, card games, battle royale.

---

### UPG-006 · Persistent Player Profiles (SQLite / PostgreSQL)

> **Status:** 🔧 Skeleton implemented (`persistence/FileProfileStore.java`, `PlayerProfile.java`, `ProfileSerializer.java`)
> **Enables:** Stats persistence, cross-session identity, leaderboards, friend lists

**What to complete — SQLite backend:**

```java
// persistence/SqliteProfileStore.java
public class SqliteProfileStore implements ProfileStore {
    // Uses java.sql (built into JDK — zero deps)
    // Connection to aegiscore.db via DriverManager.getConnection("jdbc:sqlite:aegiscore.db")
    // Requires sqlite-jdbc.jar (only optional external dep in the entire project)
    
    @Override public void save(PlayerProfile p)    { /* INSERT OR REPLACE */ }
    @Override public PlayerProfile load(String name){ /* SELECT * FROM profiles WHERE name=? */ }
}
```

**Schema:**
```sql
CREATE TABLE profiles (
    name          TEXT PRIMARY KEY,
    elo           INTEGER DEFAULT 1000,
    total_sessions INTEGER DEFAULT 0,
    total_games   INTEGER DEFAULT 0,
    joined_date   TEXT,
    region        TEXT,
    metadata      TEXT  -- JSON blob for game-specific fields
);
```

**Game types:** Any game with persistent identity — RPGs, ranked competitive, social games.

---

### UPG-007 · Guild / Party System

> **Status:** 📋 Planned
> **Enables:** Persistent cross-session player groups, guild lobbies, clan war coordination

**Design:**

```
social/
├── Guild.java              # Guild: id, name, tag, owner, member list, ELO rating
├── GuildRegistry.java      # Global ConcurrentHashMap<String, Guild>
├── GuildCommandHandler.java # GUILD CREATE/JOIN/LEAVE/INVITE/KICK/INFO commands
└── PartyManager.java       # Ephemeral pre-formed group that enters matchmaking together
```

**Protocol extensions:**
```
GUILD CREATE <name> <tag>         → create a new guild
GUILD JOIN   <guild-id>           → request to join
GUILD INVITE <player-name>        → invite to your guild
PARTY FORM   <player1> <player2>  → form a temporary party for matchmaking
PARTY QUEUE                       → entire party enters matchmaking together
```

**Game types:** MMO guilds, MOBA pre-made teams, clan-based shooters, RTS team battles.

---

### UPG-008 · Temporal Session Replay Engine (TSRE)

> **Status:** 🔧 Skeleton implemented (`persistence/SessionLedger.java`, `SessionEvent.java`, `LedgerWriter.java`, `LedgerReplayer.java`)
> **Enables:** Dispute resolution, anti-cheat auditing, match history, coaching replay

**What to complete:**

| Class | Remaining Work |
|:------|:---------------|
| `SessionLedger.java` | Wire into `Room.java` — append event on every state change |
| `LedgerWriter.java` | Call `writeLedger()` in `Room.close()` |
| `LedgerReplayer.java` | Add HTTP endpoint: `GET /session/<room-id>/replay` (UPG-016 dependency) |

**Ledger file format:**
```
# AegisCore Session Ledger — Room r-042 — 2026-06-08T17:30:00Z
JOIN     17:30:00.001  Kirito
JOIN     17:30:02.340  Asuna
READY    17:30:10.001  Kirito
READY    17:30:11.500  Asuna
STATE    17:30:11.500  READY_CHECK
STATE    17:30:16.501  IN_PROGRESS
CHAT     17:30:18.200  Kirito "Good luck!"
```

**Game types:** Competitive games needing dispute resolution, esports platforms, any game with a reporting/review system.

---

### UPG-009 · VR Spatial Lobby Sync

> **Status:** 📋 Planned (Phase 5 of ROADMAP.md)
> **Enables:** Players see each other's avatars and positions in the VR pre-game lobby

**Design:**

```
vr/
├── SpatialState.java        # Position (x,y,z), rotation (quat), animation state
├── SpatialSyncHandler.java  # Handles MOVE <x> <y> <z> <qw> <qx> <qy> <qz> command
├── SpatialBroadcaster.java  # Broadcasts position deltas to all players in same room
└── MotionCompressor.java    # Delta compression — only sends changed axes (saves bandwidth)
```

**Protocol extension:**
```
MOVE <x> <y> <z> <qw> <qx> <qy> <qz>   → update your spatial position
→ [SPATIAL] Kirito 1.2 0.0 -3.4 1.0 0.0 0.0 0.0   (broadcast to room)
```

**Bandwidth model:**
- 60Hz position updates × 32 players × ~64 bytes = ~120 KB/s per room
- Delta compression + interest management reduces this ~10×

**Game types:** Full-dive VR lobbies, VR social spaces, AR outdoor multiplayer, metaverse entry points.

---

### UPG-010 · AR Proximity Matchmaking

> **Status:** 📋 Planned
> **Enables:** Match players physically near each other in AR games (Pokémon GO-style)

**Design:**

```
ar/
├── GeoLocation.java         # Lat/lon + accuracy radius
├── ProximityMatchPolicy.java # Match players within configurable distance (meters)
├── GeoQueue.java            # Geospatial queue: clusters players by location grid cell
└── ProximityCommandHandler.java # LOCATE <lat> <lon> command
```

**Protocol extension:**
```
LOCATE <lat> <lon>    → register your GPS position for proximity matching
QUEUE  PROXIMITY      → enter queue with proximity preference
```

**Game types:** Location-based AR games, outdoor multiplayer experiences, city-scale PvP.

---

### UPG-011 · gRPC Game World Bridge

> **Status:** 📋 Planned (Phase 4 of ROADMAP.md)
> **Enables:** Structured, type-safe handoff from AegisCore lobby to your game world server

**Design:**

```
grpc/
├── LobbyService.proto       # Protobuf definition: SessionStarted, PlayerInfo, RoomConfig
├── LobbyServiceImpl.java    # gRPC server stub: called when room reaches IN_PROGRESS
└── GameWorldClient.java     # gRPC client: notifies game world server of new session
```

**Proto definition:**
```protobuf
service AegisCoreCallback {
  rpc SessionStarted(SessionStartRequest) returns (SessionStartResponse);
}
message SessionStartRequest {
  string room_id      = 1;
  string game_mode    = 2;
  repeated PlayerInfo players = 3;
}
message PlayerInfo {
  string session_id   = 1;
  string display_name = 2;
  int32  elo          = 3;
  string region       = 4;
}
```

**Game types:** Any game with a dedicated game world server. Replaces manual socket callbacks.

---

### UPG-012 · Redis Pub/Sub Distributed Clustering

> **Status:** 📋 Planned (Phase 4 of ROADMAP.md)
> **Enables:** Horizontal scaling — multiple AegisCore nodes share player/room state

**Architecture:**

```
AegisCore Node A  ──┐
AegisCore Node B  ──┤──  Redis  ──  Pub/Sub channels:
AegisCore Node C  ──┘              aegis:players, aegis:rooms, aegis:matches
```

**Design:**

```
cluster/
├── RedisClusterBus.java      # Jedis-based pub/sub publisher and subscriber
├── ClusterEvent.java         # JOIN_ROOM, LEAVE_ROOM, PLAYER_READY, MATCH_FOUND
├── DistributedPlayerRegistry.java  # Redis-backed player registry
└── DistributedRoomRegistry.java    # Redis-backed room registry
```

**Game types:** Large-scale MMOs, esports platforms, global battle royale — anything needing >1 server node.

---

### UPG-013 · Spectator & Broadcast Mode

> **Status:** 🔧 Skeleton defined (`spectator/SpectatorSession.java`, `SpectatorRegistry.java`)
> **Enables:** Live observers, streaming integration, coaching, tournament casting

**What to complete:**

```
spectator/
├── SpectatorSession.java      # Receives all room broadcasts, no state mutation
├── SpectatorRegistry.java     # Per-room spectator list
└── SpectatorCommandHandler.java # SPECTATE <room-id> and UNSPECTATE
```

**Stream integration:** Combine with UPG-001 (WebSocket) to push live lobby state to a browser-based casting dashboard.

**Game types:** Esports tournaments, competitive leagues, game coaching platforms.

---

### UPG-014 · Tournament / Bracket Engine

> **Status:** 📋 Planned
> **Enables:** Organized multi-match competitions — single elimination, double elimination, round robin

**Design:**

```
tournament/
├── Tournament.java          # Tournament state: name, format, participants, bracket
├── BracketEngine.java       # Generates and advances single/double-elim brackets
├── Match.java               # Match record: player1, player2, winner, room_id, timestamp
├── TournamentRegistry.java  # Global tournament map
└── TournamentCommands.java  # TOURNAMENT CREATE/JOIN/START/RESULT commands
```

**Protocol extension:**
```
TOURNAMENT CREATE <name> SINGLE_ELIM 8
TOURNAMENT JOIN   <tournament-id>
TOURNAMENT START  <tournament-id>       (admin only)
TOURNAMENT RESULT <match-id> <winner>   (game world callback)
```

**Game types:** Esports tournaments, game jam competitions, ranked season events, clan wars.

---

### UPG-015 · Anti-Cheat Session Auditing

> **Status:** 📋 Planned (builds on UPG-008 TSRE)
> **Enables:** Lobby-level fair play: impossible ready times, duplicate account detection, ban evasion detection

**Design:**

```
anticheat/
├── LobbyAuditor.java         # Analyzes SessionLedger for anomalies
├── AuditRule.java            # Interface: rule name + evaluate(SessionLedger) → AuditFlag
├── AuditFlag.java            # Flag: severity, rule_name, evidence, timestamp
└── AuditReport.java          # Summary report per session, written to logs/audits/
```

**Built-in rules:**
- `InstantReadyRule` — ready within 100ms of joining (bot indicator)
- `DuplicateNameRule` — same display name from different IPs
- `FloodCommandRule` — command rate spikes (bypassed rate limiter via reconnect)
- `RoomHopRule` — joins and leaves >5 rooms in 60 seconds

**Game types:** Any competitive game. Essential for ranked modes.

---

### UPG-016 · Prometheus / Grafana Metrics Pipeline

> **Status:** 🔧 Skeleton defined (`core/MetricsCollector.java`, `MetricsServer.java`)
> **Enables:** Production observability — dashboards, alerts, SLA monitoring

**What to complete:**

```java
// core/MetricsServer.java — expose /metrics and /health via embedded HTTP
HttpServer http = HttpServer.create(new InetSocketAddress(8080), 0);
http.createContext("/metrics", ctx -> {
    String body = MetricsCollector.formatPrometheus();
    ctx.sendResponseHeaders(200, body.length());
    ctx.getResponseBody().write(body.getBytes());
});
http.createContext("/health", ctx -> { /* {"status":"UP"} */ });
```

**Prometheus metrics exposed:**
```
aegiscore_connected_players_total
aegiscore_active_rooms_total
aegiscore_queued_players_total
aegiscore_messages_sent_total
aegiscore_bytes_sent_total
aegiscore_matchmaking_wait_seconds (histogram)
aegiscore_jvm_heap_bytes
```

**Game types:** Any production game server. Required for ops teams.

---

### UPG-017 · Docker & Kubernetes Deployment

> **Status:** 📋 Planned
> **Enables:** One-command deployment, cloud-native scaling, CI/CD pipeline integration

**Files to create:**

```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY bin/ bin/
EXPOSE 5000 5001 8080
CMD ["java", "-cp", "bin", "server.Server"]
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  aegiscore:
    build: .
    ports:
      - "5000:5000"   # TCP lobby
      - "5001:5001"   # WebSocket lobby
      - "8080:8080"   # Metrics/health
    environment:
      - AEGIS_PORT=5000
      - AEGIS_WS_PORT=5001
      - AEGIS_MAX_ROOMS=500
      - AEGIS_LOG_LEVEL=INFO
```

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: aegiscore
spec:
  replicas: 3
  selector:
    matchLabels: {app: aegiscore}
  template:
    spec:
      containers:
      - name: aegiscore
        image: aegiscore:latest
        ports:
        - containerPort: 5000
        - containerPort: 5001
        - containerPort: 8080
        livenessProbe:
          httpGet: {path: /health, port: 8080}
```

**Game types:** Any game targeting cloud deployment. Essential for production scale.

---

### UPG-018 · Spatial Audio Routing Metadata

> **Status:** 📋 Planned (Phase 5 of ROADMAP.md)
> **Enables:** Voice chat lobby — players hear teammates before the game even starts

**Design:**

```
audio/
├── AudioChannel.java        # Named audio group: lobby, room, team, world-zone
├── AudioRouter.java         # Routes players to audio channels based on room/team membership
└── AudioCommandHandler.java # AUDIO JOIN/LEAVE/MUTE commands
```

**Protocol extension:**
```
AUDIO JOIN  <channel-name>   → join a voice channel
AUDIO MUTE  <player-name>    → mute a specific player
AUDIO LEAVE                  → leave current channel
→ [AUDIO] Channel: room-r001 | Members: Kirito, Asuna
```

**Integration:** AegisCore manages channel membership metadata only. Actual audio stream routes through a dedicated voice server (e.g., Agora, LiveKit, or self-hosted Livekit/Janus). AegisCore tells your voice server who should be in which channel.

**Game types:** VR social spaces, team-based shooters, MMO parties, battle royale squads.

---

### UPG-019 · Cross-Realm Lobby Continuity

> **Status:** 📋 Planned
> **Enables:** Players move between game instances (e.g., dungeon → town → dungeon) without disconnecting from AegisCore

**Design:**

```
session/
├── RealmDescriptor.java     # Named game instance: realm_id, server_addr, port, capacity
├── RealmRegistry.java       # Active game world instances AegisCore knows about
├── RealmTransfer.java       # Coordinated transfer: PENDING_INVITE → IN_GAME state
└── RealmCommandHandler.java # REALM LIST / REALM TRANSFER <realm-id> commands
```

**Flow:**
```
Player finishes dungeon → game world server fires REALM COMPLETE
→ AegisCore moves player back to IN_LOBBY
→ Player sees available realms via REALM LIST
→ Joins party → enters next realm together
```

**Game types:** MMOs, persistent world games, hub-and-spoke game designs, metaverse platforms.

---

### UPG-020 · Behavioral Friction Mitigation (BFM)

> **Status:** 📋 Planned
> **Enables:** Reduce AFK, rage-quit, and griefing via lobby-level behavioral signals

**Design:**

```
behavior/
├── BehaviorProfile.java     # Per-player: ready_rate, abandon_rate, report_count, commend_count
├── BehaviorTracker.java     # Hooks into RoomEvents to track behavior patterns
├── FrictionPolicy.java      # Policies: low-prio queue for abandoners, warning thresholds
└── BehaviorCommandHandler.java # REPORT <player> <reason> / COMMEND <player>
```

**Tracked signals:**
- **Ready rate:** % of times player marks READY vs. joins rooms
- **Abandon rate:** % of IN_PROGRESS rooms player left within first 60 seconds
- **Report ratio:** net reports received vs. commends received
- **Queue AFK:** joined queue but did not respond to match found within 30s

**Effect:**
- High abandon rate → placed in "low-priority" matchmaking queue
- High report count → flagged for manual review
- High commend rate → priority queue access (positive reinforcement)

**Game types:** Any competitive game. Mirrors Valve's Trust Factor / Riot's behavior system.

---

## 📡 Protocol Extension Reference

All AegisCore protocol extensions follow the same line-oriented text format.
New commands are additive — existing commands never break.

### Current Command Set (Stable)

```
NAME   <username>
CREATE <room-name> [slots]
JOIN   <room-id>
LEAVE
LIST
READY
UNREADY
QUEUE
DEQUEUE
CHAT   <message>
STATS
QUIT
```

### Planned Command Extensions

| Command | Upgrade | Description |
|:--------|:--------|:------------|
| `AUTH <token>` | UPG-003 | Authenticate with JWT token |
| `WHISPER <name> <msg>` | F-08 (done) | Private message |
| `INVITE <name>` | F-09 (done) | Room invite by name |
| `SPECTATE <room-id>` | UPG-013 | Join as observer |
| `ADMIN <sub-cmd>` | F-19 (done) | Admin operations |
| `MOVE <x> <y> <z> <qw> <qx> <qy> <qz>` | UPG-009 | VR spatial position |
| `LOCATE <lat> <lon>` | UPG-010 | AR GPS position |
| `AUDIO JOIN/LEAVE/MUTE` | UPG-018 | Voice channel membership |
| `GUILD CREATE/JOIN/LEAVE/INVITE` | UPG-007 | Guild management |
| `PARTY FORM/QUEUE` | UPG-007 | Party matchmaking |
| `TOURNAMENT CREATE/JOIN/START` | UPG-014 | Tournament management |
| `REALM LIST/TRANSFER` | UPG-019 | Cross-realm navigation |
| `REPORT <name> <reason>` | UPG-020 | Behavior reporting |
| `COMMEND <name>` | UPG-020 | Positive commendation |

### Server Response Prefixes (Extended)

| Prefix | Source |
|:-------|:-------|
| `[SERVER]` | System info and confirmations |
| `[ROOM]` | Room-scoped broadcasts |
| `[MATCH]` | Matchmaking events |
| `[READY]` | Ready-check state changes |
| `[INFO]` | Join/leave/game-start events |
| `[ERROR]` | Validation and state errors |
| `[WHISPER]` | Private messages |
| `[INVITE]` | Invite notifications |
| `[SPATIAL]` | VR position updates |
| `[AUDIO]` | Voice channel events |
| `[GUILD]` | Guild notifications |
| `[TOURNAMENT]` | Tournament bracket events |
| `[REALM]` | Cross-realm transfer events |
| `[AUDIT]` | Anti-cheat flags (admin only) |

---

## 📊 Component Readiness Matrix

| Upgrade ID | Feature | Status | Game Types Unlocked |
|:-----------|:--------|:-------|:-------------------|
| F-01..F-25 | Core lobby, rooms, matchmaking, security, social | ✅ **Done** | All multiplayer games |
| UPG-001 | WebSocket Transport | 🔧 Partial | Browser, Unity WebGL, Godot HTML5 |
| UPG-002 | TLS/SSL | 📋 Planned | Mobile (App Store compliance), production |
| UPG-003 | JWT Auth | 📋 Planned | Games with user accounts |
| UPG-004 | NIO Event Loop | 📋 Planned | Battle royale, MMO (10K+ players) |
| UPG-005 | Skill-Based Matchmaking | 🔧 Partial | All competitive PvP |
| UPG-006 | Persistent Profiles | 🔧 Partial | Ranked games, RPG, any with stats |
| UPG-007 | Guild / Party System | 📋 Planned | MMO, MOBA, clan shooters |
| UPG-008 | Session Replay (TSRE) | 🔧 Partial | Competitive, esports, coaching |
| UPG-009 | VR Spatial Sync | 📋 Planned | VR lobbies, metaverse |
| UPG-010 | AR Proximity Match | 📋 Planned | Location-based AR games |
| UPG-011 | gRPC Game Bridge | 📋 Planned | All games with dedicated game servers |
| UPG-012 | Redis Clustering | 📋 Planned | Global scale, multi-region |
| UPG-013 | Spectator Mode | 🔧 Partial | Esports, coaching, streaming |
| UPG-014 | Tournament Engine | 📋 Planned | Competitive leagues, events |
| UPG-015 | Anti-Cheat Auditing | 📋 Planned | All ranked competitive games |
| UPG-016 | Prometheus Metrics | 🔧 Partial | Production ops, any live game |
| UPG-017 | Docker / Kubernetes | 📋 Planned | Cloud deployment |
| UPG-018 | Spatial Audio Routing | 📋 Planned | VR, team games, social platforms |
| UPG-019 | Cross-Realm Continuity | 📋 Planned | MMO, persistent world, metaverse |
| UPG-020 | Behavioral Friction Mitigation | 📋 Planned | Ranked competitive, community health |

---

## 🏗️ Recommended Integration Order by Game Type

### Competitive Arena / FPS / Battle Royale
```
F-01..F-25 → UPG-005 (ELO) → UPG-003 (Auth) → UPG-006 (Profiles)
→ UPG-008 (Ledger) → UPG-015 (Anti-Cheat) → UPG-004 (NIO for scale)
→ UPG-011 (gRPC) → UPG-016 (Metrics) → UPG-017 (Docker)
```

### VR / AR Immersive Game
```
F-01..F-25 → UPG-001 (WebSocket) → UPG-009 (Spatial Sync)
→ UPG-018 (Audio) → UPG-003 (Auth) → UPG-010 (AR Proximity if outdoor)
→ UPG-019 (Cross-Realm) → UPG-017 (Docker)
```

### MMO / Persistent World
```
F-01..F-25 → UPG-006 (Profiles) → UPG-007 (Guilds)
→ UPG-003 (Auth) → UPG-019 (Cross-Realm) → UPG-012 (Clustering)
→ UPG-011 (gRPC) → UPG-017 (Kubernetes)
```

### Esports / Tournament Platform
```
F-01..F-25 → UPG-005 (ELO) → UPG-013 (Spectator)
→ UPG-014 (Tournament) → UPG-008 (Ledger) → UPG-015 (Anti-Cheat)
→ UPG-001 (WebSocket for casting dashboard) → UPG-016 (Metrics)
```

### Browser / Casual Multiplayer
```
F-01..F-25 → UPG-001 (WebSocket) → UPG-002 (TLS)
→ UPG-006 (Profiles) → UPG-017 (Docker)
```

---

*AegisCore — Built with Java 26. Zero dependencies. The backbone layer every multiplayer world needs.*
