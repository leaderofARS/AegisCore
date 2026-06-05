# AegisCore Deployment Guide

This document describes how to compile, run, test, and deploy the **AegisCore** Game Lobby Server in local and production environments.

---

## 1. Prerequisites
- **Java Development Kit (JDK):** Java 21 LTS or newer. Verify using:
  ```bash
  java --version
  ```
- **Operating System:** Cross-platform (Windows, Linux, macOS supported).

---

## 2. Compilation
Compile all packages into a unified binary directory (`bin/`). Run the following command from the project root directory:

```bash
javac -d bin src/core/*.java src/matchmaking/*.java src/player/*.java src/protocol/*.java src/room/*.java src/server/*.java
```

---

## 3. Running the Server & Client

### 3.1 Start the Server
Start the TCP lobby server (listens on port `5000` by default):
```bash
java -cp bin server.Server
```

### 3.2 Start the Interactive CLI Client
Open a separate terminal window and connect to the local server:
```bash
java -cp bin server.Client
```
Alternatively, you can connect using raw socket CLI tools:
```bash
telnet localhost 5000
```

---

## 4. Running the Test Suite

### 4.1 Integration & Load Tests
Compile and run the Java integration and load test harnesses:
```bash
# Compile tests
javac -cp bin -d bin tests/*.java

# Run integration tests
java -cp bin tests.SystemIntegrationTest

# Run load/stress tests
java -cp bin tests.LoadTest
```

### 4.2 End-to-End (E2E) Verification Script
To run the automated PowerShell end-to-end lobby script:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/e2e-lobby-test.ps1
```

---

## 5. Logs & Observability
All server executions output structured concurrent logs into the `logs/` directory:
- `logs/Server.log`: Server lifecycle state transitions, connections, and shutdown sequences.
- `logs/ClientHandler.log`: Per-session command transactions and socket-level write/read logging.
- `logs/Registry.log`: Registry mutation events, registrations, and player evictions.
- `logs/Client.log`: Client-side log outputs.

---

## 6. Shutdown Procedure
To terminate the server cleanly, send a SIGINT signal (e.g. press `Ctrl+C` in the server terminal). 

AegisCore will intercept this signal using a JVM shutdown hook and execute a **3-Phase Graceful Shutdown**:
1. Close the master `ServerSocket` to reject new connections.
2. Broadcast a shutdown warning and disconnect all active players cleanly.
3. Terminate countdown timer executor threads and flush all logs.

---

## 7. Future Deployment Path
Planned enhancements for enterprise production setups include:
- Containerization using Docker to pack the lobby server into lightweight alpine-based layers.
- Kubernetes deployment blueprints for scaling lobby node pods.
- CI/CD integration with GitHub Actions for automated lint, build, test, and container registry publishing.
