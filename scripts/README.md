# Scripts Directory

This directory contains utility and automated test scripts for the AegisCore Game Lobby Server.

---

## Script Inventory

- **[e2e-lobby-test.ps1](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/scripts/e2e-lobby-test.ps1):**
  Automated end-to-end verification script. It connects multiple virtual clients to simulate name setting, room creation, joining, ready check synchronization, countdown triggers, matchmaking, chat messaging, and clean exits.
- **[stress-test.ps1](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/scripts/stress-test.ps1):**
  Load and stress testing script. Spawns high-frequency connections to profile the server's thread handling, resource boundaries, and connection release mechanics under pressure.
- **[list-md.ps1](file:///C:/Users/Asus/Desktop/MultiThreadSystemJAVA/scripts/list-md.ps1):**
  A utility script that lists all markdown files in the project workspace, useful for documentation status reviews.

---

## Execution Prerequisites

These scripts are written in PowerShell. To run them, bypass the local execution policy in your terminal session:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/<script-name>.ps1
```
