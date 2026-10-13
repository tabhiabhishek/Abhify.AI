"""
config.py: process-level settings from environment variables / backend/.env.

Two kinds of configuration exist in Abhify.AI:
  * This file: how the *process* runs (Ollama URL, CORS, Whisper, limits).
    Set with environment variables (Docker, services, packaging).
  * local_file_service.load_settings(): per-installation choices the user makes
    in the app (model profile, feature switches), stored in
    <data root>/config/settings.json.

Model names are NOT configured here any more; see system_service.active_models()
(settings.json, overridable with LLM_MODEL / EMBED_MODEL env vars).
Where user data lives: see local_file_service.py.
"""
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BASE_DIR / ".env", env_file_encoding="utf-8", extra="ignore")

    # ---- Ollama ----
    ollama_base_url: str = "http://localhost:11434"
    llm_temperature: float = 0.2

    # ---- Speech (backend fallback transcription) ----
    whisper_model: str = "base"                # tiny | base | small | medium | large-v3
    whisper_device: str = "auto"               # auto | cuda | cpu

    # ---- HTTP ----
    # Comma-separated allowed frontend origins ("*" allows any; testing only).
    cors_origins: str = (
        "http://localhost:8000,http://127.0.0.1:8000,"
        "http://localhost:5500,http://127.0.0.1:5500,"
        "http://localhost:3000,http://127.0.0.1:3000,"
        "tauri://localhost,http://tauri.localhost,https://tauri.localhost"
    )
    cors_origin_regex: str | None = r"https://.*\.(netlify|vercel)\.app"
    # Admin endpoints (backup/restore, data relocation, model downloads) only
    # accept requests from the local machine unless this is turned on.
    allow_remote_admin: bool = False

    # ---- Caching / limits ----
    news_cache_ttl: int = 30 * 60
    max_upload_mb: int = 20

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
