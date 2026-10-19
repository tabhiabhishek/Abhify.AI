# =====================================================================
# Host side: SIGNING PIPELINE lab test in Windows Sandbox (docs/CODE_SIGNING.md, step 4).
#   powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-signlab.ps1 [-Version 2.0.0-dev.4]
# A throw-away self-signed certificate is created INSIDE the Sandbox (never on this PC), a COPY of
# the installer is signed with packaging\windows\sign.ps1 and checked: valid signature + timestamp,
# wrong publisher refused, tampering detected, checksum changes, the signed installer still installs.
# The signed lab file never leaves the Sandbox. Networking is on (for the RFC 3161 timestamp).
# Results: build\sandbox-signlab\result.json
# =====================================================================
param([string]$Version = "", [int]$TimeoutMinutes = 20)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\..\.."
$results = Join-Path $root "build\sandbox-signlab"
if (-not $Version) {
    $Version = [regex]::Match((Get-Content "$root\backend\version.py" -Raw), '__version__\s*=\s*"([^"]+)"').Groups[1].Value
}
$installer = "$root\dist\windows\$Version\Abhify.AI-Setup-$Version.exe"
if (-not (Test-Path $installer)) { throw "Installer not found: $installer" }
$signtoolDir = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" | Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty DirectoryName          # plain path (Split-Path would add a provider prefix)
if (-not $signtoolDir) { throw "signtool.exe not found (Windows SDK)" }
if (Test-Path $results) { Remove-Item -Recurse -Force $results }
New-Item -ItemType Directory -Force "$results\inputs" | Out-Null
Copy-Item $installer "$results\inputs\unsigned-copy.exe"          # the original is never touched
@{ version = $Version; original_sha256 = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLower() } |
    ConvertTo-Json | Out-File -Encoding utf8 "$results\config.json"

$wsb = @"
<Configuration>
  <vGPU>Disable</vGPU>
  <MemoryInMB>4096</MemoryInMB>
  <MappedFolders>
    <MappedFolder><HostFolder>$(Split-Path $PSScriptRoot)</HostFolder><SandboxFolder>C:\abhify\packaging</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$signtoolDir</HostFolder><SandboxFolder>C:\abhify\signtool</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$results</HostFolder><SandboxFolder>C:\abhify\results</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand>
    <Command>powershell.exe -ExecutionPolicy Bypass -WindowStyle Minimized -File C:\abhify\packaging\sandbox\sandbox-signlab.ps1</Command>
  </LogonCommand>
</Configuration>
"@
$wsbPath = Join-Path $results "abhify-signlab.wsb"
$wsb | Out-File -Encoding utf8 $wsbPath
Start-Process $wsbPath
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (-not (Test-Path "$results\done.txt") -and (Get-Date) -lt $deadline) { Start-Sleep 5 }
if (Test-Path "$results\result.json") {
    $r = Get-Content "$results\result.json" -Raw | ConvertFrom-Json
    Write-Host ("Signing lab test: {0}/{1} steps passed" -f $r.passed, $r.total)
    $r.steps.PSObject.Properties | ForEach-Object { Write-Host (" {0} {1}  {2}" -f ($(if ($_.Value.ok) { "PASS" } else { "FAIL" })), $_.Name, $_.Value.detail) }
} else { Write-Host "No result after $TimeoutMinutes minutes. See $results\log.txt" }
Remove-Item "$results\inputs\unsigned-copy.exe" -ErrorAction SilentlyContinue
Get-Process WindowsSandbox*, vmmemWindowsSandbox -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
