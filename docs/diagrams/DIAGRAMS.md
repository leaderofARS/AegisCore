# AegisCore System Diagrams

This document contains visual representations of the **AegisCore** Game Lobby Server system architecture, lifecycle states, sequences, and data flows using Mermaid.

---

## 1. System Architecture

Shows the 6 packages of AegisCore and their relationship mappings:

```mermaid
graph TD
    subgraph server
        Server --> ClientHandler
        ClientHandler --> Client
    end

    subgraph protocol
        CommandRouter --> CommandType
        CommandRouter --> CommandContext
    end

    subgraph player
        PlayerRegistry --> Player
        Player --> PlayerStatus
    end

    subgraph room
        RoomRegistry --> Room
        Room --> RoomState
    end

    subgraph matchmaking
        MatchmakingQueue --> MatchConfig
    end

    subgraph core
        Logger
        ServerStats
    end

    ClientHandler --> CommandRouter
    CommandRouter --> PlayerRegistry
    CommandRouter --> RoomRegistry
    CommandRouter --> MatchmakingQueue
    Room --> ClientHandler
```

---

## 2. Thread Lifecycle

Lifecycle transitions of a `ClientHandler` thread handling a single connection:

```mermaid
stateDiagram-v2
    [*] --> NEW: Socket Accepted
    NEW --> RUNNABLE: thread.start()
    RUNNABLE --> BLOCKED: socket.readLine() (Waiting for command)
    BLOCKED --> RUNNABLE: Data Received
    RUNNABLE --> BLOCKED: Dispatch complete
    RUNNABLE --> TERMINATED: quit command / IOException / Disconnect
    TERMINATED --> [*]: socket.close() & PlayerRegistry.removePlayer()
```

---

## 3. Connection & Game Startup Sequence

A complete flow showing two clients connecting, naming, creating/joining a room, going ready, and starting a game session:

```mermaid
sequenceDiagram
    autonumber
    actor ClientA as Player A (Kirito)
    actor ClientB as Player B (Asuna)
    participant S as Server
    participant CH_A as ClientHandler A
    participant CH_B as ClientHandler B
    participant R as Room (r-001)

    ClientA->>S: TCP Connect
    S-->>CH_A: Spawn ClientHandler A Thread
    ClientA->>CH_A: NAME Kirito
    CH_A->>ClientA: Welcome message [IN_LOBBY]

    ClientA->>CH_A: CREATE Aincrad 2
    CH_A->>R: Instantiate Room r-001
    R-->>CH_A: Room created
    CH_A->>ClientA: Room ID: r-001 [IN_ROOM]

    ClientB->>S: TCP Connect
    S-->>CH_B: Spawn ClientHandler B Thread
    ClientB->>CH_B: NAME Asuna
    CH_B->>ClientB: Welcome message [IN_LOBBY]

    ClientB->>CH_B: JOIN r-001
    CH_B->>R: Add Player B to Room r-001
    R-->>CH_A: [ROOM] Asuna joined the room
    R-->>CH_B: [SERVER] Joined room r-001

    ClientA->>CH_A: READY
    CH_A->>R: setReady(Player A)
    R-->>CH_A: [READY] Kirito is ready (1/2)
    R-->>CH_B: [READY] Kirito is ready (1/2)

    ClientB->>CH_B: READY
    CH_B->>R: setReady(Player B)
    R-->>CH_A: [READY] Asuna is ready (2/2)
    R-->>CH_B: [READY] Asuna is ready (2/2)
    Note over R: All ready. Trigger 5-sec countdown.
    R-->>CH_A: [READY] Starting in 5...
    R-->>CH_B: [READY] Starting in 5...
    Note over R: Countdown finishes (1... 0)
    R-->>CH_A: [INFO] ⚔ Game session started!
    R-->>CH_B: [INFO] ⚔ Game session started!
```

---

## 4. Class Diagram

Class relationships, fields, and core methods:

```mermaid
classDiagram
    class Server {
        +ServerSocket serverSocket
        +start()
        +shutdown()
    }
    class ClientHandler {
        +Socket socket
        +BufferedReader input
        +PrintWriter output
        +run()
        +sendMessage(String msg)
        +forceDisconnect()
    }
    class Player {
        +String id
        +String name
        +PlayerStatus status
        +String roomId
    }
    class PlayerRegistry {
        -ConcurrentHashMap connectedPlayers
        +addPlayer(Player p)
        +removePlayer(String id)
        +getPlayerByName(String name)
    }
    class Room {
        +String id
        +String name
        +int maxSlots
        +CopyOnWriteArrayList players
        +RoomState state
        +addPlayer(Player p)
        +removePlayer(Player p)
        +setReady(Player p)
        +setUnready(Player p)
    }
    class RoomRegistry {
        -ConcurrentHashMap rooms
        +createRoom(String name, int slots)
        +getRoom(String id)
        +removeRoom(String id)
    }
    class CommandRouter {
        +dispatch(CommandContext ctx)
    }
    class MatchmakingQueue {
        -LinkedBlockingQueue queue
        +enqueue(Player p)
        +dequeue(Player p)
        +run()
    }

    Server --> ClientHandler: spawns
    ClientHandler --> Player: represents
    PlayerRegistry --> Player: manages
    RoomRegistry --> Room: manages
    Room --> Player: contains
    ClientHandler --> CommandRouter: routes commands
    CommandRouter --> PlayerRegistry: queries
    CommandRouter --> RoomRegistry: queries
    CommandRouter --> MatchmakingQueue: queue operations
```

---

## 5. Room State Machine

Lobby room state changes:

```mermaid
stateDiagram-v2
    [*] --> WAITING: Room Created
    WAITING --> WAITING: Player Joins / Leaves
    WAITING --> READY_CHECK: All slots filled & marked READY
    READY_CHECK --> WAITING: Player leaves / UNREADY during countdown
    READY_CHECK --> IN_PROGRESS: Countdown reaches 0
    IN_PROGRESS --> CLOSED: Game finished / All players quit
    CLOSED --> [*]: Room deleted
```

---

## 6. Player State Machine

Player status transitions:

```mermaid
stateDiagram-v2
    [*] --> CONNECTED: TCP Socket Open
    CONNECTED --> IN_LOBBY: NAME command set
    IN_LOBBY --> IN_ROOM: CREATE / JOIN command
    IN_LOBBY --> QUEUED: QUEUE command
    QUEUED --> IN_LOBBY: DEQUEUE command
    QUEUED --> IN_ROOM: Matchmaking pairs player
    IN_ROOM --> IN_LOBBY: LEAVE command / Room destroyed
    IN_ROOM --> IN_GAME: Room countdown completes
    IN_GAME --> IN_LOBBY: Game session ends / returns to lobby
    IN_LOBBY --> [*]: QUIT command / connection drop
```

---

## 7. Data Flow Broadcast Routing

How chat messages propagate from one client to all other occupants in the room:

```mermaid
flowchart LR
    ClientA[Client A] -- "CHAT Hello!" --> CH_A[ClientHandler A]
    CH_A --> CR[CommandRouter]
    CR --> R[Room]
    R --> Broadcast{Iterate over Room occupants}
    Broadcast -- "Send" --> CH_B[ClientHandler B]
    Broadcast -- "Send" --> CH_A[ClientHandler A]
    CH_B --> ClientB[Client B]
```
