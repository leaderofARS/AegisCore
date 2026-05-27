# LEVEL 2.2 — Shared State & Client Registry

> **AegisCore Multithreaded Server**
> Transform isolated client sessions into a globally coordinated server with shared state.

---

## The Core Insight

Level 2.1 gave every client its own thread.
But threads had no knowledge of each other.

Level 2.2 introduces a **shared object** that every thread can reach:

```
Thread A (Client A) ──┐
Thread B (Client B) ──┼──→ SharedClientRegistry  ←── shared mutable memory
Thread C (Client C) ──┘
```

The moment you add this, threads are no longer isolated.
They all touch the same data structure.
And the moment multiple threads touch the same data simultaneously, you enter **concurrency danger territory**.

This is the central engineering challenge of Level 2.2.

---

## What Level 2.2 Introduced

### 1. Global Client Registry

The server now tracks:

```
SharedClientRegistry {
    connectedClients: Map<clientId → ClientHandler>
}
```

Every thread that handles a client:

- **adds** itself to the registry when the client connects
- **removes** itself from the registry when the client disconnects

The registry gives the server global awareness.
For the first time, one thread can find another thread's handler and interact with it.

---

### 2. Shared Mutable Memory

This is where concurrency became dangerous.

Multiple threads accessing the same `Map` simultaneously:

```
Thread A  →  addClient(idA, handlerA)     ← modifying the map
Thread B  →  addClient(idB, handlerB)     ← modifying the map at the same time
Thread C  →  iterating for broadcast      ← reading the map while it's being modified
```

**What can go wrong with a plain `HashMap`:**

- Two threads insert simultaneously → internal array corrupts → infinite loop
- Thread iterating the map while another modifies it → `ConcurrentModificationException`
- Thread reads partially-written entry → sees null fields → `NullPointerException`

These are **race conditions**.
They are non-deterministic. They may not appear in testing.
They appear in production, under load, unpredictably.

---

### 3. Concurrent Collections

You learned:

> **Ordinary collections are unsafe under concurrency.**

A `HashMap` makes no guarantees about what happens when two threads modify it simultaneously. It is designed for single-threaded use only.

The fix: **`ConcurrentHashMap`**.

```java
private final ConcurrentHashMap<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
```

`ConcurrentHashMap` provides:

| Guarantee | Meaning |
|---|---|
| **Thread-safe reads** | Multiple threads can read simultaneously — no corruption |
| **Thread-safe structural modifications** | `put()` and `remove()` are safe under concurrent access |
| **Weakly-consistent iteration** | Iterating never throws `ConcurrentModificationException` |
| **No null values** | Prevents a class of null-related race conditions |

This is a massive realization for backend engineering.
The collection type is not a stylistic choice. It is a correctness choice.
The wrong collection type is a latent race condition waiting to appear under load.

---

### 4. Lifecycle Management

Before Level 2.2, clients had two states: connected or not.

After Level 2.2, clients have a managed lifecycle:

```
ACCEPT SOCKET
      ↓
CREATE HANDLER
      ↓
INITIALIZE (socket, streams)
      ↓
REGISTER  →  addClient(id, handler)   ← enters shared global state
      ↓
ACTIVE    →  read/write loop
      ↓
DISCONNECT (client closes connection, I/O error, or explicit exit)
      ↓
UNREGISTER  →  removeClient(id)       ← leaves shared global state
      ↓
CLEANUP  →  close socket, release resources
```

Every transition in this lifecycle must leave the shared state **consistent**.
No half-registered handlers. No handlers that stay registered after disconnect.
No handlers that are registered before they are ready to receive messages.

This is **state consistency management** — one of the hardest areas in software engineering.

---

### 5. The Zombie Session Problem

A zombie session is a handler that:

- is no longer serving a live client
- is still present in the registry

This happens when:

```
client disconnects
→ thread catches exception
→ cleanup code does NOT call removeClient()
→ handler stays in registry forever
```

Consequences:

| Symptom | Root cause |
|---|---|
| Stale references accumulate | Map grows without bound |
| Broadcast sends to dead sockets | Wasted work, eventual errors |
| Memory leaks | Handler objects and sockets never garbage collected |
| Incorrect client counts | Registry shows more clients than are actually connected |

The fix: **guaranteed cleanup**.

```java
try {
    // client serving loop
} catch (IOException e) {
    // log the error
} finally {
    registry.removeClient(clientId);  // always executed — even if exception was thrown
    closeSocket();
}
```

The `finally` block runs regardless of how the thread exits.
This guarantees that a thread **always** cleans up after itself.
Zombie sessions become structurally impossible.

---

## What Level 2.2 Really Was

Not:

> "store clients in a list"

It was:

> **Maintaining consistent shared state under concurrent lifecycle changes.**

Every add, every remove, every iterate — happening simultaneously across multiple threads.
Every lifecycle transition — connect, disconnect, crash — must leave the registry in a valid state.

This is one of the hardest areas in software engineering.
Production systems fail here all the time.

- memory leaks from zombie sessions
- race conditions in concurrent maps
- crashes from null handlers seen mid-iteration
- stale references that send data to dead sockets

Level 2.2 is where you first encountered all of these.

---

## The Problem Level 2.2 Left Unsolved

Level 2.2 made the server globally aware.
Threads could now find each other through the registry.

But finding a client's handler is not the same as safely communicating through it.

If Thread A (broadcasting for Client A) calls `sendMessage()` on Client Z's handler,
and Thread B (broadcasting for Client B) does the same thing at the same time —
both threads are writing to Client Z's `OutputStream` simultaneously.

The output stream has no lock. Two writes at the same time produce:

```
[Ali[Bob]]: helyhello
```

Corrupted output. Silent. Non-deterministic.

This is the output race condition that Level 2.3 and Level 2.4 tackle.

---

## Level 2.2 Core Concepts

| Concept | What it means here |
|---|---|
| **Shared mutable state** | A data structure multiple threads can read and write simultaneously |
| **Race condition** | Two threads access shared state in a timing-dependent order that produces corruption |
| **ConcurrentHashMap** | Thread-safe map — structural modifications are safe, iteration never throws |
| **Weakly-consistent iterator** | Iteration sees a snapshot — concurrent adds/removes may or may not be visible |
| **Lifecycle management** | Ordered transitions: initialize → register → active → unregister → cleanup |
| **Zombie session** | A dead handler that was never removed from the registry |
| **Guaranteed cleanup** | `finally` block ensures `removeClient()` is always called — zombie prevention |
| **State consistency** | The registry always reflects reality — no phantom entries, no missing entries |

---

## Invariants Established

```
1. A handler is present in the registry if and only if its client session is active.
2. removeClient() is always called in the finally block — guaranteed execution.
3. The registry map is a ConcurrentHashMap — concurrent structural modifications are safe.
4. No two threads can corrupt the registry structure by simultaneously inserting or removing.
5. Iteration over the registry never throws ConcurrentModificationException.
6. A dead session never remains in the registry beyond the lifetime of its thread.
```
