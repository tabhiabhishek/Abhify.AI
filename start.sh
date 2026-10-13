#!/usr/bin/env sh
# Abhify.AI: one-click start on macOS / Linux (development / source install).
# Listens on 127.0.0.1 only; export ABHIFY_HOST=0.0.0.0 to allow other devices.
set -e
cd "$(dirname "$0")/backend"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -r requirements.txt; }
exec .venv/bin/python -m uvicorn main:app --host "${ABHIFY_HOST:-127.0.0.1}" --port 8000
