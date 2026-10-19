# =====================================================================
# Runs INSIDE Windows Sandbox (networking ON): dev.6 end-to-end, everything real except the update
# SOURCE, which is a local test HTTPS server (no real release channel exists).
#   C:\abhify\installer  dev.6 installer (read-only)      C:\abhify\assets  test CA/cert, test update server,
#   C:\abhify\results    results (read-write)                                signed TEST manifests, public test key, sample.pdf
# Each step records what actually happened; a step that couldn't be exercised says so.
# =====================================================================
$ErrorActionPreference = "Continue"
$out = "C:\abhify\results"; $assets = "C:\abhify\assets"
$R = [ordered]@{ started = (Get-Date).ToString("s"); steps = [ordered]@{} }
function Save { $R.passed = @($R.steps.Values | Where-Object { $_.ok }).Count; $R.total = $R.steps.Count; $R | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$out\result.json" }
function Step($n, $ok, $d) { $R.steps[$n] = [ordered]@{ ok = [bool]$ok; detail = "$d" }; "$(Get-Date -Format HH:mm:ss) $n : $ok : $d" | Out-File -Append "$out\log.txt"; Save }
function Note($t) { "$(Get-Date -Format HH:mm:ss) NOTE $t" | Out-File -Append "$out\log.txt" }
$inst = "$env:LOCALAPPDATA\Programs\Abhify.AI"; $py = "$inst\runtime\python.exe"; $launcher = "$inst\app\desktop\launcher.pyw"
$data = "$env:LOCALAPPDATA\AbhifyAI"
function Port { try { (Get-Content "$data\run\backend.json" -Raw | ConvertFrom-Json).port } catch { 8000 } }
function Url($p) { "http://127.0.0.1:$(Port)$p" }
function J($p, $m = "GET", $b = $null, $t = 120, $h = @{}) {
    try {
        if ($null -ne $b) { Invoke-RestMethod (Url $p) -Method $m -Headers $h -ContentType "application/json" -Body ($b | ConvertTo-Json -Compress -Depth 5) -TimeoutSec $t }
        else { Invoke-RestMethod (Url $p) -Method $m -Headers $h -TimeoutSec $t }
    } catch { [pscustomobject]@{ _status = [int]$_.Exception.Response.StatusCode.value__; _error = "$($_.ErrorDetails.Message)" } }
}
function WaitUp($s = 120) { for ($i = 0; $i -lt $s; $i++) { Start-Sleep 1; try { $h = Invoke-RestMethod (Url "/health") -TimeoutSec 5; if ($h) { return $h } } catch {} }; $null }
function StartApp { Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$launcher`""; WaitUp }
function StopApp { & $py $launcher --stop | Out-Null; Start-Sleep 4 }
function OllamaExe { "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" }
"--- dev.6 E2E $(Get-Date)" | Out-File "$out\log.txt"
$R.os = (Get-CimInstance Win32_OperatingSystem).Caption + " " + [Environment]::OSVersion.Version
$R.gpu = (Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join ", "
$R.ram_gb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
$env:ABHIFY_NO_BROWSER = "1"

# ---------------------------------------------------------------- install
$setup = Get-ChildItem "C:\abhify\installer\Abhify.AI-Setup-*.exe" | Select-Object -First 1
$cfg = Get-Content "$assets\expected.json" -Raw | ConvertFrom-Json
Step "installer_checksum" ((Get-FileHash $setup.FullName).Hash.ToLower() -eq $cfg.installer_sha256) $cfg.installer_sha256
$p = Start-Process $setup.FullName -ArgumentList "/S" -PassThru; $null = $p.WaitForExit(600000)
$h = StartApp
Step "app_starts" ($h.version -eq $cfg.version) "version=$($h.version) port=$(Port) ollama=$($h.ollama)"

# ---------------------------------------------------------------- A. automatic setup (real downloads)
$plan = J "/setup/plan"
$acts = ($plan.components | ForEach-Object { "$($_.id)=$($_.action)" }) -join " "
Step "plan_lists_components" ($plan.components[0].action -eq "install" -and $plan.download_gb -gt 2) "$acts; download_gb=$($plan.download_gb); profile=$($plan.profile) ($($plan.reason))"
Step "nothing_downloaded_before_confirm" (-not (Test-Path "$data\downloads\OllamaSetup.exe*") -and -not (Test-Path (OllamaExe))) ""
$r = J "/setup/auto" "POST" @{ confirm = $false }
Step "confirmation_required" ($r._status -eq 400) "HTTP $($r._status)"

# real disk-full: fill C: so that ~0.5 GB stays free
$free = (Get-PSDrive C).Free; $fill = [int64]($free - 500MB)
fsutil file createnew C:\abhify-fill.bin $fill | Out-Null
$pf = J "/setup/plan"; $sf = J "/setup/auto" "POST" @{ confirm = $true }
Step "disk_full_refused_before_download" (($pf.problems -join " ") -match "disk space" -and $sf._status -eq 400 -and -not (Test-Path "$data\downloads\OllamaSetup.exe*")) "free during test=$([math]::Round((Get-PSDrive C).Free/1GB,2)) GB; $($pf.problems -join ' | ')"
Remove-Item C:\abhify-fill.bin -Force

$r = J "/setup/auto" "POST" @{ confirm = $true }
Step "auto_setup_started" ($r.status -eq "running") "status=$($r.status)"
$seen = @{}; $netTested = $false; $pauseTested = $false; $deadline = (Get-Date).AddMinutes(75)
while ((Get-Date) -lt $deadline) {
    $j = J "/setup/auto"
    if ($j.step) { $seen[$j.step] = $true }
    $llm = $plan.components[1].id.Substring(6)
    $pull = $j.pulls.$llm
    if (-not $netTested -and $j.status -eq "running" -and $pull -and $pull.total -gt 0 -and $pull.completed -gt 0.2 * $pull.total) {
        Note ("network adapter OFF at {0:N0}% of {1}" -f (100 * $pull.completed / $pull.total), $llm)
        Get-NetAdapter | Disable-NetAdapter -Confirm:$false
        $t0 = Get-Date
        do { Start-Sleep 5; $j2 = J "/setup/auto" } while ($j2.status -eq "running" -and ((Get-Date) - $t0).TotalSeconds -lt 300)
        $R.network_outage = @{ status_after = $j2.status; seconds = [int]((Get-Date) - $t0).TotalSeconds; message = $j2.message; error = $j2.error }
        Get-NetAdapter | Enable-NetAdapter -Confirm:$false; Start-Sleep 15
        if ($j2.status -ne "running") {
            Step "network_outage_reported" ($j2.status -eq "failed") "after $($R.network_outage.seconds)s: $($j2.status): $($j2.error)"
            $rr = J "/setup/auto" "POST" @{ confirm = $true }
            Step "retry_after_outage" ($rr.status -eq "running") "status=$($rr.status)"
        } else {
            Step "network_outage_reported" $true "Ollama kept retrying through a 300 s outage (job still running, no false success); network restored"
        }
        $netTested = $true
    }
    if ($netTested -and -not $pauseTested -and $j.status -eq "running" -and $pull -and $pull.total -gt 0 -and $pull.completed -gt 0.5 * $pull.total -and $pull.status -eq "downloading") {
        $before = $pull.completed
        J "/setup/auto/pause" "POST" | Out-Null
        $t0 = Get-Date; do { Start-Sleep 2; $jp = J "/setup/auto" } while ($jp.status -eq "running" -and ((Get-Date) - $t0).TotalSeconds -lt 60)
        Step "pause" ($jp.status -eq "paused") "status=$($jp.status) at $([math]::Round(100*$before/$pull.total))%"
        $rr = J "/setup/auto" "POST" @{ confirm = $true }
        Start-Sleep 10; $after = (J "/setup/auto").pulls.$llm
        Step "resume_continues" ($rr.status -eq "running" -and $after.completed -ge $before * 0.95) ("resumed at {0:N0} MB (paused at {1:N0} MB)" -f ($after.completed / 1MB), ($before / 1MB))
        $pauseTested = $true
    }
    if ($j.status -in "done", "cancelled") { break }
    if ($j.status -eq "failed") { Note "failed: $($j.error)"; break }
    Start-Sleep 3
}
$j = J "/setup/auto"
Step "auto_setup_completed" ($j.status -eq "done") "status=$($j.status) steps_seen=$($seen.Keys -join ',') error=$($j.error)"
Step "real_ollama_installer_verified_and_installed" ($seen["ollama-verify"] -and $seen["ollama-install"] -and (Test-Path (OllamaExe))) "steps: $(@('ollama-download','ollama-verify','ollama-install','ollama-start') | Where-Object { $seen[$_] })"
$comp = Get-Content "$data\config\components.json" -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
Step "components_recorded" ($comp -and $comp.ollama.version -and @($comp.models.PSObject.Properties).Count -ge 2) "ollama=$($comp.ollama.version) models=$(($comp.models.PSObject.Properties | ForEach-Object { $_.Name + '@' + $_.Value.digest.Substring(0, [math]::Min(19, $_.Value.digest.Length)) }) -join ', ') validation=$($comp.validation | ConvertTo-Json -Compress)"
$p2 = J "/setup/plan"
Step "second_plan_downloads_nothing" ($p2.nothing_to_do -and $p2.download_gb -eq 0) "nothing_to_do=$($p2.nothing_to_do)"
Step "pause_and_outage_exercised" ($pauseTested -and $netTested) "pause=$pauseTested outage=$netTested"

# ---------------------------------------------------------------- B. real use: chat, documents, memory (CPU)
$up = (& curl.exe -s -F "file=@$assets\sample.pdf;type=application/pdf" (Url "/knowledge/upload")) | ConvertFrom-Json
Step "document_indexed" ($up.chunks -ge 1) "chunks=$($up.chunks)"
J "/memory" "POST" @{ content = "E2E memory: prefers short answers"; category = "preference" } | Out-Null
$c = J "/chat" "POST" @{ message = "Search my documents: what is the test codeword?" } 600
Step "chat_with_document_citation" (($c.answer -match "BLUE-HERON-9") -and (($c.sources -join " ") -match "sample.pdf")) "answer='$($c.answer)' sources=$($c.sources -join ',')"
$ps = (& (OllamaExe) ps | Out-String) -replace "\s+", " "
Step "inference_on_cpu_no_gpu" ($ps -match "CPU" -or $R.gpu -notmatch "NVIDIA") "ollama ps: $ps | gpu: $($R.gpu)"

# ---------------------------------------------------------------- C. membership (requests through a tunnel header count as remote)
$en = J "/companion/enable" "POST"
$inv = J "/companion/invitations" "POST" @{ label = "E2E guest"; role = "member"; hours = 24 }
$tunnel = @{ "X-Forwarded-For" = "100.64.0.9" }
$red = J "/companion/redeem" "POST" @{ code = $inv.code; device_name = "E2E phone" } 30 $tunnel
$g = @{ "X-Forwarded-For" = "100.64.0.9"; Authorization = "Bearer $($red.token)" }
$o = @{ "X-Forwarded-For" = "100.64.0.9"; Authorization = "Bearer $($en.token)" }
$again = J "/companion/redeem" "POST" @{ code = $inv.code; device_name = "x" } 30 $tunnel
Step "invitation_single_use" ($red.role -eq "member" -and $again._status -eq 400) "redeem role=$($red.role); second use HTTP $($again._status)"
Step "guest_denied_owner_data" ((J "/memory" "GET" $null 30 $g)._status -eq 403 -and (J "/conversations" "GET" $null 30 $g)._status -eq 403) ""
$gc = J "/chat" "POST" @{ message = "What is overfitting? One sentence." } 600 $g
Step "guest_chat_works_without_owner_data" ($gc.answer -and $gc.guest -eq $true -and -not ($gc.answer -match "BLUE-HERON")) "guest=$($gc.guest)"
Step "owner_device_sees_own_data" (@((J "/memory" "GET" $null 30 $o).memories).Count -ge 1) ""
Step "remote_cannot_change_registration" ((J "/companion/registration-mode" "POST" @{ mode = "public"; confirm = $true } 30 $o)._status -eq 403) ""
J "/companion/members/$($red.member_id)/revoke" "POST" | Out-Null
Step "revoked_guest_cut_off" ((J "/news" "GET" $null 30 $g)._status -eq 401) ""

# ---------------------------------------------------------------- D. offline (Windows Firewall blocks all outbound; loopback still works)
$before = @{ mem = @((J "/memory").memories).Count; conv = @((J "/conversations").conversations).Count; chunks = (J "/knowledge").chunks }
netsh advfirewall set allprofiles firewallpolicy blockinbound,blockoutbound | Out-Null
$blocked = -not (Test-NetConnection 1.1.1.1 -Port 443 -WarningAction SilentlyContinue -InformationLevel Quiet)
StopApp; $h = StartApp
$oc = J "/chat" "POST" @{ message = "Search my documents: what is the test codeword?" } 600
$after = @{ mem = @((J "/memory").memories).Count; conv = @((J "/conversations").conversations).Count; chunks = (J "/knowledge").chunks }
Step "offline_restart_and_core_features" ($blocked -and $h -and $h.internet -eq $false -and ($oc.answer -match "BLUE-HERON-9")) "outbound blocked=$blocked internet=$($h.internet) answer='$($oc.answer)'"
Step "offline_data_persisted" ($after.mem -eq $before.mem -and $after.conv -ge $before.conv -and $after.chunks -eq $before.chunks) "before=$($before | ConvertTo-Json -Compress) after=$($after | ConvertTo-Json -Compress)"
netsh advfirewall set allprofiles firewallpolicy blockinbound,allowoutbound | Out-Null

# ---------------------------------------------------------------- E. signed updates against the local TEST HTTPS source
$pkgCopy = "C:\abhify-test-pkg.exe"; Copy-Item $setup.FullName $pkgCopy
$srvLog = "$out\update-server-requests.log"
$srv = Start-Process $py -ArgumentList "`"$assets\update_server.py`" `"$pkgCopy`" `"$srvLog`"" -PassThru -WindowStyle Hidden
Start-Sleep 3
function SrvSet($m, $p) { Invoke-WebRequest "https://localhost:8443/__set?m=$m&p=$p" -UseBasicParsing -TimeoutSec 10 | Out-Null }
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }   # test script only (the APP verifies via SSL_CERT_FILE)
StopApp
$st = Get-Content "$data\config\settings.json" -Raw | ConvertFrom-Json
$st.updates | Add-Member -Force -NotePropertyName manifest_url -NotePropertyValue "https://localhost:8443/latest.json"
$st.updates | Add-Member -Force -NotePropertyName trusted_keys -NotePropertyValue (Get-Content "$assets\trusted_key.json" -Raw | ConvertFrom-Json)
$st | ConvertTo-Json -Depth 10 | Out-File -Encoding utf8 "$data\config\settings.json"
$env:SSL_CERT_FILE = "$assets\ca.pem"                                                    # the app trusts the TEST CA only for this run
$h = StartApp
SrvSet "valid" "normal"
$u = J "/updates/check" "POST"
Step "update_valid_signed_manifest" ($u.update_available -and $u.latest -eq "2.0.0-dev.7" -and $u.verified) "latest=$($u.latest) verified=$($u.verified) error=$($u.error)"
$reqs = Get-Content $srvLog | ConvertFrom-Json
Step "update_request_has_no_user_data" (@($reqs | Where-Object { $_.path -eq "/latest.json" -and ($_.auth -or $_.cookie) }).Count -eq 0) "requests=$(@($reqs).Count)"
foreach ($v in @(@("other_key", "signature does not match"), @("unknown_key", "unknown key"), @("tampered", "signature does not match"))) {
    SrvSet $v[0] "normal"; $x = J "/updates/check" "POST"
    Step "update_rejects_$($v[0])" (-not $x.update_available -and $x.error -match $v[1]) "$($x.error)"
}
SrvSet "same_version" "normal"; $x = J "/updates/check" "POST"
Step "update_same_version_not_offered" (-not $x.update_available -and -not $x.error) "latest=$($x.latest)"
SrvSet "other_platform" "normal"; $x = J "/updates/check" "POST"
Step "update_other_platform_not_offered" (-not $x.update_available) "error=$($x.error)"

SrvSet "valid" "slow"; J "/updates/check" "POST" | Out-Null
$job = Start-Job -ScriptBlock { param($u) try { Invoke-RestMethod "$u/updates/download" -Method Post -TimeoutSec 600 } catch { $_.ErrorDetails.Message } } -ArgumentList (Url "")
Start-Sleep 4; J "/updates/cancel" "POST" | Out-Null
$cr = Receive-Job $job -Wait -AutoRemoveJob
Step "update_download_cancel" ("$cr" -match "cancelled") "$cr"
SrvSet "valid" "drop_once"
$d1 = J "/updates/download" "POST" $null 600
$d2 = J "/updates/download" "POST" $null 600
$ranges = @((Get-Content $srvLog | ConvertFrom-Json) | Where-Object { $_.path -eq "/pkg/Setup.exe" -and $_.range }).Count
Step "update_download_interrupted_then_resumed" ($d1._status -eq 400 -and $d2.prepared -and $ranges -ge 1) "first=$($d1._error) second=prepared:$($d2.prepared) range_requests=$ranges"
foreach ($v in @(@("valid", "corrupt"), @("valid", "truncate"), @("wrong_size", "normal"), @("wrong_sha", "normal"))) {
    Remove-Item "$data\updates\staging\*" -Force -ErrorAction SilentlyContinue
    SrvSet $v[0] $v[1]; J "/updates/check" "POST" | Out-Null
    $x = J "/updates/download" "POST" $null 600
    Step "update_rejects_package_$($v[0])_$($v[1])" ($x._status -eq 400 -and -not (Get-ChildItem "$data\updates\staging\*.exe" -ErrorAction SilentlyContinue)) "$($x._error)"
}
SrvSet "valid" "normal"; J "/updates/check" "POST" | Out-Null; $d = J "/updates/download" "POST" $null 600
$nc = J "/updates/install" "POST" @{ confirm = $false }
Step "update_install_needs_confirmation" ($nc._status -eq 400 -and -not (Get-Process | Where-Object { $_.Path -like "$data\updates\*" })) "HTTP $($nc._status)"
$ic = J "/updates/install" "POST" @{ confirm = $true }
Start-Sleep 5
$proc = Get-Process | Where-Object { $_.Path -like "$data\updates\staging\*" }
Step "update_installer_launched_after_confirm" ($ic.launched -and $proc) "launched=$($ic.launched) process=$($proc.Path)"
$proc | Stop-Process -Force                                                              # user closes the installer / it fails
Start-Sleep 3
$hh = J "/health"
Step "installer_abort_leaves_app_working" ($hh.version -eq $cfg.version) "version=$($hh.version)"
Step "installer_archived_for_rollback" (Test-Path "$data\updates\archive\Abhify.AI-Setup-2.0.0-dev.7.exe") ""
StopApp; $h = StartApp
$us = J "/updates"
Step "startup_records_update_not_installed" ($us.last_install.result -match "not installed") "$($us.last_install.result)"

# model update: failure (Ollama blocked from the internet) then success
$embed = $comp.active_models.embed
netsh advfirewall firewall add rule name="e2e-block-ollama" dir=out action=block program="$(OllamaExe)" | Out-Null
$mf = J "/updates/models/apply" "POST" @{ model = $embed; confirm = $true } 900
netsh advfirewall firewall delete rule name="e2e-block-ollama" | Out-Null
$emb = try { (Invoke-RestMethod "http://127.0.0.1:11434/api/embed" -Method Post -ContentType "application/json" -Body (@{ model = $embed; input = "still works" } | ConvertTo-Json)).embeddings[0].Count } catch { 0 }
Step "model_update_failure_restores_previous" ($mf._status -eq 400 -and $mf._error -match "restored" -and $emb -gt 0) "$($mf._error) | embedding dims after: $emb"
$ms = J "/updates/models/apply" "POST" @{ model = $embed; confirm = $true } 900
$list = (& (OllamaExe) list | Out-String)
Step "model_update_success_keeps_backup" ($ms.updated -and $list -match [regex]::Escape($ms.backup)) "updated=$($ms.updated) backup=$($ms.backup)"

$final = @{ mem = @((J "/memory").memories).Count; conv = @((J "/conversations").conversations).Count; chunks = (J "/knowledge").chunks }
$prof = (Get-Content "$data\config\settings.json" -Raw | ConvertFrom-Json).model_profile
Step "data_preserved_through_updates" ($final.mem -eq $before.mem -and $final.chunks -eq $before.chunks -and $final.conv -ge $before.conv -and $prof -eq $comp.profile) "final=$($final | ConvertTo-Json -Compress) profile=$prof"

StopApp; $srv | Stop-Process -Force -ErrorAction SilentlyContinue
Copy-Item "$data\logs\*.log" $out -ErrorAction SilentlyContinue
$R.finished = (Get-Date).ToString("s"); Save
"DONE" | Out-File "$out\done.txt"
