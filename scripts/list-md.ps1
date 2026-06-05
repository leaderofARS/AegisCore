$files = Get-ChildItem -Path . -Filter *.md -Recurse
foreach ($f in $files) {
    $path = $f.FullName
    if ($path -match '\\\.git\\') { continue }
    if ($path -match '\\website\\') { continue }
    if ($path -match '\\\.antigravitycli\\') { continue }
    if ($path -match '\\AGENTS\\') { continue }
    Write-Output $f.FullName
}
