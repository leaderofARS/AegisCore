# LEVEL 2.1 — Basic Multithreading

> **AegisCore Multithreaded Server**
> Transform a single-client sequential server into a multi-client concurrent server.

---

## The Core Insight

Before Level 2.1, the server handled one client at a time.
If Client A connected, Client B had to wait until Client A disconnected.

```
accept() → handle client A → done → accept() → handle client B → done
```

This is sequential execution. The server is a single-lane road.

After Level 2.1, the server spawns a new thread for every client:

```
accept() → spawn thread for A → accept() → spawn thread for B → accept() → ...
              ↓                               ↓
         handle A forever              handle B forever
         (independent)                 (independent)
```

The server becomes a multi-lane highway. Each lane is independent.

---

## What Level 2.1 Taught

### 1. Thread-Per-Client Architecture

Core model:

```
1 client connection
→ 1 dedicated thread
→ 1 independent execution environment
```

This is one of the oldest and most important server models ever built.
Used historically in:

- web servers (Apache prefork model)
- game servers
- database connection handlers
- telnet / SSH servers

The model is simple to reason about. Each client is fully isolated.
What happens in Client A's thread cannot directly affect Client B's thread.

---

### 2. Thread Lifecycle

Every thread in Java has a complete lifecycle:

```
NEW → RUNNABLE → BLOCKED → WAITING → TERMINATED
```

In Level 2.1, you learned:

| Phase | What it means |
|---|---|
| **Creation** | `new Thread(handler)` — thread object exists, not yet running |
| **Start** | `.start()` — JVM schedules the thread for execution |
| **Running** | `run()` executes — serving the client |
| **Blocking** | Waiting for client input — thread is paused |
| **Termination** | Client disconnects — `run()` returns — thread ends |

This introduced **execution management** — the understanding that code does not execute in a straight line. Multiple lines of code run simultaneously in different threads.

---

### 3. Blocking I/O

Critical concept.

When a thread calls:

```java
String line = bufferedReader.readLine();
```

It **blocks**. The thread pauses and waits for the client to send data.

```
Client thread state:
  RUNNABLE → (client sends nothing) → BLOCKED → (client sends data) → RUNNABLE
```

This means:

- Threads spend most of their life **blocked on network input**
- A blocked thread consumes no CPU — it is parked by the OS
- The JVM can run other threads while this one waits

This is foundational networking knowledge.
The thread-per-client model works precisely because blocking I/O is cheap when each client owns a dedicated thread.

---

### 4. Client Session Isolation

Each client's thread owns:

```
socket          → the network connection
BufferedReader  → reading input from the client
PrintWriter     → writing output to the client
run() loop      → the client's entire conversation
```

The thread's `run()` loop is the client's entire world:

```java
while ((line = reader.readLine()) != null) {
    process(line);
}
```

When the client disconnects, `readLine()` returns `null`. The loop ends. The thread terminates.

This is **session architecture** — each client is a self-contained execution environment.

---

### 5. Fault Isolation

Because each client lives in its own thread, failures are contained:

```
Client A crashes (IOException, NullPointerException, etc.)
→ Client A's thread terminates
→ Server's accept() loop is unaffected
→ Client B's thread is unaffected
→ All other clients continue without interruption
```

This teaches a fundamental infrastructure principle:

> **Localized failure containment.**

A failure in one execution unit must not propagate to other execution units.
Level 2.1 achieves this by structural separation — threads cannot crash each other directly.

---

## What Level 2.1 Really Was

Not threading syntax.
Not `new Thread(runnable).start()`.

It was:

> **Understanding concurrent execution topology.**

The moment you spawn two threads, you have two simultaneous worlds.
Each world proceeds at its own pace, with no knowledge of the other.

This is the conceptual foundation that everything in Level 2.2, 2.3, and 2.4 builds on.
Without truly understanding what it means for two threads to run simultaneously and independently, none of the later problems make sense.

---

## The Problem Level 2.1 Left Unsolved

Level 2.1 made clients independent.
But it made them **too** independent.

The server had no way to know:

- how many clients were connected
- who was connected
- how to reach one client from another client's thread

Each client existed in complete isolation.
No global awareness. No shared state. No communication between sessions.

This is the problem Level 2.2 was built to solve.

---

## Level 2.1 Core Concepts

| Concept | What it means here |
|---|---|
| **Thread-per-client** | One dedicated OS thread per connected session |
| **Thread lifecycle** | Creation → start → block → terminate |
| **Blocking I/O** | Thread pauses while waiting for network input — consumes no CPU |
| **Session isolation** | Each client owns its socket, streams, and execution loop |
| **Fault isolation** | One client crashing cannot crash another client's thread |
| **Concurrent execution** | Two threads running simultaneously — truly in parallel |
| **Runnable interface** | `ClientHandler implements Runnable` — the handler IS the thread's work unit |

---

## Invariants Established

```
1. Every accepted connection spawns exactly one dedicated thread.
2. Each thread is fully responsible for its own client — socket, streams, loop, cleanup.
3. A thread terminates if and only if its client disconnects or an unrecoverable error occurs.
4. No thread can block any other thread — all I/O is isolated per session.
5. The accept() loop in Server.java is never blocked waiting for a client to finish.
```
