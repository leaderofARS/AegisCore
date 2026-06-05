# AegisCore Concurrency & Threading Model

This document outlines the multithreaded architecture, synchronization primitives, shared state mechanics, and lock-safety rules applied in **AegisCore**.

---

## 1. Threading Architecture

AegisCore implements three concurrent execution patterns:
1. **Thread-Per-Client:** The main TCP `Server` accept loop spawns a dedicated OS thread executing `ClientHandler` for every accepted client socket connection.
2. **Daemon Matchmaking Thread:** A single, dedicated background thread executes matchmaking polling loops within `MatchmakingQueue`.
3. **Countdown Scheduler Thread Pool:** A shared daemon `ScheduledExecutorService` schedules and triggers countdown tasks when rooms enter ready-check sequences.

---

## 2. Shared State Inventory

AegisCore coordinates thread interactions via standard thread-safe structures in the Java Standard Library:

- **PlayerRegistry:** Uses `ConcurrentHashMap<String, Player>` to map client identifiers to session objects.
- **RoomRegistry:** Uses `ConcurrentHashMap<String, Room>` to map room IDs to active room entities.
- **Room Players List:** Uses a `CopyOnWriteArrayList<Player>` within `Room` to allow lock-free iteration and broadcasts while players join or leave rooms.
- **Room Ready States:** Uses a `ConcurrentHashMap.newKeySet()` inside `Room` to track player ready signals with lock-free updates.
- **Matchmaking Queue:** Uses a `LinkedBlockingQueue<Player>` to synchronize producer client threads pushing players into the queue and the consumer daemon matchmaking thread polling players.

---

## 3. Synchronization & Thread-Safety Strategies

### 3.1 Socket Write Serialization
When multiple threads broadcast updates (e.g., room chat + ready check countdown timer + system messages), their writes on a client socket's output stream could interleave, resulting in garbled text. AegisCore prevents this by synchronizing writes:
```java
// ClientHandler.java
public synchronized void sendMessage(String message) {
    if (output != null) {
        output.println(message);
        output.flush();
    }
}
```
This locks writes on the local `ClientHandler` instance, allowing parallel broadcasts to execute concurrently across different sockets.

### 3.2 Atomic Telemetry Metrics
Instead of global synchronization bottlenecks, high-frequency metrics are updated using CAS (Compare-And-Swap) lock-free atomic counters in the player registry:
- `AtomicLong totalConnectionsAccepted`
- `AtomicLong totalMessagesRelayed`
- `AtomicLong totalBytesSent`

### 3.3 Concurrency Control in Rooms
Ready status checking and countdown triggers are synchronized on the `Room` instance lock to prevent race conditions (e.g., a player going unready at the exact millisecond another player goes ready):
```java
public synchronized void setReady(Player player) { ... }
public synchronized void setUnready(Player player) { ... }
```
This guarantees that the room's countdown scheduler is modified atomically.

### 3.4 Memory Visibility
To ensure thread cache visibility:
- **Player Fields:** Key fields on the `Player` class (e.g. status, room ID) are marked `volatile` to guarantee immediate cross-thread visibility of state changes.
- **Eviction Flag:** `ClientHandler` utilizes an `AtomicBoolean evictionLogged` to prevent duplicate logging of client eviction events.

---

## 4. Deadlock Prevention Rules

To prevent deadlock scenarios (thread A holding Lock X and waiting for Lock Y, while thread B holds Lock Y and waits for Lock X), AegisCore enforces a strict locking hierarchy:

- **Lock Hierarchy:** Registry lookups never acquire locks. Intrinsic locks are only acquired on specific, localized scopes (e.g., `Room` instances or `ClientHandler` instances).
- **No Nested Locking:** A thread holding a lock on a `Room` never attempts to acquire a lock on a `ClientHandler` instance, and vice-versa.
- **Weakly-Consistent Iteration:** Collection iterations (e.g., listing rooms, broadcasting to players) are executed over concurrent collections that return weakly-consistent iterators. This eliminates the need to acquire locks on the registry collections during broadcasts.
