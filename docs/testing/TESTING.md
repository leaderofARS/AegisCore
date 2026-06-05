# AegisCore Testing Infrastructure

This document details the test suites, verification scripts, and automated scenarios used to ensure the reliability, performance, and concurrency safety of the **AegisCore** Game Lobby Server.

---

## 1. Test Harness Overview

AegisCore is validated across three distinct testing tiers:

| Test Suite | File Location | Type | Verification Concern |
| :--- | :--- | :---: | :--- |
| **System Integration Test** | [SystemIntegrationTest.java](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/tests/SystemIntegrationTest.java) | Integration | Concurrency safety, registry stability, socket write contention |
| **Load Test Harness** | [LoadTest.java](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/tests/LoadTest.java) | Stress/Load | Thread capacity bounds, connection storms, message throughput |
| **End-to-End Test Script** | [e2e-lobby-test.ps1](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/scripts/e2e-lobby-test.ps1) | Functional E2E | Commands, state transitions, countdowns, matchmaking |

---

## 2. Integration Scenarios

The `SystemIntegrationTest.java` suite runs four concurrent scenarios under a simulated local network:

1. **Multiple Receivers:** Verifies that global or room broadcasts reach all connected clients.
2. **Disconnect During Broadcast:** Verifies that a client dropping mid-broadcast does not crash the broadcasting thread or cause other active clients to lose messages.
3. **Rapid Messaging (Spam Storm):** Simulates high-frequency concurrent broadcasts to verify thread safety and lack of lock contention.
4. **Reconnect Storm (Zombie Defense):** Opens and closes connections rapidly to ensure the player registry evicts sockets cleanly without creating "zombie" threads or connection leaks.

---

## 3. How to Run the Tests

### 3.1 Prerequisite Compilation
Compile all source files and test harnesses before running:
```bash
# Compile core sources
javac -d bin src/core/*.java src/matchmaking/*.java src/player/*.java src/protocol/*.java src/room/*.java src/server/*.java

# Compile test suite
javac -cp bin -d bin tests/*.java
```

### 3.2 Running the Integration Tests
Execute the Java system integration suite:
```bash
java -cp bin tests.SystemIntegrationTest
```

### 3.3 Running the Load Test
Execute the load test harness (simulates multiple virtual clients):
```bash
java -cp bin tests.LoadTest
```

### 3.4 Running the E2E PowerShell Script
Run the automated end-to-end user sequence simulation:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/e2e-lobby-test.ps1
```

### 3.5 Running the Stress Script
Run the automated stress simulation harness:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/stress-test.ps1
```

---

## 4. Test Results

A verified run of the `SystemIntegrationTest` should output the following logs:

```
==================================================
   AEGISCORE CONCURRENCY INTEGRATION HARNESS      
==================================================
[SETUP] Test Server ready and listening.

[TEST 1] Testing Multiple Receivers...
-> [TEST 1] PASS: All clients successfully received parallel broadcasts.

[TEST 2] Testing Disconnect During Broadcast...
-> [TEST 2] PASS: Server handled connection sever during broadcast loop gracefully.

[TEST 3] Testing Rapid Messaging (Spam Storm)...
-> [TEST 3] PASS: Concurrent messaging spam completed with no deadlocks or server freezes.

[TEST 4] Testing Reconnect Storm (Zombie Defense)...
Total rapid sessions executed: 80
Connection/Transmission failures: 0
Registry state (active sessions count): 0
-> [TEST 4] PASS: Reconnect storm finished cleanly with exactly 0 zombie sessions in registry.

==================================================
  ALL 4 SYSTEM CONCURRENCY TESTS PASSED SUCCESSFULLY!  
==================================================
```
