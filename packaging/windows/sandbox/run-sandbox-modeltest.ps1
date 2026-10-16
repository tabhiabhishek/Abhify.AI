# =====================================================================
# Host side: clean-PC MODEL SETUP test in Windows Sandbox, WITH networking.
#   powershell -ExecutionPolicy Bypass -File packaging\windows\sandbox\run-sandbox-modeltest.ps1 `
#       -OllamaSetup build\taskb-input\OllamaSetup.exe -Pdf build\taskb-input\sample.pdf [-Version 2.0.0-dev.3]
# Installs Abhify.AI and then Ollama in a clean VM (no GPU, CPU only), downloads the Minimal
# profile with consent, and checks: missing-Ollama handling, consent, network errors, an
# interrupted download, a model that can't start, real inference, knowledge-folder indexing and
# document Q&A, and persistence after a restart. Downloads ~1.3 GB of models.
# Results: build\sandbox-modeltest\result.json (+ log.txt)
# =====================================================================
param([Parameter(Mandatory)][string]$OllamaSetup, [Parameter(Mandatory)][string]$Pdf,
      [string]$Version = "", [int]$TimeoutMinutes = 75, [int]$MemoryMB = 8192, [switch]$KeepOpen)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\..\.."
$dist = Join-Path $root "dist\windows"
$results = Join-Path $root "build\sandbox-modeltest"
if (-not $Version) {
    $Version = [regex]::Match((Get-Content "$root\backend\version.py" -Raw), '__version__\s*=\s*"([^"]+)"').Groups[1].Value
}
$installer = @("$dist\$Version\Abhify.AI-Setup-$Version.exe", "$dist\Abhify.AI-Setup-$Version.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $installer) { throw "Installer for $Version not found" }
$sig = Get-AuthenticodeSignature (Resolve-Path $OllamaSetup)
if ($sig.Status -ne "Valid" -or $sig.SignerCertificate.Subject -notmatch "O=Ollama Inc\.") { throw "OllamaSetup.exe is not validly signed by Ollama Inc." }

if (Test-Path $results) { Remove-Item -Recurse -Force $results }
$inputs = Join-Path $results "inputs"
New-Item -ItemType Directory -Force $inputs | Out-Null
Copy-Item $installer "$inputs\Abhify.AI-Setup.exe"
Copy-Item $OllamaSetup "$inputs\OllamaSetup.exe"
Copy-Item $Pdf "$inputs\sample.pdf"
@{ version = $Version; installer_sha256 = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLower() } |
    ConvertTo-Json | Out-File -Encoding utf8 "$results\config.json"
Write-Host "Model-setup test of $installer (networking ON, $MemoryMB MB RAM, no GPU)"

$wsb = @"
<Configuration>
  <vGPU>Disable</vGPU>
  <MemoryInMB>$MemoryMB</MemoryInMB>
  <MappedFolders>
    <MappedFolder><HostFolder>$PSScriptRoot</HostFolder><SandboxFolder>C:\abhify\scripts</SandboxFolder><ReadOnly>true</ReadOnly></MappedFolder>
    <MappedFolder><HostFolder>$results</HostFolder><SandboxFolder>C:\abhify\results</SandboxFolder><ReadOnly>false</ReadOnly></MappedFolder>
  </MappedFolders>
  <LogonCommand>
    <Command>powershell.exe -ExecutionPolicy Bypass -WindowStyle Minimized -File C:\abhify\scripts\sandbox-modeltest.ps1</Command>
  </LogonCommand>
</Configuration>
"@
$wsbPath = Join-Path $results "abhify-modeltest.wsb"
$wsb | Out-File -Encoding utf8 $wsbPath
Start-Process $wsbPath
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while (-not (Test-Path "$results\done.txt") -and (Get-Date) -lt $deadline) { Start-Sleep 10 }
if (Test-Path "$results\result.json") {
    $r = Get-Content "$results\result.json" -Raw | ConvertFrom-Json
    Write-Host ("Model-setup test: {0}/{1} steps passed" -f $r.passed, $r.total)
    $r.steps.PSObject.Properties | ForEach-Object { Write-Host (" {0} {1}  {2}" -f ($(if ($_.Value.ok) { "PASS" } else { "FAIL" })), $_.Name, $_.Value.detail) }
} else { Write-Host "No result after $TimeoutMinutes minutes. See $results\log.txt" }
if (-not $KeepOpen) { Get-Process WindowsSandbox*, vmmemWindowsSandbox -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
