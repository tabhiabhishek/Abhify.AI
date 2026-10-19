# =====================================================================
# lock.ps1: (re)generate packaging\windows\requirements.lock, the hash-locked dependency set the
# Windows build installs by default (Python 3.11, Windows x64: the bundled runtime's platform).
#
#   powershell -ExecutionPolicy Bypass -File packaging\windows\lock.ps1                 # resolve latest allowed
#   powershell -ExecutionPolicy Bypass -File packaging\windows\lock.ps1 -Constraints packaging\windows\build-versions-2.0.0-dev.4.txt
#
# Uses a throw-away venv with pip-tools, never the dev venv. Review the diff of requirements.lock
# like code, run the test suite against it (see docs/RELEASE_PROCESS.md, "Dependency lock"), then
# commit it. The lock is NOT valid for other platforms or Python versions (Linux Docker image,
# macOS): those keep using backend\requirements.txt.
# =====================================================================
param([string]$Constraints = "")
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$cName = ""
if ($Constraints) {                      # must live next to this script: only its name ends up in the lock
    $c = (Resolve-Path $Constraints).Path
    if ((Split-Path $c -Parent) -ne $here) { throw "Put the constraints file in packaging\windows\" }
    $cName = Split-Path $c -Leaf
}
$tool = Join-Path ([IO.Path]::GetTempPath()) ("abhify-locktool-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
py -3.11 -m venv $tool
& "$tool\Scripts\python.exe" -m pip install -q --disable-pip-version-check "pip-tools==7.6.2"
$env:CUSTOM_COMPILE_COMMAND = "packaging\windows\lock.ps1"
Push-Location $here
try {
    $pcArgs = @("-q", "--generate-hashes", "--strip-extras", "--allow-unsafe", "--no-emit-index-url",
              "--annotation-style=line", "-o", "requirements.lock")
    if ($cName) { $pcArgs += @("-c", $cName) }
    & "$tool\Scripts\pip-compile.exe" @pcArgs "requirements-lock.in"
    if ($LASTEXITCODE -ne 0) { throw "pip-compile failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
    Remove-Item -Recurse -Force $tool -ErrorAction SilentlyContinue
}
Write-Host "requirements.lock updated: $((Select-String -Path "$here\requirements.lock" -Pattern '^[A-Za-z0-9].*==').Count) packages"
