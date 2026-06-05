# Software Requirements Specification (SRS)

## 1. Introduction

### 1.1 Purpose
This document specifies the software requirements for **AegisCore**, a high-performance, multithreaded TCP game lobby server written in Java. AegisCore provides player session management, room management, ready checks, and matchmaking queues for multiplayer game backends.

### 1.2 System Scope
The system includes:
- A TCP socket server accepting concurrent client sessions.
- A command parser and router dispatching line-oriented commands.
- A player registry managing active sessions and identification.
- A room registry managing room creation, player slot limits, listings, and room states.
- A matchmaking queue managing auto-pairing of players.
- A thread-safe file logging engine.

---

## 2. Functional Requirements

### 2.1 Player Session Management
- **FR-1.1:** The system shall accept incoming TCP connections on port 5000.
- **FR-1.2:** The system shall require players to register a unique display name using the `NAME` command before allowing other commands.
- **FR-1.3:** The system shall register named players in a global registry and evict them cleanly on client disconnect or when the `QUIT` command is issued.

### 2.2 Room Management
- **FR-2.1:** The system shall allow players in the lobby to create rooms using the `CREATE` command, specifying a room name and optional player capacity (slots).
- **FR-2.2:** The system shall assign a unique room ID (e.g., `r-001`) to each created room.
- **FR-2.3:** The system shall allow players to join open rooms by ID using the `JOIN` command, list joinable rooms using the `LIST` command, and leave rooms using the `LEAVE` command.
- **FR-2.4:** The system shall automatically destroy rooms when the last occupying player leaves.

### 2.3 Ready-Check & Countdown
- **FR-3.1:** The system shall allow players inside a room to signal readiness using the `READY` command or retract it using the `UNREADY` command.
- **FR-3.2:** When all players in a room are marked as ready, the system shall initiate a 5-second countdown.
- **FR-3.3:** If any player issues `UNREADY` or leaves the room during the countdown, the system shall cancel the countdown immediately.
- **FR-3.4:** If the countdown completes successfully, the system shall transition the room to `IN_PROGRESS` and notify all occupants.

### 2.4 Automatic Matchmaking
- **FR-4.1:** The system shall allow players to enter the matchmaking queue using the `QUEUE` command and leave it using `DEQUEUE`.
- **FR-4.2:** A background matchmaking thread shall poll queued players and automatically group them into rooms once a full match size is satisfied.

### 2.5 Room Chat Broadcast
- **FR-5.1:** The system shall allow players inside a room to send messages to all other room occupants using the `CHAT` command.

### 2.6 Server Statistics & Telemetry
- **FR-6.1:** The system shall aggregate server metrics (active sessions, lifetime accepted connections, relayed messages, and socket byte throughput).
- **FR-6.2:** The system shall allow players to query these metrics using the `STATS` command.

---

## 3. Non-Functional Requirements

### 3.1 Performance & Scalability
- **NFR-1.1:** The server socket loop shall accept connections in a non-blocking queue (OS level) and hand off to handler threads immediately.
- **NFR-1.2:** Intrinsic locks on socket output streams shall serialize writes to prevent byte interleaving without bottlenecking independent clients.

### 3.2 Reliability & Fault Tolerance
- **NFR-2.1:** The server shall isolate network exceptions thrown by a client so that a single client disconnect during a broadcast loop does not abort transmissions to other clients or crash the server.
- **NFR-2.2:** The player registry shall remain consistent, evicting sockets cleanly and avoiding zombie threads.

### 3.3 Observability
- **NFR-3.1:** The system shall output thread-safe logs to files in a `logs/` directory (`Server.log`, `ClientHandler.log`, `Registry.log`, `Client.log`).
