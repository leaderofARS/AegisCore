# AegisCore System Architecture

This document describes the high-level architecture of the **AegisCore** Game Lobby Server, detailing its package layout, component interactions, data flows, and concurrency models.

---

## 1. Modular Package Structure

AegisCore is decomposed into 6 specialized Java packages:

```
src/
├── server/
│   ├── Server.java          # Server entry point, TCP accept loop, graceful shutdown
│   ├── ClientHandler.java   # Dedicated client thread, socket I/O serializer
│   └── Client.java          # Diagnostic CLI client
├── player/
│   ├── Player.java          # Player session data, ID, name, status, current room
│   ├── PlayerStatus.java    # Player session state enum
│   └── PlayerRegistry.java  # Singleton registry managing all connected players
├── room/
│   ├── Room.java            # Room lobby state, slots, ready status, broadcast logic
│   ├── RoomState.java       # Room status state machine
│   └── RoomRegistry.java    # Singleton registry managing all active rooms
├── matchmaking/
│   ├── MatchConfig.java     # Match size configurations
│   └── MatchmakingQueue.java # Daemon thread matching players from queue
├── protocol/
│   ├── CommandType.java     # Protocol command specifications
│   ├── CommandContext.java  # Immutable execution context
│   └── CommandRouter.java   # Command parser and executor
└── core/
    ├── Logger.java          # Concurrently-safe logging system
    └── ServerStats.java     # Immutable telemetry snapshot
```

---

## 2. Component Interactions

The following ASCII diagram illustrates how core server components interact during a session:

```
                  ┌──────────────┐
                  │ Player Socket│
                  └──────┬───────┘
                         │ TCP Connection
                         ▼
                  ┌──────────────┐
                  │    Server    │
                  └──────┬───────┘
                         │ Spawns
                         ▼
                  ┌──────────────┐
                  │ ClientHandler│◄──────────────┐
                  └──────┬───────┘               │
                         │ Dispatch              │
                         ▼                       │
                  ┌──────────────┐               │ Broadcast
                  │ CommandRouter│               │
                  └──────┬───────┘               │
             ┌───────────┼───────────┐           │
             ▼           ▼           ▼           │
     ┌──────────────┐┌───────────┐┌──────────────┤
     │PlayerRegistry││RoomRegistry││     Room     │
     └──────────────┘└───────────┘└──────────────┘
```

---

## 3. Data Flow Scenario (Typical Session)

1. **Connection:** Client establishes a TCP connection to port 5000. `Server` accepts the connection and spawns a new thread executing `ClientHandler`.
2. **Identification:** The client sends `NAME Kirito`. `CommandRouter` updates the player's name and changes their state in the `PlayerRegistry` from `CONNECTED` to `IN_LOBBY`.
3. **Room Creation:** The client sends `CREATE Aincrad-Floor1 4`. `CommandRouter` invokes `RoomRegistry` to instantiate a new `Room` object, adds the player to the room, and changes their status to `IN_ROOM`.
4. **Room Invitation:** A second client connects, sets their name to `Asuna`, and sends `JOIN r-001`. They are added to the room, and their status updates to `IN_ROOM`.
5. **Readiness & Countdown:** Both players send `READY`. Once all slot spaces (or all joined players when full) confirm readiness, `Room` schedules a 5-second countdown timer.
6. **Game Start:** When the countdown reaches 0, the room state transitions to `IN_PROGRESS`, players transition to `IN_GAME`, and the game world session is initiated.

---

## 4. Threading Model

AegisCore uses a multi-threaded architecture optimized for CPU scheduling and low latency:

- **Client Threads:** Every connected client socket is assigned a dedicated thread running inside `ClientHandler`. This thread blocks on `BufferedReader.readLine()` waiting for client network packets.
- **Matchmaking Daemon Thread:** A dedicated daemon thread runs continuously within `MatchmakingQueue` to poll queued players, check compatibility constraints, and group them into rooms.
- **Countdown Scheduler:** A shared daemon `ScheduledExecutorService` handles asynchronous game start countdown triggers. This prevents client threads from blocking while waiting for timers.

---

## 5. Shared State Registry Singletons

To coordinate state across multiple independent client threads, AegisCore employs two global singleton registries:

- **PlayerRegistry:** Built on top of a `ConcurrentHashMap<String, Player>`. It tracks all active connections, mapping client session IDs to player objects. It uses lock-free reads and writes for thread-safe session validation.
- **RoomRegistry:** Built on top of a `ConcurrentHashMap<String, Room>`. It tracks active rooms, handles listings, cleans up empty rooms, and generates unique room identifiers (e.g. `r-001`).
