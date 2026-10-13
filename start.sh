#!/usr/bin/env sh
# One-click start on macOS / Linux: needs Ollama running with qwen2.5 + nomic-embed-text pulled.
set -e
cd "$(dirname "$0")/backend"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -r requirements.txt; }
exec .venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8000
