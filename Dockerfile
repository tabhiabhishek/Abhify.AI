# =====================================================================
# Abhify.AI: FastAPI backend + PWA frontend in one image.
#
#   docker build -t abhify-ai .
#   docker run -p 8000:8000 -e OLLAMA_BASE_URL=http://host.docker.internal:11434 abhify-ai
#
# The app is then available at http://localhost:8000 (PWA + API).
# Use docker-compose.yml to also run Ollama in a container.
# =====================================================================
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DATA_DIR=/data

WORKDIR /app

# curl is used by the HEALTHCHECK; libgomp1 is needed by CTranslate2 (faster-whisper) on CPU
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl libgomp1 \
 && rm -rf /var/lib/apt/lists/*

# Install Python deps first so this layer is cached between code changes
COPY backend/requirements.txt backend/requirements.txt
RUN pip install -r backend/requirements.txt

# App code: backend/ serves frontend/ as static files at "/"
COPY backend/ backend/
COPY frontend/ frontend/

# Run as an unprivileged user; /data holds ChromaDB + SQLite (mount a volume here)
RUN useradd --create-home --uid 10001 app && mkdir -p /data && chown -R app:app /data /app
USER app

WORKDIR /app/backend
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fs http://localhost:8000/health || exit 1

# 1 worker keeps the in-memory chat history and Whisper model in one process.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips=*"]
