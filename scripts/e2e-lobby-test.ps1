Write-Host "Starting E2E Lobby Test..."

# 1. Start Client 1 (Kirito)
$psi1 = New-Object System.Diagnostics.ProcessStartInfo
$psi1.FileName = "java"
$psi1.Arguments = "-cp bin server.Client"
$psi1.UseShellExecute = $false
$psi1.RedirectStandardInput = $true
$psi1.RedirectStandardOutput = $true
$psi1.RedirectStandardError = $true
$p1 = [System.Diagnostics.Process]::Start($psi1)

# 2. Start Client 2 (Asuna)
$psi2 = New-Object System.Diagnostics.ProcessStartInfo
$psi2.FileName = "java"
$psi2.Arguments = "-cp bin server.Client"
$psi2.UseShellExecute = $false
$psi2.RedirectStandardInput = $true
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$p2 = [System.Diagnostics.Process]::Start($psi2)

Start-Sleep -Seconds 2

# Kirito sets name
Write-Host "Kirito setting name..."
$p1.StandardInput.WriteLine("NAME Kirito")
Start-Sleep -Seconds 1

# Asuna sets name
Write-Host "Asuna setting name..."
$p2.StandardInput.WriteLine("NAME Asuna")
Start-Sleep -Seconds 1

# Kirito creates room
Write-Host "Kirito creating room Floor1..."
$p1.StandardInput.WriteLine("CREATE Floor1 2")
Start-Sleep -Seconds 2

# Asuna lists rooms and joins
Write-Host "Asuna listing rooms and joining r-001..."
$p2.StandardInput.WriteLine("LIST")
Start-Sleep -Seconds 1
$p2.StandardInput.WriteLine("JOIN r-001")
Start-Sleep -Seconds 2

# Both ready up
Write-Host "Asuna readying up..."
$p2.StandardInput.WriteLine("READY")
Start-Sleep -Seconds 1

Write-Host "Kirito readying up..."
$p1.StandardInput.WriteLine("READY")
Start-Sleep -Seconds 7 # Wait for 5s countdown + buffer

# Chat messages
Write-Host "Sending chat messages..."
$p1.StandardInput.WriteLine("CHAT Link Start!")
Start-Sleep -Seconds 1
$p2.StandardInput.WriteLine("CHAT Glad to be here!")
Start-Sleep -Seconds 1

# Kirito prints server stats
Write-Host "Kirito requesting stats..."
$p1.StandardInput.WriteLine("STATS")
Start-Sleep -Seconds 1

# Both quit
Write-Host "Quitting both clients..."
$p1.StandardInput.WriteLine("QUIT")
$p2.StandardInput.WriteLine("QUIT")

# Wait for processes to exit
$p1.WaitForExit(5000)
$p2.WaitForExit(5000)

Write-Host "`n=== CLIENT 1 (KIRITO) OUTPUT ==="
$out1 = $p1.StandardOutput.ReadToEnd()
Write-Host $out1

Write-Host "`n=== CLIENT 2 (ASUNA) OUTPUT ==="
$out2 = $p2.StandardOutput.ReadToEnd()
Write-Host $out2

Write-Host "`n=== CLIENT 1 ERRORS ==="
$err1 = $p1.StandardError.ReadToEnd()
Write-Host $err1

Write-Host "`n=== CLIENT 2 ERRORS ==="
$err2 = $p2.StandardError.ReadToEnd()
Write-Host $err2
