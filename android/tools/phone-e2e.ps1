# =====================================================================
# Real-phone end-to-end test of the Abhify Android app over adb (USB debugging).
#   powershell -ExecutionPolicy Bypass -File android\tools\phone-e2e.ps1 [-Model qwen2.5-1.5b-instruct-q4_k_m] [-Interrupt]
# Installs the debug build (a new app, ai.abhify.android; no other app or data is touched), runs the
# setup online, then turns AIRPLANE MODE ON for the offline steps and back OFF at the end.
# Results: build\android-e2e\*.json, screenshots, log. Emulator runs must be labelled as such.
# =====================================================================
param(
    [string]$Model = "",
    [switch]$Interrupt,              # also test pause/resume of a real download (extra ~490 MB)
    [int]$SustainedRuns = 8,
    [string]$Out = "build\android-e2e",
    [string]$Adb = "D:\AndroidDev\sdk\platform-tools\adb.exe",
    [string]$Apk = "",               # defaults to this repo's build output
    [string]$TestApk = ""
)
$ErrorActionPreference = "Stop"
$root = "$PSScriptRoot\..\.."
$apk = if ($Apk) { $Apk } else { "$root\android\app\build\outputs\apk\debug\app-debug.apk" }
$testApk = if ($TestApk) { $TestApk } else { "$root\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" }
$pkg = "ai.abhify.android"
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $Out "e2e.log"
"phone-e2e $(Get-Date -Format s)" | Out-File $log
# adb writes normal messages to stderr; in Windows PowerShell 5.1 that becomes a terminating error under "Stop"
# and hides the real reason. Capture both streams as plain text instead.
function A {
    param([Parameter(ValueFromRemainingArguments)]$a)
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { ((& $Adb @a 2>&1 | ForEach-Object { "$_" }) | Out-String).TrimEnd() } finally { $ErrorActionPreference = $prev }
}
function Install($file) {
    $o = A install -r $file          # no -g: Xiaomi refuses it without "USB debugging (Security settings)"; not needed
    Say $o
    if ($o -notmatch "Success") {
        $hint = switch -Regex ($o) {
            "USER_RESTRICTED|canceled by user" { "Xiaomi blocked it: turn on Developer options > 'Install via USB' (needs a Mi account and a SIM), then tap Install on the phone's prompt within 10 s." }
            "UPDATE_INCOMPATIBLE|signatures do not match" { "An Abhify signed with a different key is installed. Uninstall Abhify on the phone, then run again." }
            "INSUFFICIENT_STORAGE" { "Free some storage on the phone." }
            "INSTALL_GRANT_RUNTIME_PERMISSIONS" { "Xiaomi refused the automatic permission grant; this script no longer asks for it. Use the latest phone-e2e.ps1." }
            default { "See the message above." }
        }
        throw "Install failed for $(Split-Path $file -Leaf): $hint"
    }
}
function Say($m) { $m | Tee-Object -Append $log | Out-Host }
$Results = [ordered]@{}
function Step($name, $ok, $detail) { $Results[$name] = [ordered]@{ ok = [bool]$ok; detail = "$detail" }; Say ("{0} {1}: {2}" -f $(if ($ok) { "PASS" } else { "FAIL" }), $name, $detail) }

$devs = @((A devices) -split "`n" | Where-Object { $_ -match "`tdevice$" })
if ($devs.Count -ne 1) { throw "Need exactly one authorized device. $(A devices)" }
$emu = (A shell getprop ro.kernel.qemu) -eq "1" -or (A shell getprop ro.hardware) -match "ranchu|goldfish"
$Results.device_kind = if ($emu) { "EMULATOR (not a physical phone)" } else { "physical phone" }
$Results.device = "$(A shell getprop ro.product.manufacturer) $(A shell getprop ro.product.model) / Android $(A shell getprop ro.build.version.release) (API $(A shell getprop ro.build.version.sdk)) / $(A shell getprop ro.soc.model)"
Say "Device: $($Results.device) [$($Results.device_kind)]"

foreach ($f in $apk, $testApk) { if (-not (Test-Path $f)) { throw "Build first: gradlew assembleDebug assembleDebugAndroidTest ($f missing)" } }
Install $apk; Install $testApk

function Run($method, [hashtable]$extra = @{}) {
    $e = @("-e", "class", "ai.abhify.android.OnDeviceE2ETest#$method")
    foreach ($k in $extra.Keys) { $e += @("-e", $k, $extra[$k]) }
    A shell am force-stop $pkg | Out-Null                      # every step is a fresh app process
    $o = A shell am instrument -w -r @e "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
    $o | Out-File -Append $log
    $json = A shell run-as $pkg cat "files/e2e/$method.json"
    if ($json -match '^\s*\{') { $json | Out-File -Encoding utf8 (Join-Path $Out "$method.json") }
    $ok = $o -match "OK \(1 test\)"
    $fail = ([regex]::Match($o, "(?s)There was 1 failure:.*?(?=INSTRUMENTATION_CODE|\z)").Value -split "`n" | Select-Object -First 4) -join " "
    return @($ok, $(if ($ok) { ($json -replace '\s+', ' ').Substring(0, [Math]::Min(400, ($json -replace '\s+', ' ').Length)) } else { $fail }))
}
function Airplane($on) {
    $want = if ($on) { "1" } else { "0" }
    A shell cmd connectivity airplane-mode $(if ($on) { "enable" } else { "disable" }) | Out-Null
    Start-Sleep 6
    $now = A shell settings get global airplane_mode_on
    # Xiaomi may refuse to let the PC switch it: then ask the person holding the phone, and verify.
    for ($i = 0; $i -lt 3 -and $now -ne $want; $i++) {
        Read-Host ("`n>>> Please turn airplane mode {0} on the phone now (swipe down > airplane icon), then press Enter" -f $(if ($on) { "ON" } else { "OFF" })) | Out-Null
        Start-Sleep 3
        $now = A shell settings get global airplane_mode_on
    }
    return $now
}

$mArgs = @{}; if ($Model) { $mArgs.model = $Model }
try {
    Airplane $false | Out-Null
    $r = Run "device"; Step "device_facts" $r[0] $r[1]
    # database v2 -> v3 migration on this phone's real SQLite (separate test class, its own temporary database)
    $mig = A shell am instrument -w -r -e class ai.abhify.android.DbMigrationTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
    $mig | Out-File -Append $log
    Step "db_migration_v2_to_v3" ($mig -match "OK \(1 test\)") $(if ($mig -match "OK \(1 test\)") { "all rows kept, ids/timestamps/profile backfilled" } else { ($mig -split "`n" | Select-String "Error|expected|Exception" | Select-Object -First 3) -join " " })
    $r = Run "setup" $mArgs; Step "setup_download_verify_selftest" $r[0] $r[1]
    if (-not $r[0]) { throw "Setup failed, so the offline steps were skipped. See setup above and logcat.txt." }
    if ($Interrupt) { $r = Run "interruptedDownload"; Step "interrupted_download_resumes" $r[0] $r[1] }

    $ap = Airplane $true
    Step "airplane_mode_on" ($ap -eq "1") "settings airplane_mode_on=$ap"
    $r = Run "offlineChatAndMemory"; Step "offline_chat_and_memory" $r[0] $r[1]
    $r = Run "persistenceAfterRestart"; Step "offline_persistence_after_restart" $r[0] $r[1]

    # the real UI, offline: launch, send a message, read the answer bubble
    try {
        A shell am force-stop $pkg | Out-Null
        A shell am start -W -n "$pkg/.ui.MainActivity" | Out-Null
        Start-Sleep 4
        A shell uiautomator dump /sdcard/abhify-ui.xml | Out-Null
        $xml = A shell cat /sdcard/abhify-ui.xml
        $edit = [regex]::Match($xml, 'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($edit.Success) {
            $x = ([int]$edit.Groups[1].Value + [int]$edit.Groups[3].Value) / 2; $y = ([int]$edit.Groups[2].Value + [int]$edit.Groups[4].Value) / 2
            $tap = A shell input tap $x $y
            if ($tap -match "SecurityException|INJECT_EVENTS") { throw "UIBLOCKED" }
            A shell input text "What%sis%sa%stensor?" | Out-Null
            Start-Sleep 1
            A shell uiautomator dump /sdcard/abhify-ui.xml | Out-Null
            $send = [regex]::Match((A shell cat /sdcard/abhify-ui.xml), 'text="Send"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if ($send.Success) { A shell input tap (([int]$send.Groups[1].Value + [int]$send.Groups[3].Value) / 2) (([int]$send.Groups[2].Value + [int]$send.Groups[4].Value) / 2) | Out-Null }
            $answered = $false
            for ($i = 0; $i -lt 40 -and -not $answered; $i++) {
                Start-Sleep 3
                A shell uiautomator dump /sdcard/abhify-ui.xml | Out-Null
                $now = A shell cat /sdcard/abhify-ui.xml
                $answered = $now -match 'text="Send"' -and $now -match 'tokens/s'
            }
            A shell screencap -p /sdcard/abhify-offline.png | Out-Null; A pull /sdcard/abhify-offline.png (Join-Path $Out "offline-ui.png") | Out-Null
            $status = [regex]::Match($now, 'text="(AI ready[^"]*|[^"]*Loading[^"]*|⚠️[^"]*)"').Groups[1].Value
            Step "offline_ui_chat" $answered "status='$status' (screenshot offline-ui.png)"
        } else { Step "offline_ui_chat" $false "chat input not found (is the app on the setup screen?)" }
        A shell rm -f /sdcard/abhify-ui.xml /sdcard/abhify-offline.png | Out-Null
    } catch {
        if ("$_" -match "UIBLOCKED") { Step "offline_ui_chat" $false "NOT TESTED: Xiaomi blocks simulated taps unless 'USB debugging (Security settings)' is on. Test the screen by hand." }
        else { Step "offline_ui_chat" $false "error: $_" }
        A shell rm -f /sdcard/abhify-ui.xml /sdcard/abhify-offline.png | Out-Null
    }

    $r = Run "sustained" @{ runs = "$SustainedRuns" }; Step "sustained_load" $r[0] $r[1]
} finally {
    A logcat -d -v time "AbhifyLLM:V" "AndroidRuntime:E" "ai.abhify.android:V" "TestRunner:V" "*:S" | Out-File -Encoding utf8 (Join-Path $Out "logcat.txt")
    $ap = Airplane $false
    Say "airplane mode restored: airplane_mode_on=$ap"
    $Results.passed = @($Results.Values | Where-Object { $_ -is [System.Collections.IDictionary] -and $_.ok }).Count
    $Results.total = @($Results.Values | Where-Object { $_ -is [System.Collections.IDictionary] }).Count
    $Results | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 (Join-Path $Out "result.json")
    Say "PASSED $($Results.passed)/$($Results.total) on $($Results.device_kind)"
}
