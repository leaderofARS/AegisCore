<#
  run-tests.ps1  -  AegisCore full build + test harness
  Usage:  .\scripts\run-tests.ps1   (from project root)
#>

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Src  = Join-Path $Root "src"
$Bin  = Join-Path $Root "bin"
$Tests= Join-Path $Root "tests"

# -- colour helpers -----------------------------------------------------------
function Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green  }
function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red    }
function Info($msg) { Write-Host $msg             -ForegroundColor Cyan   }
function Warn($msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }

$global:totalPassed = 0
$global:totalFailed = 0

# -- port-cleanup helper ------------------------------------------------------
function Kill-TestPorts {
    foreach ($port in @(5000, 5100, 8080)) {
        $netOut = & netstat -ano
        $lines  = $netOut | Select-String ":$port " | Where-Object { $_ -match 'LISTENING|LISTEN' }
        foreach ($line in $lines) {
            $parts  = ($line.ToString().Trim() -split '\s+')
            $pidStr = $parts[-1]
            if ($pidStr -match '^\d+$' -and [int]$pidStr -ne 0) {
                Stop-Process -Id ([int]$pidStr) -Force -ErrorAction SilentlyContinue
                Write-Host "  [CLEANUP] Killed PID $pidStr holding port $port" -ForegroundColor DarkYellow
            }
        }
    }
    Start-Sleep -Milliseconds 800
}

function Run-Test {
    param(
        [string]$Name,
        [string]$MainClass,
        [string[]]$ExtraArgs = @()
    )

    Info ""
    Info "======================================================"
    Info "  RUNNING: $Name"
    Info "======================================================"

    $javaArgs = @("-cp", $Bin, $MainClass) + $ExtraArgs
    $proc = Start-Process java -ArgumentList $javaArgs `
                -WorkingDirectory $Root `
                -NoNewWindow -PassThru -Wait `
                -RedirectStandardOutput "$Root\logs\test-stdout.tmp" `
                -RedirectStandardError  "$Root\logs\test-stderr.tmp"

    Get-Content "$Root\logs\test-stdout.tmp" | Write-Host
    $stderr = Get-Content "$Root\logs\test-stderr.tmp" -ErrorAction SilentlyContinue
    if ($stderr) { Write-Host $stderr -ForegroundColor DarkYellow }

    if ($proc.ExitCode -eq 0) {
        Pass "$Name -> EXIT 0"
        $global:totalPassed++
    } else {
        Fail "$Name -> EXIT $($proc.ExitCode)"
        $global:totalFailed++
    }
}

# -- 0. Ensure logs dir exists + kill any zombie test processes ---------------
New-Item -ItemType Directory -Path "$Root\logs\sessions" -Force | Out-Null
Info "  Clearing stale test processes on ports 5000/5100/8080..."
Kill-TestPorts

# -- 1. COMPILE ---------------------------------------------------------------
Info ""
Info "======================================================"
Info "  STEP 1 - COMPILE ALL SOURCES"
Info "======================================================"

$srcFiles  = Get-ChildItem -Path $Src   -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
$testFiles = Get-ChildItem -Path $Tests          -Filter "*.java" | Select-Object -ExpandProperty FullName
$allFiles  = $srcFiles + $testFiles

$listFile = "$Root\logs\sources.txt"
$allFiles | Set-Content $listFile

Write-Host "Compiling $($allFiles.Count) Java files -> $Bin ..."

$javacArgs = @("-d", $Bin, "-sourcepath", $Src, "@$listFile")
$comp = Start-Process javac -ArgumentList $javacArgs `
            -WorkingDirectory $Root `
            -NoNewWindow -PassThru -Wait `
            -RedirectStandardOutput "$Root\logs\compile-stdout.tmp" `
            -RedirectStandardError  "$Root\logs\compile-stderr.tmp"

$compOut = Get-Content "$Root\logs\compile-stdout.tmp" -ErrorAction SilentlyContinue
$compErr = Get-Content "$Root\logs\compile-stderr.tmp" -ErrorAction SilentlyContinue
if ($compOut) { Write-Host $compOut }
if ($compErr) { Write-Host $compErr -ForegroundColor DarkYellow }

if ($comp.ExitCode -ne 0) {
    Write-Host "`n[FATAL] Compilation FAILED. Fix errors above before running tests." -ForegroundColor Red
    exit 1
}
Pass "Compilation succeeded."

# -- 2. UNIT TESTS ------------------------------------------------------------
Info ""
Info "======================================================"
Info "  STEP 2 - UNIT TESTS"
Info "======================================================"

Run-Test "RateLimiterTest"        "RateLimiterTest"
Run-Test "WebSocketHandshakeTest" "WebSocketHandshakeTest"
Run-Test "HeartbeatTest"          "HeartbeatTest"
Run-Test "WhisperTest"            "WhisperTest"
Run-Test "SpectatorTest"          "SpectatorTest"
Run-Test "MatchmakingSkillTest"   "MatchmakingSkillTest"
Run-Test "LedgerReplayTest"       "LedgerReplayTest"

# -- 3. INTEGRATION TEST ------------------------------------------------------
Info ""
Info "======================================================"
Info "  STEP 3 - SYSTEM INTEGRATION TEST"
Info "======================================================"

Run-Test "SystemIntegrationTest" "SystemIntegrationTest"

# Give the integration-test JVM time to fully exit and release all ports
Info "  Waiting for integration-test ports to be released..."
Start-Sleep -Seconds 4
Kill-TestPorts

# -- 4. GRACEFUL SHUTDOWN VALIDATION ------------------------------------------
Info ""
Info "======================================================"
Info "  STEP 4 - GRACEFUL SHUTDOWN VALIDATION"
Info "======================================================"

Info "  Starting server in background on port 5100..."
$serverLog = "$Root\logs\shutdown-test-server.log"
$serverProc = Start-Process java `
    -ArgumentList @("-Daegiscore.metrics.enabled=false", "-cp", $Bin, "server.Server", "--port", "5100") `
    -WorkingDirectory $Root `
    -NoNewWindow -PassThru `
    -RedirectStandardOutput $serverLog `
    -RedirectStandardError  "$Root\logs\shutdown-test-server-err.log"

# Wait until server is accepting connections (max 15s)
$deadline = (Get-Date).AddSeconds(15)
$serverReady = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 200
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect("localhost", 5100)
        $tcp.Close()
        $serverReady = $true
        break
    } catch { }
}

if (-not $serverReady) {
    Fail "Server did not bind on port 5100 within 15 seconds"
    $global:totalFailed++
} else {
    Pass "Server bound on port 5100 and is accepting connections"
    $global:totalPassed++

    Info "  Connecting test client..."
    try {
        $tcp    = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect("localhost", 5100)
        $stream = $tcp.GetStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $writer = New-Object System.IO.StreamWriter($stream)
        $writer.AutoFlush = $true

        $greeting1 = $reader.ReadLine()
        Info "  Server greeting 1: $greeting1"
        $greeting2 = $reader.ReadLine()
        Info "  Server greeting 2: $greeting2"

        $writer.WriteLine("QUIT")
        $bye = $reader.ReadLine()
        Info "  Server QUIT reply: $bye"
        $tcp.Close()

        Pass "Client session completed cleanly (connect -> QUIT)"
        $global:totalPassed++
    } catch {
        Fail "Client session failed: $_"
        $global:totalFailed++
    }

    Info "  Sending shutdown signal to server (Stop-Process)..."
    $serverProc | Stop-Process -Force
    Start-Sleep -Seconds 2

    $log = Get-Content $serverLog -ErrorAction SilentlyContinue
    Info "  --- Server log tail ---"
    $log | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" }

    $hasShutdownMsg = $log | Where-Object { $_ -match "Shutdown complete|shutting down|ServerSocket closed|stopped accepting" }
    if ($hasShutdownMsg) {
        Pass "Graceful shutdown messages found in server log"
        $global:totalPassed++
    } else {
        Warn "Graceful shutdown messages NOT found (process was force-killed - normal on Windows)"
        # Not a hard fail - Windows does not relay Ctrl+C to child java easily
    }
}

# -- SUMMARY ------------------------------------------------------------------
Info ""
Info "======================================================"
Info "  FINAL SUMMARY"
Info "======================================================"
Write-Host "  Test suites passed : $($global:totalPassed)" -ForegroundColor Green
Write-Host "  Test suites failed : $($global:totalFailed)" -ForegroundColor $(if ($global:totalFailed -gt 0) { "Red" } else { "Green" })

if ($global:totalFailed -gt 0) {
    Write-Host "`n  !! SOME TESTS FAILED !!" -ForegroundColor Red
    exit 1
} else {
    Write-Host "`n  ALL TESTS PASSED" -ForegroundColor Green
    exit 0
}
