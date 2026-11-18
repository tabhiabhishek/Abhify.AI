"""Settings for the Abhify cloud service, from environment variables (prefix ABHIFY_CLOUD_).

Nothing secret has a default. In production the service refuses to start with an unsafe setup
(no HTTPS public URL, no mail delivery configured)."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ABHIFY_CLOUD_", env_file=".env", extra="ignore")

    env: str = "dev"                                   # "dev" | "prod"
    data_dir: Path = Path("./data")                    # SQLite database + dev mail outbox
    public_url: str = ""                               # e.g. https://api.example.com (prod: required, https)
    trust_proxy: bool = False                          # True only behind our reverse proxy (client IP from X-Forwarded-For)

    access_token_minutes: int = 15
    refresh_token_days: int = 30
    reset_code_minutes: int = 15
    password_min_length: int = 10

    # Online AI: the service talks to a self-hosted Ollama on the same private network. Never public.
    ollama_url: str = "http://127.0.0.1:11434"
    ai_model: str = "qwen2.5:3b"
    ai_max_prompt_chars: int = 24_000
    ai_max_tokens: int = 1024

    # Shared provider access managed by the administrator (off by default). Keys only in the environment.
    shared_enabled: bool = False
    shared_order: str = "gemini,groq,openrouter"
    shared_gemini_key: str = ""
    shared_groq_key: str = ""
    shared_openrouter_key: str = ""
    shared_gemini_model: str = "gemini-3.8-flash"
    shared_groq_model: str = "llama-3.3-70b-versatile"
    shared_openrouter_model: str = "openrouter/free"
    shared_daily_limit: int = 50                       # requests per user per day (UTC)
    shared_allow_charged: bool = False                 # True only if the administrator accepts paid models

    # Mail (password reset). dev: codes go to data_dir/outbox.log. prod: SMTP is required.
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""

    # Limits
    sync_max_changes: int = 500
    sync_max_record_bytes: int = 64 * 1024
    user_quota_bytes: int = 50 * 1024 * 1024

    @property
    def db_path(self) -> Path:
        return self.data_dir / "abhify-cloud.db"

    def shared(self) -> list[tuple[str, str, str]]:
        """Configured shared providers in order: (id, key, model). Keys never leave this process."""
        keys = {"gemini": self.shared_gemini_key, "groq": self.shared_groq_key, "openrouter": self.shared_openrouter_key}
        models = {"gemini": self.shared_gemini_model, "groq": self.shared_groq_model, "openrouter": self.shared_openrouter_model}
        order = [p.strip() for p in self.shared_order.split(",") if p.strip() in keys]
        return [(p, keys[p], models[p]) for p in order if keys[p]] if self.shared_enabled else []

    def check(self) -> None:
        """Refuses an unsafe production configuration."""
        if self.env not in ("dev", "prod"):
            raise RuntimeError("ABHIFY_CLOUD_ENV must be 'dev' or 'prod'")
        from .providers import model_is_free
        if self.shared_enabled and not self.shared_allow_charged:
            for pid, _key, model in self.shared():
                if not model_is_free(pid, model):
                    raise RuntimeError(f"Shared {pid} model '{model}' may be charged. Pick a free model or set ABHIFY_CLOUD_SHARED_ALLOW_CHARGED=true.")
        if self.env == "prod":
            if not self.public_url.startswith("https://"):
                raise RuntimeError("Production needs ABHIFY_CLOUD_PUBLIC_URL with https:// (TLS terminated by the reverse proxy).")
            if not (self.smtp_host and self.smtp_from):
                raise RuntimeError("Production needs SMTP settings for password-reset email (ABHIFY_CLOUD_SMTP_*).")


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.check()
    return s
