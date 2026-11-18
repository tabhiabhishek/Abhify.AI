@echo off
REM Abhify.AI: one-click start on Windows (development / source install) - the desktop/web app with every current feature
REM (Chat with streamed answers, Projects, AI Task Runner, Study, News with Listen, Documents & Knowledge, Memory,
REM AI Models, Performance, System, Settings).
REM Needs Python 3.11 and Ollama. Models are chosen in the in-app setup wizard.
REM Listens on 127.0.0.1 only. Set ABHIFY_HOST=0.0.0.0 to allow other devices on your network.
REM   start.bat          start (or, when it is already running, just open it in the browser)
REM   start.bat stop     stop the Abhify.AI backend started from here (port 8000)
REM Optional: ABHIFY_DATA_DIR=<folder> uses another data folder; ABHIFY_PORT=<port> another port; ABHIFY_NO_BROWSER=1 doesn't open the browser.
title Abhify.AI
setlocal
cd /d "%~dp0backend"
if "%ABHIFY_HOST%"=="" set ABHIFY_HOST=127.0.0.1
if "%ABHIFY_PORT%"=="" (set PORT=8000) else (set PORT=%ABHIFY_PORT%)
set URL=http://localhost:%PORT%
REM checks use 127.0.0.1: "localhost" may try IPv6 first, and the backend listens on IPv4
set CHECK=http://127.0.0.1:%PORT%

if /i "%~1"=="stop" goto :stop

REM Already running? Then only open the app (never a second backend on the same data).
powershell -NoProfile -Command "try { $r = Invoke-RestMethod %CHECK%/ping -TimeoutSec 2; if ($r.app -eq 'Abhify.AI') { exit 0 } else { exit 1 } } catch { exit 1 }"
if %errorlevel%==0 (
  echo Abhify.AI is already running: opening %URL%
  if not "%ABHIFY_NO_BROWSER%"=="1" start "" %URL%
  goto :eof
)

if not exist .venv (
  echo Creating virtual environment...
  python -m venv .venv || goto :error
  .venv\Scripts\python.exe -m pip install -r requirements.txt || goto :error
)

REM Ollama: start it when it is installed but not running (models stay as they are; nothing is downloaded here).
powershell -NoProfile -Command "try { Invoke-RestMethod http://127.0.0.1:11434/api/version -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
if not %errorlevel%==0 (
  where ollama >nul 2>nul && (
    echo Starting Ollama...
    start "Ollama" /min ollama serve
  ) || echo Ollama is not running and was not found: AI answers need it ^(https://ollama.com^). The rest of the app still opens.
)

REM Open the browser as soon as the backend answers (not before), while the backend runs in this window.
if not "%ABHIFY_NO_BROWSER%"=="1" start "" /b powershell -NoProfile -WindowStyle Hidden -Command "for ($i = 0; $i -lt 120; $i++) { try { Invoke-RestMethod %CHECK%/ping -TimeoutSec 2 | Out-Null; Start-Process '%URL%'; break } catch { Start-Sleep -Milliseconds 500 } }"
echo Abhify.AI is starting on %URL%  (close this window or press Ctrl+C to stop)
.venv\Scripts\python.exe -m uvicorn main:app --host %ABHIFY_HOST% --port %PORT%
goto :eof

:stop
powershell -NoProfile -Command "try { $r = Invoke-RestMethod %CHECK%/ping -TimeoutSec 2; if ($r.app -eq 'Abhify.AI') { exit 0 } else { exit 1 } } catch { exit 1 }"
if not %errorlevel%==0 (
  echo Abhify.AI is not running on port %PORT%: nothing to stop.
  goto :eof
)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do (
  echo Stopping the Abhify.AI backend ^(process %%p^)...
  taskkill /pid %%p /f >nul
)
goto :eof

:error
echo Setup failed. Check that Python 3.11 is installed and on PATH.
pause
