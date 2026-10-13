"""
config.py: central settings for the AI Tech Pulse backend.

Every value can be overridden with an environment variable (or a `.env` file
next to this module). Docker Compose uses this to point the backend at the
`ollama` container instead of localhost.
"""
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BASE_DIR / ".env", env_file_encoding="utf-8", extra="ignore")

    # ---- Ollama (local LLM + embeddings) ----
    ollama_base_url: str = "http://localhost:11434"
    llm_model: str = "qwen2.5"                 # reasoning + tool calling
    embed_model: str = "nomic-embed-text"      # embeddings for ChromaDB
    llm_temperature: float = 0.2

    # ---- Storage ----
    data_dir: Path = BASE_DIR / "data"         # ChromaDB + SQLite live here
    chroma_collection: str = "knowledge_base"

    # ---- Speech (backend fallback transcription) ----
    whisper_model: str = "base"                # tiny | base | small | medium | large-v3
    whisper_device: str = "auto"               # auto | cuda | cpu

    # ---- HTTP ----
    # Comma-separated list of allowed frontend origins. "*" allows any origin
    # (handy for local testing; set real domains in production).
    cors_origins: str = (
        "http://localhost:5500,http://127.0.0.1:5500,"
        "http://localhost:8080,http://127.0.0.1:8080,"
        "http://localhost:3000,http://127.0.0.1:3000"
    )
    # Optional regex for preview deployments, e.g. r"https://.*\.netlify\.app"
    cors_origin_regex: str | None = r"https://.*\.(netlify|vercel)\.app"

    # ---- Caching of generated content (seconds) ----
    news_cache_ttl: int = 30 * 60

    # ---- Upload limits ----
    max_upload_mb: int = 20

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def chroma_dir(self) -> Path:
        return self.data_dir / "chroma"

    @property
    def sqlite_path(self) -> Path:
        return self.data_dir / "progress.db"


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.data_dir.mkdir(parents=True, exist_ok=True)
    return s
