"""
transcribe.py: Faster-Whisper backend transcription (high-accuracy fallback).

The browser's Web Speech API is fast but isn't available everywhere (e.g.
Firefox, some Android WebViews, Electron/Tauri shells). In that case the
frontend records audio with MediaRecorder and posts it here.

The model is loaded lazily on first use and reused afterwards. On machines with
an NVIDIA GPU it runs on CUDA (float16); otherwise it falls back to CPU (int8).
"""
from __future__ import annotations

import io
import logging
import threading

from config import get_settings

log = logging.getLogger(__name__)

_model = None
_lock = threading.Lock()


def _load():
    global _model
    with _lock:
        if _model is not None:
            return _model
        from faster_whisper import WhisperModel   # heavy import: only when needed

        s = get_settings()
        attempts = [("cuda", "float16"), ("cpu", "int8")] if s.whisper_device == "auto" else \
                   [(s.whisper_device, "float16" if s.whisper_device == "cuda" else "int8")]
        last = None
        for device, compute in attempts:
            try:
                _model = WhisperModel(s.whisper_model, device=device, compute_type=compute)
                log.info("Whisper '%s' loaded on %s (%s)", s.whisper_model, device, compute)
                return _model
            except Exception as e:  # no CUDA runtime, etc.
                last = e
                log.warning("Whisper on %s failed: %s", device, e)
        raise RuntimeError(f"Could not load Whisper: {last}")


def transcribe(audio: bytes, language: str | None = None) -> dict:
    """Transcribe an audio clip (webm/ogg/wav/mp3...). Blocking: call via a thread."""
    model = _load()
    segments, info = model.transcribe(io.BytesIO(audio), language=language, beam_size=5, vad_filter=True)
    text = " ".join(seg.text.strip() for seg in segments).strip()
    return {"text": text, "language": info.language, "duration": round(info.duration, 2)}
