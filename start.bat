@echo off
REM Abhify.AI: one-click start on Windows (development / source install).
REM Needs Python 3.11 and Ollama. Models are chosen in the in-app setup wizard.
REM Listens on 127.0.0.1 only. Set ABHIFY_HOST=0.0.0.0 to allow other devices on your network.
title Abhify.AI
cd /d "%~dp0backend"
if not exist .venv (
  echo Creating virtual environment...
  python -m venv .venv || goto :error
  .venv\Scripts\python.exe -m pip install -r requirements.txt || goto :error
)
if "%ABHIFY_HOST%"=="" set ABHIFY_HOST=127.0.0.1
start "" http://localhost:8000
.venv\Scripts\python.exe -m uvicorn main:app --host %ABHIFY_HOST% --port 8000
goto :eof
:error
echo Setup failed. Check that Python 3.11 is installed and on PATH.
pause
