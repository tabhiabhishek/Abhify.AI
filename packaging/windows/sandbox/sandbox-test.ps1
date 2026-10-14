# =====================================================================
# Runs INSIDE Windows Sandbox (a clean Windows: no Python, no Ollama, no network).
# Installs Abhify.AI silently, exercises it, uninstalls, and writes results to
# C:\abhify\results\result.json (mapped to build\sandbox-results on the host).
# =====================================================================
$ErrorActionPreference = "Continue"
$results = [ordered]@{ started = (Get-Date).ToString("s"); steps = [ordered]@{} }
$out = "C:\abhify\results"
function Step($name, $ok, $detail) {
    $results.steps[$name] = [ordered]@{ ok = [bool]$ok; detail = "$detail" }
    "$name : $ok : $detail" | Out-File -Append "$out\log.txt"
}
function Save { $results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 "$out\result.json" }
function Health { try { Invoke-RestMethod "http://127.0.0.1:8000/health" -TimeoutSec 3 } catch { $null } }
function WaitHealth($sec) { for ($i = 0; $i -lt $sec; $i++) { $h = Health; if ($h) { return $h }; Start-Sleep 1 }; return $null }

"--- Abhify.AI sandbox test $(Get-Date)" | Out-File "$out\log.txt"
$results.os = (Get-CimInstance Win32_OperatingSystem).Caption + " " + [Environment]::OSVersion.Version
$results.python_preinstalled = [bool](Get-Command python -ErrorAction SilentlyContinue)
$results.ollama_preinstalled = [bool](Get-Command ollama -ErrorAction SilentlyContinue)

$setup = Get-ChildItem "C:\abhify\dist\Abhify.AI-Setup-*.exe" | Select-Object -First 1
$inst = "$env:LOCALAPPDATA\Programs\Abhify.AI"
$data = "$env:LOCALAPPDATA\AbhifyAI"
$py = "$inst\runtime\python.exe"
$launcher = "$inst\app\desktop\launcher.pyw"

# 1. silent install (per-user, no admin prompt)
$t = Measure-Command { Start-Process $setup.FullName -ArgumentList "/S" -Wait }
Step "install_silent" (Test-Path "$inst\runtime\pythonw.exe") ("{0:N0}s, installer {1:N0} MB" -f $t.TotalSeconds, ($setup.Length / 1MB))
Step "start_menu_shortcut" (Test-Path "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Abhify.AI\Abhify.AI.lnk") ""
Step "desktop_shortcut" (Test-Path "$env:USERPROFILE\Desktop\Abhify.AI.lnk") ""
$reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Abhify.AI" -ErrorAction SilentlyContinue
Step "uninstall_entry" ($null -ne $reg) ("DisplayVersion=" + $reg.DisplayVersion)

# 2. bundled runtime works on a machine without Python
$imp = & $py -I -c "import fastapi, chromadb, langgraph, faster_whisper, watchdog, pystray; print('imports ok')" 2>&1
Step "runtime_imports" ($LASTEXITCODE -eq 0) ($imp | Out-String).Trim()

# 3. start the app the way the shortcut does (no browser in the sandbox test)
$env:ABHIFY_NO_BROWSER = "1"
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
$h = WaitHealth 120
Step "app_starts" ($null -ne $h) ($h | ConvertTo-Json -Compress)
Step "missing_ollama_reported" ($h -and $h.ollama -eq $false -and $h.status -eq "degraded") "health.status=$($h.status)"
$results.health = $h

# 4. data separate from program files, diagnostics on unknown hardware
Step "data_dir_outside_install" ((Test-Path "$data\data\abhify.db") -and -not (Test-Path "$inst\backend\data")) $data
try {
    $d = Invoke-RestMethod "http://127.0.0.1:8000/system/diagnostics" -TimeoutSec 20
    Step "diagnostics" $true ("gpus=$($d.gpus.Count) ram=$($d.ram.total_gb)GB recommended=$($d.recommended_profile.profile) schema=$($d.app.schema_version)")
} catch { Step "diagnostics" $false $_.Exception.Message }
try {
    Invoke-RestMethod "http://127.0.0.1:8000/setup/models/pull" -Method Post -ContentType "application/json" -Body '{"model":"qwen2.5:1.5b"}' | Out-Null
    Step "no_download_without_consent" $false "pull accepted without confirm!"
} catch { Step "no_download_without_consent" ($_.Exception.Response.StatusCode.value__ -eq 400) "HTTP $($_.Exception.Response.StatusCode.value__)" }
try {
    $r = Invoke-WebRequest "http://127.0.0.1:8000/" -UseBasicParsing -TimeoutSec 10
    Step "pwa_served" ($r.Content -match "<title>Abhify.AI</title>") "HTTP $($r.StatusCode)"
} catch { Step "pwa_served" $false $_.Exception.Message }

# 5. personal data written, single instance, clean stop
Invoke-RestMethod "http://127.0.0.1:8000/memory" -Method Post -ContentType "application/json" -Body '{"content":"sandbox memory must survive reinstall","category":"fact"}' | Out-Null
$procs = (Get-Process pythonw -ErrorAction SilentlyContinue).Count
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""; Start-Sleep 8
Step "single_instance" ((Get-Process pythonw -ErrorAction SilentlyContinue).Count -eq $procs) "pythonw processes before=$procs after=$((Get-Process pythonw -ErrorAction SilentlyContinue).Count)"
& $py $launcher --stop | Out-Null
Start-Sleep 3
Step "clean_stop" ($null -eq (Health)) ""

# 6. backup command used by upgrades
$b = & $py $launcher --backup sandbox 2>&1
Step "backup_command" ((Get-ChildItem "$data\data\backups\abhify-backup-sandbox-*.zip" -ErrorAction SilentlyContinue).Count -ge 1) ($b | Out-String).Trim()

# 7. silent uninstall keeps user data
Start-Process "$inst\Uninstall.exe" -ArgumentList "/S", "_?=$inst" -Wait
Start-Sleep 2
Step "uninstall_removes_program" (-not (Test-Path "$inst\runtime")) ""
Step "uninstall_keeps_data" (Test-Path "$data\data\abhify.db") ""
Step "uninstall_removes_entry" (-not (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Abhify.AI")) ""

# 8. reinstall picks the data up again
Start-Process $setup.FullName -ArgumentList "/S" -Wait
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
$h2 = WaitHealth 120
try {
    $m = Invoke-RestMethod "http://127.0.0.1:8000/memory" -TimeoutSec 10
    Step "reinstall_preserves_memory" (($m.memories | Where-Object { $_.content -like "sandbox memory*" }).Count -eq 1) "memories=$($m.memories.Count)"
} catch { Step "reinstall_preserves_memory" $false $_.Exception.Message }
& $py $launcher --stop | Out-Null

Copy-Item "$data\logs\*.log" $out -ErrorAction SilentlyContinue
$results.finished = (Get-Date).ToString("s")
$results.passed = @($results.steps.Values | Where-Object { $_.ok }).Count
$results.total = $results.steps.Count
Save
"DONE" | Out-File "$out\done.txt"
