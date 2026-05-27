# LEVEL 2.4 — Synchronization Hardening

> **AegisCore Multithreaded Server**
> Three concurrency problems solved: output stream races, registry consistency, and dead-client writes.

---

## The Core Insight

`ConcurrentHashMap` keeps the **structure** of the map consistent.
It says nothing about:

- whether the **objects inside the map** are ready to use
- whether those objects are **still alive** when you reach them
- whether **failure in one object** is isolated from all others

And none of these problems protect the **stream itself** from concurrent writes.
All three problems below live in these gaps.

---

## PROBLEM 1 — Output Stream Race Conditions

### The Scenario

Two clients send messages at the same time.
Both of their handler threads call `BroadcastMessage()`, which calls
`sendMessage()` on every other client — including Client Z.

```
Thread A (broadcasting for Ali)  →  write "[Ali]: hello" to Client Z's stream
Thread B (broadcasting for Bob)  →  write "[Bob]: hey"   to Client Z's stream
                                    ↑ BOTH at exactly the same time
```

**Result on Client Z's screen:**

```
[Ali[Bob]]: helyhello
```

Two messages fused into one corrupted line.
This is **concurrent output contention** — a classic, real-world systems bug.

---

### Why It Happened — Two PrintWriter Bugs

**Bug 1-A: `sendMessage()` created a new `PrintWriter` on every call.**

```java
// ClientHandler — BEFORE (Level 2.3 and earlier)
public synchronized void sendMessage(String msg) {
    PrintWriter out = new PrintWriter(socket.getOutputStream(), true); // BUG
    out.println(msg);
}
```

Every call created a *new* `PrintWriter` object wrapping the *same* underlying
`OutputStream`. Multiple threads each holding their own wrapper object over the
same byte sink — no coordination is possible.

**Bug 1-B: `run()` had its own separate local `PrintWriter`.**

```java
// run() — BEFORE
PrintWriter output = new PrintWriter(socket.getOutputStream(), true); // ALSO BUG
output.println("[INFO] Connected!");  // ← NOT guarded by any lock
```

`run()` wrote directly to the stream with no lock at all.
`sendMessage()` was `synchronized` but `run()` bypassed the gate entirely.

**The combined effect:**

```
                    ┌─────────────────────────────────┐
                    │  Client Z's OutputStream (OS)   │
                    └─────────────────────────────────┘
                              ↑             ↑
              PrintWriter_A ──┘             └── PrintWriter_B
        (Thread A, sendMessage)           (Thread B, sendMessage)
                              ↑
              PrintWriter_local
           (run() — no lock at all)
```

Three writers, one stream, zero coordination.
The OS writes bytes in whatever order threads are scheduled.
The output is non-deterministic corruption.

---

### What Is a Critical Section?

A **critical section** is any code region that reads or modifies **shared mutable state**.

In this case, the shared mutable state is the client's `OutputStream`.
The critical section is the single `output.println()` call.

**Only that line needs protection. Nothing else.**

This is the key engineering discipline: identify the *smallest* region that
requires a lock, and lock exactly that — no more, no less.

---

### Why Not Synchronize Everything?

A naive fix locks the entire broadcast system:

```java
// WRONG — over-synchronization
synchronized void BroadcastMessage(String msg) {
    for (ClientHandler h : connectedClients.values()) {
        h.sendMessage(msg);   // sendMessage() may block waiting for a slow client
    }
}
```

**What this actually means under 100 clients:**

```
 BroadcastMessage holds global lock
 ↓
 Calls sendMessage() on Client 1  →  slow client, network buffer full  →  BLOCKS
 ↓
 ALL other client threads trying to connect or disconnect are blocked too
 ↓
 NO new messages can be processed by ANYONE
 ↓
 Throughput collapses
```

The global lock serializes *every write to every client* behind *every other write*.
This is the definition of a throughput bottleneck.

---

### The Fix — Single Field + Fine-Grained Locking

**One `PrintWriter`, stored as a field. One synchronized gate. No exceptions.**

```java
// ClientHandler — AFTER
private PrintWriter output;  // ONE writer, shared by all threads for this client

public synchronized void sendMessage(String message) {
    if (!active) { return; }
    output.println(message);   // ← THE ONLY LINE THAT NEEDS THE LOCK
}
```

All writes — from the client's own thread *and* from every broadcast thread —
go through the same `synchronized` gate.

`run()` also sends through the gate:

```java
// run() — AFTER
output = new PrintWriter(socket.getOutputStream(), true);  // initialized once
sendMessage("[SERVER] Connected. Welcome!");               // uses the synchronized gate
// NO direct output.println() calls anywhere in run()
```

---

### Why This Is Fine-Grained, Not Over-Synchronized

The lock is on `this` — the individual `ClientHandler` instance.

```
Thread A writing to Client X  →  holds Client X's lock
Thread B writing to Client Y  →  holds Client Y's lock
                                 ↑ these are DIFFERENT locks
```

**They do not block each other.**
Writes to different clients happen in parallel.
Only writes to the *same* client are serialized — which is exactly the
correctness requirement.

| Approach | Locks held | Throughput | Correct? |
|---|---|---|---|
| No lock | none | maximum | ❌ — data corruption |
| Global lock on registry | one global | 1/N of maximum | ✅ — but collapses at scale |
| Per-client lock (`synchronized` on `this`) | one per client | ~maximum | ✅ — scales linearly |

Per-client locking is the **maximum safe throughput** achievable.
This is the engineering goal: *maximum safety with minimum locking.*

---

### Proof: No Two Threads Write Simultaneously

Java's `synchronized` keyword on a method acquires the **intrinsic monitor**
of the object (`this`) before entering.

```
Thread A calls sendMessage() on Handler_X:
  → tries to acquire Handler_X's monitor
  → succeeds (no other thread holds it)
  → enters method, writes, releases monitor

Thread B calls sendMessage() on Handler_X at the same time:
  → tries to acquire Handler_X's monitor
  → BLOCKED — Thread A holds it
  → waits until Thread A releases
  → enters, writes, releases
```

The JVM **guarantees** that only one thread is inside `sendMessage()` for
a given handler at any point in time. The byte stream is never touched
by two threads simultaneously. Corruption is impossible.

---

---

## PROBLEM 2 — Registry Consistency Under Load

### The Scenario

Three operations happen simultaneously:

```
broadcast thread  →  iterating connectedClients
disconnect thread →  removeClient(id)
accept thread     →  addClient(id, handler)
```

---

### Bug 2-A — Lifecycle Ordering

**What was wrong:**

```java
// Server.java — BEFORE
ClientHandler handler = new ClientHandler(socket, registry);
registry.addClient(handler.getClientId(), handler);  // ← published to map NOW
new Thread(handler).start();                         // ← output initialized LATER inside run()
```

The handler was **published before it could receive messages**.
`output` (the `PrintWriter`) is `null` until `run()` executes
`new PrintWriter(socket.getOutputStream(), ...)`.

**Timeline of the race:**

```
T=0  addClient()        → handler visible to all broadcast threads
T=1  BroadcastMessage() → finds handler → sendMessage() → output == null → silent drop
T=2  Thread.start()     → run() → output initialized
```

The new client was in the registry but deaf.
Every message broadcast in that window was silently lost.

**Root cause:** The handler was registered before it was *ready*.

**Fix:** `addClient()` moved inside `run()`, after `output` is initialized and
`active = true` is set under the handler's own lock.
`Server.java` no longer calls `addClient()` at all.

```java
// ClientHandler.run() — AFTER (five ordered steps)
output = new PrintWriter(socket.getOutputStream(), true); // Step 1: initialise
synchronized (this) { active = true; }                   // Step 2: memory barrier
registry.addClient(clientId, this);                      // Step 3: publish — NOW ready
sendMessage("[SERVER] Connected. Welcome!");              // Step 4: welcome
// ... read loop                                          // Step 5: serve
```

---

### Bug 2-B — Stale Reference + Check-Then-Act

**What was wrong:**

`BroadcastMessage()` uses `ConcurrentHashMap`'s weakly-consistent iterator.
A broadcast thread obtains a **reference to a handler** before it is removed
from the map.

```
Broadcast thread:   handler ref obtained from iterator        ← stale reference
Disconnect thread:  registry.removeClient(clientId)           ← removed from map
Disconnect thread:  closeSocket()                             ← socket.close() — NOT synchronized
Broadcast thread:   sendMessage():
                      check socket.isClosed() → false         ← RACE: checked BEFORE close
                      output.println(msg)                     ← writes to now-closed socket
                      PrintWriter swallows IOException silently
```

**The gap:** `sendMessage()` was `synchronized` but `closeSocket()` was **not**.
They did not share a mutual exclusion boundary.
The close could happen between the check and the write inside `sendMessage()`.

**Fix — two parts:**

1. `closeSocket()` is now `synchronized` on `this`. Since `sendMessage()` is
   also `synchronized` on `this`, the socket can only be closed *between*
   two sends — never in the middle of one.

2. A `volatile boolean active` field replaces the `socket.isClosed()` check.
   `active` is set to `false` **under the handler's own lock** at the start
   of `cleanup()`. `sendMessage()` reads it under the same lock — consistent
   by construction.

```java
// cleanup() — AFTER
synchronized (this) { active = false; }  // Step 1: deactivate atomically under lock
registry.removeClient(clientId);         // Step 2: remove from map (belt-and-suspenders)
closeSocket();                           // Step 3: release OS resources (now synchronized)
```

```java
// sendMessage() — AFTER
public synchronized void sendMessage(String message) {
    if (!active) { return; }    // atomic check under the lock — always consistent
    output.println(message);
    // ... dead-client detection (see Problem 3)
}
```

```java
// closeSocket() — AFTER
private synchronized void closeSocket() {   // ← synchronized ADDED
    if (socket == null || socket.isClosed()) return;
    socket.close();
}
```

**Guarantee table:**

| Scenario | Result |
|---|---|
| Broadcaster sees handler before `output` is set | `active = false` → `sendMessage()` returns immediately |
| Broadcaster sees handler after `removeClient()` | stale ref → `active = false` → `sendMessage()` no-op |
| `closeSocket()` races with `sendMessage()` | impossible — both `synchronized` on same monitor |

---

## PROBLEM 3 — Dead Client Writes

### The Scenario

```
BroadcastMessage() begins iterating
↓
Target client disconnects mid-send (network reset / RST packet)
↓
output.println() — PrintWriter swallows the IOException silently
output.checkError() — returns true
↓
OLD behaviour: logs "may be broken", does nothing else
↓
Handler stays in registry forever
Every future broadcast hits it → fails → logs noise — infinitely
```

---

### Why PrintWriter Is Silent

`PrintWriter` catches all `IOException`s internally.
It sets an internal error flag instead of throwing.
The only way to detect write failure is `output.checkError()` *after* the write.
This is the Java standard library contract. There is no way around it.

---

### Three Failure Modes

| Mode | Old behaviour | New behaviour |
|---|---|---|
| `checkError()` true after write | Log "may be broken", stay in registry | Force disconnect → self-evict |
| `active == false` at entry to `sendMessage()` | (old: null check) return | Return immediately |
| Unexpected `RuntimeException` escapes | Logged anonymously, loop continues | Logged with specific clientId, loop continues |

---

### The Self-Healing Mechanism

When `checkError()` returns `true` inside `sendMessage()`:

```java
// sendMessage() — dead-client detection (still inside synchronized block)
if (output.checkError()) {
    Logger.logClientHandlerError(
        "[DEAD CLIENT] Write failed for " + clientId + " — forcing disconnect."
    );
    forceDisconnect();  // called while holding the lock — reentrant, safe
}
```

`forceDisconnect()` — synchronized on `this`:

```java
synchronized void forceDisconnect() {
    if (!active) return;  // already in teardown — idempotent
    active = false;       // block all future sendMessage() calls immediately
    closeSocket();        // unblocks client thread's blocked readLine()
}
```

**What happens next on the client thread:**

```
readLine()  →  SocketException (connection reset / stream closed)
↓
catch (IOException e)  →  logged
↓
finally  →  cleanup()
↓
Step 1: synchronized { active = false }   (idempotent — already false)
Step 2: registry.removeClient(clientId)   ← handler leaves registry
Step 3: closeSocket()                     (idempotent — already closed)
```

The handler **evicts itself from the registry**. No external intervention.
No zombie entry. No recurring log noise.

---

### Cascade Prevention

```
Broadcast thread A:  handler.sendMessage(msg)
                       → output.println() fails
                       → checkError() = true
                       → forceDisconnect() → active=false, socket closed
                       → sendMessage() returns (does NOT throw)
                     loop continues to next handler →
Broadcast thread B:  (different client, concurrent)
                       completely unaffected — parallel write proceeds normally
```

**One dead client:**

- ✅ Does NOT throw an exception that aborts the loop
- ✅ Does NOT hold a lock that blocks other clients
- ✅ Does NOT corrupt the `ConcurrentHashMap` iterator
- ✅ Does NOT stay in the registry — self-evicts within one broadcast cycle
- ✅ Does NOT generate log noise on every subsequent broadcast

---

### BroadcastMessage — Delivery Accounting

Bytes are counted **only for confirmed deliveries**:

```java
handler.sendMessage(message);

if (handler.isActive()) {       // volatile read — no lock needed
    totalBytesSent.addAndGet(messageBytes);
    successful++;
} else {
    Logger.logRegistryError(
        "[BROADCAST] Dead client evicted mid-broadcast: " +
        handler.getClientId() + " — message not delivered."
    );
}
```

Partial-delivery summary logged when any client was missed:

```
[ERROR] [BROADCAST] Partial delivery: 4/5 clients received the message.
```

Operators get an actionable signal. No ambiguity about which client failed.
---

## PROBLEM 4 — Message Ordering & Active Failure Logging

### The Ordering Dilemma (Nondeterministic Concurrent Behavior)

Suppose:
- Client A sends messages rapidly in sequence: `[A1, A2, A3]`
- Client B sends messages rapidly in sequence: `[B1, B2, B3]`

Under high concurrency, these messages arrive at the server simultaneously across different worker threads. Without structural guardrails, the order in which they are processed, broadcast, and written to the output streams of other clients is entirely non-deterministic. They may arrive at receivers:
- **Interleaved:** `[A1, B1, A2, B2, A3, B3]`
- **Reordered:** `[A2, A1, B3, B1, A3, B2]`
- **Unpredictably timed:** Long pauses followed by rapid bursts due to thread scheduling, TCP congestion control, or lock acquisition delays.

In a multithreaded chat system, we establish **per-client sequence correctness** (messages from Client A must always be received in the order they were sent: `A1 -> A2 -> A3`), but global cross-client ordering remains naturally interleaved depending on when each thread acquires the registry or client handler locks. 

This non-determinism is the core reason concurrent systems are notoriously difficult to design and debug. Bugs are no longer static syntax errors; they become transient, timing-dependent, and highly random anomalies that disappear under local step-through debuggers (often referred to as "Heisenbugs").

---

### Active Failure Logging — The Debugging Imperative

Because concurrent bugs are timing-dependent and notoriously hard to reproduce, **active failure logging** is not a luxury — it is a production necessity. Without detailed logs, diagnosing state corruption or memory leaks becomes absolute nightmare fuel. 

AegisCore implements rigorous active logging across four operational boundaries to guarantee 100% diagnostic visibility:

#### 1. Failed Writes Detection
Because Java's `PrintWriter` swallows `IOException`s internally, AegisCore actively inspects `output.checkError()` after every write. The moment a write failure is detected, it is logged with high-severity context:
```text
[ERROR] [DEAD CLIENT] Write failed for /127.0.0.1:56805 — socket broken. Forcing disconnect.
```

#### 2. Disconnect Causes
We differentiate between expected and unexpected terminations. Clean disconnects (the client typed `exit` or closed stdin gracefully) are logged distinctively from abrupt network failures (connection reset / broken pipe):
* **Graceful Disconnect:**
  ```text
  [INFO] Client /127.0.0.1:56808 disconnected cleanly.
  [INFO] Client disconnected: /127.0.0.1:56808
  ```
* **Abrupt Socket/I/O Failure:**
  ```text
  [ERROR] I/O error for client /127.0.0.1:56804: An established connection was aborted by the software in your host machine
  ```

#### 3. Broadcast Errors
To prevent a single dead connection from crashing a global broadcast loop, the `SharedClientRegistry.BroadcastMessage()` actively catches exceptions per client handler, logs the exact culprit, and continues processing other healthy clients. If delivery was incomplete, a precise partial-delivery warning is logged:
```text
[ERROR] [BROADCAST] Dead client evicted mid-broadcast: /127.0.0.1:56805 — message not delivered.
[ERROR] [BROADCAST] Partial delivery: 4/5 clients received the message.
```

#### 4. Thread Failures and Lifecycle Events
Accept-loop failures, thread crashes, and server-wide state changes are tracked actively from `Server.java`, `ClientHandler.java`, and `Client.java`. Standard thread exceptions (such as `IOException`) and unexpected JVM-level crashes (such as `RuntimeException` or `OutOfMemoryError`) are intercepted by specialized catch-all `Throwable` blocks on all background worker threads. This guarantees that thread crashes are written cleanly to safe file-based logs instead of silently vaporizing.

Thread names are also formatted predictably (e.g., `ClientHandler-56804`) to make thread dumps and stack traces immediately readable in log files:
```text
[INFO] Server is listening on port 5000
[INFO] New client connected: 127.0.0.1
[ERROR] UNEXPECTED THREAD CRASH for client /127.0.0.1:56804: java.lang.NullPointerException
```

---

## Files Changed

### Problem 1 — Output Stream Race

| File | What changed |
|---|---|
| `ClientHandler.java` | `output` promoted from local variable to single shared field |
| `ClientHandler.java` | `sendMessage()` made `synchronized` on `this` — the per-client write gate |
| `ClientHandler.java` | `run()` uses `sendMessage()` for all writes — no direct `output.println()` anywhere |

### Problems 2 & 3 — Registry Consistency + Dead Client Writes

| File | What changed |
|---|---|
| `ClientHandler.java` | Added `volatile boolean active = false` field |
| `ClientHandler.java` | Added `isActive()` — plain volatile read, no lock |
| `ClientHandler.java` | `sendMessage()`: guard uses `!active`; dead-client triggers `forceDisconnect()` |
| `ClientHandler.java` | Added `forceDisconnect()` — synchronized, sets `active=false`, closes socket |
| `ClientHandler.java` | `closeSocket()` is now `synchronized` |
| `ClientHandler.java` | `cleanup()`: step 1 = `synchronized { active = false }` before `removeClient` |
| `ClientHandler.java` | `run()`: `addClient()` moved here — after `output` init, after `active = true` |
| `Server.java` | Removed `registry.addClient()` — handler registers itself inside `run()` |
| `SharedClientRegistry.java` | `BroadcastMessage()`: per-client failure logging with clientId |
| `SharedClientRegistry.java` | `BroadcastMessage()`: bytes counted only on confirmed send |
| `SharedClientRegistry.java` | `BroadcastMessage()`: partial-delivery summary log |
| `SharedClientRegistry.java` | Javadoc updated to reflect `active` flag replacing `socket.isClosed()` |

---

## Level 2.4 Core Concepts

| Concept | What it means here |
|---|---|
| **Critical section** | The `output.println()` call — the only line touching shared mutable state |
| **Fine-grained locking** | Lock per-client (`this`), not the whole registry — parallel writes to different clients |
| **Lock contention** | What happens when two threads want the same lock — only one proceeds, one waits |
| **Atomicity** | `output.println()` + `checkError()` happen as one unit under the lock |
| **Memory visibility** | `synchronized` provides a happens-before edge — all threads see consistent `output` |
| **Synchronization granularity** | Coarse = one lock for everything (throughput collapse). Fine = one lock per resource (scales) |
| **Concurrent correctness** | No data corruption, no zombie handlers, no silent drops — under any interleaving |
| **Volatile** | `active` flag: ensures cross-thread visibility of the boolean without acquiring a lock |
| **Weakly-consistent iterator** | `ConcurrentHashMap` never throws `ConcurrentModificationException` — stale refs are possible |
| **Self-eviction** | Dead client closes its own socket → `readLine()` throws → `finally` → `removeClient()` |

---

## Invariants Established

```
1. A handler is only in the registry when active=true AND output!=null.
2. active=false is always set under the handler's own lock.
3. sendMessage() and closeSocket() share the same lock — no partial state visible.
4. Only ONE thread writes to a client's OutputStream at any point in time.
5. A dead client self-evicts within one broadcast cycle — never stays in the map.
6. One client's failure cannot prevent any other client from receiving a message.
7. Byte telemetry reflects only confirmed deliveries, not attempted ones.
```