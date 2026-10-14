# =====================================================================
# Host side: launch a clean Windows Sandbox that installs and tests the built installer.
#   powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1
# Needs the "Windows Sandbox" feature (Windows 10/11 Pro/Enterprise). Results:
#   build\sandbox-results\result.json  (+ log.txt, app logs)
# =====================================================================
param([int]$TimeoutMinutes = 20, [switch]$KeepOpen)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\..\.."
$dist = Join-Path $root "dist\windows"
$scripts = $PSScriptRoot
$results = Join-Path $root "build\sandbox-results"
if (-not (Get-ChildItem "$dist\Abhify.AI-Setup-*.exe" -ErrorAction SilentlyContinue)) { throw "Build the installer first (packaging\windows\build.py)" }
if (Test-Path $results) { Remove-Item -Recurse -Force $results }
New-Item -ItemType Directory -Force $results | Out-Null

$wsb = @"
<Configuration>
  <Networking>Disable</Networking>
  <vGPU>Disable</vGPU>
  <MemoryInMB>6144</MemoryInMB>
  <MappedFolders>
    <MappedFolder><HostFolder>$dist</HostFolder><SandboxFolder>C:\abhify\dist</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$scripts</HostFolder><SandboxFolder>C:\abhify\scripts</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$results</HostFolder><SandboxFolder>C:\abhify\results</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand>
    <Command>powershell.exe -ExecutionPolicy Bypass -WindowStyle Minimized -File C:\abhify\scripts\sandbox-test.ps1</Command>
  </LogonCommand>
</Configuration>
"@
$wsbPath = Join-Path $results "abhify-test.wsb"
$wsb | Out-File -Encoding utf8 $wsbPath
Write-Host "Starting Windows Sandbox (networking disabled)..."
Start-Process $wsbPath

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (-not (Test-Path "$results\done.txt") -and (Get-Date) -lt $deadline) { Start-Sleep 5 }
if (Test-Path "$results\result.json") {
    $r = Get-Content "$results\result.json" -Raw | ConvertFrom-Json
    Write-Host ("Sandbox test: {0}/{1} steps passed" -f $r.passed, $r.total)
    $r.steps.PSObject.Properties | ForEach-Object { Write-Host (" {0} {1}  {2}" -f ($(if ($_.Value.ok) { "PASS" } else { "FAIL" })), $_.Name, $_.Value.detail) }
} else { Write-Host "No result after $TimeoutMinutes minutes. See $results\log.txt" }
if (-not $KeepOpen) { Get-Process WindowsSandbox*, vmmemWindowsSandbox -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
