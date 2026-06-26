# compile-client.ps1
# Compiles the AegisCore RPG game client.
# Run from the project root: .\scripts\compile-client.ps1

$root   = Split-Path -Parent $PSScriptRoot
$src    = "$root\client"
$outDir = "$root\client\bin"

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

Write-Host "Compiling game client..." -ForegroundColor Cyan
$sources = Get-ChildItem -Path $src -Filter "*.java" | Select-Object -ExpandProperty FullName
javac -d $outDir $sources 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "Client compiled successfully -> $outDir" -ForegroundColor Green
} else {
    Write-Host "Compilation FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    exit 1
}
