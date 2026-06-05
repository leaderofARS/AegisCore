# Tests Directory

This directory contains the Java-based integration and load testing suites for the AegisCore Game Lobby Server.

---

## Test Inventory

- **[SystemIntegrationTest.java](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/tests/SystemIntegrationTest.java):**
  Validates server concurrency safety. It runs four specific scenarios: multi-client message delivery, client disconnect handling mid-broadcast, rapid spam storm write stability, and connection/reconnection storms to check for socket leaks.
- **[LoadTest.java](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/tests/LoadTest.java):**
  A configurable stress and load test. Spawns custom numbers of virtual socket clients to push messages and stress the server's threading boundaries and memory visibility limits.

---

## Execution Guide

### 1. Compile the Test Suite
Compile the server files first, and then compile the test files into the `bin/` directory:
```bash
# Compile core sources
javac -d bin src/core/*.java src/matchmaking/*.java src/player/*.java src/protocol/*.java src/room/*.java src/server/*.java

# Compile test files
javac -cp bin -d bin tests/*.java
```

### 2. Execute System Integration Tests
Run the concurrency safety verification suite:
```bash
java -cp bin tests.SystemIntegrationTest
```

### 3. Execute Load Tests
Run the multi-connection stress test:
```bash
java -cp bin tests.LoadTest
```
