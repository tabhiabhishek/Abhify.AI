# =====================================================================
# Runs INSIDE Windows Sandbox: signing-pipeline lab test with a THROW-AWAY self-signed certificate.
# Nothing signed here is ever distributed; the VM and the certificate are discarded afterwards.
# =====================================================================
$ErrorActionPreference = "Continue"
$out = "C:\abhify\results"; $work = "C:\abhify-lab"
$results = [ordered]@{ started = (Get-Date).ToString("s"); steps = [ordered]@{} }
function Save {
    $results.passed = @($results.steps.Values | Where-Object { $_.ok }).Count; $results.total = $results.steps.Count
    $results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 "$out\result.json"
}
function Step($name, $ok, $detail) {
    $results.steps[$name] = [ordered]@{ ok = [bool]$ok; detail = "$detail" }
    "$(Get-Date -Format HH:mm:ss) $name : $ok : $detail" | Out-File -Append "$out\log.txt"; Save
}
function Sign($file, $subject) {
    $env:ABHIFY_SIGN_SUBJECT = $subject
    $o = & powershell -NoProfile -ExecutionPolicy Bypass -File C:\abhify\packaging\sign.ps1 -File $file 2>&1
    return @{ code = $LASTEXITCODE; text = ($o | Out-String).Trim() }
}
"--- signing lab $(Get-Date)" | Out-File "$out\log.txt"
$cfg = Get-Content "$out\config.json" -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $work | Out-Null
Copy-Item "$out\inputs\unsigned-copy.exe" "$work\lab.exe"
Step "copy_is_original_and_unsigned" (((Get-FileHash "$work\lab.exe").Hash.ToLower() -eq $cfg.original_sha256) -and ((Get-AuthenticodeSignature "$work\lab.exe").Status -eq "NotSigned")) $cfg.original_sha256

# throw-away cert, private key not exportable, trusted ONLY inside this VM
$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=Abhify.AI LAB TEST - NOT TRUSTED" `
    -CertStoreLocation Cert:\CurrentUser\My -KeyExportPolicy NonExportable -NotAfter (Get-Date).AddDays(1)
foreach ($storeName in "Root", "TrustedPublisher") {
    $st = New-Object System.Security.Cryptography.X509Certificates.X509Store($storeName, "LocalMachine")
    $st.Open("ReadWrite"); $st.Add([System.Security.Cryptography.X509Certificates.X509Certificate2]::new($cert.RawData)); $st.Close()
}
Step "lab_certificate_created" ($null -ne $cert) "thumbprint $($cert.Thumbprint), created with KeyExportPolicy NonExportable, valid 1 day"

$env:ABHIFY_SIGN_MODE = "Store"; $env:ABHIFY_SIGN_THUMBPRINT = $cert.Thumbprint
$env:ABHIFY_SIGNTOOL = "C:\abhify\signtool\signtool.exe"; $env:ABHIFY_SIGN_LAB = "1"

# 1. misconfiguration is refused before anything is signed
$env:ABHIFY_SIGN_MODE = "Nonsense"
$r = Sign "$work\lab.exe" "Abhify.AI LAB TEST"
Step "bad_mode_refused" (($r.code -eq 1) -and ((Get-AuthenticodeSignature "$work\lab.exe").Status -eq "NotSigned")) $r.text
$env:ABHIFY_SIGN_MODE = "Store"

# 2. real sign + timestamp + verify
$r = Sign "$work\lab.exe" "Abhify.AI LAB TEST"
$sig = Get-AuthenticodeSignature "$work\lab.exe"
Step "signed_timestamped_verified" (($r.code -eq 0) -and $sig.Status -eq "Valid" -and $sig.TimeStamperCertificate) ("exit {0}; {1}; timestamp: {2}" -f $r.code, $sig.Status, $sig.TimeStamperCertificate.Subject)
$after = (Get-FileHash "$work\lab.exe").Hash.ToLower()
Step "checksum_changes_after_signing" ($after -ne $cfg.original_sha256) "unsigned $($cfg.original_sha256.Substring(0,12))… → signed $($after.Substring(0,12))…"
"$after  lab.exe" | Out-File -Encoding ascii "$work\SHA256SUMS.txt"
Step "checksum_file_matches_signed_file" ((Get-Content "$work\SHA256SUMS.txt").Split(" ")[0] -eq (Get-FileHash "$work\lab.exe").Hash.ToLower()) ""

# 3. wrong expected publisher is refused (verification step of sign.ps1)
Copy-Item "$out\inputs\unsigned-copy.exe" "$work\lab2.exe"
$r = Sign "$work\lab2.exe" "Some Other Publisher"
Step "wrong_publisher_refused" ($r.code -eq 1 -and $r.text -match "does not contain") ($r.text -split "`n" | Select-Object -Last 1)

# 4. tampering after signing is detected
Copy-Item "$work\lab.exe" "$work\tampered.exe"
$bytes = [IO.File]::ReadAllBytes("$work\tampered.exe"); $bytes[4096] = $bytes[4096] -bxor 0xFF
[IO.File]::WriteAllBytes("$work\tampered.exe", $bytes)
$t = Get-AuthenticodeSignature "$work\tampered.exe"
& C:\abhify\signtool\signtool.exe verify /v "$work\tampered.exe" *> $null
Step "tampering_detected" (($t.Status -ne "Valid") -and ($LASTEXITCODE -ne 0)) "status=$($t.Status) signtool verify exit=$LASTEXITCODE"

# 5. the signed installer still installs and starts
$p = Start-Process "$work\lab.exe" -ArgumentList "/S" -PassThru; $null = $p.WaitForExit(300000)
$inst = "$env:LOCALAPPDATA\Programs\Abhify.AI"
$env:ABHIFY_NO_BROWSER = "1"
Start-Process "$inst\runtime\pythonw.exe" -ArgumentList "`"$inst\app\desktop\launcher.pyw`""
$h = $null; for ($i = 0; $i -lt 120 -and -not $h; $i++) { Start-Sleep 1; try { $h = Invoke-RestMethod "http://127.0.0.1:8000/health" -TimeoutSec 3 } catch {} }
Step "signed_installer_installs_and_runs" ($h -and $h.version -eq $cfg.version) "version=$($h.version)"
$u = Get-AuthenticodeSignature "$inst\Uninstall.exe"
Step "uninstaller_status_recorded" $true "Uninstall.exe: $($u.Status) (a pre-built installer signed afterwards keeps an unsigned uninstaller; build.py --sign signs it via !uninstfinalize)"

Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -Force -ErrorAction SilentlyContinue
$results.finished = (Get-Date).ToString("s"); Save
"DONE" | Out-File "$out\done.txt"
