# =====================================================================
# Host side: dev.6 end-to-end run in Windows Sandbox (networking ON, ~45-90 min, downloads ~3 GB:
# the real Ollama installer and the Minimal models). The update SOURCE is a local TEST HTTPS server
# with a temporary test CA and TEST signing key that must live outside the repository.
#   powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-e2e.ps1 -Assets <dir> [-Version 2.0.0-dev.6]
# <dir> must contain: ca.pem, server.pem, server.key, update_server.py, manifests\*.json, trusted_key.json,
# sample.pdf. Private signing keys must NOT be in it (they never enter the Sandbox).
# =====================================================================
param([Parameter(Mandatory)][string]$Assets, [string]$Version = "", [int]$TimeoutMinutes = 150)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\..\.."
if (-not $Version) { $Version = [regex]::Match((Get-Content "$root\backend\version.py" -Raw), '__version__\s*=\s*"([^"]+)"').Groups[1].Value }
$instDir = "$root\dist\windows\$Version"
$installer = "$instDir\Abhify.AI-Setup-$Version.exe"
if (-not (Test-Path $installer)) { throw "Installer not found: $installer" }
$Assets = (Resolve-Path $Assets).Path
if (Get-ChildItem $Assets -Recurse -Filter *.key | Where-Object { $_.Name -ne "server.key" }) { throw "A private signing key is in the assets folder; remove it." }
@{ version = $Version; installer_sha256 = (Get-FileHash $installer).Hash.ToLower() } | ConvertTo-Json | Out-File -Encoding utf8 "$Assets\expected.json"
$results = Join-Path $root "build\sandbox-e2e"
if (Test-Path $results) { Remove-Item -Recurse -Force $results }
New-Item -ItemType Directory -Force $results | Out-Null
$wsb = @"
<Configuration>
  <vGPU>Disable</vGPU>
  <MemoryInMB>8192</MemoryInMB>
  <MappedFolders>
    <MappedFolder><HostFolder>$instDir</HostFolder><SandboxFolder>C:\abhify\installer</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$Assets</HostFolder><SandboxFolder>C:\abhify\assets</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$PSScriptRoot</HostFolder><SandboxFolder>C:\abhify\scripts</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$results</HostFolder><SandboxFolder>C:\abhify\results</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand><Command>powershell.exe -ExecutionPolicy Bypass -WindowStyle Minimized -File C:\abhify\scripts\sandbox-e2e.ps1</Command></LogonCommand>
</Configuration>
"@
$wsbPath = Join-Path $results "abhify-e2e.wsb"
$wsb | Out-File -Encoding utf8 $wsbPath
Start-Process $wsbPath
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (-not (Test-Path "$results\done.txt") -and (Get-Date) -lt $deadline) { Start-Sleep 15 }
if (Test-Path "$results\result.json") {
    $r = Get-Content "$results\result.json" -Raw | ConvertFrom-Json
    Write-Host ("E2E: {0}/{1} steps passed" -f $r.passed, $r.total)
    $r.steps.PSObject.Properties | ForEach-Object { Write-Host (" {0} {1}  {2}" -f ($(if ($_.Value.ok) { "PASS" } else { "FAIL" })), $_.Name, $_.Value.detail) }
} else { Write-Host "No result after $TimeoutMinutes minutes. See $results\log.txt" }
Get-Process WindowsSandbox*, vmmemWindowsSandbox -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
