# =====================================================================
# Runs INSIDE Windows Sandbox (a clean Windows: no Python, no Ollama, no network).
# Installs Abhify.AI silently, exercises it, uninstalls, and writes results to
# C:\abhify\results\result.json (mapped to build\sandbox-results on the host).
# C:\abhify\results\config.json (written by run-sandbox-test.ps1) names the installer
# and, optionally, an earlier installer to upgrade from first.
# =====================================================================
$ErrorActionPreference = "Continue"
$results = [ordered]@{ started = (Get-Date).ToString("s"); steps = [ordered]@{} }
$out = "C:\abhify\results"
function Step($name, $ok, $detail) {
    $results.steps[$name] = [ordered]@{ ok = [bool]$ok; detail = "$detail" }
    "$name : $ok : $detail" | Out-File -Append "$out\log.txt"
}
function Save { $results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 "$out\result.json" }
function Health($headers = @{}) { try { Invoke-RestMethod "http://127.0.0.1:8000/health" -Headers $headers -TimeoutSec 3 } catch { $null } }
function WaitHealth($sec) { for ($i = 0; $i -lt $sec; $i++) { $h = Health; if ($h) { return $h }; Start-Sleep 1 }; return $null }
function Status($url, $headers = @{}) {
    try { (Invoke-WebRequest $url -Headers $headers -UseBasicParsing -TimeoutSec 20).StatusCode }
    catch { [int]$_.Exception.Response.StatusCode.value__ }
}

"--- Abhify.AI sandbox test $(Get-Date)" | Out-File "$out\log.txt"
$cfg = Get-Content "$out\config.json" -Raw | ConvertFrom-Json
$results.config = $cfg
$results.os = (Get-CimInstance Win32_OperatingSystem).Caption + " " + [Environment]::OSVersion.Version
$results.python_preinstalled = [bool](Get-Command python -ErrorAction SilentlyContinue)
$results.ollama_preinstalled = [bool](Get-Command ollama -ErrorAction SilentlyContinue)

$setup = Get-Item $cfg.installer
$inst = "$env:LOCALAPPDATA\Programs\Abhify.AI"
$data = "$env:LOCALAPPDATA\AbhifyAI"
$py = "$inst\runtime\python.exe"
$launcher = "$inst\app\desktop\launcher.pyw"
$env:ABHIFY_NO_BROWSER = "1"
Step "installer_checksum" ((Get-FileHash $setup.FullName -Algorithm SHA256).Hash.ToLower() -eq $cfg.installer_sha256) $cfg.installer_sha256

# 0. optional: real upgrade from an earlier version (N-1 -> N) while the old app is running
if ($cfg.previous_installer) {
    Start-Process $cfg.previous_installer -ArgumentList "/S" -Wait
    Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
    $h0 = WaitHealth 120
    Step "upgrade_old_version_runs" ($h0 -and $h0.version -eq $cfg.upgrade_from) "version=$($h0.version)"
    Invoke-RestMethod "http://127.0.0.1:8000/memory" -Method Post -ContentType "application/json" -Body '{"content":"memory from the previous version","category":"fact"}' | Out-Null
    $t = Measure-Command { Start-Process $setup.FullName -ArgumentList "/S" -Wait }      # app still running
    $reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Abhify.AI" -ErrorAction SilentlyContinue
    Step "upgrade_installs_new_version" ($reg.DisplayVersion -eq $cfg.version) ("{0} -> {1} in {2:N0}s" -f $cfg.upgrade_from, $reg.DisplayVersion, $t.TotalSeconds)
    Step "upgrade_backup_created" ((Get-ChildItem "$data\data\backups\abhify-backup-pre-upgrade-*.zip" -ErrorAction SilentlyContinue).Count -ge 1) ""
    Step "upgrade_old_guide_removed" (-not (Test-Path "$inst\INSTALL.md")) ""
    Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
    $h1 = WaitHealth 120
    Step "upgrade_new_version_runs" ($h1 -and $h1.version -eq $cfg.version) "version=$($h1.version)"
    try {
        $m = Invoke-RestMethod "http://127.0.0.1:8000/memory" -TimeoutSec 10
        Step "upgrade_preserves_memory" (@($m.memories | Where-Object { $_.content -eq "memory from the previous version" }).Count -eq 1) "memories=$($m.memories.Count)"
    } catch { Step "upgrade_preserves_memory" $false $_.Exception.Message }
    & $py $launcher --stop | Out-Null
    Start-Sleep 3
}

# 1. silent install (per-user, no admin prompt)
$t = Measure-Command { Start-Process $setup.FullName -ArgumentList "/S" -Wait }
Step "install_silent" (Test-Path "$inst\runtime\pythonw.exe") ("{0:N0}s, installer {1:N0} MB" -f $t.TotalSeconds, ($setup.Length / 1MB))
Step "start_menu_shortcut" (Test-Path "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Abhify.AI\Abhify.AI.lnk") ""
Step "desktop_shortcut" (Test-Path "$env:USERPROFILE\Desktop\Abhify.AI.lnk") ""
$reg = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Abhify.AI" -ErrorAction SilentlyContinue
Step "uninstall_entry" ($null -ne $reg -and $reg.DisplayVersion -eq $cfg.version) ("DisplayVersion=" + $reg.DisplayVersion)
Step "install_guide_html" ((Test-Path "$inst\INSTALL.html") -and -not (Test-Path "$inst\INSTALL.md")) ""

# 2. bundled runtime works on a machine without Python
$imp = & $py -I -c "import fastapi, chromadb, langgraph, faster_whisper, watchdog, pystray; print('imports ok')" 2>&1
Step "runtime_imports" ($LASTEXITCODE -eq 0) ($imp | Out-String).Trim()
# native code paths that need the Visual C++ runtime: ChromaDB's Rust core and CTranslate2 (Whisper)
$nat = & $py -I -c "import chromadb, tempfile, ctranslate2; c = chromadb.PersistentClient(path=tempfile.mkdtemp()); col = c.create_collection('abhify_test'); col.add(ids=['1'], embeddings=[[0.1, 0.2, 0.3]], documents=['x']); print('chroma rows', col.count(), '| ctranslate2', ctranslate2.__version__, '| cpu devices ok')" 2>&1
Step "native_libs_work" ($LASTEXITCODE -eq 0) ($nat | Out-String).Trim()
# F1: packaged bytecode must not contain the build machine's paths
$scan = & $py -I -B -c "import pathlib,sys; r=pathlib.Path(sys.argv[1]); m=[b'D:\\Abhify.AI', b'd:\\abhify.ai', b'ai_tech_pulse', b'Abhify.AI\\backend\\.venv', b'build\\windows\\staging']; f=list(r.rglob('*.pyc')); bad=[str(p) for p in f if any(x in p.read_bytes() for x in m)]; print(len(f), 'pyc,', len(bad), 'with build paths', bad[:3])" "$inst\runtime" 2>&1
Step "no_build_paths_in_bytecode" (($LASTEXITCODE -eq 0) -and (($scan | Out-String) -match ", 0 with build paths")) ($scan | Out-String).Trim()

# 3. start the app the way the shortcut does (no browser in the sandbox test)
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
$h = WaitHealth 120
Step "app_starts" ($null -ne $h -and $h.version -eq $cfg.version) ($h | ConvertTo-Json -Compress)
Step "missing_ollama_reported" ($h -and $h.ollama -eq $false -and $h.status -eq "degraded") "health.status=$($h.status)"
$results.health = $h

# 4. data separate from program files, diagnostics on unknown hardware
Step "data_dir_outside_install" ((Test-Path "$data\data\abhify.db") -and -not (Test-Path "$inst\backend\data")) $data
try {
    $d = Invoke-RestMethod "http://127.0.0.1:8000/system/diagnostics" -TimeoutSec 20
    Step "diagnostics" $true ("gpus=$($d.gpus.Count) ram=$($d.ram.total_gb)GB recommended=$($d.recommended_profile.profile) schema=$($d.app.schema_version)")
    Step "diagnostics_no_token_hash" (-not (($d | ConvertTo-Json -Depth 8) -match "token_sha256")) ""
} catch { Step "diagnostics" $false $_.Exception.Message }
try {
    Invoke-RestMethod "http://127.0.0.1:8000/setup/models/pull" -Method Post -ContentType "application/json" -Body '{"model":"qwen2.5:1.5b"}' | Out-Null
    Step "no_download_without_consent" $false "pull accepted without confirm!"
} catch { Step "no_download_without_consent" ($_.Exception.Response.StatusCode.value__ -eq 400) "HTTP $($_.Exception.Response.StatusCode.value__)" }
try {
    $r = Invoke-WebRequest "http://127.0.0.1:8000/" -UseBasicParsing -TimeoutSec 10
    Step "pwa_served" ($r.Content -match "<title>Abhify.AI</title>") "HTTP $($r.StatusCode)"
} catch { Step "pwa_served" $false $_.Exception.Message }

# 4b. companion privacy on the installed build. A request relayed through a tunnel (forwarding header)
#     counts as a phone; companion is off by default, then on with a real token.
$tunnel = @{ "X-Forwarded-For" = "100.64.0.7" }
$hr = Health $tunnel
Step "remote_unpaired_health_minimal" ($hr -and $hr.authorized -eq $false -and $null -eq $hr.llm_model -and $null -eq $hr.knowledge_chunks) ($hr | ConvertTo-Json -Compress)
$en = Invoke-RestMethod "http://127.0.0.1:8000/companion/enable" -Method Post
$auth = @{ "X-Forwarded-For" = "100.64.0.7"; "Authorization" = "Bearer $($en.token)" }
$codes = @{}
foreach ($p in "/system/diagnostics", "/backup", "/setup/profiles", "/setup/models/pull", "/system/update-check", "/docs") { $codes[$p] = Status "http://127.0.0.1:8000$p" $auth }
Step "remote_token_cannot_read_system" (@($codes.Values | Where-Object { $_ -ne 403 }).Count -eq 0) (($codes.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join " ")
Step "remote_token_can_use_memory" ((Status "http://127.0.0.1:8000/memory" $auth) -eq 200) ""
Step "remote_wrong_token_rejected" ((Status "http://127.0.0.1:8000/memory" @{ "X-Forwarded-For" = "100.64.0.7"; "Authorization" = "Bearer wrong-token-000000" }) -eq 401) ""
Status "http://127.0.0.1:8000/memory?token=SANDBOX-SECRET-QUERY-VALUE" $tunnel | Out-Null
Invoke-RestMethod "http://127.0.0.1:8000/companion/disable" -Method Post | Out-Null
Start-Sleep 1
$logText = (Get-ChildItem "$data\logs\*.log*" -ErrorAction SilentlyContinue | Get-Content -Raw) -join "`n"
Step "logs_contain_no_tokens" (-not ($logText -match "SANDBOX-SECRET-QUERY-VALUE") -and -not ($logText -match [regex]::Escape($en.token))) ""

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
    Step "reinstall_preserves_memory" (@($m.memories | Where-Object { $_.content -like "sandbox memory*" }).Count -eq 1) "memories=$($m.memories.Count)"
} catch { Step "reinstall_preserves_memory" $false $_.Exception.Message }
& $py $launcher --stop | Out-Null

Copy-Item "$data\logs\*.log" $out -ErrorAction SilentlyContinue
$results.finished = (Get-Date).ToString("s")
$results.passed = @($results.steps.Values | Where-Object { $_.ok }).Count
$results.total = $results.steps.Count
Save
"DONE" | Out-File "$out\done.txt"
