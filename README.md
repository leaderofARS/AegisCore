# AegisCore - Concurrent Real-Time Communication Infrastructure

> A scalable multithreaded concurrent TCP server built in Java — engineered as the foundation for banking infrastructure, trading systems, distributed compute engines, multiplayer game servers, IoT backends, and cybersecurity systems.

---

## Overview

This project is **not a chat application.** It is a progressive, systematically engineered **server platform** built from raw TCP sockets upward toward distributed, fault-tolerant, cloud-native infrastructure.

The system is being built across 17 engineering levels — from raw socket I/O to AI-integrated distributed infrastructure. At each level, the architecture is extended without breaking prior contracts. Every decision is documented, every trade-off is recorded, and every concurrency hazard is explicitly acknowledged.

---

## Current Development Stage

**Level 2.4 — Thread Safety & Synchronization Hardening** *(active)*

```
Level 0   ✅  Java Core Foundation
Level 1   ✅  Raw Socket Networking
Level 2.1 ✅  Basic Multithreading
Level 2.2 ✅  Shared Client Registry
Level 2.3 ✅  Broadcast Infrastructure
Level 2.4 🔥  Synchronization Hardening ← YOU ARE HERE
Level 2.5     Execution Architecture (Custom Thread Pools)
Level 3       Command & Protocol Architecture
Level 4       Server Architecture
...
Level 17      AI + Analytics Layer
```

See [ROADMAP.md](./ROADMAP.md) for the complete engineering progression.

---

## Features (Current Implementation)

| Feature | Status |
|---------|--------|
| TCP server socket | ✅ Implemented |
| Multi-client connection handling | ✅ Implemented |
| Per-client thread spawning | ✅ Implemented |
| Bidirectional message exchange | ✅ Implemented |
| Clean client disconnect (`exit`) | ✅ Implemented |
| Blocking I/O model | ✅ Implemented |
| Thread-safe client registry | ✅ Implemented (Level 2.3) |
| Global broadcast messaging | ✅ Implemented (Level 2.3) |
| Command engine (`/login`, `/msg`) | ⏳ Level 5 |
| Thread pool (ExecutorService) | ⏳ Level 6 |
| Database persistence | ⏳ Level 7 |
| Authentication & session tokens | ⏳ Level 8 |

---

## Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────┐
│                   CLIENT SIDE                   │
│  Client.java                                    │
│  └─ Socket(localhost, 5000)                     │
│     ├─ PrintWriter (send)                       │
│     └─ BufferedReader (receive)                 │
└───────────────────┬─────────────────────────────┘
                    │ TCP Connection (Port 5000)
┌───────────────────▼─────────────────────────────┐
│                   SERVER SIDE                   │
│  Server.java                                    │
│  └─ ServerSocket(5000) → accept() loop          │
│     └─ for each client:                         │
│        └─ new Thread(ClientHandler)             │
│           └─ ClientHandler.java                 │
│              ├─ readLine() → process message    │
│              └─ println()  → send response      │
└─────────────────────────────────────────────────┘
```

### Threading Model (Current — Level 2)

```
Main Thread
│
└─ ServerSocket.accept()   [BLOCKS until client arrives]
   │
   ├─ Client 1 → Thread-1 → ClientHandler [independent lifecycle]
   ├─ Client 2 → Thread-2 → ClientHandler [independent lifecycle]
   ├─ Client 3 → Thread-3 → ClientHandler [independent lifecycle]
   └─ Client N → Thread-N → ClientHandler [independent lifecycle]
```

**Known architectural risk:** This is a one-thread-per-client model. Under heavy load (1000+ clients), this creates 1000+ OS threads, each consuming approximately 1MB of stack space. This will be replaced with an `ExecutorService` thread pool at Level 6 and migrated to Java NIO (non-blocking I/O) at Level 11.

### Target Architecture (Level 4+)

```
server/
 ├── network/         ← connection lifecycle, socket management
 ├── auth/            ← login, registration, session handling
 ├── database/        ← JDBC, connection pooling
 ├── services/        ← business logic layer
 ├── commands/        ← command parsing and dispatching
 └── models/          ← User, Message, Session entities
```

---

## Tech Stack

| Component | Technology | Status |
|-----------|-----------|--------|
| Language | Java 21 | ✅ Active |
| Transport | TCP Sockets | ✅ Active |
| I/O Model | Blocking I/O | ✅ Active (temporary) |
| Concurrency | `new Thread()` | ✅ Active (temporary) |
| Thread Pool | `ExecutorService` | ⏳ Level 6 |
| Database | PostgreSQL + JDBC | ⏳ Level 7 |
| Auth | BCrypt + JWT | ⏳ Level 8 |
| Non-Blocking I/O | Java NIO (Selector/Channel) | ⏳ Level 11 |
| Message Queue | Apache Kafka | ⏳ Level 12 |
| Cache | Redis | ⏳ Level 12 |
| Container | Docker | ⏳ Level 15 |
| Orchestration | Kubernetes | ⏳ Level 15 |

---

## How To Run

### Prerequisites
- Java 21 JDK installed
- Terminal or IDE (IntelliJ IDEA recommended)

### Compile

```bash
cd src
javac Server.java ClientHandler.java Client.java
```

### Start the Server

```bash
# In Terminal 1
java Server
# Output: Server is listening on port 5000
```

### Connect a Client

```bash
# In Terminal 2 (repeat in Terminal 3, 4... for multiple clients)
java Client
# Output: Connected to server!
# Enter message to send to server (type 'exit' to quit):
```

### Test Multi-Client

Open 3+ separate terminals, run `java Client` in each. All clients connect simultaneously. The server handles each on its own independent thread.

---

## Folder Structure

```
MultiThreadSystemJAVA/
│
├── README.md               ← Project overview, architecture, how to run
├── ROADMAP.md              ← Full 17-level engineering progression
├── CHANGELOG.md            ← Version history, what changed and why
├── .gitignore              ← Ignored files (compiled classes, IDE files)
│
├── src/                    ← Source code
│   ├── Server.java         ← Main server: accepts TCP connections, spawns threads
│   ├── ClientHandler.java  ← Per-client handler: manages I/O for one connection
│   └── Client.java         ← Test client: connects to server, sends/receives
│
├── docs/                   ← Engineering documentation
│   ├── architecture/       ← System design, component diagrams
│   ├── networking/         ← Protocol spec, connection lifecycle
│   ├── concurrency/        ← Threading model, synchronization strategy
│   ├── decisions/          ← Architecture Decision Records (ADRs)
│   ├── security/           ← Auth model, threat analysis
│   ├── api/                ← Command API reference
│   ├── testing/            ← Test plans, stress test results
│   └── diagrams/           ← Mermaid and draw.io diagrams
│
├── tests/                  ← Unit and integration tests (Level 4+)
└── scripts/                ← Build, deploy, and utility scripts
```

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 21 | Production-grade, strong type system, mature concurrency APIs, JVM ecosystem |
| Transport | TCP | Reliable delivery required; UDP unsuitable for stateful sessions |
| Thread model | One thread per client | Simplest correct implementation for Level 2; explicit trade-off for future NIO migration |
| I/O | Blocking I/O | Easier mental model during initial development; replaced at Level 11 |
| Port | 5000 | Unprivileged (>1024), commonly used for development servers |
| Encoding | UTF-8 | Universal character encoding for international support |
| Message delimiter | Newline (`\n`) | Compatible with `readLine()` / `println()` contract |

See [docs/decisions/](./docs/decisions/) for full Architecture Decision Records.

---

## Known Limitations

| Limitation | Severity | Resolution Level |
|------------|----------|-----------------|
| Thread explosion under load | HIGH | Level 6 — ExecutorService |
| No shared state between clients | HIGH | Level 3 — Synchronized collections |
| No authentication | HIGH | Level 8 — BCrypt + JWT |
| No persistence | HIGH | Level 7 — PostgreSQL |
| Blocking I/O doesn't scale past ~500 clients | HIGH | Level 11 — NIO |
| No input validation or sanitization | MEDIUM | Level 5 — Command Engine |
| No graceful shutdown | MEDIUM | Level 13 — Fault Tolerance |
| No error recovery after client crash | MEDIUM | Level 13 — Fault Tolerance |
| Plaintext communication | MEDIUM | Level 8 — SSL/TLS |

---

## Future Goals

- [ ] **Level 3** — Thread-safe client registry, global broadcast messaging
- [ ] **Level 4** — Modular architecture (network/auth/service layers)
- [ ] **Level 5** — Command engine: `/login`, `/msg`, `/list`, `/quit`
- [ ] **Level 6** — Replace `new Thread()` with `ExecutorService` thread pool
- [ ] **Level 7** — PostgreSQL integration, JDBC, connection pooling
- [ ] **Level 8** — BCrypt password hashing, JWT sessions, SSL/TLS
- [ ] **Level 9** — Event bus, observer pattern, decoupled components
- [ ] **Level 10** — WebSocket support, real-time push notifications
- [ ] **Level 11** — Java NIO migration, `Selector`/`Channel`/`Buffer`
- [ ] **Level 12** — Distributed nodes, Redis cache, Kafka event streaming
- [ ] **Level 13** — Circuit breakers, retry logic, graceful shutdown
- [ ] **Level 14** — Microservice decomposition (Auth, Trading, Notification, Fraud)
- [ ] **Level 15** — Docker containers, Kubernetes orchestration, CI/CD
- [ ] **Level 16** — Sharding, distributed caching, horizontal scaling
- [ ] **Level 17** — Fraud detection AI, anomaly detection, predictive analytics

---

## Contributors

| Name | Role |
|------|------|
| *(your name)* | Architect & Lead Engineer |

---

## License

MIT License — see [LICENSE](./LICENSE) for details.

---

*Built with engineering discipline. Documented from Day 1.*