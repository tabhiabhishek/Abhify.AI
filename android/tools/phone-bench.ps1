# =====================================================================
# Real-phone benchmark of llama.cpp (CPU baseline, optional Vulkan) over adb.
# Everything runs in /data/local/tmp/abhify-bench on the phone and is deleted at the end.
#   powershell -ExecutionPolicy Bypass -File android\tools\phone-bench.ps1 -Out build\android-bench
# Needs: USB debugging on, this PC authorized on the phone. Charge the phone above 50 %.
# =====================================================================
param(
    [string]$Out = "build\android-bench",
    [string]$Adb = "D:\AndroidDev\sdk\platform-tools\adb.exe",
    [string]$CpuBuild = "D:\AndroidDev\src\llama.cpp\build-android-cpu\bin",
    [string]$VkBuild = "D:\AndroidDev\src\llama.cpp\build-android-vk\bin",
    [string]$Models = "D:\AndroidDev\models",
    [string]$Strip = "D:\AndroidDev\sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe",
    [int]$SustainedReps = 10,
    [switch]$SkipVulkan,
    [switch]$KeepOnPhone
)
$ErrorActionPreference = "Stop"
$R = "/data/local/tmp/abhify-bench"
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $Out "bench.log"
"phone-bench $(Get-Date -Format s)" | Out-File $log
function A { param([Parameter(ValueFromRemainingArguments)]$a) $o = & $Adb @a 2>&1 | Out-String; $o.TrimEnd() }
function Sh([string]$cmd) { A shell $cmd }
function Note($k, $v) { "## $k`n$v`n" | Tee-Object -Append $log | Out-Host }

$devs = @((A devices) -split "`n" | Where-Object { $_ -match "`tdevice$" })
if ($devs.Count -ne 1) { throw "Need exactly one authorized phone (adb devices shows $($devs.Count)). $(A devices)" }

# ---------------------------------------------------------------- device facts (read, not assumed)
$facts = [ordered]@{}
foreach ($p in "ro.product.manufacturer", "ro.product.model", "ro.product.device", "ro.build.version.release", "ro.build.version.sdk",
               "ro.build.display.id", "ro.soc.manufacturer", "ro.soc.model", "ro.board.platform", "ro.hardware", "ro.product.cpu.abilist",
               "ro.hardware.vulkan", "ro.hardware.egl") { $facts[$p] = Sh "getprop $p" }
$facts.cpu_features = (Sh "grep -m1 -i '^Features' /proc/cpuinfo")
$facts.cpu_parts = (Sh "grep -i 'CPU part' /proc/cpuinfo | sort | uniq -c")
$facts.cpu_max_khz = (Sh "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo `$(basename `$c) `$(cat `$c/cpufreq/cpuinfo_max_freq 2>/dev/null); done")
$facts.meminfo = (Sh "grep -E 'MemTotal|MemAvailable|SwapTotal|SwapFree' /proc/meminfo")
$facts.storage = (Sh "df -h /data | tail -1")
$facts.vulkan_features = (Sh "pm list features | grep -i vulkan")
$facts.gpu = (Sh "dumpsys SurfaceFlinger | grep -m1 -i GLES")
$facts.battery = (Sh "dumpsys battery | grep -E 'level|temperature|status|AC powered|USB powered'")
$facts.thermal = (Sh "dumpsys thermalservice | grep -E 'Thermal Status|Temperature\{' | head -12")
$facts | ConvertTo-Json -Depth 3 | Out-File -Encoding utf8 (Join-Path $Out "device.json")
$facts.GetEnumerator() | ForEach-Object { Note $_.Key $_.Value }
if ($facts["ro.product.cpu.abilist"] -notmatch "arm64-v8a") { throw "Not an arm64 phone: $($facts['ro.product.cpu.abilist']). This harness only has arm64 builds." }

# ---------------------------------------------------------------- push stripped binaries + models
$stage = Join-Path $env:TEMP "abhify-bench-stage"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
foreach ($kind in "cpu", "vk") {
    $src = if ($kind -eq "cpu") { $CpuBuild } else { $VkBuild }
    if ($kind -eq "vk" -and $SkipVulkan) { continue }
    $d = New-Item -ItemType Directory -Force (Join-Path $stage $kind)
    Get-ChildItem $src | Where-Object { $_.Name -match '\.so$|^llama-(bench|completion)$' } | ForEach-Object { & $Strip -o (Join-Path $d $_.Name) $_.FullName }
}
Sh "rm -rf $R; mkdir -p $R/models" | Out-Null
A push "$stage\." "$R/" | Out-Null
Get-ChildItem $Models -Filter *.gguf | ForEach-Object { A push $_.FullName "$R/models/" | Out-Null }
Sh "chmod 755 $R/cpu/llama-* $R/vk/llama-* 2>/dev/null; ls -la $R $R/models" | ForEach-Object { Note "pushed" $_ }
Remove-Item -Recurse -Force $stage

$m15 = "$R/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"; $m05 = "$R/models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
function Run($kind, $cmd) { Sh "cd $R/$kind && LD_LIBRARY_PATH=$R/$kind ./$cmd" }
Note "cpu_backend_variant_and_devices" (Run cpu "llama-bench --list-devices")

# ---------------------------------------------------------------- CPU baseline: throughput by thread count
foreach ($m in $m15, $m05) {
    $j = Run cpu "llama-bench -m $m -p 128 -n 64 -t 2,4,6,8 -r 3 -o json"
    $j | Out-File -Encoding utf8 (Join-Path $Out ("cpu-bench-" + [IO.Path]::GetFileNameWithoutExtension($m) + ".json"))
    Note "cpu_bench $m" $j
}

# ---------------------------------------------------------------- real completion: first-token latency + peak memory
$prompt = "<|im_start|>system\nYou are a concise AI/ML tutor.<|im_end|>\n<|im_start|>user\nIn two sentences, what is gradient descent?<|im_end|>\n<|im_start|>assistant\n"
foreach ($m in $m15, $m05) {
    $o = Sh ("cd $R/cpu && (LD_LIBRARY_PATH=$R/cpu ./llama-completion -m $m -t 4 -c 2048 -n 96 --temp 0.3 -no-cnv -p '" + $prompt.Replace("'", "") + "' > out.txt 2> err.txt & P=`$!; " +
             "HWM=0; while kill -0 `$P 2>/dev/null; do H=`$(grep VmHWM /proc/`$P/status 2>/dev/null | tr -dc 0-9); [ -n `"`$H`" ] && [ `$H -gt `$HWM ] && HWM=`$H; sleep 0.2; done; " +
             "echo PEAK_RSS_KB=`$HWM); cat out.txt; grep -E 'load time|prompt eval time|eval time|total time' err.txt")
    Note "cpu_completion $m" $o
    $o | Out-File -Encoding utf8 (Join-Path $Out ("cpu-completion-" + [IO.Path]::GetFileNameWithoutExtension($m) + ".txt"))
}

# ---------------------------------------------------------------- sustained CPU load: throttling, battery temperature
$before = Sh "dumpsys battery | grep -E 'level|temperature'; dumpsys thermalservice | grep 'Thermal Status'"
$t0 = Get-Date
$j = Run cpu "llama-bench -m $m15 -p 0 -n 256 -t 4 -r $SustainedReps -o json"
$secs = [int]((Get-Date) - $t0).TotalSeconds
$after = Sh "dumpsys battery | grep -E 'level|temperature'; dumpsys thermalservice | grep 'Thermal Status'"
$j | Out-File -Encoding utf8 (Join-Path $Out "cpu-sustained.json")
Note "sustained ($SustainedReps x 256 tokens, $secs s)" "BEFORE:`n$before`nAFTER:`n$after`nPER-REP t/s: $((($j | ConvertFrom-Json)[0].samples_ts | ForEach-Object { [math]::Round($_,1) }) -join ', ')"

# ---------------------------------------------------------------- optional Vulkan (validated separately; never assumed)
if (-not $SkipVulkan) {
    $dev = Run vk "llama-bench --list-devices"
    Note "vulkan_devices" $dev
    if ($dev -match "Vulkan") {
        foreach ($m in $m15, $m05) {
            $j = Run vk "llama-bench -m $m -p 128 -n 64 -t 4 -ngl 99 -r 3 -o json"
            $j | Out-File -Encoding utf8 (Join-Path $Out ("vk-bench-" + [IO.Path]::GetFileNameWithoutExtension($m) + ".json"))
            Note "vulkan_bench $m" $j
        }
        $o = Sh ("cd $R/vk && LD_LIBRARY_PATH=$R/vk ./llama-completion -m $m15 -t 4 -ngl 99 -c 2048 -n 64 --temp 0 -no-cnv -p 'The capital of France is' 2>&1 | tail -25")
        Note "vulkan_completion_correctness" $o
    } else { Note "vulkan" "No usable Vulkan device reported by ggml: CPU only on this phone." }
}

if (-not $KeepOnPhone) { Sh "rm -rf $R" | Out-Null; Note "cleanup" "removed $R: $(Sh "ls $R 2>&1")" }
"done" | Out-Host
