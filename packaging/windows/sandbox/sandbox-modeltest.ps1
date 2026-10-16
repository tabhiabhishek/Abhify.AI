# =====================================================================
# Runs INSIDE Windows Sandbox (clean Windows, networking ON, no GPU): clean-PC model setup.
# Writes C:\abhify\results\result.json. Every step records what actually happened; a model
# download only counts as successful if inference afterwards really answers.
# =====================================================================
$ErrorActionPreference = "Continue"
$out = "C:\abhify\results"; $in = "$out\inputs"
$results = [ordered]@{ started = (Get-Date).ToString("s"); steps = [ordered]@{}; timeline = @() }
function Step($name, $ok, $detail) {
    $results.steps[$name] = [ordered]@{ ok = [bool]$ok; detail = "$detail" }
    "$(Get-Date -Format HH:mm:ss) $name : $ok : $detail" | Out-File -Append "$out\log.txt"
    Save
}
function Note($t) { "$(Get-Date -Format HH:mm:ss) NOTE $t" | Out-File -Append "$out\log.txt" }
function Save {
    $results.passed = @($results.steps.Values | Where-Object { $_.ok }).Count; $results.total = $results.steps.Count
    $results | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$out\result.json"
}
$api = "http://127.0.0.1:8000"
function J($path, $method = "GET", $body = $null, $timeout = 60) {
    try {
        if ($body) { Invoke-RestMethod "$api$path" -Method $method -ContentType "application/json" -Body ($body | ConvertTo-Json -Compress) -TimeoutSec $timeout }
        else { Invoke-RestMethod "$api$path" -Method $method -TimeoutSec $timeout }
    } catch { [pscustomobject]@{ _status = [int]$_.Exception.Response.StatusCode.value__; _error = $_.ErrorDetails.Message } }
}
function WaitFor($sec, [scriptblock]$cond) { for ($i = 0; $i -lt $sec; $i++) { $v = & $cond; if ($v) { return $v }; Start-Sleep 1 }; return $null }
function OllamaUp { try { Invoke-RestMethod "http://127.0.0.1:11434/api/version" -TimeoutSec 3 } catch { $null } }
function PullUntilDone($model, $sec) {
    $s = WaitFor $sec { $p = (J "/setup/models/pull").pulls.$model; if ($p -and $p.status -ne "downloading") { $p } }
    return $s
}

"--- Abhify.AI clean-PC model-setup test $(Get-Date)" | Out-File "$out\log.txt"
$cfg = Get-Content "$out\config.json" -Raw | ConvertFrom-Json
$results.config = $cfg
$results.os = (Get-CimInstance Win32_OperatingSystem).Caption + " " + [Environment]::OSVersion.Version
$results.ram_gb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$results.gpu = (Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join ", "
$results.python_preinstalled = [bool](Get-Command python -ErrorAction SilentlyContinue)
$results.ollama_preinstalled = [bool](Get-Command ollama -ErrorAction SilentlyContinue)
$inst = "$env:LOCALAPPDATA\Programs\Abhify.AI"; $py = "$inst\runtime\python.exe"; $launcher = "$inst\app\desktop\launcher.pyw"
$env:ABHIFY_NO_BROWSER = "1"

Step "installer_checksum" ((Get-FileHash "$in\Abhify.AI-Setup.exe" -Algorithm SHA256).Hash.ToLower() -eq $cfg.installer_sha256) $cfg.installer_sha256
Start-Process "$in\Abhify.AI-Setup.exe" -ArgumentList "/S" -Wait
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
$h = WaitFor 120 { try { Invoke-RestMethod "$api/health" -TimeoutSec 3 } catch { $null } }
Step "app_installed_and_running" ($h -and $h.version -eq $cfg.version) "version=$($h.version)"

# ---- B1: missing Ollama
Step "missing_ollama_detected" ($h.ollama -eq $false -and $h.status -eq "degraded") "ollama=$($h.ollama) status=$($h.status)"
$d = J "/system/diagnostics"
Step "missing_ollama_guidance_data" ($d.ollama.reachable -eq $false -and $null -ne $d.ollama.installed) "reachable=$($d.ollama.reachable) installed=$($d.ollama.installed)"
Step "cpu_only_profile_recommended" ($d.gpus.Count -eq 0 -and $d.recommended_profile.profile -eq "minimal") "gpus=$($d.gpus.Count) ram=$($d.ram.total_gb)GB recommended=$($d.recommended_profile.profile): $($d.recommended_profile.reason)"

# ---- install Ollama (signed installer, checked on the host)
# Wait for the installer process only: Start-Process -Wait would also wait for the Ollama tray app
# the installer launches at the end, which never exits.
$t = Measure-Command {
    $p = Start-Process "$in\OllamaSetup.exe" -ArgumentList "/VERYSILENT", "/NORESTART", "/SUPPRESSMSGBOXES" -PassThru
    if (-not $p.WaitForExit(900000)) { Note "Ollama installer still running after 15 min" }
}
Note "Ollama installer exit code: $($p.ExitCode)"
$ollamaExe = Get-ChildItem "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" -ErrorAction SilentlyContinue
if (-not (WaitFor 60 { OllamaUp })) {
    Note "Ollama not serving after install; starting 'ollama serve'"
    Start-Process $ollamaExe.FullName -ArgumentList "serve" -WindowStyle Hidden
}
$ov = WaitFor 60 { OllamaUp }
Step "ollama_installed_and_serving" ($null -ne $ov) ("install {0:N0}s, version {1}" -f $t.TotalSeconds, $ov.version)
$h = WaitFor 60 { $x = J "/health"; if ($x.ollama) { $x } }
Step "app_detects_ollama" ($h.ollama -eq $true -and $h.llm_ready -eq $false) "ollama=$($h.ollama) llm_ready=$($h.llm_ready)"

# ---- B2: size + disk information before consent
J "/setup/profile" "POST" @{ profile = "minimal" } | Out-Null
$p = J "/setup/profiles"
$min = $p.profiles.minimal
Step "size_and_disk_shown_before_download" (@($min.disk).Count -ge 1 -and @($min.disk | Where-Object { $_.estimated_download_gb -gt 0 -and $_.free_gb -gt 0 }).Count -eq @($min.disk).Count) (($min.disk | ForEach-Object { "$($_.model): ~$($_.estimated_download_gb) GB, needs $($_.required_free_gb) GB free, $($_.free_gb) GB free, ok=$($_.ok)" }) -join " | ")

# ---- B3: consent
$r = J "/setup/models/pull" "POST" @{ model = "nomic-embed-text" }
Step "no_download_without_consent" ($r._status -eq 400) "HTTP $($r._status)"
$tags0 = (Invoke-RestMethod "http://127.0.0.1:11434/api/tags").models.Count
Step "nothing_downloaded_without_consent" ($tags0 -eq 0) "models in Ollama: $tags0"

# ---- B4: network error during a download (outbound traffic of ollama.exe blocked by the firewall)
netsh advfirewall firewall add rule name="abhify-test-block-ollama" dir=out action=block program="$($ollamaExe.FullName)" | Out-Null
J "/setup/models/pull" "POST" @{ model = "all-minilm"; confirm = $true } | Out-Null
$s = PullUntilDone "all-minilm" 240
Step "network_error_reported" ($s.status -eq "failed" -and $s.message) "status=$($s.status) message=$($s.message)"
netsh advfirewall firewall delete rule name="abhify-test-block-ollama" | Out-Null

# ---- download the embedding model (consent given)
$t = Measure-Command { J "/setup/models/pull" "POST" @{ model = "nomic-embed-text"; confirm = $true } | Out-Null; $s = PullUntilDone "nomic-embed-text" 1200 }
$tags = (Invoke-RestMethod "http://127.0.0.1:11434/api/tags").models.name
Step "embed_model_downloaded" ($s.status -eq "done" -and ($tags -match "nomic-embed-text")) ("status={0} in {1:N0}s; ollama has: {2}" -f $s.status, $t.TotalSeconds, ($tags -join ","))

# ---- B5: interrupted download (Ollama killed mid-way), then resume
$llm = $min.llm
J "/setup/models/pull" "POST" @{ model = $llm; confirm = $true } | Out-Null
$mid = WaitFor 1200 { $x = (J "/setup/models/pull").pulls.$llm; if ($x.total -gt 0 -and $x.completed -gt 0.25 * $x.total) { $x } }
Note ("killing Ollama at {0:N0}%" -f (100 * $mid.completed / [math]::Max($mid.total, 1)))
Get-Process ollama* -ErrorAction SilentlyContinue | Stop-Process -Force
$s = PullUntilDone $llm 120
$results.interrupt_state = $s
Step "interrupted_download_reported_as_failed" ($s.status -eq "failed") "status=$($s.status) message=$($s.message)"
Start-Process $ollamaExe.FullName -ArgumentList "serve" -WindowStyle Hidden
WaitFor 60 { OllamaUp } | Out-Null
$pp = J "/setup/profiles"
Step "interrupted_model_not_shown_as_installed" (@($pp.profiles.minimal.missing) -contains $llm) "missing=$($pp.profiles.minimal.missing -join ',')"
$t = Measure-Command { J "/setup/models/pull" "POST" @{ model = $llm; confirm = $true } | Out-Null; $s = PullUntilDone $llm 2400 }
$tags = (Invoke-RestMethod "http://127.0.0.1:11434/api/tags").models.name
Step "download_resumes_and_completes" ($s.status -eq "done" -and ($tags -contains $llm)) ("status={0} in {1:N0}s; ollama has: {2}" -f $s.status, $t.TotalSeconds, ($tags -join ","))

# ---- B6: verify inference after installation (CPU only)
$h = J "/health"
Step "health_ai_ready" ($h.ollama -and $h.llm_ready -and $h.embed_ready) "llm_ready=$($h.llm_ready) embed_ready=$($h.embed_ready)"
$tst = J "/setup/test" "POST" $null 300
Step "inference_verified" ($tst.ok -eq $true -and $tst.reply) "ok=$($tst.ok) reply='$($tst.reply)' seconds=$($tst.seconds) error=$($tst.error)"
$c = J "/chat" "POST" @{ message = "In one short sentence: what is overfitting?" } 600
Step "chat_answers_on_cpu" ($c.answer -and $c.answer.Length -gt 10) ("{0} chars: {1}" -f $c.answer.Length, ($c.answer -replace "\s+", " ").Substring(0, [math]::Min(120, $c.answer.Length)))

# ---- B7: a model that can't start (profile whose model isn't installed)
J "/setup/profile" "POST" @{ profile = "quality" } | Out-Null
$bad = J "/setup/test" "POST" $null 120
Step "model_start_failure_reported" ($bad.ok -eq $false -and $bad.error) "ok=$($bad.ok) error=$($bad.error)"
J "/setup/profile" "POST" @{ profile = "minimal" } | Out-Null
J "/setup/complete" "POST" | Out-Null

# ---- knowledge folder: index a harmless PDF and ask about it
$st = J "/knowledge/watcher/start" "POST"
$kdir = $st.folder
Copy-Item "$in\sample.pdf" (Join-Path $kdir "sample.pdf")
$f = WaitFor 600 { $x = (J "/knowledge/files").files | Where-Object { $_.path -eq "sample.pdf" }; if ($x -and $x.status -in "indexed", "failed", "skipped") { $x } }
Step "knowledge_pdf_indexed" ($f.status -eq "indexed" -and $f.chunks -ge 1) "status=$($f.status) chunks=$($f.chunks) error=$($f.error)"
$c2 = J "/chat" "POST" @{ message = "Search my documents: what is the test codeword?" } 600
$results.doc_answer = $c2
Step "document_question_answered" (($c2.answer -match "BLUE-HERON-9") -and (($c2.sources -join " ") -match "sample.pdf")) ("answer='{0}' sources={1} tools={2}" -f (($c2.answer -replace "\s+", " ").Substring(0, [math]::Min(150, $c2.answer.Length))), ($c2.sources -join ","), ($c2.tools_used -join ","))
J "/memory" "POST" @{ content = "model-setup test memory"; category = "fact" } | Out-Null

# ---- restart persistence
& $py $launcher --stop | Out-Null
Start-Sleep 3
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""
$h = WaitFor 120 { $x = J "/health"; if ($x.version) { $x } }
$conv = (J "/conversations").conversations.Count
$mem = @((J "/memory").memories | Where-Object { $_.content -eq "model-setup test memory" }).Count
$kn = J "/knowledge/status"
Step "restart_persistence" ($conv -ge 1 -and $mem -eq 1 -and $kn.chunks -ge 1 -and $h.llm_ready) "conversations=$conv memory=$mem chunks=$($kn.chunks) watcher_running=$($kn.running) llm_ready=$($h.llm_ready)"
& $py $launcher --stop | Out-Null

Copy-Item "$env:LOCALAPPDATA\AbhifyAI\logs\*.log" $out -ErrorAction SilentlyContinue
$results.finished = (Get-Date).ToString("s")
Save
"DONE" | Out-File "$out\done.txt"
