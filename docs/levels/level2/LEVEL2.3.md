# LEVEL 2.3 — Broadcast System

> **AegisCore Multithreaded Server**
> Transform independent sessions into an interconnected communication ecosystem.

---

## The Core Insight

Before Level 2.3, clients could connect and communicate with the server.
But they could not communicate with each other.
Each client session was a closed loop:

```
Client A  ←→  Server  (Client A's thread only)
Client B  ←→  Server  (Client B's thread only)
```

After Level 2.3, every client's message is delivered to every other client:

```
Client A sends "hello"
→ Server broadcasts
→ Client B receives "[A]: hello"
→ Client C receives "[A]: hello"
→ Client D receives "[A]: hello"
```

This is **message fanout** — one input, many outputs.
And it changes the entire concurrency picture of the server.

---

## What Level 2.3 Introduced

### 1. Message Fanout

Core concept:

```
1 incoming message
→ iterate ALL connected handlers
→ N outgoing writes
```

This is foundational to:

| System | How fanout appears |
|---|---|
| **Pub/Sub systems** | One publisher, many subscribers |
| **Apache Kafka** | One topic, many consumer groups |
| **Redis Pub/Sub** | One channel, many listeners |
| **Multiplayer game sync** | One player action, all other players updated |
| **Realtime collaboration** | One document edit, all other editors see it |
| **Market data feeds** | One price tick, thousands of trader terminals updated |

Broadcast is not a chat feature.
Broadcast is a fundamental distributed systems pattern.

---

### 2. The Broadcast Implementation

`BroadcastMessage()` in `SharedClientRegistry`:

```java
public void BroadcastMessage(String message, String senderId) {
    for (ClientHandler handler : connectedClients.values()) {
        if (!handler.getClientId().equals(senderId)) {
            handler.sendMessage(message);
        }
    }
}
```

Simple in structure.
Dangerous in concurrency.

Every step of this loop runs **inside the calling thread** — the thread of the client who sent the message.
That thread now writes to every other client's `OutputStream` while those clients' own threads may be doing the same.

---

### 3. Shared Communication Topology

Before Level 2.3:

```
Threads were independent.
Threads shared the registry — but only to track who existed.
```

After Level 2.3:

```
Threads WRITE THROUGH each other's handlers.
Thread A iterates the registry and calls sendMessage() on handler B, C, D.
Thread B simultaneously iterates and calls sendMessage() on handler A, C, D.
```

The communication graph looks like this:

```
         Handler A
        ↗    ↑    ↖
Handler D     |     Handler B
        ↖    ↓    ↗
         Handler C
```

Every handler is both a sender (through broadcast) and a receiver (through its own stream).
Every thread writes to every other thread's handler.

This changes concurrency completely.

---

### 4. Concurrent Iteration Pressure

With broadcast, the registry is under far more pressure:

```
Thread A  →  iterating connectedClients  (to broadcast A's message)
Thread B  →  iterating connectedClients  (to broadcast B's message)
Thread C  →  removeClient(C's id)        (C disconnected during broadcast)
Thread D  →  addClient(D's id, handler)  (new client arrived during broadcast)
```

All four operations run simultaneously.

`ConcurrentHashMap` prevents structural corruption.
Its weakly-consistent iterator prevents `ConcurrentModificationException`.

But it does **not** prevent:

- Thread A holding a reference to Handler C after C is removed from the map
- Thread A calling `sendMessage()` on a handler whose socket was already closed
- Thread A and Thread B both calling `sendMessage()` on Handler D's stream at the same time

These are the new failure modes that broadcast introduces.

---

### 5. Output Race Conditions

This is the most serious problem introduced by Level 2.3.

Two threads broadcasting simultaneously both call `sendMessage()` on Handler Z:

```
Thread A:  write "[Ali]: hello" → Handler Z's OutputStream
Thread B:  write "[Bob]: hey"   → Handler Z's OutputStream
                                   ↑ BOTH at exactly the same time
```

**What Client Z receives:**

```
[Ali[Bob]]: helyhello
```

Two messages fused into one corrupted line.

This is **concurrent output contention**.
The `OutputStream` is a shared resource.
Two threads writing to it simultaneously produce non-deterministic byte interleaving.

This bug is:

- **Silent** — no exception is thrown
- **Non-deterministic** — only appears under concurrency
- **Invisible** — local testing with one client never triggers it
- **Real** — appears in production under load

Level 2.4 introduces fine-grained per-client locking to solve this.

---

### 6. Failure Propagation

Before broadcast, client failures were isolated:

```
Client A crashes → Thread A terminates → no effect on anyone else
```

After broadcast, a client failure during a broadcast can affect the broadcast loop:

```
BroadcastMessage() iterating handlers
→ reaches Handler Z (Z just disconnected)
→ calls sendMessage() on Z
→ output.println() fails silently (PrintWriter swallows IOException)
→ OR: socket.close() was called mid-write → exception escapes
→ if unhandled: the entire broadcast loop terminates early
→ Clients after Z in the iteration never receive the message
```

This teaches **cascading failure awareness** — a failure in one output path can corrupt the entire delivery of a message.

The fix: per-client failure handling inside the broadcast loop.
Catch and handle failure at the individual client level.
The loop must continue regardless of what any single client does.

---

### 7. Self-Exclusion

A broadcasting client must not receive its own message:

```java
if (!handler.getClientId().equals(senderId)) {
    handler.sendMessage(message);
}
```

This is a correctness requirement, not a feature.
If a client receives its own message, it will echo it back.
That echo may trigger another broadcast.
The result: an infinite message amplification loop.

---

## What Level 2.3 Really Was

Not chat broadcasting.

It was:

> **Concurrent event fanout architecture.**

One event triggers synchronized writes to N independent output streams,
across N independent threads,
under concurrent additions and removals,
with cascading failure risk at every step.

This concept powers:

- messaging systems (Kafka, RabbitMQ, Redis)
- market data feeds (Bloomberg, trading platforms)
- realtime collaboration (Google Docs, Figma)
- distributed event systems (game servers, streaming platforms)

Understanding fanout — and what it means for concurrency, fault isolation, and throughput — is core backend infrastructure knowledge.

---

## The Problem Level 2.3 Left Unsolved

Level 2.3 implemented broadcast.
But it left three concrete concurrency bugs unresolved:

| Bug | Description |
|---|---|
| **Output stream race** | Two threads write to the same client's OutputStream simultaneously |
| **Registration ordering** | Handler registered before output stream is initialized → null write during broadcast |
| **Dead client writes** | Broadcast writes to a socket that was closed mid-iteration → stays in registry forever |

These three bugs are precisely what Level 2.4 identifies, analyzes, and hardens.

---

## Level 2.3 Core Concepts

| Concept | What it means here |
|---|---|
| **Message fanout** | One input event → writes to all N connected client streams |
| **Communication topology** | Every thread writes through every other thread's handler |
| **Concurrent iteration** | Multiple threads iterate the registry simultaneously — adds/removes mid-iteration |
| **Output race condition** | Two threads writing to the same OutputStream simultaneously → byte corruption |
| **Failure propagation** | One client's dead socket can abort the entire broadcast loop |
| **Cascade prevention** | Per-client exception handling inside the broadcast loop — loop continues regardless |
| **Self-exclusion** | Sender does not receive its own broadcast — prevents echo amplification |
| **Weakly-consistent iterator** | ConcurrentHashMap iteration never throws, but may return stale references |

---

## Invariants Established

```
1. Every message is delivered to all connected clients except the sender.
2. The broadcast loop never terminates early due to one client's failure.
3. A client never receives its own broadcasted message.
4. The broadcast loop iterates the registry with a weakly-consistent iterator — no CME.
5. Failures during sendMessage() are caught per-client and logged individually.
6. The registry structure is never corrupted by concurrent iteration and modification.
```
