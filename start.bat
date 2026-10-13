@echo off
REM One-click start on Windows: needs Ollama running with qwen2.5 + nomic-embed-text pulled.
cd /d "%~dp0backend"
if not exist .venv (
  echo Creating virtual environment...
  python -m venv .venv
  .venv\Scripts\python.exe -m pip install -r requirements.txt
)
start "" http://localhost:8000
.venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
