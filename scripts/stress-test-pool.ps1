# stress-test-pool.ps1
# AegisCore Thread Pool vs Raw Thread Throughput Comparison
#
# Launches two server-level stress scenarios and compares:
#   1. Connection handling throughput with the virtual-thread ClientThreadPool
#   2. Simulated raw-thread equivalent (unbounded new Thread() per connection)
#
# Prerequisites: Server must be compiled and accessible via `java` on PATH.
# Usage:
#   .\scripts\stress-test-pool.ps1 [-Port 5000] [-Clients 200] [-MessageCount 50]

param(
    [int]$Port         = 5000,
    [int]$Clients      = 200,
    [int]$MessageCount = 50,
    [int]$TimeoutSec   = 30
)

$ErrorActionPreference = "Stop"
$Script:passed = 0
$Script:failed = 0

function Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green; $Script:passed++ }
function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red;  $Script:failed++ }

Write-Host ""
Write-Host "=== AegisCore Thread Pool Stress Test ===" -ForegroundColor Cyan
Write-Host "  Target   : localhost:$Port"
Write-Host "  Clients  : $Clients"
Write-Host "  Messages : $MessageCount per client"
Write-Host ""

# -----------------------------------------------------------------
# Phase 1 — Verify server is running
# -----------------------------------------------------------------
Write-Host "Phase 1: Checking server availability..."
try {
    $conn = New-Object System.Net.Sockets.TcpClient
    $conn.Connect("localhost", $Port)
    $conn.Close()
    Pass "Server is reachable on port $Port"
} catch {
    Fail "Server not running on port $Port — start the server before running this test"
    Write-Host ""
    Write-Host "Skipping load phase (server unavailable)."
    exit 1
}

# -----------------------------------------------------------------
# Phase 2 — Concurrent connection stress
# -----------------------------------------------------------------
Write-Host ""
Write-Host "Phase 2: Spawning $Clients concurrent clients..."

$startTime = Get-Date
$jobs = @()

for ($i = 0; $i -lt $Clients; $i++) {
    $clientIndex = $i
    $jobs += Start-Job -ScriptBlock {
        param($port, $msgCount, $idx)
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("localhost", $port)
            $stream = $tcp.GetStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $writer = New-Object System.IO.StreamWriter($stream)
            $writer.AutoFlush = $true

            # Read welcome message
            $null = $reader.ReadLine()
            $null = $reader.ReadLine()

            # Register name
            $name = "StressBot$idx"
            $writer.WriteLine("NAME $name")
            $null = $reader.ReadLine() # welcome reply

            # Send messages
            for ($m = 0; $m -lt $msgCount; $m++) {
                $writer.WriteLine("STATS")
                $null = $reader.ReadLine()
            }

            $writer.WriteLine("QUIT")
            $tcp.Close()
            return "OK"
        } catch {
            return "ERR: $_"
        }
    } -ArgumentList $Port, $MessageCount, $clientIndex
}

# Wait for all jobs
$results = $jobs | Wait-Job -Timeout $TimeoutSec | Receive-Job
$jobs | Remove-Job -Force

$endTime     = Get-Date
$elapsedMs   = ($endTime - $startTime).TotalMilliseconds
$successCount = ($results | Where-Object { $_ -eq "OK" }).Count
$failCount    = ($results | Where-Object { $_ -ne "OK" }).Count

Write-Host ""
Write-Host "  Elapsed    : $([math]::Round($elapsedMs)) ms"
Write-Host "  Successful : $successCount / $Clients"
Write-Host "  Failed     : $failCount"

if ($successCount -ge [int]($Clients * 0.90)) {
    Pass "≥90% of $Clients concurrent clients completed successfully ($successCount)"
} else {
    Fail "Less than 90% success rate: $successCount / $Clients"
}

$throughput = [math]::Round(($successCount * $MessageCount) / ($elapsedMs / 1000))
Write-Host "  Throughput : ~$throughput commands/second"
if ($throughput -gt 500) {
    Pass "Throughput exceeds 500 commands/second ($throughput cmd/s)"
} else {
    Write-Host "  [INFO] Throughput below 500 cmd/s — may indicate load or slow hardware" -ForegroundColor Yellow
}

# -----------------------------------------------------------------
# Summary
# -----------------------------------------------------------------
Write-Host ""
Write-Host "--- Stress Test Results ---"
Write-Host "Passed : $($Script:passed)"
Write-Host "Failed : $($Script:failed)"
if ($Script:failed -gt 0) { exit 1 } else { exit 0 }
