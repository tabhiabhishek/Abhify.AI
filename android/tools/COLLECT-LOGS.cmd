@echo off
rem Collects Abhify's diagnostics from the connected phone (USB debugging on). Reads only Abhify's own
rem records and error log lines; no other app's data. Output: the "logs" folder next to this file.
cd /d "%~dp0"
set ADB=platform-tools\adb.exe
if not exist logs mkdir logs
%ADB% devices -l > logs\devices.txt
%ADB% shell getprop ro.product.model > logs\phone.txt
%ADB% shell getprop ro.build.version.release >> logs\phone.txt
%ADB% shell getprop ro.soc.model >> logs\phone.txt
%ADB% shell dumpsys package ai.abhify.android | findstr /i "versionName versionCode nativeLibraryDir primaryCpuAbi" > logs\app.txt
%ADB% shell run-as ai.abhify.android cat no_backup/models/state.json > logs\state.json 2>&1
%ADB% shell run-as ai.abhify.android ls -l no_backup/models/staging no_backup/models/installed > logs\model-files.txt 2>&1
%ADB% logcat -d -v time AbhifyLLM:V AndroidRuntime:E DEBUG:E libc:F *:S > logs\logcat.txt
echo.
echo Saved to the "logs" folder. Send it back.
pause
