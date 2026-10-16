# =====================================================================
# Host side: launch a clean Windows Sandbox that installs and tests the built installer.
#   powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-test.ps1
#   ... -UpgradeFrom 2.0.0-dev.2      also test a real upgrade from an earlier installer first
#   ... -Version 2.0.0-dev.3          test a specific build (default: backend\version.py)
# Needs the "Windows Sandbox" feature (Windows 10/11 Pro/Enterprise). Results:
#   build\sandbox-results\result.json  (+ log.txt, app logs)
# Installers are looked up in dist\windows\<version>\ (2.0.0-dev.3+) or dist\windows\ (dev.2).
# =====================================================================
param([int]$TimeoutMinutes = 25, [switch]$KeepOpen, [string]$Version = "", [string]$UpgradeFrom = "")
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\..\.."
$dist = Join-Path $root "dist\windows"
$scripts = $PSScriptRoot
$results = Join-Path $root "build\sandbox-results"

function Find-Installer($ver) {
    foreach ($p in @("$dist\$ver\Abhify.AI-Setup-$ver.exe", "$dist\Abhify.AI-Setup-$ver.exe")) {
        if (Test-Path $p) { return (Resolve-Path $p).Path }
    }
    throw "Installer for $ver not found in $dist (build it with packaging\windows\build.py)"
}
if (-not $Version) {
    $Version = [regex]::Match((Get-Content "$root\backend\version.py" -Raw), '__version__\s*=\s*"([^"]+)"').Groups[1].Value
}
$current = Find-Installer $Version
$previous = if ($UpgradeFrom) { Find-Installer $UpgradeFrom } else { $null }

if (Test-Path $results) { Remove-Item -Recurse -Force $results }
New-Item -ItemType Directory -Force $results | Out-Null
# Paths as the sandbox sees them (dist\windows is mapped to C:\abhify\dist)
$cfg = [ordered]@{
    version = $Version; installer = $current.Replace($dist, "C:\abhify\dist")
    upgrade_from = $UpgradeFrom; previous_installer = $(if ($previous) { $previous.Replace($dist, "C:\abhify\dist") } else { "" })
    installer_sha256 = (Get-FileHash $current -Algorithm SHA256).Hash.ToLower()
}
$cfg | ConvertTo-Json | Out-File -Encoding utf8 "$results\config.json"
Write-Host "Testing $current$(if ($previous) { " (upgrade from $UpgradeFrom)" })"

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
